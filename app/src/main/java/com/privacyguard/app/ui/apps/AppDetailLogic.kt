package com.privacyguard.app.ui.apps

import com.privacyguard.app.ui.activity.ActivityItem

/** App detail's pure parts (DESIGN_SPEC_1.1.md §6.4), apart from Android for tests. */
object AppDetailLogic {

    /**
     * A group in "Connected recently": an organisation and purpose ("Google ads"), or
     * [title] null for names no list knows ("Other"). [items]: one per name, newest first.
     */
    data class Section(val title: String?, val items: List<ActivityItem>, val newest: Long)

    /**
     * One row per name: the blocked attempt when there was one (that is what the user may
     * want to change), and a blocked lookup before a blocked connection (a per-app Allow
     * acts only on connections, so the choice offered must be "everywhere", D3). The row
     * counts every attempt at the name.
     */
    fun perName(items: List<ActivityItem>): List<ActivityItem> =
        items.groupBy { it.host ?: it.ip.orEmpty() }
            .filterKeys { it.isNotEmpty() }
            .map { (_, rows) ->
                val blocked = rows.filter { it.blocked }
                val shown = blocked.filter { it.isLookup }.maxByOrNull { it.timestamp }
                    ?: blocked.maxByOrNull { it.timestamp }
                    ?: rows.maxBy { it.timestamp }
                shown.copy(count = rows.sumOf { it.count }, timestamp = rows.maxOf { it.timestamp })
            }

    /** Named groups newest first, then "Other"; rows newest first inside each. */
    fun sections(items: List<ActivityItem>): List<Section> {
        val rows = perName(items)
        // A purpose title ("Google ads") first; else the owner alone ("Google" for an app's
        // own first-party names), so a company's names are not scattered under "Other".
        fun group(it: ActivityItem): String? = it.title.takeIf { t -> t != (it.host ?: it.ip) } ?: it.company
        val (named, other) = rows.partition { group(it) != null }
        val sections = named.groupBy { group(it).orEmpty() }.map { (title, group) ->
            val sorted = group.sortedByDescending { it.timestamp }
            Section(title, sorted, sorted.first().timestamp)
        }.sortedByDescending { it.newest }
        val rest = other.sortedByDescending { it.timestamp }
        return if (rest.isEmpty()) sections else sections + Section(null, rest, rest.first().timestamp)
    }
}
