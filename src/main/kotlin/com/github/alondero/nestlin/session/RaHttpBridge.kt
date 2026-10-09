package com.github.alondero.nestlin.session

import com.sun.jna.Pointer
import com.github.alondero.nestlin.util.Redactor
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * HTTP bridge between the rcheevos façade and Java's HTTP client (issue #268).
 *
 * The C façade's `server_call_shim` copies each request rcheevos wants to
 * send onto a small queue; rcheevos then waits for a response delivered via
 * `ra_facade_complete_http_request`. This bridge is the middleman: it polls
 * the queue from a background thread, hands each request to an
 * [RaHttpTransport], and posts the response (or failure) back to the façade.
 *
 * ## Exactly-once completion
 *
 * rcheevos releases a request's bookkeeping — and settles a login — only
 * when its response is delivered. So every request the bridge dequeues is
 * completed exactly once:
 *
 * - the façade hands each request out once, identified by a request id;
 * - a transport that throws instead of calling back is treated as a
 *   client error ([JavaHttpClientTransport.RC_API_SERVER_RESPONSE_CLIENT_ERROR]);
 * - a transport that calls back twice has its second callback dropped
 *   (the id is no longer in [inFlight]).
 *
 * Staleness (the user logged out, the game was unloaded) is rcheevos's job:
 * it tracks its own async operations and ignores a response whose
 * operation was abandoned. The bridge always delivers.
 *
 * ## Lifecycle
 *
 * - [start] spawns a single-thread executor and a polling loop.
 * - [stop] shuts the executor down. Responses that arrive afterwards are
 *   dropped, never delivered — the façade may be about to be destroyed, and
 *   `ra_facade_destroy` settles any still-pending request itself. After stop
 *   the bridge is unusable; the owner must stop it BEFORE destroying the
 *   façade handle.
 *
 * ## Threading
 *
 * - The polling loop runs on a dedicated single-thread executor
 *   (`pollExecutor`) so the native polling doesn't share a thread with
 *   the HTTP client's worker pool.
 * - HTTP responses arrive on the [RaHttpTransport]'s executor and are
 *   parked in [responses]; the poll loop delivers them, so all JNA calls are
 *   serialised on the poll thread. (They must not be `submit`ted to
 *   `pollExecutor`: its single thread is occupied by the poll loop for the
 *   bridge's whole lifetime, so a submitted task would never run.)
 */
class RaHttpBridge internal constructor(
    private val bindings: RaFacadeBindings,
    private val handle: Pointer,
    private val transport: RaHttpTransport,
) {
    /** Requests handed to the transport and not yet completed, by request id. */
    private val inFlight: MutableMap<Int, RaHttpRequest> = mutableMapOf()
    private val monitor: Any = Any()

    /** Responses waiting for the poll thread to deliver them to the façade. */
    private val responses: ConcurrentLinkedQueue<Pair<Int, RaHttpResponse>> = ConcurrentLinkedQueue()

    /**
     * Optional observer fired after every response is delivered back to
     * rcheevos. The sign-in manager hooks in here to check whether the
     * delivery settled a login. Invoked synchronously on the poll thread.
     */
    @Volatile var responseListener: ((RaHttpRequest, RaHttpResponse) -> Unit)? = null

    private val running = AtomicBoolean(false)
    private val pollExecutor = Executors.newSingleThreadExecutor { r ->
        Thread(r, "ra-http-bridge-poll").apply { isDaemon = true }
    }

    /** True between [start] and [stop]. */
    val isRunning: Boolean get() = running.get()

    /**
     * Start the polling loop. Idempotent — a second call while running
     * returns without spawning another executor.
     */
    fun start() {
        if (!running.compareAndSet(false, true)) return
        pollExecutor.submit { pollLoop() }
    }

    /**
     * Shut the bridge down and wait (briefly) for an in-progress delivery to
     * finish, so no JNA call is still running when the caller destroys the
     * façade. Idempotent.
     */
    fun stop() {
        if (!running.compareAndSet(true, false)) return
        pollExecutor.shutdown()
        try {
            // Wait briefly for the loop to drain; if it doesn't return in
            // 1s, force-shutdown. The loop polls every POLL_INTERVAL_MS,
            // so a slow request will at most block termination that long.
            pollExecutor.awaitTermination(1, TimeUnit.SECONDS)
        } catch (_: InterruptedException) {
            // restore interrupt flag and force-shutdown
            Thread.currentThread().interrupt()
        }
        pollExecutor.shutdownNow()
    }

    private fun pollLoop() {
        while (running.get()) {
            try {
                deliverPendingResponses()
                while (running.get() && drainOne()) {
                    // keep draining — a game load can queue several requests
                }
                // A synchronous transport has already answered.
                deliverPendingResponses()
            } catch (e: UnsatisfiedLinkError) {
                // Library unloaded mid-poll — exit the loop quietly.
                return
            } catch (e: Exception) {
                // Defensive — a JNA mapping error must not kill the
                // executor. Log once and back off.
                System.err.println("[RA] HTTP bridge poll error: ${e.javaClass.simpleName}: ${Redactor.redactMessage(e.message)}")
                if (!sleep(POLL_BACKOFF_MS)) return
            }
            // Cooperative sleep so an empty queue doesn't burn CPU. The
            // sleep is interruptible — stop() can wake the loop immediately.
            if (!sleep(POLL_INTERVAL_MS)) return
        }
    }

    /** Returns false when interrupted (the bridge is being stopped). */
    private fun sleep(millis: Long): Boolean = try {
        Thread.sleep(millis)
        true
    } catch (_: InterruptedException) {
        Thread.currentThread().interrupt()
        false
    }

    /** Dispatch the next queued request, if any. Returns true if one was dispatched. */
    private fun drainOne(): Boolean {
        val slot = RaHttpRequestSlot()
        slot.write()
        val has = bindings.ra_facade_dequeue_http_request(handle, slot)
        slot.read()
        if (has == 0) return false

        val request = RaHttpRequest(
            url = bytesToString(slot.url),
            postData = if (slot.hasPostData.toInt() != 0) bytesToString(slot.postData) else null,
            contentType = bytesToString(slot.contentType).takeIf { it.isNotEmpty() },
            requestId = slot.requestId,
        )
        synchronized(monitor) { inFlight[request.requestId] = request }

        try {
            transport.send(request) { response -> onTransportResponse(request.requestId, response) }
        } catch (e: Exception) {
            // The transport never called back, so rcheevos would wait on
            // this request forever. Fail it instead.
            System.err.println("[RA] HTTP transport rejected a request: ${e.javaClass.simpleName}: ${Redactor.redactMessage(e.message)}")
            deliverResponse(
                request.requestId,
                RaHttpResponse(status = JavaHttpClientTransport.RC_API_SERVER_RESPONSE_CLIENT_ERROR, body = null),
            )
        }
        return true
    }

    /** Transport callback — any thread. Park it for the poll thread's JNA call. */
    private fun onTransportResponse(requestId: Int, response: RaHttpResponse) {
        // After stop() nothing delivers it; the façade's destroy settles the request.
        if (running.get()) responses.add(requestId to response)
    }

    private fun deliverPendingResponses() {
        while (running.get()) {
            val (requestId, response) = responses.poll() ?: return
            deliverResponse(requestId, response)
        }
    }

    private fun deliverResponse(requestId: Int, response: RaHttpResponse) {
        // Removing under the monitor makes a second callback for the same
        // request a no-op — the exactly-once contract.
        val request = synchronized(monitor) { inFlight.remove(requestId) } ?: return
        val body = response.body
        try {
            bindings.ra_facade_complete_http_request(handle, requestId, response.status, body, body?.size ?: 0)
        } catch (e: UnsatisfiedLinkError) {
            // Library went away mid-completion — nothing to do.
            return
        }
        responseListener?.invoke(request, response)
    }

    private fun bytesToString(bytes: ByteArray): String {
        val end = bytes.indexOf(0)
        val trimmed = if (end >= 0) bytes.copyOf(end) else bytes
        return String(trimmed, Charsets.UTF_8)
    }

    companion object {
        /** How often the poll loop wakes to check the C queue. */
        private const val POLL_INTERVAL_MS: Long = 20

        /** Backoff after a poll-loop exception to avoid hot-looping. */
        private const val POLL_BACKOFF_MS: Long = 200
    }
}
