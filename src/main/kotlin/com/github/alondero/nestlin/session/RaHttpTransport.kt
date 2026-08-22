package com.github.alondero.nestlin.session

/**
 * Pluggable HTTP transport used by [RaHttpBridge] to execute the requests
 * rcheevos hands off (issue #268).
 *
 * Production uses [JavaHttpClientTransport] (Java 11+ `java.net.http`).
 * Tests use a fake transport that returns scripted responses without ever
 * touching the network.
 *
 * The interface is single-method and async; the bridge calls [send] from
 * a background thread and the transport must invoke [callback] exactly
 * once when the response is available (success, timeout, DNS failure, or
 * any other terminal state). A transport that calls the callback more than
 * once will produce a "stale response" path on the C side — the issue #268
 * generation guard will drop the second call, but the contract still
 * requires exact-once.
 */
interface RaHttpTransport {
    /**
     * Send [request] and invoke [callback] exactly once with the outcome.
     *
     * Implementations MUST be safe to call from any thread. The callback
     * is invoked on a transport-chosen thread (typically the HttpClient's
     * executor); the bridge re-posts to its own thread before calling back
     * into the C side.
     *
     * Timeouts are bounded — see [RaHttpBridge] for the connection /
     * request values. A timeout fires the callback with a [RaHttpResponse]
     * whose [status] is negative (matching `RC_API_SERVER_RESPONSE_CLIENT_ERROR`
     * on the C side).
     */
    fun send(request: RaHttpRequest, callback: (RaHttpResponse) -> Unit)
}

/**
 * Outgoing HTTP request. Mirrors the C-side `ra_http_request_t` fields the
 * bridge extracted; kept as a Kotlin data class so callers don't need to
 * touch JNA memory.
 */
data class RaHttpRequest(
    val url: String,
    /** POST body. Null or empty for GET requests. */
    val postData: String?,
    /** Content-Type for the POST body. Ignored for GET. */
    val contentType: String?,
    /** The C-side generation that owns this request (used for the stale-response guard). */
    val generation: Int,
)

/**
 * HTTP response the bridge delivers back to rcheevos.
 *
 * [status] carries the HTTP status code on success, or one of:
 *   - `RC_API_SERVER_RESPONSE_CLIENT_ERROR` (-1) for non-retryable failures
 *   - `RC_API_SERVER_RESPONSE_RETRYABLE_CLIENT_ERROR` (-2) for retryable failures
 *   - any non-2xx HTTP code verbatim (e.g. 401, 500) — rcheevos interprets
 *     these as server-side responses, not transport failures
 *
 * [body] is the response payload or null on empty/error. [bodyLength] is
 * the byte count; the bridge always passes a copy so the C side can
 * safely retain it past the call.
 */
data class RaHttpResponse(
    val status: Int,
    val body: String?,
    val bodyLength: Int,
)

/**
 * Production HTTP transport using Java 11+ [java.net.http.HttpClient].
 *
 * - Connection timeout: 10s — fast enough that a flaky network surfaces
 *   within the issue #268 single-attempt budget.
 * - Request timeout: 30s — RA login typically returns in <2s; achievement
 *   set loads can take longer on cold caches.
 * - User-Agent: a Nestlin-specific string including the rcheevos clause
 *   (obtained via `rc_client_get_user_agent_clause` so the version tracks
 *   the vendored library).
 * - Redirects: NEVER followed — RA endpoints don't redirect, and a
 *   redirect here would be a server-side issue worth surfacing.
 */
class JavaHttpClientTransport(
    private val userAgent: String = DEFAULT_USER_AGENT,
    private val connectTimeoutMillis: Int = DEFAULT_CONNECT_TIMEOUT_MS,
    private val requestTimeoutMillis: Int = DEFAULT_REQUEST_TIMEOUT_MS,
) : RaHttpTransport {

    private val client: java.net.http.HttpClient = java.net.http.HttpClient.newBuilder()
        .connectTimeout(java.time.Duration.ofMillis(connectTimeoutMillis.toLong()))
        .followRedirects(java.net.http.HttpClient.Redirect.NEVER)
        .build()

    override fun send(request: RaHttpRequest, callback: (RaHttpResponse) -> Unit) {
        val javaReq = if (request.postData.isNullOrEmpty()) {
            java.net.http.HttpRequest.newBuilder()
                .uri(java.net.URI.create(request.url))
                .timeout(java.time.Duration.ofMillis(requestTimeoutMillis.toLong()))
                .header("User-Agent", userAgent)
                .GET()
                .build()
        } else {
            java.net.http.HttpRequest.newBuilder()
                .uri(java.net.URI.create(request.url))
                .timeout(java.time.Duration.ofMillis(requestTimeoutMillis.toLong()))
                .header("User-Agent", userAgent)
                .header("Content-Type", request.contentType ?: "application/x-www-form-urlencoded")
                .POST(java.net.http.HttpRequest.BodyPublishers.ofString(request.postData))
                .build()
        }

        client.sendAsync(javaReq, java.net.http.HttpResponse.BodyHandlers.ofString())
            .whenComplete { resp, ex ->
                if (ex != null) {
                    // Transport failure — treat as retryable. The bridge
                    // surfaces this to rcheevos, which will likely retry.
                    callback(RaHttpResponse(
                        status = RC_API_SERVER_RESPONSE_RETRYABLE_CLIENT_ERROR,
                        body = null,
                        bodyLength = 0,
                    ))
                } else {
                    val body = resp.body() ?: ""
                    callback(RaHttpResponse(
                        status = resp.statusCode(),
                        body = body,
                        bodyLength = body.length,
                    ))
                }
            }
    }

    companion object {
        /** Default User-Agent. Includes the `rcheevos/` clause for server-side analytics. */
        const val DEFAULT_USER_AGENT: String =
            "Nestlin/1.0 (https://github.com/alondero/nestlin) rcheevos/12.4.0"

        const val DEFAULT_CONNECT_TIMEOUT_MS: Int = 10_000
        const val DEFAULT_REQUEST_TIMEOUT_MS: Int = 30_000

        /** Mirrors `RC_API_SERVER_RESPONSE_CLIENT_ERROR` in rc_api_request.h. */
        const val RC_API_SERVER_RESPONSE_CLIENT_ERROR: Int = -1

        /** Mirrors `RC_API_SERVER_RESPONSE_RETRYABLE_CLIENT_ERROR`. */
        const val RC_API_SERVER_RESPONSE_RETRYABLE_CLIENT_ERROR: Int = -2
    }
}