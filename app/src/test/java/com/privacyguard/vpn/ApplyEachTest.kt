package com.privacyguard.vpn

import com.privacyguard.core.filter.FilterEngine
import com.privacyguard.core.filter.FilterRule
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.util.concurrent.atomic.AtomicInteger

/**
 * A rule or level change saved in the UI reaches the running tunnel only through a
 * collector in PrivacyVpnService. One failed reload (database locked, disk full)
 * used to end that collector for good: later changes were shown as saved but never
 * enforced until protection restarted.
 */
class ApplyEachTest {

    private fun block(id: String, ip: String) = FilterRule(
        id = id, label = id, action = FilterRule.Action.DENY, source = FilterRule.Source.USER,
        priority = FilterRule.HIGH_PRIORITY, matchIp = ip,
    )

    private fun FilterEngine.blocks(ip: String) =
        evaluate(10_123, "com.example", null, ip, 443, 6).isBlocked

    private fun waitFor(ms: Long, done: () -> Boolean): Boolean {
        val end = System.currentTimeMillis() + ms
        while (System.currentTimeMillis() < end) { if (done()) return true; Thread.sleep(10) }
        return done()
    }

    @Test
    fun a_failed_reload_is_retried_and_the_collector_keeps_running() {
        // The service's scope: a failure that escapes is logged, not rethrown.
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default + WorkerGuard.coroutineHandler)
        val engine = FilterEngine()
        val version = MutableStateFlow(1L)
        val calls = AtomicInteger()
        val failuresBefore = WorkerGuard.failures.get()
        try {
            val job = scope.launch {
                WorkerGuard.applyEach(version, "rules", retryMs = 20) { v ->
                    // The first reload fails, as a locked database would.
                    if (calls.incrementAndGet() == 1) throw java.io.IOException("database is locked")
                    engine.setRules(if (v == 1L) listOf(block("a", "1.2.3.4")) else listOf(block("b", "5.6.7.8")))
                }
            }

            assertTrue("the rule was never applied after the failed reload", waitFor(2_000) { engine.blocks("1.2.3.4") })
            assertTrue(WorkerGuard.failures.get() > failuresBefore)

            // Still collecting: the next change is applied too.
            version.value = 2L
            assertTrue("a later change was not applied", waitFor(2_000) { engine.blocks("5.6.7.8") })
            assertFalse(engine.blocks("1.2.3.4"))
            assertTrue(job.isActive)
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun the_service_applies_rules_and_level_through_the_retrying_collector() {
        val src = File("src/main/java/com/privacyguard/platform/android/PrivacyVpnService.kt").readText()
        assertTrue(src.contains("WorkerGuard.applyEach(RuleSyncBus.version"))
        assertTrue(src.contains("WorkerGuard.applyEach(settings.protectionLevel"))
        assertFalse(src.contains("RuleSyncBus.version.collect"))
    }
}
