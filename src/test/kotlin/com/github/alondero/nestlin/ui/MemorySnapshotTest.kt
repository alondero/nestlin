package com.github.alondero.nestlin.ui

import com.github.alondero.nestlin.testutil.assertThrowsWithMessage
import com.natpryce.hamkrest.assertion.assertThat
import com.natpryce.hamkrest.equalTo
import org.junit.jupiter.api.Test

/**
 * Pure-logic tests for the Memory Editor's change-diff engine (issue #169).
 *
 * The [MemorySnapshot] class encapsulates the comparison between two byte arrays
 * taken at consecutive 10 Hz refresh ticks: the cell factory consults the
 * per-byte direction (UP / DOWN / UNCHANGED) to colour each cell green / blue
 * or leave it default. The diff is a pure function — no UI dependency — so
 * the boundary-value cases (unsigned wraparound) and the equal-arrays short
 * circuit are easy to exercise here without booting JavaFX.
 */
class MemorySnapshotTest {

    @Test
    fun `diff reports UNCHANGED for identical byte arrays`() {
        val prev = byteArrayOf(0x10, 0x20, 0x30)
        val curr = byteArrayOf(0x10, 0x20, 0x30)

        val snap = MemorySnapshot.diff(prev, curr)

        assertThat(snap.directions.toList(), equalTo(listOf(
            Direction.UNCHANGED,
            Direction.UNCHANGED,
            Direction.UNCHANGED,
        )))
    }

    @Test
    fun `diff reports UP when a byte strictly increases`() {
        val prev = byteArrayOf(0x10, 0x20, 0x30)
        val curr = byteArrayOf(0x10, 0x30, 0x30)

        val snap = MemorySnapshot.diff(prev, curr)

        assertThat(snap.directions[0], equalTo(Direction.UNCHANGED))
        assertThat(snap.directions[1], equalTo(Direction.UP))
        assertThat(snap.directions[2], equalTo(Direction.UNCHANGED))
    }

    @Test
    fun `diff reports DOWN when a byte strictly decreases`() {
        val prev = byteArrayOf(0x10, 0x20, 0x30)
        val curr = byteArrayOf(0x10, 0x10, 0x30)

        val snap = MemorySnapshot.diff(prev, curr)

        assertThat(snap.directions[0], equalTo(Direction.UNCHANGED))
        assertThat(snap.directions[1], equalTo(Direction.DOWN))
        assertThat(snap.directions[2], equalTo(Direction.UNCHANGED))
    }

    @Test
    fun `diff treats 0x00 to 0xFF as UP - unsigned wraparound in the increasing direction`() {
        // 0x00 → 0xFF in unsigned 8-bit is +255, so the direction is UP (a counter
        // wrapping from 0 to its max is "increasing", which is what a player
        // watching their health counter expect when it rolls over).
        val prev = byteArrayOf(0x00)
        val curr = byteArrayOf(0xFF.toByte())

        val snap = MemorySnapshot.diff(prev, curr)

        assertThat(snap.directions[0], equalTo(Direction.UP))
    }

    @Test
    fun `diff treats 0xFF to 0x00 as DOWN - unsigned wraparound in the decreasing direction`() {
        // 0xFF → 0x00 in unsigned 8-bit is -255, so the direction is DOWN. A byte
        // rolling over from its max to 0 is a decrease, even though the raw Byte
        // value sign-flips (the signed-Byte trap that motivated the and 0xFF).
        val prev = byteArrayOf(0xFF.toByte())
        val curr = byteArrayOf(0x00)

        val snap = MemorySnapshot.diff(prev, curr)

        assertThat(snap.directions[0], equalTo(Direction.DOWN))
    }

    @Test
    fun `diff on empty arrays yields an empty snapshot`() {
        val snap = MemorySnapshot.diff(byteArrayOf(), byteArrayOf())

        assertThat(snap.bytes.size, equalTo(0))
        assertThat(snap.directions.size, equalTo(0))
    }

    @Test
    fun `diff throws when array sizes do not match`() {
        val prev = byteArrayOf(0x10, 0x20)
        val curr = byteArrayOf(0x10, 0x20, 0x30)

        assertThrowsWithMessage<IllegalArgumentException>("size") {
            MemorySnapshot.diff(prev, curr)
        }
    }

    @Test
    fun `directionAt returns UNCHANGED for out-of-range indices`() {
        // The cell factory may ask for directions past the snapshot's end (e.g.
        // a defensive look-up); it should not crash. Mirrors the "be liberal in
        // what you accept" principle so the cell factory's bounds don't have
        // to perfectly match the snapshot's bounds.
        val snap = MemorySnapshot.diff(byteArrayOf(0x00), byteArrayOf(0x01))

        assertThat(snap.directionAt(0), equalTo(Direction.UP))
        assertThat(snap.directionAt(99), equalTo(Direction.UNCHANGED))
    }

    @Test
    fun `allChanged produces a snapshot where every cell is marked UP`() {
        // Used to flash the whole grid on ROM load / reset — the visible cells
        // light up uniformly for one tick and then the fade animation decays
        // them. Bytes are preserved (so the formatRow display stays correct
        // during the flash) but every direction is forced to UP.
        val bytes = byteArrayOf(0x10, 0x20, 0x30, 0x40)

        val snap = MemorySnapshot.allChanged(bytes)

        assertThat(snap.bytes.toList(), equalTo(bytes.toList()))
        assertThat(snap.directions.toList(), equalTo(listOf(
            Direction.UP,
            Direction.UP,
            Direction.UP,
            Direction.UP,
        )))
    }
}
