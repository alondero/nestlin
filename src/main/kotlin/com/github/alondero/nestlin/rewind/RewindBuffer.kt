package com.github.alondero.nestlin.rewind

/**
 * A bounded ring buffer of emulator savestate snapshots backing the hold-Backspace
 * rewind feature (issue #52).
 *
 * Each entry is an opaque savestate blob (`SaveState.save` output). One snapshot is
 * captured per emulated frame; at 60 fps a 10-second window is 600 entries of ~10 KB,
 * roughly 6 MB. When the buffer is full, the OLDEST snapshot is dropped to make room —
 * standard ring behaviour, so the window always covers "the last N frames".
 *
 * ### Rewind semantics
 *
 * The newest snapshot sits at the *head* of the timeline. [rewind] walks the playhead
 * backward by removing the newest snapshot(s) and returning the one now at the head — the
 * caller loads that snapshot to move the machine into the past. The single oldest snapshot
 * is a floor: [rewind] never removes it, so a user who holds Backspace past the start of
 * the buffer simply parks on the oldest available state rather than running off the end.
 *
 * Capturing again after a rewind appends onto the (now shorter) timeline, which is exactly
 * the "rewrote history" behaviour you want: frames the user scrubbed past are gone.
 *
 * ### Threading
 *
 * [capture] and [rewind] run on the emulation thread; [clear] runs on the JavaFX thread but
 * only while the emulation thread is stopped (Load Game / Hard Reset go through
 * `stopEmulation()` first). All methods are nonetheless `@Synchronized` so the structure is
 * self-contained and safe even if a future caller relaxes that ordering.
 */
class RewindBuffer(val capacity: Int) {

    init {
        require(capacity > 0) { "Rewind buffer capacity must be positive, was $capacity" }
    }

    // Newest snapshot at the end (addLast), oldest at the start (index 0).
    private val entries = ArrayDeque<ByteArray>()

    /** Number of snapshots currently retained. */
    val size: Int
        @Synchronized get() = entries.size

    /** True when at least two snapshots remain, i.e. [rewind] can still move further back. */
    val canRewind: Boolean
        @Synchronized get() = entries.size > 1

    /** Append [state] as the newest snapshot, evicting the oldest if at [capacity]. */
    @Synchronized
    fun capture(state: ByteArray) {
        if (entries.size >= capacity) entries.removeFirst()
        entries.addLast(state)
    }

    /**
     * Move the playhead back by up to [steps] snapshots and return the snapshot now at the
     * head of the timeline (the most-recent survivor). Removes up to [steps] of the newest
     * snapshots but never drops below the single oldest snapshot (the rewind floor).
     *
     * Returns `null` only when the buffer is empty.
     */
    @Synchronized
    fun rewind(steps: Int = 1): ByteArray? {
        repeat(steps) {
            if (entries.size > 1) entries.removeLast()
        }
        return entries.lastOrNull()
    }

    /** Drop every snapshot. Called when the ROM identity changes (Load Game / Hard Reset). */
    @Synchronized
    fun clear() {
        entries.clear()
    }
}
