package com.github.alondero.nestlin.session

import com.sun.net.httpserver.HttpServer
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.net.InetAddress
import java.net.InetSocketAddress
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit

/**
 * [JavaHttpClientTransport] against a loopback server — never the real RA
 * host. Response bodies must arrive byte-for-byte: rcheevos parses the JSON
 * by byte length, and badge/avatar images are binary.
 */
class JavaHttpClientTransportTest {

    private val server: HttpServer = HttpServer.create(InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0)
    private val transport = JavaHttpClientTransport()

    @AfterEach
    fun tearDown() {
        server.stop(0)
    }

    @Test
    fun `binary response body arrives byte for byte`() {
        val png = ByteArray(256) { it.toByte() }
        serve("/badge.png", "image/png", png)

        val response = fetch("/badge.png")

        assertEquals(200, response.status)
        assertArrayEquals(png, response.body)
    }

    @Test
    fun `UTF-8 response body arrives as its encoded bytes`() {
        val json = """{"Success":true,"User":"Ålicé ✓"}""".toByteArray(Charsets.UTF_8)
        serve("/dorequest.php", "application/json", json)

        val response = fetch("/dorequest.php", postData = "r=login2&u=alice")

        assertArrayEquals(json, response.body)
    }

    private fun serve(path: String, contentType: String, body: ByteArray) {
        server.createContext(path) { exchange ->
            exchange.requestBody.readAllBytes()
            exchange.responseHeaders.add("Content-Type", contentType)
            exchange.sendResponseHeaders(200, body.size.toLong())
            exchange.responseBody.use { it.write(body) }
        }
        server.start()
    }

    private fun fetch(path: String, postData: String? = null): RaHttpResponse {
        val url = "http://127.0.0.1:${server.address.port}$path"
        val result = CompletableFuture<RaHttpResponse>()
        transport.send(
            RaHttpRequest(url = url, postData = postData, contentType = null, requestId = 1),
        ) { result.complete(it) }
        return result.get(10, TimeUnit.SECONDS)
    }
}
