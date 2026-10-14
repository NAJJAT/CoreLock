package com.privacyguard.vpn

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** V-18: a failure on the packet path must not end the process. */
class WorkerGuardTest {

    @After
    fun tearDown() { WorkerGuard.onWorkerDied = null }

    @Test
    fun a_dying_worker_thread_is_reported_by_name() {
        val died = CountDownLatch(1)
        var name: String? = null
        WorkerGuard.onWorkerDied = { name = it; died.countDown() }

        WorkerGuard.thread({ throw IllegalStateException("boom") }, "tun-reader").start()

        assertTrue(died.await(2, TimeUnit.SECONDS))
        assertEquals("tun-reader", name)
    }

    @Test
    fun worker_threads_are_daemons_with_the_guard_installed() {
        val t = WorkerGuard.thread({}, "tcp-selector")
        assertTrue(t.isDaemon)
        assertEquals(WorkerGuard.threadHandler, t.uncaughtExceptionHandler)
    }

    @Test
    fun a_failing_coroutine_is_counted_and_its_siblings_run_on() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default + WorkerGuard.coroutineHandler)
        val before = WorkerGuard.failures.get()

        scope.launch { throw IllegalStateException("database full") }.join()
        val sibling = scope.launch { }
        sibling.join()

        assertEquals(before + 1, WorkerGuard.failures.get())
        assertFalse(sibling.isCancelled)
    }

    @Test
    fun guard_swallows_a_task_failure() {
        val before = WorkerGuard.failures.get()
        WorkerGuard.guard("dns") { throw java.io.IOException("socket") }
        assertEquals(before + 1, WorkerGuard.failures.get())
    }

    @Test
    fun restart_budget_allows_three_per_window_then_refuses() {
        val budget = RestartBudget(max = 3, windowMs = 600_000)
        assertTrue(budget.tryAcquire(0))
        assertTrue(budget.tryAcquire(1_000))
        assertTrue(budget.tryAcquire(2_000))
        assertFalse(budget.tryAcquire(3_000))
        // The first restart leaves the window: one more is allowed.
        assertTrue(budget.tryAcquire(600_000))
        assertFalse(budget.tryAcquire(600_001))
    }
}
