package com.privacyguard.vpn.mitm

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class InspectionSlotsTest {

    @Test fun reserves_up_to_the_limit_then_refuses() {
        val s = InspectionSlots(3)
        repeat(3) { assertTrue(s.tryReserve()) }
        assertFalse(s.tryReserve())
        s.release()
        assertTrue(s.tryReserve())
        assertEquals(3, s.active)
    }

    @Test fun release_once_gives_back_a_single_slot() {
        val s = InspectionSlots(2)
        s.tryReserve(); s.tryReserve()
        val r = s.releaseOnce()
        r(); r(); r()
        assertEquals(1, s.active)
    }

    @Test fun release_never_goes_negative() {
        val s = InspectionSlots(1)
        s.release()
        assertEquals(0, s.active)
        assertTrue(s.tryReserve()); assertFalse(s.tryReserve())
    }

    @Test fun limit_holds_under_contention() {
        val s = InspectionSlots(32)
        val got = AtomicInteger()
        val pool = Executors.newFixedThreadPool(8)
        val start = CountDownLatch(1)
        repeat(1_000) { pool.execute { start.await(); if (s.tryReserve()) got.incrementAndGet() } }
        start.countDown()
        pool.shutdown(); assertTrue(pool.awaitTermination(10, TimeUnit.SECONDS))
        assertEquals(32, got.get())
    }
}
