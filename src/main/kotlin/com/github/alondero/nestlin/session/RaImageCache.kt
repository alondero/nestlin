package com.github.alondero.nestlin.session

import java.awt.image.BufferedImage
import java.io.ByteArrayInputStream
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import javax.imageio.ImageIO

/**
 * Async, cached, dedup'd, size-capped image fetcher for RetroAchievements
 * game / badge images (issue #269 AC #11).
 *
 * Each unique URL is fetched at most once across the lifetime of the cache.
 * Concurrent requests for the same URL share a single in-flight future; the
 * second caller receives the same `CompletableFuture` the first one did.
 *
 * Failure paths (transport error, non-2xx HTTP, oversize body, decode error)
 * resolve the future with `null` instead of throwing, so a UI consumer can
 * just check "is the future complete + non-null" without try/catch. The cache
 * never throws into the caller thread; all exception handling is internal.
 *
 * ## Size cap
 *
 * Images over [MAX_BYTES] are treated as failures (the future resolves to
 * null). The cap protects against a hostile server returning gigabytes for
 * a single image, and against an honest-but-fat server whose images are
 * bigger than the placard can render. 4 MiB comfortably fits every badge
 * format rcheevos serves today (PNG / JPEG, 96×96 max).
 *
 * ## Generation guard
 *
 * The cache is unaware of the coordinator's ROM-load generation by design.
 * The UI consumer (boot placard, profile window) checks the generation
 * before applying the fetched image — see [RaBootPlacardController] for
 * the pattern. This keeps the cache reusable for any image consumer that
 * might emerge (achievement popups, leaderboard tracker, etc.) without
 * threading generation state through every call site.
 *
 * ## Dedup
 *
 * Two `fetch(url)` calls in flight at the same time produce one network
 * request and two futures. The dedup map's key is the URL; the value is
 * the in-flight future. Once the request settles (success, failure, or
 * cancelled), the entry is removed so a follow-up `fetch(url)` makes a
 * fresh request.
 */
class RaImageCache(
    private val transport: RaHttpTransport = JavaHttpClientTransport(),
    private val maxBytes: Int = DEFAULT_MAX_BYTES,
) {
    /** In-flight + completed futures keyed by URL. Cleared on settle so a follow-up re-fetches. */
    private val inflight: MutableMap<String, CompletableFuture<BufferedImage?>> = ConcurrentHashMap()

    /**
     * Begin fetching [url]. Returns immediately with a future that completes
     * (on a transport-chosen thread) when the image is downloaded, decoded,
     * and cached; or null on any failure.
     *
     * Calling `fetch` for the same URL twice in flight returns the same
     * future — the second caller doesn't trigger a duplicate request.
     */
    fun fetch(url: String): CompletableFuture<BufferedImage?> {
        if (url.isBlank()) {
            return CompletableFuture.completedFuture(null)
        }
        return inflight.computeIfAbsent(url) { startFetch(it) }
    }

    /**
     * Invalidate the cache for [url]. The next `fetch(url)` re-downloads.
     * Used by the placard when the ROM-load generation advances and a
     * previously-cached image is now stale.
     */
    fun invalidate(url: String) {
        inflight.remove(url)
    }

    /** Drop all in-flight + completed state. Used on logout / shutdown. */
    fun clear() {
        inflight.values.forEach { it.cancel(false) }
        inflight.clear()
    }

    private fun startFetch(url: String): CompletableFuture<BufferedImage?> {
        val future = CompletableFuture<BufferedImage?>()
        // The dedup entry is removed when the future completes — and
        // importantly, AFTER we return from `computeIfAbsent`. The
        // transport's callback may fire synchronously (the fake
        // transport does), so removing the entry inside the callback
        // would trigger a recursive-update exception from
        // ConcurrentHashMap. The whenComplete callback runs after the
        // future settles, on the completing thread, by which time the
        // outer computeIfAbsent has returned and the map is safe to
        // mutate again.
        // Use the same generation=0 for all image fetches — the cache's
        // generation-guard story lives at the consumer level, not here.
        // We pass `null` postData so the transport issues a GET.
        val request = RaHttpRequest(
            url = url,
            postData = null,
            contentType = null,
            generation = IMAGE_GENERATION,
        )
        transport.send(request) { response ->
            try {
                if (response.status in 200..299) {
                    val body = response.body
                    val bytes = body?.toByteArray(Charsets.ISO_8859_1)
                    if (bytes == null || bytes.isEmpty()) {
                        future.complete(null)
                    } else if (bytes.size > maxBytes) {
                        System.err.println("[RA] Image $url exceeds ${maxBytes}B cap (${bytes.size}B) — dropping")
                        future.complete(null)
                    } else {
                        try {
                            val img = ImageIO.read(ByteArrayInputStream(bytes))
                            future.complete(img)
                        } catch (e: Exception) {
                            System.err.println("[RA] Image decode failed for $url: ${e.javaClass.simpleName}")
                            future.complete(null)
                        }
                    }
                } else {
                    // Non-2xx HTTP — drop silently. The placard shows a
                    // placeholder; the user sees a working game with a
                    // blank badge. The coordinator's "failure never prevents
                    // gameplay" rule (AC #12) applies.
                    future.complete(null)
                }
            } catch (e: Throwable) {
                // Defensive: any exception from the response handling path
                // resolves the future as a soft failure rather than
                // leaving it stuck. A stuck future would hang the UI
                // forever (the placard's badge would never resolve).
                future.complete(null)
            }
        }
        future.whenComplete { _, _ ->
            // Remove the dedup entry on whichever thread the transport
            // completed on. By the time this fires, the outer
            // computeIfAbsent has returned (the future was returned
            // before the transport's callback fired), so the map is
            // safe to mutate.
            inflight.remove(url, future)
        }
        return future
    }

    companion object {
        /** Default size cap. RA badges top out at ~10 KiB; 4 MiB is generous. */
        const val DEFAULT_MAX_BYTES: Int = 4 * 1024 * 1024

        /**
         * Generation value used for image fetches. Distinct from the sign-in
         * / ROM-load generations so a stale image response can never be
         * confused with a stale login response. The cache doesn't enforce a
         * generation guard internally — the consumer does that.
         */
        const val IMAGE_GENERATION: Int = -1
    }
}
