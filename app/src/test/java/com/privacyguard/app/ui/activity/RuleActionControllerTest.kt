package com.privacyguard.app.ui.activity

import com.privacyguard.core.filter.FilterRule
import com.privacyguard.core.session.BlockSource
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.ConscryptMode

/** §9 Phase 4: the malware warning comes before any Allow; Undo deletes the rule just created. */
@RunWith(RobolectricTestRunner::class)
@ConscryptMode(ConscryptMode.Mode.OFF)
class RuleActionControllerTest {

    /** In-memory rules; [malware] are the names on a malware list. */
    private class FakeStore(val malware: Set<String> = emptySet()) : RuleStore {
        val rules = linkedMapOf<String, FilterRule>()
        override suspend fun isMalwareNow(host: String) = host in malware
        override suspend fun apply(rule: FilterRule): ActivityRepository.Applied {
            val previous = rules[rule.id]
            rules[rule.id] = rule
            return ActivityRepository.Applied(rule, previous)
        }
        override suspend fun undo(applied: ActivityRepository.Applied) {
            if (applied.previous != null) rules[applied.rule.id] = applied.previous else rules.remove(applied.rule.id)
        }
        override suspend fun removeUserRule(id: String): FilterRule? = rules[id]?.takeIf { it.source == FilterRule.Source.USER }?.also { rules.remove(id) }
        override suspend fun restore(rule: FilterRule) { rules[rule.id] = rule }
    }

    // Unconfined + a store that never suspends: every action finishes inside perform().
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)

    private fun controller(store: RuleStore) = RuleActionController(RuntimeEnvironment.getApplication(), scope, store)

    private fun item(host: String = "ads.example.com", lookup: Boolean = false, reason: ActivityReason = ActivityReason.ADS_LIST, ruleId: String? = null) =
        ActivityItem(
            id = "c1", isLookup = lookup, timestamp = 1L, packageName = "com.android.chrome", appName = "Chrome",
            host = host, ip = "203.0.113.7", port = 443, protocol = "TCP", blocked = true,
            blockSource = if (lookup) BlockSource.DNS else BlockSource.SNI, reason = reason, ruleId = ruleId,
        )

    @Test
    fun allow_for_app_creates_one_high_priority_rule_and_undo_deletes_it() {
        val store = FakeStore()
        val c = controller(store)
        var done = false
        c.perform(ActivityAction.ALLOW_FOR_APP, item()) { done = true }
        assertTrue(done)
        val rule = store.rules.values.single()
        assertEquals(FilterRule.Action.ALLOW, rule.action)
        assertEquals("com.android.chrome", rule.matchPackage)
        assertEquals(FilterRule.HIGH_PRIORITY, rule.priority)
        assertFalse(rule.overridesSecurity)

        val message = c.message.value!!
        assertEquals("Allowed. Try the app again.", message.text)
        message.undo!!.invoke()
        assertTrue("Undo deletes the rule just created", store.rules.isEmpty())
    }

    @Test
    fun undo_puts_back_a_rule_that_existed_before() {
        val store = FakeStore()
        val older = ActivityRules.allowEverywhere("ads.example.com", "My old label")
        store.rules[older.id] = older
        val c = controller(store)
        c.perform(ActivityAction.ALLOW_EVERYWHERE, item())
        c.message.value!!.undo!!.invoke()
        assertEquals("My old label", store.rules[older.id]?.label)
    }

    @Test
    fun dns_layer_allow_is_everywhere_even_if_for_app_was_asked() {
        val store = FakeStore()
        controller(store).perform(ActivityAction.ALLOW_FOR_APP, item(lookup = true))
        assertNull("D3: no per-app rule for a lookup", store.rules.values.single().matchPackage)
    }

    @Test
    fun malware_shows_the_warning_and_creates_nothing_until_confirmed() {
        val store = FakeStore(malware = setOf("bad.example"))
        val c = controller(store)
        var done = false
        c.perform(ActivityAction.ALLOW_ANYWAY, item(host = "bad.example", reason = ActivityReason.MALWARE_LIST)) { done = true }
        assertFalse(done)
        assertTrue("no rule before the warning is confirmed", store.rules.isEmpty())
        val pending = c.malwareWarning.value
        assertNotNull(pending)
        assertEquals(ActivityAction.ALLOW_ANYWAY, pending!!.action)

        c.perform(pending.action, pending.item, confirmedMalware = true) { done = true }
        assertTrue(done)
        val rule = store.rules.values.single()
        assertTrue(rule.overridesSecurity)
        assertEquals("override stays as narrow as where it was found", "com.android.chrome", rule.matchPackage)
        assertNull(c.malwareWarning.value)
    }

    @Test
    fun an_ad_row_that_is_now_on_a_malware_list_also_asks_first() {
        // The list said "ads" when the screen loaded; the check at tap time says malware.
        val store = FakeStore(malware = setOf("ads.example.com"))
        val c = controller(store)
        c.perform(ActivityAction.ALLOW_EVERYWHERE, item())
        assertTrue(store.rules.isEmpty())
        assertEquals(ActivityAction.ALLOW_EVERYWHERE, c.malwareWarning.value?.action)
    }

    @Test
    fun remove_my_rule_deletes_only_a_user_rule_and_undo_restores_it() {
        val store = FakeStore()
        val mine = ActivityRules.blockEverywhere("site.example", null, "Block site")!!
        val system = mine.copy(id = "sys", source = FilterRule.Source.SYSTEM)
        store.rules[mine.id] = mine
        store.rules[system.id] = system
        val c = controller(store)

        c.perform(ActivityAction.REMOVE_MY_RULE, item(host = "site.example", reason = ActivityReason.USER_RULE, ruleId = "sys"))
        assertTrue("a system rule is never removed here", "sys" in store.rules)

        c.perform(ActivityAction.REMOVE_MY_RULE, item(host = "site.example", reason = ActivityReason.USER_RULE, ruleId = mine.id))
        assertFalse(mine.id in store.rules)
        c.message.value!!.undo!!.invoke()
        assertTrue(mine.id in store.rules)
    }

    @Test
    fun undo_tells_the_caller_so_a_hidden_row_comes_back() {
        val store = FakeStore()
        val c = controller(store)
        var undone = false
        c.perform(ActivityAction.ALLOW_EVERYWHERE, item(), onUndone = { undone = true })
        assertFalse(undone)
        c.message.value!!.undo!!.invoke()
        assertTrue(undone)
        assertTrue(store.rules.isEmpty())
    }

    @Test
    fun keep_blocked_changes_nothing() {
        val store = FakeStore()
        var done = false
        controller(store).perform(ActivityAction.KEEP_BLOCKED, item()) { done = true }
        assertTrue(done)
        assertTrue(store.rules.isEmpty())
    }
}
