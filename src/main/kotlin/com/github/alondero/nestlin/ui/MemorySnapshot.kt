package com.github.alondero.nestlin.ui

import com.github.alondero.nestlin.toUnsignedInt

/**
 * Per-byte change direction produced by [MemorySnapshot.diff], consumed by
 * the Memory Editor's cell factory to colour each cell green (UP), blue
 * (DOWN), or default (UNCHANGED). Pure value — no UI, no animation, no
 * timing concerns. The fade animation is a separate concern owned by the
 * cell factory's age counter.
 */
enum class Direction { UNCHANGED, UP, DOWN }

/**
 * The Memory Editor's diff engine (issue #169).
 *
 * A [MemorySnapshot] captures one tick of the editor's 10 Hz refresh: the
 * bytes observed in the visible range, plus a per-byte [Direction] computed
 * by [diff]ing against the previous tick's bytes. The cell factory reads
 * [directionAt] to colour each byte — no animation state lives here; the
 * cell factory ages the highlight across ticks on its own.
 *
 * The class is intentionally a pure value: no JavaFX, no `Timeline`, no
 * `peek` reference. The refresh timer in [MemoryEditorWindow] owns the
 * I/O; the snapshot just stores the result. That separation keeps the
 * diff engine unit-testable on its own (see `MemorySnapshotTest`) and
 * lets us swap the renderer (TextFlow, Canvas, future WebView) without
 * re-validating the diff logic.
 *
 * **Boundary values.** Byte comparisons go through [toUnsignedInt] (the
 * project's standard unsigned-byte widening) so they treat bytes as
 * unsigned 0..255, not signed -128..127. That makes `0x00 → 0xFF` an UP
 * change and `0xFF → 0x00` a DOWN change — which is what a human watching
 * a counter wrap expects, and which the raw `Byte.compareTo` would
 * silently get wrong. Two dedicated tests pin the behaviour.
 */
data class MemorySnapshot(
    val bytes: ByteArray,
    val directions: Array<Direction>,
) {
    /**
     * Look up the direction at byte index [index], or [Direction.UNCHANGED]
     * for indices outside [directions]. Liberal on out-of-range lookups so
     * the cell factory's bounds checks don't have to perfectly mirror the
     * snapshot's bounds — a defensive default is cheaper than a crash.
     */
    fun directionAt(index: Int): Direction =
        if (index in directions.indices) directions[index] else Direction.UNCHANGED

    // The data-class auto-generated `equals`/`hashCode` would use reference
    // equality on the array fields (ByteArray and Array<Direction>), which
    // would break value semantics — two snapshots with the same bytes +
    // directions would compare unequal and have different hashCodes. The
    // override below restores content-based equality so future collections
    // that key on snapshot identity behave sanely.
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is MemorySnapshot) return false
        if (!bytes.contentEquals(other.bytes)) return false
        if (!directions.contentEquals(other.directions)) return false
        return true
    }

    override fun hashCode(): Int {
        var result = bytes.contentHashCode()
        result = 31 * result + directions.contentHashCode()
        return result
    }

    companion object {
        /**
         * Diff [previous] against [current] and return a snapshot carrying
         * per-byte directions. Throws if the arrays differ in length — the
         * caller (the refresh timer) owns the array shape and shouldn't be
         * able to pass a malformed pair.
         */
        fun diff(previous: ByteArray, current: ByteArray): MemorySnapshot {
            require(previous.size == current.size) {
                "MemorySnapshot.diff: array size mismatch " +
                    "(previous=${previous.size}, current=${current.size})"
            }
            val dirs = Array(current.size) { i ->
                val p = previous[i].toUnsignedInt()
                val c = current[i].toUnsignedInt()
                when {
                    c > p -> Direction.UP
                    c < p -> Direction.DOWN
                    else -> Direction.UNCHANGED
                }
            }
            return MemorySnapshot(current, dirs)
        }

        /**
         * Build a snapshot where every cell is forced to [Direction.UP]
         * (or whichever direction you pass). Used by
         * [MemoryEditorWindow.markAllChanged] to flash the grid on ROM
         * load / reset: the bytes are kept intact so the displayed text
         * stays correct, but the directions all light up uniformly for
         * one tick, then the fade animation decays them.
         */
        fun allChanged(
            bytes: ByteArray,
            direction: Direction = Direction.UP,
        ): MemorySnapshot =
            MemorySnapshot(bytes, Array(bytes.size) { direction })
    }
}
