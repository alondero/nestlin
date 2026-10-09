package com.github.alondero.nestlin.session

import com.github.alondero.nestlin.util.Redactor

/**
 * Recording fake [RaHttpTransport] used by the [RaHttpBridge] /
 * [RaSignInManager] test suite (issue #268).
 *
 * The fake can be scripted to:
 *   - record every request for later assertion
 *   - return a scripted response immediately (success / 401 / 500)
 *   - simulate a transport failure (negative status)
 *   - simulate a callback that fires twice (the bridge's exact-once guard
 *     is then expected to drop the second one)
 *   - throw from [send] instead of calling back (e.g. a malformed URL)
 *
 * All tests use this fake; production never touches it.
 */
class FakeRaHttpTransport : RaHttpTransport {
    /** Every request the bridge has handed to the transport, in order. */
    val sent: MutableList<RaHttpRequest> = java.util.concurrent.CopyOnWriteArrayList()

    /** Queued responses; one is consumed per request. If empty, the default
     *  is a 200 OK with an empty body. */
    private val responses: ArrayDeque<(RaHttpRequest) -> RaHttpResponse> = ArrayDeque()
    var defaultResponse: (RaHttpRequest) -> RaHttpResponse = { _ ->
        RaHttpResponse.text(status = 200, body = "{}")
    }

    /** When true, every callback fires twice. Tests assert that the bridge
     *  drops the duplicate. */
    var doubleCallback: Boolean = false

    /** When set, [send] throws this instead of invoking the callback. */
    var throwOnSend: Throwable? = null

    fun enqueueResponse(response: RaHttpResponse) {
        responses.addLast { _ -> response }
    }

    fun enqueueResponse(status: Int, body: String? = "{}") {
        responses.addLast { _ -> RaHttpResponse.text(status = status, body = body) }
    }

    fun enqueueTransportFailure() {
        responses.addLast { _ ->
            RaHttpResponse(status = JavaHttpClientTransport.RC_API_SERVER_RESPONSE_RETRYABLE_CLIENT_ERROR, body = null)
        }
    }

    override fun send(request: RaHttpRequest, callback: (RaHttpResponse) -> Unit) {
        // Defence: scrub the body before it reaches the test's call list, so a
        // test author that puts a token in a POST body doesn't accidentally
        // leak it via the test's own diagnostics.
        val safeRequest = RaHttpRequest(
            url = Redactor.redactUrl(request.url),
            postData = request.postData?.let { Redactor.redactMessage(it) },
            contentType = request.contentType,
            requestId = request.requestId,
        )
        sent += safeRequest
        throwOnSend?.let { throw it }
        val response = synchronized(responses) {
            if (responses.isNotEmpty()) responses.removeFirst() else defaultResponse
        }
        callback(response.invoke(request))
        if (doubleCallback) callback(response.invoke(request))
    }
}
