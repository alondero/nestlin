package com.github.alondero.nestlin.rewind

import com.natpryce.hamkrest.assertion.assertThat
import com.natpryce.hamkrest.equalTo
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test

/**
 * Unit tests for the rewind ring buffer (issue #52).
 *
 * The buffer is a pure data structure: it knows nothing about savestates beyond
 * "a snapshot is an opaque [ByteArray]". The emulator-facing semantics it must honour:
 *
 *  - **capture** appends the newest snapshot; once full it drops the OLDEST (ring behaviour).
 *  - **rewind(n)** walks the playhead back by up to n snapshots and returns the snapshot now
 *    at the head, never dropping below the single oldest snapshot (the rewind floor).
 *  - **clear** empties it (per-ROM reset on Load Game / Hard Reset).
 *
 * Snapshots here are 1-byte arrays whose single byte is a frame id, so assertions can read
 * "which frame did we rewind to" directly.
 */
class RewindBufferTest {

    private fun frame(id: Int) = byteArrayOf(id.toByte())
    private fun ByteArray.id() = this[0].toInt() and 0xFF

    @Test
    fun `capacity must be positive`() {
        assertThrows(IllegalArgumentException::class.java) { RewindBuffer(0) }
        assertThrows(IllegalArgumentException::class.java) { RewindBuffer(-5) }
    }

    @Test
    fun `a fresh buffer is empty and cannot rewind`() {
        val buf = RewindBuffer(10)
        assertThat(buf.size, equalTo(0))
        assertThat(buf.canRewind, equalTo(false))
        assertNull(buf.rewind())
    }

    @Test
    fun `capture grows the buffer up to capacity`() {
        val buf = RewindBuffer(3)
        buf.capture(frame(0))
        buf.capture(frame(1))
        assertThat(buf.size, equalTo(2))
        buf.capture(frame(2))
        buf.capture(frame(3))  // overflow: drops frame 0
        assertThat(buf.size, equalTo(3))
    }

    @Test
    fun `overflow drops the oldest snapshot first`() {
        val buf = RewindBuffer(3)
        for (i in 0..5) buf.capture(frame(i))  // 0..5, capacity 3 -> keeps 3,4,5
        // Rewinding from the newest (5) walks 5 -> 4 -> 3 (floor), never reaching 0/1/2.
        assertThat(buf.rewind()!!.id(), equalTo(4))
        assertThat(buf.rewind()!!.id(), equalTo(3))
        assertThat(buf.rewind()!!.id(), equalTo(3))  // floored at oldest survivor
    }

    @Test
    fun `rewind of one step walks back one snapshot at a time`() {
        val buf = RewindBuffer(10)
        for (i in 0..4) buf.capture(frame(i))  // [0,1,2,3,4]
        // The live machine is just past frame 4, so the first step back lands on 3.
        assertThat(buf.rewind(1)!!.id(), equalTo(3))
        assertThat(buf.rewind(1)!!.id(), equalTo(2))
        assertThat(buf.rewind(1)!!.id(), equalTo(1))
        assertThat(buf.rewind(1)!!.id(), equalTo(0))
    }

    @Test
    fun `rewind floors at the oldest snapshot and never returns null while non-empty`() {
        val buf = RewindBuffer(10)
        for (i in 0..2) buf.capture(frame(i))  // [0,1,2]
        assertThat(buf.rewind(1)!!.id(), equalTo(1))
        assertThat(buf.rewind(1)!!.id(), equalTo(0))
        assertThat(buf.rewind(1)!!.id(), equalTo(0))  // stays put at the floor
        assertThat(buf.canRewind, equalTo(false))     // only the floor snapshot remains
    }

    @Test
    fun `multi-step rewind skips intermediate snapshots (3x scrub)`() {
        val buf = RewindBuffer(100)
        for (i in 0..9) buf.capture(frame(i))  // [0..9]
        // 3x scrub: each step jumps back 3 snapshots.
        assertThat(buf.rewind(3)!!.id(), equalTo(6))  // dropped 9,8,7 -> head 6
        assertThat(buf.rewind(3)!!.id(), equalTo(3))  // dropped 6,5,4 -> head 3
        assertThat(buf.rewind(3)!!.id(), equalTo(0))  // dropped 3,2,1 -> head 0
        assertThat(buf.rewind(3)!!.id(), equalTo(0))  // floored
    }

    @Test
    fun `multi-step rewind near the floor stops at the oldest survivor`() {
        val buf = RewindBuffer(100)
        for (i in 0..3) buf.capture(frame(i))  // [0,1,2,3]
        // Asking for 3 steps with only enough room for 3 removals still keeps the floor.
        assertThat(buf.rewind(3)!!.id(), equalTo(0))
        assertThat(buf.size, equalTo(1))
    }

    @Test
    fun `capturing after a rewind continues the timeline from the rewound head`() {
        val buf = RewindBuffer(100)
        for (i in 0..4) buf.capture(frame(i))  // [0,1,2,3,4]
        buf.rewind(1)                           // head now 3, buffer [0,1,2,3]
        buf.rewind(1)                           // head now 2, buffer [0,1,2]
        // User releases at frame 2; forward play resumes and captures new frames.
        buf.capture(frame(99))                  // buffer [0,1,2,99]
        assertThat(buf.size, equalTo(4))
        // The frames we rewound past (3,4) are gone — history was rewritten.
        assertThat(buf.rewind(1)!!.id(), equalTo(2))
        assertThat(buf.rewind(1)!!.id(), equalTo(1))
    }

    @Test
    fun `clear empties the buffer`() {
        val buf = RewindBuffer(10)
        for (i in 0..4) buf.capture(frame(i))
        buf.clear()
        assertThat(buf.size, equalTo(0))
        assertThat(buf.canRewind, equalTo(false))
        assertNull(buf.rewind())
    }
}
