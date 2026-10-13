package com.privacyguard.app.core.behavior

/**
 * Which behaviour findings deserve a notification: HIGH ones only, each (app, host,
 * kind) at most once per [REPEAT_MS], also across VPN restarts ([notified] is
 * persisted by the caller). Pure, so it can be tested.
 */
object BehaviorNotifier {

    const val REPEAT_MS = 24L * 60L * 60L * 1000L

    /** SHA-256 of app, host and kind: the record is a plain file, not a list of sites. */
    fun key(f: BehaviorFinding): String =
        java.security.MessageDigest.getInstance("SHA-256")
            .digest("${f.packageName}|${f.hostname}|${f.kind}".toByteArray())
            .joinToString("") { "%02x".format(it) }

    const val PREFS = "behavior_notified"

    /**
     * @param notified key → when it was last notified.
     * @return the findings to notify now, and the updated (pruned) record.
     */
    fun select(
        findings: List<BehaviorFinding>,
        notified: Map<String, Long>,
        now: Long,
    ): Pair<List<BehaviorFinding>, Map<String, Long>> {
        val kept = notified.filterValues { now - it < REPEAT_MS }.toMutableMap()
        val due = findings
            .filter { it.severity == BehaviorSeverity.HIGH }
            .distinctBy(::key)
            .filter { key(it) !in kept }
        due.forEach { kept[key(it)] = now }
        return due to kept
    }
}
