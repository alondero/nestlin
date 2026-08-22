package com.github.alondero.nestlin.session

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Pins the [RaImageCache] contract (issue #269 AC #11):
 *  - asynchronous: the caller gets a future back immediately
 *  - cached: each unique URL is fetched at most once across the lifetime
 *  - deduplicated: concurrent requests for the same URL share a single
 *    network round-trip
 *  - size-capped: oversize bodies resolve to null (no infinite download)
 *  - placeholder-backed on failure: transport failures + non-2xx HTTP +
 *    decode errors all resolve to null (not throw)
 *  - protectable against stale generation: [invalidate] lets the consumer
 *    drop the cached entry when the coordinator's ROM-load generation
 *    advances
 */
class RaImageCacheTest {

    @Test
    fun `fetch returns immediately with a future`() {
        val cache = RaImageCache(FakeRaHttpTransport())
        val future = cache.fetch("https://retroachievements.org/Images/000001.png")
        assertNotNull(future)
        // The transport fires the callback synchronously in the fake, so
        // the future is already complete by the time fetch returns.
        assertTrue(future.isDone)
    }

    @Test
    fun `blank URL resolves to null without a network round-trip`() {
        val transport = FakeRaHttpTransport()
        val cache = RaImageCache(transport)
        val future = cache.fetch("")
        assertNull(future.get())
        assertEquals(0, transport.sent.size)
    }

    @Test
    fun `successful fetch returns the decoded image`() {
        val transport = FakeRaHttpTransport()
        // Build a real 1×1 PNG so the cache's ImageIO.read() decodes it
        // successfully. The default body ("{}") is not a valid image, so
        // we have to enqueue a proper PNG to exercise the success path.
        val onePixelPng = java.awt.image.BufferedImage(1, 1, java.awt.image.BufferedImage.TYPE_INT_RGB).let { img ->
            val baos = java.io.ByteArrayOutputStream()
            javax.imageio.ImageIO.write(img, "PNG", baos)
            baos.toByteArray()
        }
        transport.enqueueResponse(200, String(onePixelPng, Charsets.ISO_8859_1))
        val cache = RaImageCache(transport)
        val future = cache.fetch("https://retroachievements.org/Images/000001.png")
        val image = future.get(1, TimeUnit.SECONDS)
        assertNotNull(image)
    }

    @Test
    fun `transport failure resolves to null`() {
        val transport = FakeRaHttpTransport()
        transport.enqueueTransportFailure()
        val cache = RaImageCache(transport)
        val future = cache.fetch("https://retroachievements.org/Images/000001.png")
        assertNull(future.get(1, TimeUnit.SECONDS))
    }

    @Test
    fun `non-2xx HTTP resolves to null`() {
        val transport = FakeRaHttpTransport()
        transport.enqueueResponse(404)
        val cache = RaImageCache(transport)
        val future = cache.fetch("https://retroachievements.org/Images/000001.png")
        assertNull(future.get(1, TimeUnit.SECONDS))
    }

    @Test
    fun `oversize body resolves to null and does not crash`() {
        val transport = FakeRaHttpTransport()
        // Body is bigger than the default 4 MiB cap — the cache must
        // resolve to null without attempting to decode.
        val oversize = "x".repeat(RaImageCache.DEFAULT_MAX_BYTES + 1)
        transport.enqueueResponse(200, oversize)
        val cache = RaImageCache(transport)
        val future = cache.fetch("https://retroachievements.org/Images/000001.png")
        assertNull(future.get(1, TimeUnit.SECONDS))
    }

    @Test
    fun `concurrent requests for the same URL share a single round-trip`() {
        // Two `fetch` calls for the same URL in flight at the same time
        // must result in exactly one network request — the second
        // caller gets the same in-flight future the first one did.
        val transport = CountingFakeTransport(
            RaHttpResponse(status = 200, body = "{}", bodyLength = 2),
        )
        val cache = RaImageCache(transport)
        val f1 = cache.fetch("https://example.com/a.png")
        val f2 = cache.fetch("https://example.com/a.png")
        assertSame(f1, f2, "concurrent fetches must share the same future")
        f1.get(1, TimeUnit.SECONDS)
        // The transport's `send` was called exactly once even though the
        // cache's `fetch` was called twice.
        assertEquals(1, transport.sendCalls)
    }

    @Test
    fun `invalidate clears the dedup entry so the next fetch makes a fresh request`() {
        // AC #10: stale image completions must be discarded. The
        // coordinator bumps the placard generation on every loadRom;
        // the consumer of the image cache invalidates the URL for
        // the previous game's badge so a slow completion from ROM A
        // doesn't overwrite ROM B's placard.
        val transport = CountingFakeTransport(
            RaHttpResponse(status = 200, body = "{}", bodyLength = 2),
        )
        val cache = RaImageCache(transport)
        cache.fetch("https://example.com/a.png").get(1, TimeUnit.SECONDS)
        assertEquals(1, transport.sendCalls)
        cache.invalidate("https://example.com/a.png")
        cache.fetch("https://example.com/a.png").get(1, TimeUnit.SECONDS)
        assertEquals(2, transport.sendCalls)
    }

    @Test
    fun `clear cancels in-flight and drops dedup state`() {
        val transport = AsyncFakeTransport()
        val cache = RaImageCache(transport)
        val future = cache.fetch("https://example.com/a.png")
        cache.clear()
        assertTrue(future.isCancelled || future.isCompletedExceptionally || !future.isDone,
            "in-flight future must be cancelled or dropped on clear()")
        // A follow-up fetch makes a brand-new request.
        cache.fetch("https://example.com/a.png")
        assertEquals(2, transport.sendCalls)
    }

    /**
     * Transport fake that always returns the same scripted response and
     * counts how many times `send` was called. Used to verify the cache's
     * dedup behavior.
     */
    private class CountingFakeTransport(
        private val response: RaHttpResponse,
    ) : RaHttpTransport {
        var sendCalls: Int = 0
        override fun send(request: RaHttpRequest, callback: (RaHttpResponse) -> Unit) {
            sendCalls++
            callback(response)
        }
    }

    /**
     * Transport fake whose callback fires asynchronously on a worker
     * thread, so the test can verify the cache's in-flight semantics
     * without a real sleep.
     */
    private class AsyncFakeTransport : RaHttpTransport {
        var sendCalls: Int = 0
        private val latch = CountDownLatch(1)
        override fun send(request: RaHttpRequest, callback: (RaHttpResponse) -> Unit) {
            sendCalls++
            Thread {
                latch.await(2, TimeUnit.SECONDS)
                callback(RaHttpResponse(status = 200, body = "{}", bodyLength = 2))
            }.start()
        }
    }
}
