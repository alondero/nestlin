package com.github.alondero.nestlin.cheat

import java.util.Collections

/**
 * Session configuration, separate from emulated storage and save states.
 * Mutate only while emulation is stopped. Reads perform no allocations or writes.
 */
internal class CheatEngine {
    private class Configuration(val cheats: List<Cheat>, val byAddress: Array<List<CheatCode>?>?)

    // Publish the list and its completed index together across UI/emulation threads.
    @Volatile
    private var configuration = Configuration(emptyList(), null)

    val cheats: List<Cheat> get() = configuration.cheats

    fun replace(cheats: List<Cheat>) {
        val snapshot = Collections.unmodifiableList(cheats.toList())
        val enabled = snapshot.filter { it.enabled }.map { it.code }
        val index = if (enabled.isEmpty()) null else arrayOfNulls<List<CheatCode>>(0x10000).also { table ->
            enabled.groupBy { backingAddress(it.address) }.forEach { (address, codes) -> table[address] = codes }
        }
        configuration = Configuration(snapshot, index)
    }

    fun apply(address: Int, original: Byte): Byte {
        val table = configuration.byAddress ?: return original
        if (address !in 0..0xFFFF) return original
        val codes = table[backingAddress(address)] ?: return original
        for (code in codes) {
            // Every compare sees the mapper/RAM byte, never a preceding cheat's replacement.
            if (code.compare == null || code.compare == (original.toInt() and 0xFF)) return code.value.toByte()
        }
        return original
    }

    private fun backingAddress(address: Int): Int = if (address < 0x2000) address and 0x7FF else address
}
