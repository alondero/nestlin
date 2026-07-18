package com.github.alondero.nestlin.gamepak

import com.natpryce.hamkrest.assertion.assertThat
import com.natpryce.hamkrest.equalTo
import org.junit.jupiter.api.Test

/**
 * Unit tests for [MapperStateSnapshot]'s equality contract (issue #99).
 *
 * `equals`/`hashCode` are hand-written (not data-class-generated) because [ByteArray]
 * fields need content comparison, not reference identity. The regression this guards
 * against: two snapshots with identical banking registers but divergent CHR-RAM or
 * PRG-RAM comparing as equal, which would silently green-light any future
 * `assertThat(mapper.snapshot(), equalTo(expected))` RAM round-trip assertion.
 */
class MapperStateSnapshotTest {

    private fun snapshot(
        chrRam: ByteArray? = null,
        prgRam: ByteArray? = null
    ) = MapperStateSnapshot(
        mapperId = 1,
        type = "TEST",
        banks = mapOf("prg" to 0),
        registers = mapOf("ctrl" to 0),
        irqState = null,
        chrRam = chrRam,
        prgRam = prgRam
    )

    // ---- chrRam ----

    @Test
    fun `snapshots with identical chrRam contents are equal`() {
        val a = snapshot(chrRam = byteArrayOf(0, 1, 2))
        val b = snapshot(chrRam = byteArrayOf(0, 1, 2))
        assertThat(a == b, equalTo(true))
        assertThat(a.hashCode() == b.hashCode(), equalTo(true))
    }

    @Test
    fun `snapshots differing by a single chrRam byte are not equal`() {
        val a = snapshot(chrRam = byteArrayOf(0, 1, 2))
        val b = snapshot(chrRam = byteArrayOf(0, 1, 3))
        assertThat(a == b, equalTo(false))
    }

    @Test
    fun `one null and one non-null chrRam are not equal`() {
        val a = snapshot(chrRam = byteArrayOf(0, 1, 2))
        val b = snapshot(chrRam = null)
        assertThat(a == b, equalTo(false))
        assertThat(b == a, equalTo(false))
    }

    // ---- prgRam ----

    @Test
    fun `snapshots with identical prgRam contents are equal`() {
        val a = snapshot(prgRam = byteArrayOf(9, 8, 7))
        val b = snapshot(prgRam = byteArrayOf(9, 8, 7))
        assertThat(a == b, equalTo(true))
        assertThat(a.hashCode() == b.hashCode(), equalTo(true))
    }

    @Test
    fun `snapshots differing by a single prgRam byte are not equal`() {
        val a = snapshot(prgRam = byteArrayOf(9, 8, 7))
        val b = snapshot(prgRam = byteArrayOf(9, 8, 6))
        assertThat(a == b, equalTo(false))
    }

    @Test
    fun `one null and one non-null prgRam are not equal`() {
        val a = snapshot(prgRam = byteArrayOf(9, 8, 7))
        val b = snapshot(prgRam = null)
        assertThat(a == b, equalTo(false))
        assertThat(b == a, equalTo(false))
    }

    // ---- both null (the current case for mappers that don't expose RAM) ----

    @Test
    fun `snapshots with both RAM fields null are equal`() {
        assertThat(snapshot() == snapshot(), equalTo(true))
        assertThat(snapshot().hashCode() == snapshot().hashCode(), equalTo(true))
    }

    // ---- non-RAM fields still gate equality ----

    @Test
    fun `snapshots differing only by banks are not equal`() {
        val a = snapshot(chrRam = byteArrayOf(0, 1, 2))
        val b = MapperStateSnapshot(
            mapperId = 1, type = "TEST",
            banks = mapOf("prg" to 1),   // differs
            registers = mapOf("ctrl" to 0), irqState = null,
            chrRam = byteArrayOf(0, 1, 2), prgRam = null
        )
        assertThat(a == b, equalTo(false))
    }
}
