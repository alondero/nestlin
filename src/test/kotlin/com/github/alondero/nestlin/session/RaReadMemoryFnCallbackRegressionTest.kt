package com.github.alondero.nestlin.session

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Regression checks for the JNA callback surface used by
 * `NativeRetroAchievementsService.installMemoryReader`.
 *
 * Two related bugs were caught during review of PR #316:
 *
 *   1. `RaReadMemoryFn` must extend `com.sun.jna.Callback` so JNA can
 *      marshal a Kotlin `fun interface` lambda as a native function
 *      pointer. Without the marker, JNA rejects the lambda at the JNA
 *      boundary with `IllegalArgumentException: Unsupported argument
 *      type <ClassName>$$Lambda/... at parameter 1`. The previous
 *      regression came from commit `28fb30c` (fix(smoke): skip callback
 *      registration in step 7) which removed `: Callback` to work
 *      around an unrelated `byte[]` length-annotation issue. The byte[]
 *      workaround is correct (the smoke test no longer calls
 *      `set_memory_reader`), but dropping the marker broke the
 *      production path.
 *
 *   2. JNA tracks [Callback] instances via [java.lang.ref.WeakReference]
 *      so it can clean up after the C side releases the function pointer.
 *      If user code doesn't hold a strong reference, GC reclaims the
 *      wrapper while the C side still owns the function pointer —
 *      every subsequent native `evaluate_frame` then lands in freed
 *      memory and crashes the JVM with a fatal access violation. The
 *      fix stores the wrapper in `NativeRetroAchievementsService`'s
 *      `activeReaderWrapper` field. This test pins that field's
 *      existence so a future refactor that drops it (e.g. returning
 *      to the pre-fix pattern of `wrapJvmReader(reader)` as a
 *      short-lived expression) fails the build before it can hit the
 *      GC race at 60 FPS.
 *
 * The 4-argument JNA callback signature (matching the C
 * `ra_facade_read_memory_fn` typedef) is structurally pinned by the
 * JNA compiler — adding or removing `userdata` would require editing
 * the production code, which would itself fail to compile against
 * rcheevos_facade.h, so we don't need a separate test for it.
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

    @Test
    fun `NativeRetroAchievementsService retains the active JNA callback wrapper as a strong reference`() {
        // Use reflection: instantiating NativeRetroAchievementsService
        // directly requires loading the native rcheevos library, which
        // is environment-dependent (CI runners may not have it). The
        // field-existence check pins the fix without requiring the
        // library to be present.
        val cls = Class.forName("com.github.alondero.nestlin.session.NativeRetroAchievementsService")
        val field = try {
            cls.getDeclaredField("activeReaderWrapper")
        } catch (e: NoSuchFieldException) {
            null
        }
        assertNotNull(field,
            "NativeRetroAchievementsService must declare 'activeReaderWrapper' to " +
                "retain the JNA callback wrapper strongly. Without it, GC can reclaim " +
                "the wrapper mid-emulation while the C side still owns the function " +
                "pointer — every subsequent native call then lands in freed memory.")
        // The field must hold a RaReadMemoryFn (or null when no reader
        // is installed — that's still a strong-reference slot, just empty).
        assertEquals(
            "com.github.alondero.nestlin.session.RaReadMemoryFn",
            field!!.type.name,
            "activeReaderWrapper must be of type RaReadMemoryFn"
        )
    }
}
