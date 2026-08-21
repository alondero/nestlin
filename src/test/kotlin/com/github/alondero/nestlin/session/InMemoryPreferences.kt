package com.github.alondero.nestlin.session

import java.util.prefs.AbstractPreferences

/**
 * Minimal in-memory [java.util.prefs.Preferences] implementation for
 * tests. The standard `Preferences.userRoot()` writes to the OS registry /
 * plist which is not hermetic; this lets every test run with a fresh
 * empty prefs tree.
 *
 * Only the methods the tests actually call are implemented. Adding new
 * methods to a class that touches the prefs tree without extending this
 * fake fails the build at compile time, which is the desired signal.
 */
internal class InMemoryPreferences : AbstractPreferences(null, "") {
    private val map: MutableMap<String, String> = mutableMapOf()
    private val children: MutableMap<String, AbstractPreferences> = mutableMapOf()

    override fun putSpi(key: String, value: String) { map[key] = value }
    override fun getSpi(key: String): String? = map[key]
    override fun removeSpi(key: String) { map.remove(key) }
    override fun keysSpi(): Array<String> = map.keys.toTypedArray()
    override fun childrenNamesSpi(): Array<String> = children.keys.toTypedArray()
    override fun childSpi(name: String): AbstractPreferences {
        val existing = children[name]
        if (existing != null) return existing
        val created = InMemoryPreferences()
        children[name] = created
        return created
    }
    override fun removeNodeSpi() { /* leaf node, nothing to do */ }
    override fun syncSpi() { /* no-op: in-memory */ }
    override fun flushSpi() { /* no-op: in-memory */ }
}