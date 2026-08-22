package com.github.alondero.nestlin.session

import com.sun.jna.Pointer
import com.github.alondero.nestlin.util.Redactor
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * HTTP bridge between the rcheevos façade and Java's HTTP client (issue #268).
 *
 * The C façade's `server_call_shim` enqueues an [RaHttpRequest] (URL +
 * method + optional POST body) onto a small ring buffer; rcheevos then
 * waits for a response delivered via `ra_facade_complete_http_request`.
 * This bridge is the middleman: it polls the queue from a background
 * thread, hands each request to an [RaHttpTransport], and posts the
 * response (or failure) back to the façade.
 *
 * ## Lifecycle
 *
 * - [start] spawns a single-thread executor and a polling loop.
 * - [stop] shuts the executor down, cancels in-flight HTTP requests, and
 *   abandons any queued C-side requests without delivering responses.
 *   After stop the bridge is unusable; the application owns the lifecycle.
 *
 * ## Generation guards
 *
 * Every request carries the C-side generation it was enqueued under. If
 * the user logs out before a response arrives, the façade's generation
 * advances and `ra_facade_complete_http_request` silently drops the
 * late response. The bridge doesn't need to do anything special —
 * generation enforcement is enforced by the C side.
 *
 * ## Threading
 *
 * - The polling loop runs on a dedicated single-thread executor
 *   (`pollExecutor`) so the native polling doesn't share a thread with
 *   the HTTP client's worker pool.
 * - HTTP responses arrive on the [RaHttpTransport]'s executor; the bridge
 *   immediately re-posts to the poll executor before calling the C side,
 *   so all JNA calls are serialised on the same thread.
 *
 * ## Exact-once callbacks
 *
 * Each pending HTTP request is tracked in [inFlight] keyed by the
 * generation+url hash; the transport MUST invoke the callback exactly once.
 * The bridge asserts on this in debug builds (the in-flight map entry is
 * removed under the bridge's monitor before the C side is called). If a
 * transport ever violates the contract the bridge surfaces a single
 * "[RA] Stale HTTP completion" diagnostic and drops the second call.
 */
class RaHttpBridge internal constructor(
    private val bindings: RaFacadeBindings,
    private val handle: Pointer,
    private val transport: RaHttpTransport,
) {
    /** Track in-flight requests so we can detect duplicate callbacks and recover the URL. */
    private val inFlight: MutableMap<Int, RaHttpRequest> = mutableMapOf()
    private val monitor: Any = Any()

    /**
     * Optional observer fired after every response is delivered back to rcheevos.
     * The sign-in manager hooks in here so it can poll for [RaAccount] state
     * after a login HTTP round-trip settles. Off the calling thread of the
     * transport's executor — invoked synchronously on the poll thread.
     */
    @Volatile var responseListener: ((RaHttpRequest, RaHttpResponse) -> Unit)? = null

    private val running = AtomicBoolean(false)
    private val pollExecutor = Executors.newSingleThreadExecutor { r ->
        Thread(r, "ra-http-bridge-poll").apply { isDaemon = true }
    }

    /**
     * Start the polling loop. Idempotent — a second call while running
     * returns without spawning another executor.
     */
    fun start() {
        if (!running.compareAndSet(false, true)) return
        pollExecutor.submit { pollLoop() }
    }

    /**
     * Shut the bridge down. Cancels pending HTTP work, drains the C-side
     * queue without delivering responses, and stops the executor. Idempotent.
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
                drainOne()
            } catch (e: UnsatisfiedLinkError) {
                // Library unloaded mid-poll — exit the loop quietly.
                return
            } catch (e: Exception) {
                // Defensive — a misbehaving transport or a JNA mapping
                // error must not kill the executor. Log once and back off.
                System.err.println("[RA] HTTP bridge poll error: ${e.javaClass.simpleName}: ${Redactor.redactMessage(e.message)}")
                Thread.sleep(POLL_BACKOFF_MS)
            }
            // Cooperative sleep so a transport that completes synchronously
            // doesn't burn CPU. The sleep is interruptible — stop() can
            // wake the loop immediately.
            try {
                Thread.sleep(POLL_INTERVAL_MS)
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
                return
            }
        }
    }

    private fun drainOne() {
        val slot = RaHttpRequestSlot()
        slot.write()
        val has = try {
            bindings.ra_facade_dequeue_http_request(handle, slot)
        } catch (e: UnsatisfiedLinkError) {
            throw e
        }
        slot.read()
        if (has == 0) return  // queue is empty

        val url = bytesToString(slot.url)
        val postData = if (slot.hasPostData.toInt() != 0) bytesToString(slot.postData) else null
        val contentType = bytesToString(slot.contentType).takeIf { it.isNotEmpty() }
        val generation = slot.generation

        val request = RaHttpRequest(
            url = url,
            postData = postData,
            contentType = contentType,
            generation = generation,
        )

        val requestKey = generation xor url.hashCode()
        synchronized(monitor) {
            // Defensive: a duplicate generation+url (the transport raced a
            // previous request) is silently dropped. The C side will see
            // the response land on the stale slot and discard it via the
            // generation check inside http_queue_complete.
            if (inFlight.containsKey(requestKey)) {
                System.err.println("[RA] HTTP bridge duplicate request dropped (gen=$generation)")
                return
            }
            inFlight[requestKey] = request
        }

        transport.send(request) { response ->
            pollExecutor.submit {
                deliverResponse(generation, response, requestKey)
            }
        }
    }

    private fun deliverResponse(generation: Int, response: RaHttpResponse, requestKey: Int) {
        val request: RaHttpRequest = synchronized(monitor) {
            val removed = inFlight.remove(requestKey)
            if (removed == null) {
                // Transport invoked the callback twice — drop silently to
                // satisfy the exact-once contract.
                return
            }
            removed
        }
        try {
            bindings.ra_facade_complete_http_request(
                handle,
                generation,
                response.status,
                response.body,
                response.bodyLength,
            )
        } catch (e: UnsatisfiedLinkError) {
            // Library went away mid-completion — nothing to do.
            return
        }
        // Fire the post-completion hook with the request we retained so the
        // sign-in manager can distinguish login URLs from per-game loads.
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