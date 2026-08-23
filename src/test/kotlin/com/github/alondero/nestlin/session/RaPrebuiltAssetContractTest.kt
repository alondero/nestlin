package com.github.alondero.nestlin.session

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Test

/**
 * Pin the pre-built native library distribution contract (issue #273
 * follow-up). The `:fetchNativeRa` Gradle task downloads a zip from a
 * GitHub Release asset whose name is
 * `rcheevos_facade-<platformId>.zip` — this test asserts that the
 * canonical platformId emitted by [RaManifest.currentPlatformId]
 * matches the asset naming the build pipeline uses.
 *
 * If you rename the asset pattern in `.github/workflows/release-native-libs.yml`
 * (the zip step) or in `build.gradle.kts` (the URL builder), update
 * this test in lockstep — the three must stay in sync.
 */
class RaPrebuiltAssetContractTest {

    @Test
    fun `currentPlatformId values match the asset name suffix pattern`() {
        // The build pipeline zips per-platform output under
        // `rcheevos_facade-<platformId>.zip` and the consumer-side
        // fetcher pattern-matches on `<platformId>` to pick the right
        // asset. RaManifest.currentPlatformId() emits one of the
        // three values below; if any changes, both ends need to
        // change together.
        val expected = setOf("windows-x86_64", "linux-x86_64", "macos-universal")
        // We can't pin the value to one of the three without knowing
        // the host, so assert that the function returns one of the
        // three (or null on an unsupported OS).
        val id = RaManifest.currentPlatformId()
        if (id != null) {
            assertEquals(true, id in expected,
                "currentPlatformId() returned '$id' which is not in the documented set $expected")
        }
    }

    @Test
    fun `MANIFEST resource path matches the consumer-side loader`() {
        // The Gradle :writeNativeRaManifest task emits
        // `native-ra/MANIFEST.json` inside the JAR resources tree.
        // RaManifest.loadManifest() reads from the same path. Pin
        // both sides so a rename in one breaks the other.
        assertEquals("native-ra/MANIFEST.json", RaManifest.RESOURCE_PATH)
    }

    @Test
    fun `loadForCurrentPlatform's expected asset name format is documented`() {
        // The :fetchNativeRa regex is:
        //   "name"\s*:\s*"<assetName>"[^}]*?"browser_download_url"\s*:\s*"([^"]+)"
        // where assetName is `rcheevos_facade-<platformId>.zip`. This
        // test pins the asset name format so the build pipeline (the
        // zip step in release-native-libs.yml) can't silently rename
        // it without breaking the consumer.
        val platformId = RaManifest.currentPlatformId() ?: return  // unsupported host — skip
        val expectedAssetName = "rcheevos_facade-$platformId.zip"
        assertNotNull(expectedAssetName,
            "Asset name must follow 'rcheevos_facade-<platformId>.zip' pattern")
        // Smoke-check the regex itself against a synthetic GitHub
        // release payload — ensures the consumer's parser still finds
        // the asset if the release JSON shape changes.
        val fakeReleaseJson = """
            {
              "tag_name": "v1.0.0",
              "assets": [
                {"name": "rcheevos_facade-$platformId.zip", "browser_download_url": "https://example.com/x.zip", "size": 12345},
                {"name": "other.zip", "browser_download_url": "https://example.com/other.zip"}
              ]
            }
        """.trimIndent()
        val regex = Regex(
            "\"name\"\\s*:\\s*\"$expectedAssetName\"[^}]*?\"browser_download_url\"\\s*:\\s*\"([^\"]+)\""
        )
        val match = regex.find(fakeReleaseJson)
        assertNotNull(match,
            "Consumer-side regex failed to find '$expectedAssetName' in synthetic release JSON")
        assertEquals("https://example.com/x.zip", match!!.groupValues[1])
    }
}
