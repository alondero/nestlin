package com.github.alondero.nestlin.session

import com.sun.jna.Memory
import com.sun.jna.Pointer
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Regression checks for the JNA callback surface used by
 * `NativeRetroAchievementsService.installMemoryReader`.
 *
 * Three layers of coverage:
 *
 *  1. **Structural** — the marker, field existence, and 4-arg arity.
 *     Catches reverts of the JNA fix in a future commit.
 *
 *  2. **Behavioral** — round-trip tests through [wrapJvmReader] using
 *     [Memory] as a synthetic native [Pointer]. Exercises the actual
 *     Pointer.write call path so a future regression that breaks the
 *     bridge (wrong argument order, off-by-one buffer copy, missing
 *     defensive clamp, etc.) fails the test rather than corrupting
 *     native memory at 60 FPS.
 *
 *  3. **C-ABI parity** — the Java reflection check on
 *     [RaReadMemoryFn.read]'s parameter count + types pins the JVM-side
 *     signature to the C `ra_facade_read_memory_fn` typedef. Dropping
 *     `userdata` from the Kotlin interface compiles fine and dies at
 *     runtime when the C side passes 4 args into a 3-arg trampoline —
 *     this test catches that drift at build time.
 *
 * PR #316 review N2 (PR #290 review feedback): `userdata` is `Pointer?`
 * rather than `Pointer` so a null userdata (JNA's `Pointer.NULL`)
 * reaches the lambda body without Kotlin's implicit entry null-check
 * firing. The `accepts null userdata` test pins that contract.
 *
 * The tests deliberately do not load the native rcheevos library —
 * [Memory] is a pure-JVM stand-in for any native buffer.
 */
class RaReadMemoryFnCallbackRegressionTest {

    // ------------------------------------------------------------------
    // Structural (existed before PR #316 review round 2)
    // ------------------------------------------------------------------

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

    // ------------------------------------------------------------------
    // C-ABI parity (PR #316 review B3)
    // ------------------------------------------------------------------

    @Test
    fun `RaReadMemoryFn read declares 4 parameters matching the C ABI`() {
        // The C typedef in ra_facade.h:
        //   typedef uint32_t (*ra_facade_read_memory_fn)(uint32_t address,
        //                                                uint8_t* buffer,
        //                                                uint32_t num_bytes,
        //                                                void* userdata);
        // Dropping `userdata` from the Kotlin interface compiles fine
        // and dies at runtime when the C side passes 4 args into a
        // 3-arg JNA trampoline. Pin the parameter count + types here.
        val method = RaReadMemoryFn::class.java.declaredMethods
            .singleOrNull { it.name == "read" && it.parameterCount == 4 }
        assertNotNull(method,
            "RaReadMemoryFn must declare a 4-arg read method matching the C " +
                "ra_facade_read_memory_fn typedef (address, buffer, num_bytes, userdata). " +
                "Declared methods: ${RaReadMemoryFn::class.java.declaredMethods.map { "${it.name}(${it.parameterCount})" }}")
        // Parameter types: (int, Pointer, int, Pointer). Note that on the
        // JVM, `Pointer?` is erased to `Pointer` (nullable annotations
        // are metadata only), so the reflection type is just `Pointer`.
        assertEquals(Int::class.javaPrimitiveType, method!!.parameterTypes[0],
            "param 0 must be Int (uint32_t address)")
        assertSame(Pointer::class.java, method.parameterTypes[1],
            "param 1 must be Pointer (uint8_t* buffer)")
        assertEquals(Int::class.javaPrimitiveType, method.parameterTypes[2],
            "param 2 must be Int (uint32_t num_bytes)")
        assertSame(Pointer::class.java, method.parameterTypes[3],
            "param 3 must be Pointer (void* userdata) — nullable on Kotlin side")
    }

    // ------------------------------------------------------------------
    // Behavioral round-trip tests (PR #316 review B3)
    // ------------------------------------------------------------------

    /**
     * Small read: a 4-byte read on a small scratch writes 4 bytes into
     * the native pointer via the same code path the C runtime uses.
     */
    @Test
    fun `wrapJvmReader copies JVM reader output into the native Pointer for small reads`() {
        val mem = Memory(16L)
        mem.clear()
        val jvm = JvmReadMemoryFn { address, buffer, numBytes ->
            // Write a known pattern: byte i = (address + i) low byte
            for (i in 0 until numBytes) {
                buffer[i] = ((address + i) and 0xFF).toByte()
            }
            numBytes
        }
        val reader = wrapJvmReader(jvm, ByteArray(64))
        reader.read(0x0042, mem, 4, null)

        assertEquals(0x42.toByte(), mem.getByte(0L))
        assertEquals(0x43.toByte(), mem.getByte(1L))
        assertEquals(0x44.toByte(), mem.getByte(2L))
        assertEquals(0x45.toByte(), mem.getByte(3L))
    }

    @Test
    fun `wrapJvmReader returns 0 for zero-byte reads and skips the JVM reader entirely`() {
        val mem = Memory(1L)
        val jvmInvocations = java.util.concurrent.atomic.AtomicInteger(0)
        val jvm = JvmReadMemoryFn { _, _, _ ->
            jvmInvocations.incrementAndGet()
            99 // would-be-written counter, should never be reached
        }
        val reader = wrapJvmReader(jvm, ByteArray(64))
        val written = reader.read(0x1000, mem, 0, null)
        assertEquals(0, written)
        assertEquals(0, jvmInvocations.get(),
            "wrapJvmReader must short-circuit zero-byte reads without calling the JVM reader")
    }

    @Test
    fun `wrapJvmReader clamps the write count to what the JVM reader actually wrote`() {
        val mem = Memory(16L)
        mem.clear()
        val jvm = JvmReadMemoryFn { _, buffer, _ ->
            // Wrote only 2 bytes despite being asked for 16
            buffer[0] = 0xAA.toByte()
            buffer[1] = 0xBB.toByte()
            2
        }
        val reader = wrapJvmReader(jvm, ByteArray(64))
        val written = reader.read(0, mem, 16, null)
        assertEquals(2, written)
        assertEquals(0xAA.toByte(), mem.getByte(0L))
        assertEquals(0xBB.toByte(), mem.getByte(1L))
        // Bytes 2..15 must remain 0 (mem was cleared) — the bridge must
        // NOT copy unwritten scratch bytes into the native buffer.
        for (i in 2 until 16) {
            assertEquals(0.toByte(), mem.getByte(i.toLong()),
                "byte $i should be 0 (cleared), not stale scratch data")
        }
    }

    @Test
    fun `wrapJvmReader falls back to one-shot allocation when numBytes exceeds scratch`() {
        // 100-byte request with a 64-byte scratch — bridge must NOT
        // reuse the scratch (it's only 64 bytes). It allocates a 100-byte
        // buffer, calls the JVM reader, and copies back.
        val mem = Memory(128L)
        mem.clear()
        val jvm = JvmReadMemoryFn { _, buffer, numBytes ->
            for (i in 0 until numBytes) buffer[i] = (i and 0xFF).toByte()
            numBytes
        }
        val reader = wrapJvmReader(jvm, ByteArray(64))
        reader.read(0, mem, 100, null)
        for (i in 0 until 100) {
            assertEquals((i and 0xFF).toByte(), mem.getByte(i.toLong()),
                "byte $i should match the JVM reader's output")
        }
    }

    /**
     * PR #316 review N1: a misbehaving JVM reader that returns a
     * write count larger than `numBytes` would cause
     * `Pointer.write(0, tmp, 0, written)` to read past the end of the
     * Java array (out-of-bounds → IndexOutOfBoundsException, or worse,
     * undefined behaviour). The bridge must clamp `written` to
     * `[0, numBytes]` before the write.
     */
    @Test
    fun `wrapJvmReader defensively clamps written to numBytes`() {
        val mem = Memory(8L)
        mem.clear()
        val jvm = JvmReadMemoryFn { _, _, _ ->
            // Misbehaving reader: claims to write 999 bytes. Without the
            // defensive clamp, the bridge would call buffer.write(0,
            // tmp, 0, 999), reading 999 bytes from an 8-byte temp
            // array — ArrayIndexOutOfBoundsException, or undefined.
            999
        }
        val reader = wrapJvmReader(jvm, ByteArray(64))
        val written = reader.read(0, mem, 8, null)
        assertTrue(written in 0..8,
            "wrapJvmReader must clamp written to [0, numBytes]; got $written for an 8-byte read")
    }

    /**
     * PR #316 review N2: `userdata` is `Pointer?` so JNA's
     * `Pointer.NULL` (= Java null) reaches the lambda body without
     * Kotlin's implicit entry null-check firing. Pin this contract.
     */
    @Test
    fun `wrapJvmReader accepts null userdata without NPE`() {
        val mem = Memory(4L)
        mem.clear()
        val reader = wrapJvmReader(JvmReadMemoryFn { _, _, n -> n }, ByteArray(64))
        // No exception must be thrown. The native façade always passes
        // a non-null handle, but the bridge must tolerate the C side
        // passing NULL userdata (which JNA maps to Java null).
        val written = reader.read(0, mem, 4, null)
        assertEquals(4, written)
    }
}
