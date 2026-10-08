package com.privacyguard.app.core.tracker

/**
 * Who operates a domain, and who publishes an app — so a destination can be shown
 * as "WhatsApp's own servers (Meta)" or "Amazon Web Services hosting" instead of a
 * generic "Network".
 *
 * Matching is by registrable suffix ("g.whatsapp.net" → "whatsapp.net"). The
 * tracker database still decides what is a tracker; this only names owners.
 */
object OrgDirectory {

    enum class Kind(val label: String) {
        /** A company's own product servers (WhatsApp, TikTok, Snapchat, Google services, …). */
        SERVICE("Service"),
        /** Hosting / CDN providers that serve content for many companies. */
        HOSTING("Hosting & CDN"),
        /** Phone maker / OS infrastructure. */
        PLATFORM("Device & OS"),
    }

    data class Org(val name: String, val kind: Kind)

    private val META = Org("Meta", Kind.SERVICE)
    private val GOOGLE = Org("Google", Kind.SERVICE)
    private val BYTEDANCE = Org("ByteDance (TikTok)", Kind.SERVICE)
    private val SNAP = Org("Snap Inc.", Kind.SERVICE)
    private val MICROSOFT = Org("Microsoft", Kind.SERVICE)
    private val APPLE = Org("Apple", Kind.SERVICE)
    private val AMAZON = Org("Amazon", Kind.SERVICE)
    private val SAMSUNG = Org("Samsung", Kind.PLATFORM)
    private val X = Org("X Corp", Kind.SERVICE)
    private val TELEGRAM = Org("Telegram", Kind.SERVICE)
    private val SPOTIFY = Org("Spotify", Kind.SERVICE)
    private val NETFLIX = Org("Netflix", Kind.SERVICE)
    private val BINANCE = Org("Binance", Kind.SERVICE)

    private val bySuffix: Map<String, Org> = buildMap {
        fun add(org: Org, vararg suffixes: String) = suffixes.forEach { put(it, org) }
        add(META, "facebook.com", "facebook.net", "fbcdn.net", "fbsbx.com", "fb.com", "fb.me",
            "messenger.com", "instagram.com", "cdninstagram.com", "whatsapp.net", "whatsapp.com",
            "wa.me", "meta.com", "oculus.com", "threads.net", "facebookcorewwwi.onion")
        add(GOOGLE, "google.com", "googleapis.com", "gstatic.com", "googleusercontent.com",
            "googlevideo.com", "youtube.com", "ytimg.com", "ggpht.com", "gvt1.com", "gvt2.com",
            "google-analytics.com", "googletagmanager.com", "googlesyndication.com",
            "doubleclick.net", "googleadservices.com", "app-measurement.com", "firebaseio.com",
            "firebase.com", "crashlytics.com", "android.com", "gmail.com", "1e100.net", "goo.gl",
            "withgoogle.com", "googlezip.net", "google.co.uk", "google.de", "youtube-nocookie.com")
        add(BYTEDANCE, "tiktok.com", "tiktokv.com", "tiktokv.eu", "tiktokv.us", "tiktokcdn.com",
            "tiktokcdn-eu.com", "tiktokcdn-us.com", "tiktokw.us", "tiktokw.eu", "ttwstatic.com",
            "byteoversea.com", "byteoversea.net", "ibytedtos.com", "ibyteimg.com", "bytedance.com",
            "musical.ly", "muscdn.com", "tiktok-row.org", "tiktok-row.net", "bytegecko.com",
            "byteintl.com", "capcut.com", "pangle.io", "pangleglobal.com")
        add(SNAP, "snapchat.com", "snap.com", "sc-cdn.net", "snap-dev.net", "snapads.com",
            "sc-static.net", "snapkit.com", "bitmoji.com", "sc-prod.net", "feelinsonice-hrd.appspot.com")
        add(MICROSOFT, "microsoft.com", "live.com", "office.com", "office.net", "office365.com",
            "msn.com", "bing.com", "skype.com", "outlook.com", "windows.net", "msftconnecttest.com",
            "linkedin.com", "licdn.com", "xboxlive.com", "msedge.net", "microsoftonline.com",
            "visualstudio.com", "github.com", "githubusercontent.com", "azureedge.net")
        add(APPLE, "apple.com", "icloud.com", "mzstatic.com", "apple-dns.net", "cdn-apple.com")
        add(AMAZON, "amazon.com", "amazon.de", "amazon.co.uk", "media-amazon.com", "ssl-images-amazon.com",
            "amazon-adsystem.com", "primevideo.com", "audible.com")
        add(SAMSUNG, "samsung.com", "samsungcloud.com", "samsungdm.com", "samsungapps.com",
            "samsungosp.com", "samsungcloudsolution.com", "samsungcloudsolution.net",
            "samsungqbe.com", "samsungelectronics.com", "secb2b.com", "ospserver.net",
            "samsunghealth.com", "galaxymobile.jp", "samsungknox.com", "samsungiotcloud.com")
        add(X, "twitter.com", "twimg.com", "x.com", "t.co")
        add(TELEGRAM, "telegram.org", "t.me", "telegram.me", "telesco.pe")
        add(SPOTIFY, "spotify.com", "scdn.co", "spotifycdn.com", "spotilocal.com")
        add(NETFLIX, "netflix.com", "nflxvideo.net", "nflximg.net", "nflxext.com", "nflxso.net")
        add(BINANCE, "binance.com", "binance.org", "bnbstatic.com", "binanceapi.com")

        val hosting = mapOf(
            "Amazon Web Services" to listOf("amazonaws.com", "cloudfront.net", "awsglobalaccelerator.com", "aws.dev"),
            "Cloudflare" to listOf("cloudflare.com", "cloudflare.net", "cloudflare-dns.com", "one.one.one.one", "workers.dev", "pages.dev"),
            "Akamai" to listOf("akamai.net", "akamaiedge.net", "akamaihd.net", "akamaized.net", "akamaitechnologies.com", "edgekey.net", "edgesuite.net", "akadns.net"),
            "Fastly" to listOf("fastly.net", "fastlylb.net", "fastly.com"),
            "Microsoft Azure" to listOf("azure.com", "azurewebsites.net", "cloudapp.net", "trafficmanager.net", "azurefd.net"),
            "Google Cloud" to listOf("appspot.com", "run.app", "cloudfunctions.net", "googlehosted.com"),
            "Edgio" to listOf("edgecastcdn.net", "llnwd.net", "systemcdn.net"),
            "BunnyCDN" to listOf("b-cdn.net", "bunnycdn.com"),
            "jsDelivr" to listOf("jsdelivr.net"),
        )
        hosting.forEach { (name, suffixes) -> add(Org(name, Kind.HOSTING), *suffixes.toTypedArray()) }
    }

    /** App package prefixes → publisher. Longest prefix wins. */
    private val byPackagePrefix: List<Pair<String, Org>> = listOf(
        "com.whatsapp" to META, "com.facebook." to META, "com.instagram." to META,
        "com.meta." to META, "com.oculus." to META,
        "com.zhiliaoapp.musically" to BYTEDANCE, "com.ss.android." to BYTEDANCE,
        "com.lemon.lvoverseas" to BYTEDANCE,
        "com.snapchat.android" to SNAP,
        "com.google." to GOOGLE, "com.android.chrome" to GOOGLE, "com.android.vending" to GOOGLE,
        "com.microsoft." to MICROSOFT, "com.skype." to MICROSOFT, "com.linkedin." to MICROSOFT,
        "com.samsung." to SAMSUNG, "com.sec." to SAMSUNG, "com.osp." to SAMSUNG,
        "com.twitter.android" to X, "org.telegram." to TELEGRAM,
        "com.spotify." to SPOTIFY, "com.netflix." to NETFLIX, "com.amazon." to AMAZON,
        "com.binance." to BINANCE,
    ).sortedByDescending { it.first.length }

    /** The operator of [host], or null when it is not in the directory. */
    fun ownerOf(host: String): Org? {
        var h = host.lowercase().trimEnd('.')
        while (true) {
            bySuffix[h]?.let { return it }
            val dot = h.indexOf('.')
            if (dot < 0) return null
            h = h.substring(dot + 1)
        }
    }

    /** The publisher of [packageName], or null when unknown. */
    fun publisherOf(packageName: String): Org? =
        byPackagePrefix.firstOrNull { (prefix, _) -> packageName == prefix || packageName.startsWith(prefix) }?.second

    /**
     * True when [host] belongs to the same company as the app — e.g. facebook.com
     * for WhatsApp. Such traffic is the app talking to its own backend, not a
     * third party tracking you, even when the domain also hosts an SDK.
     */
    fun isFirstParty(packageName: String, host: String): Boolean {
        val publisher = publisherOf(packageName) ?: return false
        return ownerOf(host) == publisher
    }
}
