package com.privacyguard.vpn.mitm

import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/**
 * How many connections HTTPS inspection decrypts at once. Each one holds blocking
 * threads for its whole life, so without a bound a busy browser could use up the
 * threads the rest of the app needs. A connection that finds no free slot is passed
 * through without decryption (name blocking has already been applied to it).
 */
class InspectionSlots(val max: Int) {

    private val inUse = AtomicInteger(0)

    val active: Int get() = inUse.get()

    /** Takes a slot if one is free. Every true must be matched by exactly one [release]. */
    fun tryReserve(): Boolean {
        while (true) {
            val n = inUse.get()
            if (n >= max) return false
            if (inUse.compareAndSet(n, n + 1)) return true
        }
    }

    /** Gives a slot back; never goes below zero. */
    fun release() {
        while (true) {
            val n = inUse.get()
            if (n <= 0) return
            if (inUse.compareAndSet(n, n - 1)) return
        }
    }

    /** A release that runs at most once, for paths that can end in more than one place. */
    fun releaseOnce(): () -> Unit {
        val done = AtomicBoolean(false)
        return { if (done.compareAndSet(false, true)) release() }
    }
}
