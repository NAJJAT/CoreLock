package com.privacyguard.app.core.tracker

import java.util.Locale

/** What a destination is for, as far as the hostname tells us. */
enum class DomainPurpose {
    /** The app maker's own servers (feeds, messages, login, its own logging). */
    FIRST_PARTY,
    ADS,
    ANALYTICS,
    /** Other tracking: crash reporting, fingerprinting, profiling, social SDKs. */
    TRACKING,
    /** Content delivery: images, video and app files. */
    CDN,
    /** A known company's service that is none of the above (maps, payments, push…). */
    SERVICE,
    /** Nothing in our lists matches; the owner is a guess from the domain name only. */
    UNKNOWN,
}

data class DomainClass(
    val host: String,
    /** Registrable domain ("instagram.com" for "i.instagram.com"). */
    val baseDomain: String,
    val purpose: DomainPurpose,
    /** Owning company, or null when no list names one. */
    val company: String?,
    /** Tracker product from the tracker database, e.g. "AppsFlyer". */
    val trackerName: String? = null,
    val trackerCategory: TrackerCategory? = null,
)

/**
 * Classifies hostnames by owner and purpose, optionally from the point of view of
 * the app that contacted them (Facebook talking to facebook.com is first-party;
 * a game doing so is the Facebook SDK).
 *
 * Only hostnames are used: we see where traffic goes, never what is inside it. Some
 * hosts serve several purposes (graph.facebook.com is login, the app's API and Meta's
 * app events), so a "first-party" label can still include the maker's own analytics.
 */
object DomainClassifier {

    private class Owner(
        val company: String,
        val packagePrefixes: List<String>,
        val domains: List<String>,
        val cdnDomains: List<String> = emptyList(),
    )

    private val owners = listOf(
        Owner(
            "Meta Platforms",
            listOf("com.facebook.", "com.instagram.", "com.whatsapp", "com.oculus."),
            listOf("facebook.com", "facebook.net", "instagram.com", "whatsapp.com", "whatsapp.net",
                "messenger.com", "meta.com", "threads.net", "oculus.com", "fb.com", "fb.me", "m.me"),
            listOf("fbcdn.net", "fbsbx.com", "cdninstagram.com"),
        ),
        Owner(
            "Google",
            listOf("com.google.", "com.android.vending", "com.android.chrome"),
            listOf("google.com", "googleapis.com", "youtube.com", "android.com", "gmail.com",
                "googleusercontent.com", "withgoogle.com", "google", "goog", "app.goo.gl"),
            listOf("gstatic.com", "ytimg.com", "googlevideo.com", "ggpht.com", "gvt1.com", "gvt2.com", "gvt3.com"),
        ),
        Owner(
            "Samsung",
            listOf("com.samsung.", "com.sec.", "com.osp.app.signin", "com.wssyncmldm"),
            listOf("samsung.com", "samsungapps.com", "samsungcloud.com", "samsungcloudsolution.com",
                "samsungcloudsolution.net", "samsungosp.com", "samsungdm.com", "samsungqbe.com",
                "samsungelectronics.com", "secb2b.com", "samsungiotcloud.com", "samsunghealth.com",
                "samsungconsent.com", "samsungmax.com", "galaxystore.com", "bixby.ai"),
            listOf("samsungcdn.com"),
        ),
        Owner(
            "Microsoft",
            listOf("com.microsoft.", "com.skype."),
            listOf("microsoft.com", "live.com", "office.com", "office.net", "msn.com", "bing.com", "skype.com",
                "outlook.com", "windows.net", "microsoftonline.com", "sharepoint.com", "onedrive.com", "xbox.com"),
            listOf("msecnd.net", "azureedge.net"),
        ),
        Owner(
            "ByteDance",
            listOf("com.zhiliaoapp.musically", "com.ss.android.", "com.lemon.lvoverseas"),
            listOf("tiktok.com", "tiktokv.com", "tiktokv.eu", "byteoversea.com", "musical.ly", "capcut.com"),
            listOf("tiktokcdn.com", "tiktokcdn-eu.com", "ibytedtos.com", "byteimg.com", "ttwstatic.com"),
        ),
        Owner("X Corp", listOf("com.twitter."), listOf("twitter.com", "x.com", "t.co"), listOf("twimg.com")),
        Owner("Snap", listOf("com.snapchat."), listOf("snapchat.com", "snap.com", "snapkit.com"), listOf("sc-cdn.net", "sc-static.net")),
        Owner("Spotify", listOf("com.spotify."), listOf("spotify.com", "spotify.net"), listOf("scdn.co", "spotifycdn.com")),
        Owner("Netflix", listOf("com.netflix."), listOf("netflix.com", "netflix.net"), listOf("nflxvideo.net", "nflximg.net", "nflxext.com", "nflxso.net")),
        Owner("Amazon", listOf("com.amazon."), listOf("amazon.com", "amazon.de", "amazon.nl", "amazon.fr", "amazon.co.uk", "a2z.com", "amazonvideo.com", "primevideo.com"), listOf("media-amazon.com", "ssl-images-amazon.com")),
        Owner("Telegram", listOf("org.telegram."), listOf("telegram.org", "t.me", "telegram.me"), emptyList()),
        Owner("Apple", listOf("com.apple."), listOf("apple.com", "icloud.com"), listOf("mzstatic.com")),
    )

    /** Third-party CDNs and cloud front doors, by suffix. */
    private val cdnProviders = listOf(
        "akamaihd.net" to "Akamai", "akamaized.net" to "Akamai", "akamaiedge.net" to "Akamai",
        "akamai.net" to "Akamai", "edgekey.net" to "Akamai", "edgesuite.net" to "Akamai",
        "akamaitechnologies.com" to "Akamai",
        "cloudfront.net" to "Amazon CloudFront",
        "fastly.net" to "Fastly", "fastlylb.net" to "Fastly", "fastly-edge.com" to "Fastly",
        "cloudflare.net" to "Cloudflare", "cloudflare.com" to "Cloudflare", "cdnjs.cloudflare.com" to "Cloudflare",
        "azurefd.net" to "Microsoft Azure", "trafficmanager.net" to "Microsoft Azure",
        "llnwd.net" to "Edgio", "edgecastcdn.net" to "Edgio", "b-cdn.net" to "Bunny CDN",
        "jsdelivr.net" to "jsDelivr", "cdn77.org" to "CDN77", "gcdn.co" to "G-Core Labs",
    )

    /** Known cloud hosting: the operator is known, the customer is not. */
    private val clouds = listOf(
        "amazonaws.com" to "Amazon Web Services", "appspot.com" to "Google Cloud",
        "run.app" to "Google Cloud", "firebaseio.com" to "Google Firebase", "firebaseapp.com" to "Google Firebase",
        "web.app" to "Google Firebase", "herokuapp.com" to "Heroku", "azurewebsites.net" to "Microsoft Azure",
        "cloudapp.net" to "Microsoft Azure", "digitaloceanspaces.com" to "DigitalOcean",
    )

    // Two-label public suffixes we meet often; good enough for grouping, not a PSL.
    private val twoLabelSuffixes = setOf(
        "co.uk", "org.uk", "ac.uk", "gov.uk", "com.au", "net.au", "co.jp", "ne.jp", "co.kr", "com.br",
        "com.tr", "com.cn", "com.hk", "com.tw", "com.sg", "co.in", "co.za", "com.mx", "com.ar", "co.nz",
        "com.sa", "com.eg", "co.il", "com.my", "co.id", "com.ua", "com.pl",
    )

    // Package segments that say nothing about the maker.
    private val genericSegments = setOf(
        "com", "org", "net", "io", "app", "apps", "android", "mobile", "client", "main", "lite", "free",
        "pro", "plus", "game", "games", "global", "official", "www", "inc", "ltd", "co", "nl", "be", "de",
        "fr", "uk", "us", "eu", "tv", "me", "my", "the",
    )

    fun baseDomain(host: String): String {
        val labels = normalise(host).split('.').filter { it.isNotEmpty() }
        if (labels.size <= 2) return labels.joinToString(".")
        val lastTwo = labels.takeLast(2).joinToString(".")
        return if (lastTwo in twoLabelSuffixes) labels.takeLast(3).joinToString(".") else lastTwo
    }

    /** Company behind [packageName] when we know it, e.g. "Meta Platforms" for Instagram. */
    fun companyForPackage(packageName: String): String? = ownerOfPackage(packageName)?.company

    fun classify(host: String, appPackage: String? = null): DomainClass {
        val h = normalise(host)
        val base = baseDomain(h)
        val appOwner = appPackage?.let(::ownerOfPackage)
        val hostOwner = ownerOfHost(h)
        val tracker = TrackerDatabase.lookupByDomain(h)

        if (tracker != null) {
            val sameMaker = appOwner != null && sameCompany(appOwner.company, tracker.company)
            val purpose = when (tracker.category) {
                TrackerCategory.ADVERTISING -> DomainPurpose.ADS
                TrackerCategory.ANALYTICS, TrackerCategory.CRASH_REPORTING -> DomainPurpose.ANALYTICS
                // The Facebook SDK inside Facebook is just Facebook.
                TrackerCategory.SOCIAL, TrackerCategory.OTHER ->
                    if (sameMaker) DomainPurpose.FIRST_PARTY else DomainPurpose.TRACKING
                TrackerCategory.FINGERPRINTING, TrackerCategory.PROFILING -> DomainPurpose.TRACKING
            }
            return DomainClass(h, base, purpose, hostOwner?.company ?: tracker.company, tracker.name, tracker.category)
        }

        if (hostOwner != null) {
            val isCdn = hostOwner.cdnDomains.any { matches(h, it) }
            val purpose = when {
                isCdn -> DomainPurpose.CDN
                appOwner === hostOwner -> DomainPurpose.FIRST_PARTY
                else -> DomainPurpose.SERVICE
            }
            return DomainClass(h, base, purpose, hostOwner.company)
        }

        cdnProviders.firstOrNull { matches(h, it.first) }?.let { (_, company) ->
            return DomainClass(h, base, DomainPurpose.CDN, company)
        }

        // An unknown host whose name carries the app's own name ("supercell.com" for
        // com.supercell.clashofclans) is very likely the maker's own server.
        if (appPackage != null && appOwner == null && sharesMakerName(base, appPackage)) {
            return DomainClass(h, base, DomainPurpose.FIRST_PARTY, null)
        }

        clouds.firstOrNull { matches(h, it.first) }?.let { (_, company) ->
            return DomainClass(h, base, DomainPurpose.UNKNOWN, company)
        }
        return DomainClass(h, base, DomainPurpose.UNKNOWN, null)
    }

    private fun ownerOfPackage(packageName: String): Owner? =
        owners.firstOrNull { owner -> owner.packagePrefixes.any { packageName == it.trimEnd('.') || packageName.startsWith(it) } }

    private fun ownerOfHost(host: String): Owner? =
        owners.firstOrNull { owner ->
            owner.domains.any { matches(host, it) } || owner.cdnDomains.any { matches(host, it) } ||
                // google.de, google.co.uk, …
                (owner.company == "Google" && baseDomain(host).substringBefore('.') == "google")
        }

    private fun sharesMakerName(baseDomain: String, packageName: String): Boolean {
        val name = baseDomain.substringBefore('.')
        if (name.length < 4) return false
        return packageName.lowercase(Locale.ROOT).split('.', '_')
            .filter { it.length >= 4 && it !in genericSegments }
            .any { it == name || (name.length >= 5 && it.startsWith(name)) || (it.length >= 5 && name.startsWith(it)) }
    }

    private fun sameCompany(a: String, b: String): Boolean {
        fun key(s: String) = s.lowercase(Locale.ROOT).substringBefore(' ').substringBefore('/')
        return key(a) == key(b)
    }

    private fun matches(host: String, suffix: String): Boolean =
        host == suffix || host.endsWith(".$suffix")

    private fun normalise(host: String): String = host.trim().trimEnd('.').lowercase(Locale.ROOT)
}
