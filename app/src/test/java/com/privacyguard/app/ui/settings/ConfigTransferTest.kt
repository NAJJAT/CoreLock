package com.privacyguard.app.ui.settings

import com.privacyguard.app.ui.settings.ConfigTransfer.ReadResult
import com.privacyguard.app.ui.settings.ConfigTransfer.Refusal
import com.privacyguard.app.ui.settings.ConfigTransfer.RuleEntry
import com.privacyguard.core.filter.FilterRule
import com.privacyguard.core.metadata.EncryptionStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.ConscryptMode
import java.io.ByteArrayInputStream

/**
 * D2 / ADR 0013: the rules-and-settings file is untrusted input. Robolectric for org.json,
 * which is part of Android.
 */
@RunWith(RobolectricTestRunner::class)
@ConscryptMode(ConscryptMode.Mode.OFF)
class ConfigTransferTest {

    private fun file(rules: String = "[]", settings: String = "{}", version: Any = 1, format: String = ConfigTransfer.FORMAT) =
        """{"format":"$format","version":$version,"rules":$rules,"settings":$settings}"""

    private fun read(text: String) = ConfigTransfer.read(text.toByteArray())
    private fun ok(text: String) = read(text) as ReadResult.Ok
    private fun refused(text: String) = (read(text) as ReadResult.Refused).reason

    private fun userRule(id: String, action: FilterRule.Action, domain: String? = null, pkg: String? = null, ip: String? = null,
                         background: Boolean? = null, overrides: Boolean = false, enabled: Boolean = true) = FilterRule(
        id = id, label = "label $id", action = action, source = FilterRule.Source.USER, priority = FilterRule.HIGH_PRIORITY,
        isEnabled = enabled, matchDomain = domain, matchPackage = pkg, matchIp = ip, matchBackground = background,
        overridesSecurity = overrides,
    )

    @Test
    fun a_round_trip_keeps_every_rule_and_setting() {
        val rules = listOf(
            userRule("1", FilterRule.Action.DENY, domain = "ads.example.com"),
            userRule("2", FilterRule.Action.ALLOW, domain = "*.bank.example"),
            userRule("3", FilterRule.Action.DENY, pkg = "com.example.app", background = true),
            userRule("4", FilterRule.Action.DENY, ip = "10.0.0.0/8", enabled = false),
            userRule("5", FilterRule.Action.ALLOW, pkg = "com.whatsapp", domain = "cdn.example.com"),
        )
        val settings = ConfigTransfer.Settings(
            level = "STRICT", blockQuic = ConfigTransfer.Quic.LEVEL_DEFAULT, encryptedDns = true,
            dnsServer = AdvancedLogic.DnsPreset.QUAD9, blockUnencrypted = true, blockWeakTls = false,
            certificateChecks = true, publicIpCheck = false, notifications = true, notificationDetails = false,
            weeklyReport = true, keepHistoryDays = 14, theme = "LIGHT",
        )
        val back = ok(ConfigTransfer.write(ConfigTransfer.exportable(rules), settings))
        assertEquals(ConfigTransfer.exportable(rules), back.rules)
        assertEquals(5, back.rules.size)
        assertEquals(settings, back.settings)
        assertEquals(0, back.skipped)
    }

    @Test
    fun the_export_holds_only_the_users_own_rules_and_never_the_security_override_or_a_label() {
        val rules = listOf(
            userRule("1", FilterRule.Action.ALLOW, domain = "phish.example", overrides = true),
            FilterRule(id = "b", label = "list", action = FilterRule.Action.DENY, source = FilterRule.Source.BLOCKLIST, matchDomain = "x.example"),
            SettingsViewModel.blockCleartextRule("Block HTTP"),
        )
        val json = ConfigTransfer.write(ConfigTransfer.exportable(rules), ConfigTransfer.Settings())
        assertFalse(json.contains("overridesSecurity") || json.contains("label"))
        assertFalse("list rules are not the user's", json.contains("x.example"))
        assertFalse("the cleartext rule travels as a setting", json.contains("CLEARTEXT"))
        assertEquals(1, ok(json).rules.size)
    }

    @Test
    fun an_imported_allow_rule_never_overrides_the_malware_lists() {
        val file = file(rules = """[{"action":"allow","site":"phish.example","overridesSecurity":true,"priority":999999,"source":"SYSTEM"}]""")
        val entry = ok(file).rules.single()
        val rule = ConfigTransfer.toFilterRule(entry, "id", "Allow phish.example")
        assertFalse(rule.overridesSecurity)
        assertEquals(FilterRule.Source.USER, rule.source)
        assertEquals(FilterRule.HIGH_PRIORITY, rule.priority)
    }

    @Test
    fun files_that_are_not_ours_are_refused_whole() {
        assertEquals(Refusal.NOT_OURS, refused("not json"))
        assertEquals(Refusal.NOT_OURS, refused("[]"))
        assertEquals(Refusal.NOT_OURS, refused("{}"))
        assertEquals(Refusal.NOT_OURS, refused(file(format = "something-else")))
        assertEquals(Refusal.NOT_OURS, refused(file(version = "\"1\"")))
        assertEquals(Refusal.NOT_OURS, refused(file(version = 0)))
        assertEquals(Refusal.NOT_OURS, refused(file(rules = "{}")))
        assertEquals(Refusal.NOT_OURS, refused(file(settings = "[]")))
        assertEquals(Refusal.NOT_OURS, refused(file().dropLast(1)))
        // Invalid UTF-8.
        assertEquals(Refusal.NOT_OURS, (ConfigTransfer.read(byteArrayOf(0x7b, 0xC3.toByte(), 0x28, 0x7d)) as ReadResult.Refused).reason)
    }

    @Test
    fun a_newer_version_is_refused_with_its_own_reason() {
        assertEquals(Refusal.NEWER_VERSION, refused(file(version = 2)))
    }

    @Test
    fun an_oversized_file_is_refused_before_parsing_and_a_long_stream_is_not_read_to_the_end() {
        val big = ByteArray(ConfigTransfer.MAX_BYTES + 1) { ' '.code.toByte() }
        assertEquals(Refusal.TOO_BIG, (ConfigTransfer.read(big) as ReadResult.Refused).reason)

        var served = 0L
        val endless = object : java.io.InputStream() {
            override fun read(): Int { served++; return ' '.code }
            override fun read(b: ByteArray, off: Int, len: Int): Int { served += len; b.fill(' '.code.toByte(), off, off + len); return len }
        }
        assertTrue(SettingsHomeViewModel.isTooBig(SettingsHomeViewModel.readAtMost(endless, ConfigTransfer.MAX_BYTES)))
        assertTrue("stopped soon after the limit, read $served", served <= ConfigTransfer.MAX_BYTES + 16 * 1024)
        val small = SettingsHomeViewModel.readAtMost(ByteArrayInputStream(file().toByteArray()), ConfigTransfer.MAX_BYTES)
        assertEquals(file(), String(small))
    }

    @Test
    fun deep_nesting_is_refused_before_the_parser_sees_it() {
        val deep = "[".repeat(200_000) + "]".repeat(200_000)
        assertEquals(Refusal.NOT_OURS, refused(file(rules = deep)))
        assertEquals(Refusal.NOT_OURS, refused(file(rules = """[{"action":"block","site":{"a":{"b":1}}}]""")))
        // Brackets inside strings don't count.
        assertTrue(ConfigTransfer.depthAtMost("""{"a":"[[[[[[[[\"{{{{"}""", 1))
        assertFalse(ConfigTransfer.depthAtMost("""{"a":[[1]]}""", 2))
    }

    @Test
    fun more_than_the_rule_limit_is_refused() {
        val many = (0..ConfigTransfer.MAX_RULES).joinToString(",", "[", "]") { """{"action":"block","site":"s$it.example"}""" }
        assertEquals(Refusal.TOO_MANY_RULES, refused(file(rules = many)))
        val limit = (1..ConfigTransfer.MAX_RULES).joinToString(",", "[", "]") { """{"action":"block","site":"s$it.example"}""" }
        assertEquals(ConfigTransfer.MAX_RULES, ok(file(rules = limit)).rules.size)
    }

    @Test
    fun invalid_rules_are_skipped_and_counted_never_half_applied() {
        val rules = """[
            {"action":"block","site":"good.example"},
            {"action":"delete","site":"a.example"},
            {"site":"no-action.example"},
            {"action":"block"},
            {"action":"block","site":"bad name.example"},
            {"action":"block","site":"1.2.3.4"},
            {"action":"block","app":"not a package"},
            {"action":"block","address":"999.1.1.1"},
            {"action":"block","address":"10.0.0.0/33"},
            {"action":"block","site":"p.example","port":70000},
            {"action":"block","site":"p.example","port":443.5},
            {"action":"block","site":"p.example","protocol":"SCTP"},
            {"action":"block","site":"p.example","encryption":"MAGIC"},
            {"action":"block","site":"bg.example","background":true},
            {"action":"block","site":123},
            {"action":"block","site":"${"a".repeat(300)}.example"},
            "a string",
            {"action":"allow","site":"HTTPS://Good2.Example.:443/path"}
        ]"""
        val result = ok(file(rules = rules))
        assertEquals(listOf("good.example", "good2.example"), result.rules.map { it.site })
        assertEquals(16, result.skipped)
    }

    @Test
    fun formula_like_or_odd_strings_never_reach_a_stored_label() {
        // The label is never read from the file: it is written by the app from the rule.
        val result = ok(file(rules = """[{"action":"block","site":"x.example","label":"=HYPERLINK(\"http://evil\")"}]"""))
        val (labelRes, isApp) = ConfigTransfer.labelOf(result.rules.single())
        assertEquals(com.privacyguard.app.R.string.rule_block_domain, labelRes)
        assertFalse(isApp)
        // A site that is a formula is not a valid name.
        assertEquals(1, ok(file(rules = """[{"action":"block","site":"=cmd|'/c calc'!A1"}]""")).skipped)
    }

    @Test
    fun settings_are_validated_one_by_one_and_a_typed_dns_server_is_never_imported() {
        val s = ok(file(settings = """{
            "protectionLevel":"PARANOID","blockQuic":"maybe","encryptedDns":"yes",
            "dnsServer":"203.0.113.9","keepHistoryDays":3650,"theme":"PINK",
            "notifications":false,"weeklyReport":true,"unknownKey":1
        }"""))
        assertEquals(ConfigTransfer.Settings(notifications = false, weeklyReport = true), s.settings)
        assertEquals(6, s.skipped)
        assertEquals(AdvancedLogic.DnsPreset.GOOGLE, ok(file(settings = """{"dnsServer":"8.8.8.8"}""")).settings.dnsServer)
        assertEquals(ConfigTransfer.Quic.LEVEL_DEFAULT, ok(file(settings = """{"blockQuic":"level"}""")).settings.blockQuic)
    }

    @Test
    fun only_new_rules_are_added_and_existing_ones_are_never_replaced() {
        val existing = listOf(
            userRule("u1", FilterRule.Action.DENY, domain = "ads.example.com", enabled = false),
            userRule("pkg:block:com.example.app", FilterRule.Action.DENY, pkg = "com.example.app"),
        )
        val entries = listOf(
            RuleEntry(FilterRule.Action.DENY, site = "ads.example.com"),               // same rule (enabled differs)
            RuleEntry(FilterRule.Action.ALLOW, site = "ads.example.com"),              // other action: new
            RuleEntry(FilterRule.Action.DENY, app = "com.example.app"),                // same fixed id
            RuleEntry(FilterRule.Action.DENY, app = "com.other.app", background = true),
            RuleEntry(FilterRule.Action.DENY, app = "com.other.app", background = true), // twice in the file
        )
        val plan = ConfigTransfer.plan(entries, existing)
        assertEquals(listOf(entries[1], entries[3]), plan.newRules)
        assertEquals(3, plan.alreadyThere)
        assertEquals(1, plan.blocks)
        assertEquals(1, plan.allows)
    }

    @Test
    fun app_rules_get_the_ids_the_apps_screen_uses() {
        val random = { "random" }
        assertEquals("pkg:block:com.a.b", ConfigTransfer.idOf(RuleEntry(FilterRule.Action.DENY, app = "com.a.b"), random))
        assertEquals("bg:block:com.a.b", ConfigTransfer.idOf(RuleEntry(FilterRule.Action.DENY, app = "com.a.b", background = true), random))
        assertEquals("random", ConfigTransfer.idOf(RuleEntry(FilterRule.Action.ALLOW, app = "com.a.b"), random))
        assertEquals("random", ConfigTransfer.idOf(RuleEntry(FilterRule.Action.DENY, app = "com.a.b", site = "x.example"), random))
    }

    @Test
    fun changed_settings_count_only_what_differs() {
        val now = ConfigTransfer.Settings(level = "STANDARD", notifications = true, theme = "DARK")
        val file = ConfigTransfer.Settings(level = "STANDARD", notifications = false, keepHistoryDays = 7)
        assertEquals(2, ConfigTransfer.changedSettings(file, now))
        assertNull(file.theme)
    }

    @Test
    fun json_null_is_treated_as_absent() {
        val r = ok(file(rules = """[{"action":"block","site":"x.example","app":null,"port":null}]""")).rules.single()
        assertEquals(RuleEntry(FilterRule.Action.DENY, site = "x.example"), r)
        assertEquals(EncryptionStatus.CLEARTEXT,
            ok(file(rules = """[{"action":"block","site":"x.example","encryption":"CLEARTEXT"}]""")).rules.single().encryption)
    }
}
