package com.privacyguard.app.ui.settings

import com.privacyguard.app.ui.rules.RulesLogic
import com.privacyguard.app.ui.rules.RulesViewModel
import com.privacyguard.core.filter.FilterRule
import com.privacyguard.core.metadata.EncryptionStatus
import org.json.JSONArray
import org.json.JSONObject
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction

/**
 * Export and import of the user's rules and settings (decision D2, ADR 0013).
 *
 * The file is untrusted input: it may come from a chat or a download (CLAUDE.md §3.3). So
 * the import checks size, nesting and counts before and while parsing, validates every
 * field with the checks the add-rule sheet uses, skips (and counts) what is invalid, and
 * never carries anything that weakens protection without the app's own warning: no
 * `overridesSecurity`, no custom DNS server, no inspection, proxy or screenshot setting.
 * Android's own JSON parser is used (no new dependency).
 */
object ConfigTransfer {

    const val FORMAT = "corelock-rules-and-settings"
    const val VERSION = 1

    /** Far above any real file (5,000 rules are about 600 KB), and small enough to read at once. */
    const val MAX_BYTES = 1 shl 20
    const val MAX_RULES = 5_000

    /** root › rules › rule is 3 levels; anything deeper is not ours, and deep nesting could overflow the parser's stack. */
    const val MAX_DEPTH = 4

    /** Longest valid value of any field (a package name or a site is at most 255 / 253 characters). */
    private const val MAX_STRING = 255

    /** One rule as the file carries it: what it matches, never a label, an id or the security override. */
    data class RuleEntry(
        val action: FilterRule.Action,
        val site: String? = null,
        val app: String? = null,
        val address: String? = null,
        val port: Int? = null,
        val protocol: FilterRule.Protocol? = null,
        val encryption: EncryptionStatus? = null,
        val background: Boolean? = null,
        val enabled: Boolean = true,
    )

    /** "Block QUIC": on, off, or the protection level's default. */
    enum class Quic { ON, OFF, LEVEL_DEFAULT }

    /** The settings a file can carry; null = not in the file (left as it is). */
    data class Settings(
        val level: String? = null,
        val blockQuic: Quic? = null,
        val encryptedDns: Boolean? = null,
        val dnsServer: AdvancedLogic.DnsPreset? = null,
        val blockUnencrypted: Boolean? = null,
        val blockWeakTls: Boolean? = null,
        val certificateChecks: Boolean? = null,
        val publicIpCheck: Boolean? = null,
        val notifications: Boolean? = null,
        val notificationDetails: Boolean? = null,
        val weeklyReport: Boolean? = null,
        val keepHistoryDays: Int? = null,
        val theme: String? = null,
    ) {
        /** The settings this file sets, by name, to compare with the current ones. */
        fun present(): Map<String, Any> = listOfNotNull(
            level?.let { K_LEVEL to it }, blockQuic?.let { K_QUIC to it }, encryptedDns?.let { K_DOH to it },
            dnsServer?.let { K_DNS to it }, blockUnencrypted?.let { K_CLEARTEXT to it }, blockWeakTls?.let { K_WEAK_TLS to it },
            certificateChecks?.let { K_CT to it }, publicIpCheck?.let { K_IP to it }, notifications?.let { K_NOTIF to it },
            notificationDetails?.let { K_DETAILS to it }, weeklyReport?.let { K_WEEKLY to it },
            keepHistoryDays?.let { K_DAYS to it }, theme?.let { K_THEME to it },
        ).toMap()
    }

    sealed interface ReadResult {
        /** [skipped]: rules and settings left out because they were not valid. */
        data class Ok(val rules: List<RuleEntry>, val settings: Settings, val skipped: Int) : ReadResult
        data class Refused(val reason: Refusal) : ReadResult
    }

    enum class Refusal { TOO_BIG, NOT_OURS, NEWER_VERSION, TOO_MANY_RULES }

    // ── Export ──────────────────────────────────────────────────────────────

    /** The user's own rules that a file can carry: a site, an address or an app. */
    fun exportable(rules: List<FilterRule>): List<RuleEntry> =
        rules.filter { it.source == FilterRule.Source.USER && it.id !in GLOBAL_RULE_IDS }.mapNotNull(::entryOf)

    fun write(rules: List<RuleEntry>, settings: Settings): String {
        val root = JSONObject()
            .put("format", FORMAT)
            .put("version", VERSION)
        root.put("rules", JSONArray().apply { rules.forEach { put(toJson(it)) } })
        root.put("settings", JSONObject().apply {
            settings.level?.let { put(K_LEVEL, it) }
            settings.blockQuic?.let { put(K_QUIC, when (it) { Quic.ON -> true; Quic.OFF -> false; Quic.LEVEL_DEFAULT -> QUIC_LEVEL }) }
            settings.encryptedDns?.let { put(K_DOH, it) }
            settings.dnsServer?.let { put(K_DNS, it.ip) }
            settings.blockUnencrypted?.let { put(K_CLEARTEXT, it) }
            settings.blockWeakTls?.let { put(K_WEAK_TLS, it) }
            settings.certificateChecks?.let { put(K_CT, it) }
            settings.publicIpCheck?.let { put(K_IP, it) }
            settings.notifications?.let { put(K_NOTIF, it) }
            settings.notificationDetails?.let { put(K_DETAILS, it) }
            settings.weeklyReport?.let { put(K_WEEKLY, it) }
            settings.keepHistoryDays?.let { put(K_DAYS, it) }
            settings.theme?.let { put(K_THEME, it) }
        })
        return root.toString(2)
    }

    private fun entryOf(rule: FilterRule): RuleEntry? {
        if (rule.matchDomain.isNullOrBlank() && rule.matchIp.isNullOrBlank() && rule.matchPackage.isNullOrBlank()) return null
        return RuleEntry(
            action = rule.action,
            site = rule.matchDomain?.takeIf { it.isNotBlank() },
            app = rule.matchPackage?.takeIf { it.isNotBlank() },
            address = rule.matchIp?.takeIf { it.isNotBlank() },
            port = rule.matchPort,
            protocol = rule.matchProtocol,
            encryption = rule.matchEncryption,
            background = rule.matchBackground,
            enabled = rule.isEnabled,
        )
    }

    private fun toJson(e: RuleEntry) = JSONObject().apply {
        put("action", if (e.action == FilterRule.Action.DENY) "block" else "allow")
        e.site?.let { put("site", it) }
        e.app?.let { put("app", it) }
        e.address?.let { put("address", it) }
        e.port?.let { put("port", it) }
        e.protocol?.let { put("protocol", it.name) }
        e.encryption?.let { put("encryption", it.name) }
        e.background?.let { put("background", it) }
        if (!e.enabled) put("enabled", false)
    }

    // ── Import ──────────────────────────────────────────────────────────────

    fun read(bytes: ByteArray): ReadResult {
        if (bytes.size > MAX_BYTES) return ReadResult.Refused(Refusal.TOO_BIG)
        val text = try {
            Charsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(bytes)).toString()
        } catch (_: CharacterCodingException) {
            return ReadResult.Refused(Refusal.NOT_OURS)
        }
        // Checked before parsing: the parser recurses once per level.
        if (!depthAtMost(text, MAX_DEPTH)) return ReadResult.Refused(Refusal.NOT_OURS)
        val root = runCatching { JSONObject(text) }.getOrNull() ?: return ReadResult.Refused(Refusal.NOT_OURS)
        if (root.opt("format") != FORMAT) return ReadResult.Refused(Refusal.NOT_OURS)
        val version = root.opt("version") as? Int ?: return ReadResult.Refused(Refusal.NOT_OURS)
        if (version > VERSION) return ReadResult.Refused(Refusal.NEWER_VERSION)
        if (version < 1) return ReadResult.Refused(Refusal.NOT_OURS)

        val rulesJson = when (val r = root.opt("rules")) {
            null -> JSONArray()
            is JSONArray -> r
            else -> return ReadResult.Refused(Refusal.NOT_OURS)
        }
        if (rulesJson.length() > MAX_RULES) return ReadResult.Refused(Refusal.TOO_MANY_RULES)
        var skipped = 0
        val rules = (0 until rulesJson.length()).mapNotNull { i ->
            (rulesJson.opt(i) as? JSONObject)?.let(::ruleOf).also { if (it == null) skipped++ }
        }
        val (settings, badSettings) = when (val s = root.opt("settings")) {
            null -> Settings() to 0
            is JSONObject -> settingsOf(s)
            else -> return ReadResult.Refused(Refusal.NOT_OURS)
        }
        return ReadResult.Ok(rules, settings, skipped + badSettings)
    }

    /** True when no object or array in [text] is nested deeper than [max]; brackets inside strings don't count. */
    internal fun depthAtMost(text: String, max: Int): Boolean {
        var depth = 0
        var inString = false
        var escaped = false
        for (c in text) {
            if (inString) {
                when {
                    escaped -> escaped = false
                    c == '\\' -> escaped = true
                    c == '"' -> inString = false
                }
                continue
            }
            when (c) {
                '"' -> inString = true
                '{', '[' -> if (++depth > max) return false
                '}', ']' -> depth--
            }
        }
        return true
    }

    /** A rule from the file, or null when any field is invalid (the whole rule is skipped, never half-applied). */
    internal fun ruleOf(o: JSONObject): RuleEntry? {
        val action = when (o.opt("action")) {
            "block" -> FilterRule.Action.DENY
            "allow" -> FilterRule.Action.ALLOW
            else -> return null
        }
        val site = o.optField<String>("site") { return null }?.let { RulesLogic.parseSite(it) ?: return null }
        val app = o.optField<String>("app") { return null }?.trim()?.let { if (RulesLogic.isValidPackage(it)) it else return null }
        val address = o.optField<String>("address") { return null }?.trim()?.let { if (RulesViewModel.isValidIpOrCidr(it)) it else return null }
        // Without a site, an app or an address a rule would match every connection.
        if (site == null && app == null && address == null) return null
        val port = o.optField<Int>("port") { return null }?.let { if (it in 1..65535) it else return null }
        val protocol = o.optField<String>("protocol") { return null }?.let { n -> FilterRule.Protocol.entries.firstOrNull { it.name == n } ?: return null }
        val encryption = o.optField<String>("encryption") { return null }?.let { n -> EncryptionStatus.entries.firstOrNull { it.name == n } ?: return null }
        val background = o.optField<Boolean>("background") { return null }
        // "In background" is about an app; on a site alone it would apply to every app's background traffic.
        if (background != null && app == null) return null
        val enabled = o.optField<Boolean>("enabled") { return null } ?: true
        return RuleEntry(action, site, app, address, port, protocol, encryption, background, enabled)
    }

    private fun settingsOf(o: JSONObject): Pair<Settings, Int> {
        var bad = 0
        fun <T> pick(key: String, parse: (Any) -> T?): T? {
            val raw = o.opt(key) ?: return null
            return parse(raw).also { if (it == null) bad++ }
        }
        val bool = { v: Any -> v as? Boolean }
        val settings = Settings(
            level = pick(K_LEVEL) { v -> (v as? String)?.takeIf { it in LEVELS } },
            blockQuic = pick(K_QUIC) { v -> when (v) { true -> Quic.ON; false -> Quic.OFF; QUIC_LEVEL -> Quic.LEVEL_DEFAULT; else -> null } },
            encryptedDns = pick(K_DOH, bool),
            // Only the three servers the app offers: a file must not be able to send all DNS
            // lookups to a server the user never chose on this phone.
            dnsServer = pick(K_DNS) { v -> AdvancedLogic.DnsPreset.entries.firstOrNull { it.ip == v } },
            blockUnencrypted = pick(K_CLEARTEXT, bool),
            blockWeakTls = pick(K_WEAK_TLS, bool),
            certificateChecks = pick(K_CT, bool),
            publicIpCheck = pick(K_IP, bool),
            notifications = pick(K_NOTIF, bool),
            notificationDetails = pick(K_DETAILS, bool),
            weeklyReport = pick(K_WEEKLY, bool),
            keepHistoryDays = pick(K_DAYS) { v -> (v as? Int)?.takeIf { it in SettingsLogic.RETENTION_DAYS } },
            theme = pick(K_THEME) { v -> (v as? String)?.takeIf { it in THEMES } },
        )
        return settings to bad
    }

    /**
     * [key] as a [T]: null when absent, [onWrong] when present with another type or too
     * long. JSON null counts as absent.
     */
    private inline fun <reified T> JSONObject.optField(key: String, onWrong: () -> Nothing): T? {
        val v = opt(key)
        if (v == null || v == JSONObject.NULL) return null
        if (v !is T) onWrong()
        if (v is String && v.length > MAX_STRING) onWrong()
        return v
    }

    // ── What an import adds ─────────────────────────────────────────────────

    /** What the confirmation shows and what Import adds. Rules already there are not added again. */
    data class Plan(val newRules: List<RuleEntry>, val alreadyThere: Int) {
        val blocks get() = newRules.count { it.action == FilterRule.Action.DENY }
        val allows get() = newRules.size - blocks
    }

    fun plan(entries: List<RuleEntry>, existing: List<FilterRule>): Plan {
        val have = existing.mapNotNull(::entryOf).map { it.copy(enabled = true) }.toHashSet()
        val haveIds = existing.map { it.id }.toHashSet()
        val seen = HashSet<RuleEntry>()
        val fresh = entries.filter { e ->
            val key = e.copy(enabled = true)
            val fixedId = fixedIdOf(e)
            key !in have && (fixedId == null || fixedId !in haveIds) && seen.add(key)
        }
        return Plan(fresh, entries.size - fresh.size)
    }

    /**
     * The id the Apps screen expects for "block this app" and "block in background", so an
     * imported app rule shows on that app's page as one made there; other rules get [random].
     */
    fun idOf(e: RuleEntry, random: () -> String): String = fixedIdOf(e) ?: random()

    private fun fixedIdOf(e: RuleEntry): String? {
        val appOnly = e.app != null && e.site == null && e.address == null && e.port == null && e.protocol == null && e.encryption == null
        if (!appOnly || e.action != FilterRule.Action.DENY) return null
        return if (e.background == true) com.privacyguard.app.ui.apps.AppsLogic.backgroundBlockRuleId(e.app!!)
        else if (e.background == null) com.privacyguard.app.ui.apps.AppsLogic.packageBlockRuleId(e.app!!)
        else null
    }

    /** The engine rule for an imported entry. Always a user rule at the user-rule priority, never overriding malware lists. */
    fun toFilterRule(e: RuleEntry, id: String, label: String) = FilterRule(
        id = id,
        label = label,
        action = e.action,
        source = FilterRule.Source.USER,
        priority = FilterRule.HIGH_PRIORITY,
        isEnabled = e.enabled,
        matchPackage = e.app,
        matchDomain = e.site,
        matchIp = e.address,
        matchPort = e.port,
        matchProtocol = e.protocol,
        matchEncryption = e.encryption,
        matchBackground = e.background,
        overridesSecurity = false,
    )

    /**
     * How an imported rule is named when it has no title of its own (RulesLogic.titleOf):
     * the string the add-rule sheet uses, and whether its subject is an app (shown by name).
     * The file's own words are never stored.
     */
    fun labelOf(e: RuleEntry): Pair<Int, Boolean> {
        val appOnly = e.app != null && e.site == null && e.address == null
        val block = e.action == FilterRule.Action.DENY
        return when {
            appOnly && block && e.background == true -> com.privacyguard.app.R.string.rule_block_app_bg to true
            appOnly -> (if (block) com.privacyguard.app.R.string.rule_block_app else com.privacyguard.app.R.string.rule_allow_app) to true
            else -> (if (block) com.privacyguard.app.R.string.rule_block_domain else com.privacyguard.app.R.string.rule_allow_domain) to false
        }
    }

    /** How many of the file's settings differ from [current]: what the confirmation counts. */
    fun changedSettings(file: Settings, current: Settings): Int {
        val now = current.present()
        return file.present().count { (k, v) -> now[k] != v }
    }

    /** The rules behind Advanced › "Block unencrypted" and "Block weak TLS": carried as settings, not rules. */
    val GLOBAL_RULE_IDS = setOf(SettingsViewModel.RULE_ID_BLOCK_CLEARTEXT, SettingsViewModel.RULE_ID_BLOCK_WEAK_TLS)

    private val LEVELS = setOf("MINIMAL", "STANDARD", "STRICT")
    private val THEMES = setOf("SYSTEM", "LIGHT", "DARK")
    private const val QUIC_LEVEL = "level"

    internal const val K_LEVEL = "protectionLevel"
    internal const val K_QUIC = "blockQuic"
    internal const val K_DOH = "encryptedDns"
    internal const val K_DNS = "dnsServer"
    internal const val K_CLEARTEXT = "blockUnencrypted"
    internal const val K_WEAK_TLS = "blockWeakTls"
    internal const val K_CT = "certificateChecks"
    internal const val K_IP = "publicIpCheck"
    internal const val K_NOTIF = "notifications"
    internal const val K_DETAILS = "notificationDetails"
    internal const val K_WEEKLY = "weeklyReport"
    internal const val K_DAYS = "keepHistoryDays"
    internal const val K_THEME = "theme"
}
