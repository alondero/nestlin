package com.github.alondero.nestlin.session

import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Regression check: [RaReadMemoryFn] extends [com.sun.jna.Callback] so
 * JNA can marshal a Kotlin `fun interface` lambda as a native function
 * pointer when `NativeRetroAchievementsService.installMemoryReader`
 * hands it to `ra_facade_set_memory_reader`.
 *
 * Without `: Callback`, JNA rejects the lambda at the JNA boundary
 * with `IllegalArgumentException: Unsupported argument type
 * <ClassName>$$Lambda/... at parameter 1`. The UI starts up fine but
 * the first `loadRom` call blows up because the coordinator's
 * `installMemoryReader` runs inside `prepareServiceForCurrentFromInfo`
 * (issue #270 AC: the reader must be installed BEFORE the runtime
 * begins evaluating the first frame).
 *
 * The previous regression came from commit `28fb30c` (fix(smoke): skip
 * callback registration in step 7) which removed `: Callback` to work
 * around an unrelated `byte[]` length-annotation issue. The byte[]
 * workaround is correct (the smoke test no longer calls
 * `set_memory_reader`), but dropping the marker broke the production
 * path. This test pins the marker.
 */
class RaReadMemoryFnCallbackRegressionTest {

    @Test
    fun `RaReadMemoryFn extends JNA Callback so lambdas marshal as native function pointers`() {
        assertTrue(
            com.sun.jna.Callback::class.java.isAssignableFrom(RaReadMemoryFn::class.java),
            "RaReadMemoryFn must extend com.sun.jna.Callback so JNA can marshal " +
                "its lambdas as native callbacks in installMemoryReader."
        )
    }
}