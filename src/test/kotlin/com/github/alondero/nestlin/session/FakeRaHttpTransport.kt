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
 *
 * All tests use this fake; production never touches it.
 */
class FakeRaHttpTransport : RaHttpTransport {
    /** Every request the bridge has handed to the transport, in order. */
    val sent: MutableList<RaHttpRequest> = mutableListOf()

    /** Queued responses; one is consumed per request. If empty, the default
     *  is a 200 OK with an empty body. */
    private val responses: ArrayDeque<(RaHttpRequest) -> RaHttpResponse> = ArrayDeque()
    var defaultResponse: (RaHttpRequest) -> RaHttpResponse = { _ ->
        RaHttpResponse(status = 200, body = "{}", bodyLength = 2)
    }

    /** When true, every callback fires twice. Tests assert that the bridge
     *  drops the duplicate. */
    var doubleCallback: Boolean = false

    fun enqueueResponse(response: RaHttpResponse) {
        responses.addLast { _ -> response }
    }

    fun enqueueResponse(status: Int, body: String? = "{}") {
        responses.addLast { _ ->
            RaHttpResponse(status = status, body = body, bodyLength = body?.length ?: 0)
        }
    }

    fun enqueueTransportFailure() {
        responses.addLast { _ ->
            RaHttpResponse(status = JavaHttpClientTransport.RC_API_SERVER_RESPONSE_RETRYABLE_CLIENT_ERROR, body = null, bodyLength = 0)
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
            generation = request.generation,
        )
        sent += safeRequest
        val response = if (responses.isNotEmpty()) responses.removeFirst() else defaultResponse
        callback(response.invoke(request))
        if (doubleCallback) callback(response.invoke(request))
    }
}