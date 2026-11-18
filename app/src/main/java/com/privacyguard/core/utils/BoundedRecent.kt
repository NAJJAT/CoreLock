package com.privacyguard.core.utils

/**
 * A key → time map with a hard cap: past [capacity] keys, the one used longest ago goes
 * first. The packet thread and other threads share it, so every call takes one short lock;
 * each call is O(1), and touching a key that is already there allocates nothing.
 *
 * Why a hard cap (bug-fix round 2): the service's per-tuple tables used to drop only
 * entries older than their time window, so a burst of distinct tuples within the window
 * grew them without limit and every new entry scanned the whole table.
 */
class BoundedRecentMap<K : Any>(private val capacity: Int) {
    init { require(capacity > 0) }

    // Access order: put() and get() of an existing key move it to the newest end in place.
    private val map = object : LinkedHashMap<K, Long>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<K, Long>?) = size > capacity
    }

    /** Records [time] for [key]; the time stored before, or null. */
    @Synchronized fun put(key: K, time: Long): Long? = map.put(key, time)

    /** The stored time for [key], or null. */
    @Synchronized fun get(key: K): Long? = map[key]

    @Synchronized fun remove(key: K): Long? = map.remove(key)

    @Synchronized fun clear() = map.clear()

    val size: Int @Synchronized get() = map.size
}

/**
 * A set with a hard cap: past [capacity] members, the oldest added goes first. Used for
 * "already told the user" sets, so a stream of new names, packages or hashes can't grow
 * memory without limit; an evicted member may be notified once more, which is harmless.
 */
class BoundedSet<K : Any>(private val capacity: Int) {
    init { require(capacity > 0) }

    private val set = object : LinkedHashMap<K, Unit>(16, 0.75f, false) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<K, Unit>?) = size > capacity
    }

    /** True when [key] was not a member (it is now). */
    @Synchronized fun add(key: K): Boolean = set.put(key, Unit) == null

    @Synchronized operator fun contains(key: K): Boolean = set.containsKey(key)

    @Synchronized fun clear() = set.clear()

    val size: Int @Synchronized get() = set.size
}
