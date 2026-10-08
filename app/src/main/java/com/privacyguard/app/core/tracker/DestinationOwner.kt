package com.privacyguard.app.core.tracker

import com.privacyguard.app.core.geoip.GeoIpResolver

/**
 * Names who is behind a connection, from the best evidence available:
 * tracker database → known company domains → known IP ranges → the site's own
 * registrable domain. Every screen uses this, so a destination is described the
 * same way everywhere and never as a bare "Unknown"/"Network".
 */
object DestinationOwner {

    enum class Role(val label: String) {
        /** The app's own company (facebook.com for WhatsApp). */
        FIRST_PARTY("App's own servers"),
        /** Listed in the tracker database (ads, analytics, profiling). */
        TRACKER("Tracker"),
        /** Hosting/CDN provider serving content on someone's behalf. */
        HOSTING("Hosting & CDN"),
        /** Phone maker / OS services. */
        PLATFORM("Device & OS"),
        /** Another company's service. */
        THIRD_PARTY("Third party"),
        /** Only an address or an unlisted site is known. */
        UNLISTED("Unlisted"),
    }

    data class Info(
        /** Company or site name, e.g. "Meta", "Amazon Web Services", "bbci.co.uk". */
        val owner: String,
        val role: Role,
        /** Tracker product, e.g. "Facebook SDK", when the host is a listed tracker. */
        val trackerName: String?,
    ) {
        /** One-line description for lists, e.g. "Meta · App's own servers". */
        val summary: String get() = trackerName?.let { "$it · $owner" } ?: "$owner · ${role.label}"
    }

    /**
     * Describes the destination [host] (may be null when only [ip] is known),
     * as seen from [packageName] (null for device-wide views).
     */
    fun describe(host: String?, ip: String, packageName: String? = null): Info {
        val name = host?.takeIf { it.isNotBlank() && !isIpLiteral(it) }
        val org = name?.let(OrgDirectory::ownerOf)
        val tracker = name?.let(TrackerDatabase::lookupByDomain)
        val firstParty = packageName != null && name != null && OrgDirectory.isFirstParty(packageName, name)

        if (firstParty && org != null) return Info(org.name, Role.FIRST_PARTY, null)
        if (tracker != null) return Info(org?.name ?: tracker.company, Role.TRACKER, tracker.name)
        if (org != null) {
            val role = when (org.kind) {
                OrgDirectory.Kind.HOSTING -> Role.HOSTING
                OrgDirectory.Kind.PLATFORM -> Role.PLATFORM
                OrgDirectory.Kind.SERVICE -> Role.THIRD_PARTY
            }
            return Info(org.name, role, null)
        }
        val geoOrg = GeoIpResolver.lookup(ip)?.org
        // An address in the app's own company's range ("Meta Platforms" for WhatsApp)
        // is first party even when no hostname was recorded for it.
        val publisher = packageName?.let(OrgDirectory::publisherOf)
        if (geoOrg != null && publisher != null && geoOrg.startsWith(publisher.name.substringBefore(' '))) {
            return Info(publisher.name, Role.FIRST_PARTY, null)
        }
        if (geoOrg != null) {
            val hosting = HOSTING_HINTS.any { geoOrg.contains(it, ignoreCase = true) }
            return Info(geoOrg, if (hosting) Role.HOSTING else Role.THIRD_PARTY, null)
        }
        if (name != null) return Info(registrableDomain(name), Role.UNLISTED, null)
        return Info("Unlisted network ($ip)", Role.UNLISTED, null)
    }

    /** "a.b.example.co.uk" → "example.co.uk"; "g.whatsapp.net" → "whatsapp.net". */
    fun registrableDomain(host: String): String {
        val labels = host.lowercase().trimEnd('.').split('.')
        if (labels.size <= 2) return labels.joinToString(".")
        val secondLevel = labels[labels.size - 2]
        val twoPartSuffix = labels.last().length == 2 && secondLevel in COUNTRY_SECOND_LEVELS
        val keep = if (twoPartSuffix) 3 else 2
        return labels.takeLast(keep).joinToString(".")
    }

    private val HOSTING_HINTS = listOf("cloud", "akamai", "fastly", "amazon web", "aws", "hosting", "cdn", "azure", "digitalocean", "ovh", "hetzner")

    private val COUNTRY_SECOND_LEVELS = setOf("co", "com", "org", "net", "ac", "gov", "edu", "ne", "or", "go")

    private fun isIpLiteral(s: String) = s.contains(':') || s.all { it.isDigit() || it == '.' }
}
