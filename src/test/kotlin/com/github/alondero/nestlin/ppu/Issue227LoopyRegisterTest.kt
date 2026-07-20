package com.github.alondero.nestlin.ppu

import com.github.alondero.nestlin.Memory
import com.github.alondero.nestlin.toSignedByte
import com.natpryce.hamkrest.assertion.assertThat
import com.natpryce.hamkrest.equalTo
import org.junit.jupiter.api.Test

/**
 * Regression tests for GitHub issue #227 — grouped PPU loopy-register /
 * timing inaccuracies found during the 2026-07 subsystem accuracy review.
 *
 * Each test maps to one of the six bullets in the issue. Tests that already
 * pass without code changes confirm a fix landed in a prior commit
 * (notably ae4329d, "PPUMASK layer gating, sprite overflow, greyscale/
 * emphasis, $2007 palette buffer, $2006 fine-Y, Frame IntArray"). Tests
 * that fail mark bugs still in the codebase.
 */
class Issue227LoopyRegisterTest {

    // ---- 1. $2006 first write must clear fine-Y bit 2 (already fixed) -----

    @Test
    fun `first $2006 write clears stale fine Y bit 2 from a prior $2005`() {
        val ppu = PpuAddressedMemory()
        // A $2005 second write can set fineY up to 7 (bit 2 set)...
        ppu[5] = 0x07.toSignedByte()   // first $2005: fine X = 7
        ppu[5] = 0xE7.toSignedByte()   // second $2005: coarse Y = 0x1C, fine Y = 7 (bit 2 set)

        // ...but the first $2006 write only carries fineY bits 0-1 and hardware
        // forces bit 14 of t (fineY bit 2) to ZERO.
        ppu[6] = 0x00.toSignedByte()

        assertThat("bit 14 of t forced zero", ppu.tempVRamAddress.fineYScroll, equalTo(0))
    }

    // ---- 2. coarse-Y == 30 edge case in incrementVerticalPosition -------

    @Test
    fun `coarse Y 30 advances to 31 on next increment, no toggle`() {
        // Hardware sequence when coarse Y starts at 30 (set via $2006):
        //   fineY overflows -> coarseY++ -> coarseY becomes 31
        // The 30 -> 31 transition is JUST an increment; the toggle fires when
        // coarse Y wraps from 29 -> 0, and the 31 -> 0 wrap is silent.
        // Nestlin currently does the wrong thing: it toggles AND zeroes at 30.
        val addr = VramAddress()
        addr.coarseYScroll = 30
        addr.fineYScroll = 7  // overflow so the increment reaches coarseY
        addr.verticalNameTable = false

        addr.incrementVerticalPosition()

        assertThat("30 should advance to 31", addr.coarseYScroll, equalTo(31))
        assertThat("30 -> 31 must NOT toggle nametable", addr.verticalNameTable, equalTo(false))
    }

    @Test
    fun `coarse Y 31 wraps to 0 on next increment, no toggle`() {
        val addr = VramAddress()
        addr.coarseYScroll = 31
        addr.fineYScroll = 7
        addr.verticalNameTable = false

        addr.incrementVerticalPosition()

        assertThat("31 should wrap to 0", addr.coarseYScroll, equalTo(0))
        assertThat("31 -> 0 must NOT toggle nametable", addr.verticalNameTable, equalTo(false))
    }

    @Test
    fun `coarse Y 29 wraps to 0 with vertical nametable toggle`() {
        val addr = VramAddress()
        addr.coarseYScroll = 29
        addr.fineYScroll = 7
        addr.verticalNameTable = false

        addr.incrementVerticalPosition()

        assertThat("29 should wrap to 0", addr.coarseYScroll, equalTo(0))
        assertThat("29 -> 0 MUST toggle nametable", addr.verticalNameTable, equalTo(true))
    }

    @Test
    fun `normal coarse Y 28 advances to 29 with no wrap or toggle`() {
        val addr = VramAddress()
        addr.coarseYScroll = 28
        addr.fineYScroll = 7
        addr.verticalNameTable = false

        addr.incrementVerticalPosition()

        assertThat("28 should advance to 29", addr.coarseYScroll, equalTo(29))
        assertThat("28 -> 29 must NOT toggle", addr.verticalNameTable, equalTo(false))
    }

    // ---- 3. $2007 palette read leaves nametable byte in the buffer (already fixed) ----

    @Test
    fun `palette read fills the read buffer with the nametable under the palette`() {
        val ppu = PpuAddressedMemory()
        ppu.ppuInternalMemory[0x2F00] = 0xAB.toSignedByte()   // nametable "under" $3F00
        ppu.ppuInternalMemory[0x3F00] = 0x21.toSignedByte()   // backdrop palette

        // First $2006 write: high byte of 0x3F00 = 0x3F (upper 6 bits of v).
        // Second $2006 write: low byte = 0x00 (lower 8 bits of v).
        ppu[6] = 0x3F.toSignedByte()
        ppu[6] = 0x00.toSignedByte()

        val paletteValue = ppu[7]
        assertThat("palette returned immediately", paletteValue, equalTo(0x21.toSignedByte()))

        // Move to 0x2000; the FIRST read returns the buffer, which must hold
        // the nametable byte from under the palette, not the palette value.
        ppu[6] = 0x20.toSignedByte()
        ppu[6] = 0x00.toSignedByte()

        val buffered = ppu[7]
        assertThat("buffer held nametable-under-palette", buffered, equalTo(0xAB.toSignedByte()))
    }

    // ---- 4. OAMADDR must offset sprite-evaluation start (not always sprite 0) ----

    @Test
    fun `sprite evaluation starts at the current OAMADDR not sprite 0`() {
        // 9 in-range sprites on scanline 100 at indices 0..8.
        // With OAMADDR = 0: scan starts at sprite 0; the first 8 fill secondary OAM,
        // sprite 8 is the 9th in-range and triggers overflow. Sprite 0 IS in slot 0.
        // With OAMADDR = 4 (= sprite 1): scan starts at sprite 1; sprites 1..8 fill
        // secondary OAM. Sprite 0 (encountered last after the wraparound) is the
        // 9th in-range and triggers overflow. Sprite 0 is NOT in secondary OAM.
        //
        // We verify this via sprite-0 hit, which fires only when an OPAQUE sprite-0
        // pixel overlaps an OPAQUE background pixel. We place sprite 0 at column 10
        // and give it opaque pixels; everything else is off-screen-X. We put an
        // opaque background tile at scanline 100 col 10.
        //
        // Bug (OAMADDR ignored): sprite 0 always in secondary OAM -> hit fires.
        // Fix (OAMADDR respected): with OAMADDR=4, sprite 0 is overflowed -> no hit.
        val memory = Memory()
        val ppu = Ppu(memory)

        // CHR: opaque tile 0 (pattern table 0) — all bits set, so every pixel opaque.
        val chr = ByteArray(0x2000)
        for (i in 0..7) {
            chr[i] = 0xFF.toSignedByte()
            chr[8 + i] = 0xFF.toSignedByte()
        }
        memory.ppuAddressedMemory.ppuInternalMemory.loadChrRom(chr)

        // Background palette index 1 -> non-backdrop colour so the bg pixel is opaque.
        memory.ppuAddressedMemory.ppuInternalMemory[0x3F11] = 0x30.toSignedByte()
        memory.ppuAddressedMemory.ppuInternalMemory[0x3F10] = 0x10.toSignedByte()

        // Fill nametable with tile 0 (every tile is opaque).
        for (addr in 0x2000..0x23BF) memory.ppuAddressedMemory.ppuInternalMemory[addr] = 0x00.toSignedByte()
        // Fill attribute table with "palette 1" (set bits 0,1 for top-left quadrant).
        for (addr in 0x23C0..0x23FF) memory.ppuAddressedMemory.ppuInternalMemory[addr] = 0x00.toSignedByte()

        // OAM: 9 in-range sprites at Y=99 (visible on scanlines 100..107).
        // Sprite 0 has X=10 (the only one we can see). The rest are off-screen-X.
        val oam = memory.ppuAddressedMemory.objectAttributeMemory
        for (i in 0..8) {
            oam[i * 4 + 0] = 99.toSignedByte()  // Y
            oam[i * 4 + 1] = 0x00.toSignedByte() // tile
            oam[i * 4 + 2] = 0x00.toSignedByte() // ATTR: opaque, in front
            oam[i * 4 + 3] = if (i == 0) 10.toSignedByte() else 200.toSignedByte()
        }
        // Off-screen all the rest.
        for (i in 9 until 64) oam[i * 4] = 0xF0.toSignedByte()

        // OAMADDR = 4 (start at sprite 1). With the fix, sprite 0 gets overflowed.
        memory.ppuAddressedMemory.oamAddress = 4.toSignedByte()

        // Render with bg AND sprites visible.
        memory.ppuAddressedMemory.mask.register = 0b0001_1000.toByte()

        // Tick up to 4 frames to let sprite evaluation pick up the new OAMADDR.
        var guard = 4 * 89342
        while (guard-- > 0) ppu.tick()

        // With the bug, sprite 0 is always in secondary OAM and renders at col 10
        // every scanline where bg is opaque. Sprite-0 hit fires. With the fix, sprite
        // 0 is overflowed on each eval cycle and never renders -> sprite-0 hit does
        // NOT fire.
        assertThat("sprite-0 hit must not fire when OAMADDR rotates sprite 0 out",
            memory.ppuAddressedMemory.status.sprite0Hit(), equalTo(false))
    }

    @Test
    fun `sprite evaluation with OAMADDR 0 puts sprite 0 in secondary OAM and triggers hit`() {
        // Companion test to the one above: same setup but OAMADDR=0. Sprite 0 IS in
        // secondary OAM, so the hit fires. This is the regression guard — if we
        // accidentally break OAMADDR=0 too, this catches it.
        val memory = Memory()
        val ppu = Ppu(memory)

        val chr = ByteArray(0x2000)
        for (i in 0..7) {
            chr[i] = 0xFF.toSignedByte()
            chr[8 + i] = 0xFF.toSignedByte()
        }
        memory.ppuAddressedMemory.ppuInternalMemory.loadChrRom(chr)
        memory.ppuAddressedMemory.ppuInternalMemory[0x3F11] = 0x30.toSignedByte()
        memory.ppuAddressedMemory.ppuInternalMemory[0x3F10] = 0x10.toSignedByte()
        for (addr in 0x2000..0x23BF) memory.ppuAddressedMemory.ppuInternalMemory[addr] = 0x00.toSignedByte()
        for (addr in 0x23C0..0x23FF) memory.ppuAddressedMemory.ppuInternalMemory[addr] = 0x00.toSignedByte()

        val oam = memory.ppuAddressedMemory.objectAttributeMemory
        for (i in 0..8) {
            oam[i * 4 + 0] = 99.toSignedByte()
            oam[i * 4 + 1] = 0x00.toSignedByte()
            oam[i * 4 + 2] = 0x00.toSignedByte()
            oam[i * 4 + 3] = if (i == 0) 10.toSignedByte() else 200.toSignedByte()
        }
        for (i in 9 until 64) oam[i * 4] = 0xF0.toSignedByte()

        memory.ppuAddressedMemory.oamAddress = 0.toSignedByte()
        memory.ppuAddressedMemory.mask.register = 0b0001_1000.toByte()

        var guard = 4 * 89342
        while (guard-- > 0) ppu.tick()

        assertThat("sprite-0 hit must fire when sprite 0 is in secondary OAM",
            memory.ppuAddressedMemory.status.sprite0Hit(), equalTo(true))
    }

    // ---- 5. Horizontal scroll copy timed at dot 257 of the prior line ----

    @Test
    fun `horizontal scroll is copied from t to v at dot 257 not at cycle 0`() {
        // Hardware: at NES dot 257 (== Nestlin cycle 256) of each rendering-enabled
        // scanline, v's horizontal bits are loaded from t. The previous line's
        // t-update thus takes effect for the NEXT visible scanline. Nestlin used
        // to do this copy at cycle 0 of the rendering line instead — the same
        // value, just one scanline late, so a $2005/$2006 write during the
        // sprite-fetch window of scanline N was picked up by scanline N+1 instead
        // of N+2.
        //
        // Test: set t.coarseX = 10, then tick to the pre-render line's cycle 257
        // (Nestlin cycle 256, after the copy fires). v.coarseX should equal 10
        // immediately. With the old code, v.coarseX would still be 0 here because
        // the copy wouldn't fire until cycle 0 of the NEXT scanline — and even
        // then, preloadFirstTwoTiles and fetchData have already advanced it past
        // 10 by the time we observe it.
        val memory = Memory()
        val ppu = Ppu(memory)
        memory.ppuAddressedMemory.mask.register = 0b0000_1000.toByte() // rendering on
        memory.ppuAddressedMemory.tempVRamAddress.coarseXScroll = 10

        // Tick to cycle 257 of the pre-render line. The cycle-256 copy fires
        // on the tick that takes us from cycle 256 to cycle 257, so check
        // v.coarseX at cycle 257 — strictly after the copy, before fetchData's
        // cycle-326/334 increments and before preloadFirstTwoTiles at cycle 0
        // of the next scanline.
        var guard = 4 * 89342
        while (guard-- > 0) {
            ppu.tick()
            if (ppu.currentScanline == ppu.region.preRenderScanline && ppu.currentCycle == 257) break
        }
        require(guard > 0) { "never reached pre-render cycle 257" }

        assertThat("v.coarseX reflects t.coarseX from pre-render dot-257 copy",
            memory.ppuAddressedMemory.vRamAddress.coarseXScroll, equalTo(10))
    }

    @Test
    fun `dot-257 copy preserves v coarse X across a scanline with no $2005 writes`() {
        // Setup: t.coarseX = 5. After the pre-render dot-257 copy, v.coarseX = 5.
        // Tick to the next cycle 257 (of scanline 0); the copy fires again but t
        // is unchanged, so v.coarseX stays 5. This sanity-checks that the copy
        // doesn't destabilise v mid-line.
        val memory = Memory()
        val ppu = Ppu(memory)
        memory.ppuAddressedMemory.mask.register = 0b0000_1000.toByte()
        memory.ppuAddressedMemory.tempVRamAddress.coarseXScroll = 5

        // Advance to pre-render cycle 257 (copy has fired).
        var guard = 4 * 89342
        while (guard-- > 0) {
            ppu.tick()
            if (ppu.currentScanline == ppu.region.preRenderScanline && ppu.currentCycle == 257) break
        }
        require(guard > 0)
        assertThat("v.coarseX = 5 immediately after pre-render dot-257 copy",
            memory.ppuAddressedMemory.vRamAddress.coarseXScroll, equalTo(5))

        // Tick to scanline 0, cycle 257 (the next dot-257). The copy fires
        // again; with no $2005 write in between, v.coarseX must still equal
        // t.coarseX = 5. Intermediate fetchData/preloadFirstTwoTiles increments
        // are reset by the dot-257 copy, which is exactly the invariant we want.
        while (guard-- > 0) {
            ppu.tick()
            if (ppu.currentScanline == 0 && ppu.currentCycle == 257) break
        }
        require(guard > 0)
        assertThat("dot-257 copy at scanline 0 cycle 257 leaves v.coarseX = 5",
            memory.ppuAddressedMemory.vRamAddress.coarseXScroll, equalTo(5))
    }

    // ---- 6. $2002 open-bus: low 5 bits decay, not the status register ----

    @Test
    fun `$2002 read returns open-bus decay in low 5 bits, not status register`() {
        val ppu = PpuAddressedMemory()
        // Status low 5 bits are undefined hardware-wise — open-bus decay of the
        // most recent PPU write. Write some non-zero value to a PPU register,
        // then read $2002 and check the low 5 bits reflect the bus.
        ppu[0] = 0xAB.toSignedByte() // PPUCTRL: high bits now carry 0xAB onto the bus
        val result = ppu[2]
        // bits 5-7 from status register (likely 0), bits 0-4 from open-bus = $AB & 0x1F = 0x0B
        assertThat("low 5 bits = open bus (0xAB & 0x1F)",
            result.toInt() and 0x1F, equalTo(0x0B))
    }

    @Test
    fun `$2002 read updates the open-bus decay to the next register write`() {
        val ppu = PpuAddressedMemory()
        ppu[0] = 0xFF.toSignedByte() // $2000 write: bus = $FF
        // Read $2002 — low 5 bits = 0xFF & 0x1F = 0x1F (open bus)
        assertThat(ppu[2].toInt() and 0x1F, equalTo(0x1F))

        ppu[1] = 0x00.toSignedByte() // $2001 write: bus = $00
        // Read $2002 — low 5 bits now = 0x00 & 0x1F = 0x00 (open bus decayed)
        assertThat(ppu[2].toInt() and 0x1F, equalTo(0x00))
    }

    @Test
    fun `$2002 read still exposes status bits 5 to 7 correctly`() {
        val ppu = PpuAddressedMemory()
        // Set vblank + sprite 0 hit + sprite overflow so bits 5-7 are all 1.
        ppu[0] = 0xAB.toSignedByte() // bus = 0xAB (low 5 = 0x0B)
        ppu.status.register = 0b1110_0000.toSignedByte()
        val result = ppu[2]
        assertThat("bit 7 = vblank", (result.toInt() shr 7) and 1, equalTo(1))
        assertThat("bit 6 = sprite 0 hit", (result.toInt() shr 6) and 1, equalTo(1))
        assertThat("bit 5 = sprite overflow", (result.toInt() shr 5) and 1, equalTo(1))
    }
}
