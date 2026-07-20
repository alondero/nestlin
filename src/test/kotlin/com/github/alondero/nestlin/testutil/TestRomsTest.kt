package com.github.alondero.nestlin.testutil

import com.github.alondero.nestlin.Nestlin
import com.github.alondero.nestlin.file.load
import com.github.alondero.nestlin.gamepak.GamePak
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.nio.file.Files

/**
 * Regression test for GitHub issue #21: the bundled `nestest.nes` test ROM must resolve
 * identically regardless of the JVM's working directory.
 *
 * Before the fix, every test that wanted the ROM reached for it via
 * `Paths.get("testroms/nestest.nes")` — a relative path that only resolved when the JVM was
 * started from the project root. Any IDE run-config, debugger, or orchestrated subprocess
 * that didn't set the working directory to the root would silently fail with a deep
 * `NoSuchFileException` after the test had already been "discovered" by JUnit.
 *
 * The fix moved the ROM into `src/test/resources/` (the standard Gradle/Java test-resource
 * location, which is bundled onto the classpath at test runtime) and added [TestRoms] to
 * resolve it from there. This test pins two things:
 *
 *  1. The resource is reachable and the bytes are stable (24 576 bytes; matches the
 *     in-tree `testroms/nestest.nes` checksum that has been used for years). If someone
 *     accidentally replaces the resource, this fails fast with a concrete byte count.
 *  2. The lookup still works when `System.getProperty("user.dir")` points somewhere
 *     unrelated — `"/"` on Unix and `"C:\\Windows"`-shaped paths on Windows both make
 *     a relative lookup impossible, so this proves the implementation isn't leaning on
 *     the CWD.
 */
class TestRomsTest {

    private lateinit var originalUserDir: String

    @BeforeEach
    fun snapshotUserDir() {
        originalUserDir = System.getProperty("user.dir")
    }

    @AfterEach
    fun restoreUserDir() {
        System.setProperty("user.dir", originalUserDir)
    }

    @Test
    fun `nestestResource is on the classpath and points to a real entry`() {
        val url = TestRoms.nestestResource()
        assertNotNull(url, "nestest.nes must be on the test classpath")
        // openStream() throws if the resource can't be opened; an openStream().use {}
        // that completes without exception is enough for the regression.
        url.openStream().use {
            assertTrue(it.readAllBytes().isNotEmpty(), "nestest.nes resource should be non-empty")
        }
    }

    @Test
    fun `nestestBytes returns the full 24592-byte iNES image`() {
        // The exact iNES byte count: 16-byte header + 16 384-byte PRG + 8 192-byte CHR
        // = 24 592. This has been the canonical fixture size for as long as the project
        // has shipped the ROM in git, so a regression on this number almost certainly
        // means someone replaced the resource with a corrupted or stub copy.
        val bytes = TestRoms.nestestBytes()
        assertEquals(24_592, bytes.size, "nestest.nes byte count drift — was the resource replaced?")
        // iNES magic: the first three bytes are "NES" — same check RomUtils.Path.load()
        // performs for header validity, here pinned at the source.
        val magic = String(bytes.copyOfRange(0, 3))
        assertEquals("NES", magic, "nestest.nes must start with the iNES magic header")
        assertEquals(0x1A.toByte(), bytes[3], "iNES byte 4 must be 0x1A (Format byte)")
    }

    @Test
    fun `nestestBytes and nestestPath agree byte-for-byte`() {
        // The two accessors should describe the same ROM. If they ever diverge, a test
        // that uses one form wouldn't catch a bug introduced in the other.
        val fromBytes = TestRoms.nestestBytes()
        val fromPath = Files.readAllBytes(TestRoms.nestestPath())
        assertArrayEquals(fromBytes, fromPath, "TestRoms.nestestBytes() and nestestPath() must read the same ROM")
    }

    @Test
    fun `nestestPath returns a real file that Nestlin_loadBytes can consume`() {
        val path = TestRoms.nestestPath()
        assertTrue(Files.exists(path), "nestestPath() must return an existing file")
        assertTrue(path.isAbsolute, "nestestPath() must return an absolute path so external processes can open it")

        // End-to-end: the path-form ROM is consumable by the production code path
        // (Path.load() → GamePak(bytes, "nestest")) and round-trips back to the same bytes.
        val bytesFromPath = path.load()!!
        val pak = GamePak(bytesFromPath, "nestest")
        assertEquals("nestest", pak.name)
        // mapper is on GamePak's Header property, not on GamePak itself.
        assertEquals(0, pak.header.mapper)
    }

    @Test
    fun `lookup is independent of user_dir point to the filesystem root`() {
        // The bug-shape: if the implementation accidentally reached for the ROM via a
        // relative path that got resolved against `user.dir`, pointing `user.dir` at the
        // filesystem root would break the lookup on any platform where the actual file is
        // not at "/nestest.nes". The classpath-backed design ignores `user.dir` entirely.
        val unreachableCwd = if (System.getProperty("os.name").lowercase().contains("windows"))
            "C:\\Windows"
        else
            "/"
        System.setProperty("user.dir", unreachableCwd)

        // These calls must still succeed even though user.dir no longer points at the repo.
        val bytes = TestRoms.nestestBytes()
        assertEquals(24_592, bytes.size, "nestestBytes() must not depend on user.dir")

        val path = TestRoms.nestestPath()
        assertTrue(Files.exists(path), "nestestPath() must not depend on user.dir — got $path")
    }

    @Test
    fun `Nestlin_loadBytes can boot the ROM end-to-end`() {
        // Smoke: the new bytes-only loader (the API most migrated tests now use) takes
        // the ROM from TestRoms, constructs a GamePak, and lands the CPU inside nestest's
        // automation region at $C000 — exactly what CpuTest verifies, just via the
        // higher-level helper instead of directly through GamePak().
        val nes = Nestlin().apply {
            loadBytes(TestRoms.nestestBytes())
            powerReset()
        }
        // The PRG/RAM mapping test in CpuTest proves the PC starts at $C000 in automation
        // mode; we don't re-pin the exact PC here (that's CpuTest's job) — we just need
        // a successful boot signal, which is "a mapper installed and CPU is executing."
        assertEquals(0, nes.cpu.currentGame?.header?.mapper,
            "nestest is mapper 0 (NROM); loader must have wired it into cpu.currentGame")
    }
}
