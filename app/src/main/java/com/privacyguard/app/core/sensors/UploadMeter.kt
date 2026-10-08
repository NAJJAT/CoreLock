package com.privacyguard.app.core.sensors

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLongArray

/**
 * Bytes each app (UID) sent through the tunnel, in 5-second buckets over the
 * last 5 minutes, so "the camera opened, then the app uploaded" can be checked
 * for a ±60 s window. Fed from the VPN forwarders; costs one map lookup and one
 * atomic add per outgoing packet.
 */
object UploadMeter {

    const val BUCKET_MS = 5_000L
    private const val BUCKETS = 60 // 5 minutes

    private class Ring {
        val bytes = AtomicLongArray(BUCKETS)
        val slotOf = AtomicLongArray(BUCKETS) // which 5-s slot each bucket currently holds
    }

    private val rings = ConcurrentHashMap<Int, Ring>()

    fun record(uid: Int, bytes: Int, now: Long = System.currentTimeMillis()) {
        if (uid < 0 || bytes <= 0) return
        val ring = rings.getOrPut(uid) { Ring() }
        val slot = now / BUCKET_MS
        val i = (slot % BUCKETS).toInt()
        val held = ring.slotOf.get(i)
        if (held != slot && ring.slotOf.compareAndSet(i, held, slot)) ring.bytes.set(i, 0)
        ring.bytes.addAndGet(i, bytes.toLong())
    }

    /** Bytes [uid] sent between [from] and [to] (bucket precision; only the last 5 minutes). */
    fun bytesBetween(uid: Int, from: Long, to: Long): Long {
        val ring = rings[uid] ?: return 0
        val first = from / BUCKET_MS
        val last = to / BUCKET_MS
        var total = 0L
        for (i in 0 until BUCKETS) {
            val slot = ring.slotOf.get(i)
            if (slot in first..last) total += ring.bytes.get(i)
        }
        return total
    }

    fun clear() = rings.clear()
}
