package com.privacyguard.app.core.tracker

enum class TrackerCategory(val label: String) {
    ADVERTISING("Advertising"),
    ANALYTICS("Analytics"),
    CRASH_REPORTING("Crash Reporting"),
    FINGERPRINTING("Fingerprinting"),
    SOCIAL("Social"),
    PROFILING("Profiling"),
    OTHER("Other"),
}

data class TrackerEntry(
    val name: String,
    val company: String,
    val category: TrackerCategory,
    val domains: List<String>,
    val classPatterns: List<String> = emptyList(),
)

object TrackerDatabase {

    val all: List<TrackerEntry> = listOf(

        // ── Advertising ───────────────────────────────────────────────────────

        TrackerEntry("Google AdMob", "Google LLC", TrackerCategory.ADVERTISING,
            listOf("admob.com", "googleadservices.com", "googlesyndication.com", "doubleclick.net",
                "adservice.google.com", "googleads.g.doubleclick.net"),
            listOf("com.google.android.gms.ads", "com.google.ads")),

        TrackerEntry("Facebook Audience Network", "Meta Platforms", TrackerCategory.ADVERTISING,
            listOf("an.facebook.com", "graph.facebook.com", "connect.facebook.net",
                "fbcdn.net", "fbsbx.com"),
            listOf("com.facebook.ads", "com.facebook.audiencenetwork")),

        TrackerEntry("AppLovin", "AppLovin Corporation", TrackerCategory.ADVERTISING,
            listOf("applovin.com", "applovin-api.com"),
            listOf("com.applovin")),

        TrackerEntry("IronSource", "IronSource Ltd", TrackerCategory.ADVERTISING,
            listOf("ironsource.com", "supersonic.com", "supersonicads.com"),
            listOf("com.ironsource.mediationsdk", "com.supersonic.sdk")),

        TrackerEntry("Unity Ads", "Unity Technologies", TrackerCategory.ADVERTISING,
            listOf("unityads.unity3d.com", "auction.unityads.unity3d.com", "publisher-event.unityads.unity3d.com"),
            listOf("com.unity3d.ads", "com.unity.ads")),

        TrackerEntry("Chartboost", "Chartboost Inc", TrackerCategory.ADVERTISING,
            listOf("chartboost.com", "live.chartboost.com"),
            listOf("com.chartboost.sdk")),

        TrackerEntry("InMobi", "InMobi Pte Ltd", TrackerCategory.ADVERTISING,
            listOf("inmobi.com", "w.inmobi.com", "acdn.adnxs.com"),
            listOf("com.inmobi.ads")),

        TrackerEntry("Verizon / Yahoo Ads", "Verizon Media", TrackerCategory.ADVERTISING,
            listOf("ads.yahoo.com", "oath.com", "mopub.com", "burstly.com"),
            listOf("com.mopub.mobileads", "com.verizon.ads")),

        TrackerEntry("Criteo", "Criteo SA", TrackerCategory.ADVERTISING,
            listOf("criteo.com", "static.criteo.net", "events.criteo.com", "sslwidget.criteo.com"),
            listOf("com.criteo.publisher")),

        TrackerEntry("The Trade Desk", "The Trade Desk Inc", TrackerCategory.ADVERTISING,
            listOf("adsrvr.org", "thetradedesk.com", "liveramp.com"),
            listOf()),

        TrackerEntry("Taboola", "Taboola Inc", TrackerCategory.ADVERTISING,
            listOf("taboola.com", "trc.taboola.com", "cdn.taboola.com"),
            listOf("com.taboola.android")),

        TrackerEntry("Outbrain", "Outbrain Inc", TrackerCategory.ADVERTISING,
            listOf("outbrain.com", "amplify.outbrain.com", "widgets.outbrain.com"),
            listOf("com.outbrain.pilot")),

        TrackerEntry("Fyber / Digital Turbine", "Digital Turbine", TrackerCategory.ADVERTISING,
            listOf("fyber.com", "inner-active.mobi", "digitalturbine.com"),
            listOf("com.fyber", "com.inneractive")),

        TrackerEntry("Yandex Ads", "Yandex LLC", TrackerCategory.ADVERTISING,
            listOf("an.yandex.ru", "ads.adfox.ru", "yabs.yandex.ru"),
            listOf("com.yandex.mobile.ads")),

        TrackerEntry("Snap Audience Network", "Snap Inc", TrackerCategory.ADVERTISING,
            listOf("ads.snapchat.com", "sc-cdn.net"),
            listOf("com.snap.adkit")),

        TrackerEntry("TikTok Ads", "ByteDance Ltd", TrackerCategory.ADVERTISING,
            listOf("ads.tiktok.com", "analytics.tiktok.com", "log.tiktokv.com"),
            listOf("com.bytedance.sdk.openadsdk")),

        TrackerEntry("Vungle", "Vungle Inc", TrackerCategory.ADVERTISING,
            listOf("vungle.com", "rtn.vungle.com"),
            listOf("com.vungle.warren")),

        TrackerEntry("AdColony", "Digital Turbine", TrackerCategory.ADVERTISING,
            listOf("adcolony.com", "ads.adcolony.com"),
            listOf("com.adcolony.sdk")),

        // ── Analytics ─────────────────────────────────────────────────────────

        TrackerEntry("Google Analytics / Firebase Analytics", "Google LLC", TrackerCategory.ANALYTICS,
            listOf("google-analytics.com", "analytics.google.com", "firebase.google.com",
                "firebaseapp.com", "app-measurement.com", "firebaseinstallations.googleapis.com"),
            listOf("com.google.android.gms.analytics", "com.google.firebase.analytics",
                "com.google.firebase.perf")),

        TrackerEntry("AppsFlyer", "AppsFlyer Ltd", TrackerCategory.ANALYTICS,
            listOf("appsflyer.com", "onelink.me", "af-mam.com", "t3.appsflyer.com"),
            listOf("com.appsflyer")),

        TrackerEntry("Adjust", "Adjust GmbH", TrackerCategory.ANALYTICS,
            listOf("adjust.com", "app.adjust.com", "s2s.adjust.com"),
            listOf("com.adjust.sdk")),

        TrackerEntry("Branch", "Branch Metrics", TrackerCategory.ANALYTICS,
            listOf("branch.io", "app.link", "bnc.lt", "cdn.branch.io"),
            listOf("io.branch.referral")),

        TrackerEntry("Amplitude", "Amplitude Inc", TrackerCategory.ANALYTICS,
            listOf("amplitude.com", "api.amplitude.com", "api2.amplitude.com"),
            listOf("com.amplitude.api", "com.amplitude.android")),

        TrackerEntry("Mixpanel", "Mixpanel Inc", TrackerCategory.ANALYTICS,
            listOf("mixpanel.com", "api.mixpanel.com", "decide.mixpanel.com"),
            listOf("com.mixpanel.android")),

        TrackerEntry("Segment", "Twilio Segment", TrackerCategory.ANALYTICS,
            listOf("segment.com", "api.segment.io", "cdn.segment.com"),
            listOf("com.segment.analytics")),

        TrackerEntry("Flurry", "Yahoo / Verizon", TrackerCategory.ANALYTICS,
            listOf("flurry.com", "data.flurry.com", "api.flurry.com"),
            listOf("com.flurry.android")),

        TrackerEntry("Kochava", "Kochava Inc", TrackerCategory.ANALYTICS,
            listOf("kochava.com", "control.kochava.com"),
            listOf("com.kochava.base")),

        TrackerEntry("Singular", "Singular Labs", TrackerCategory.ANALYTICS,
            listOf("singular.net", "sdk-api-v1.singular.net"),
            listOf("com.singular.sdk")),

        TrackerEntry("CleverTap", "CleverTap Pvt Ltd", TrackerCategory.ANALYTICS,
            listOf("clevertap.com", "wzrkt.com", "eu1.clevertap.com"),
            listOf("com.clevertap.android")),

        TrackerEntry("Braze / Appboy", "Braze Inc", TrackerCategory.ANALYTICS,
            listOf("braze.com", "appboy.com", "iad.appboy.com", "sdk.iad.braze.com"),
            listOf("com.braze", "com.appboy")),

        TrackerEntry("MoEngage", "MoEngage Inc", TrackerCategory.ANALYTICS,
            listOf("moengage.com", "sdk.moengage.com"),
            listOf("com.moengage.core")),

        TrackerEntry("Leanplum", "Leanplum Inc", TrackerCategory.ANALYTICS,
            listOf("leanplum.com", "www.leanplum.com"),
            listOf("com.leanplum")),

        TrackerEntry("Localytics", "Localytics", TrackerCategory.ANALYTICS,
            listOf("localytics.com", "analytics.localytics.com"),
            listOf("com.localytics.android")),

        TrackerEntry("Heap Analytics", "Heap Inc", TrackerCategory.ANALYTICS,
            listOf("heapanalytics.com", "api.heapanalytics.com"),
            listOf("io.heap.android")),

        TrackerEntry("Countly", "Count.ly", TrackerCategory.ANALYTICS,
            listOf("countly.com"),
            listOf("ly.count.android.sdk")),

        // ── Crash Reporting ───────────────────────────────────────────────────

        TrackerEntry("Firebase Crashlytics", "Google LLC", TrackerCategory.CRASH_REPORTING,
            listOf("firebase.google.com", "crashlytics.com", "api.crashlytics.com"),
            listOf("com.google.firebase.crashlytics", "com.crashlytics.sdk.android",
                "io.fabric.sdk.android")),

        TrackerEntry("Sentry", "Sentry.io", TrackerCategory.CRASH_REPORTING,
            listOf("sentry.io", "o.sentry.io", "browser.sentry-cdn.com"),
            listOf("io.sentry.android")),

        TrackerEntry("New Relic", "New Relic Inc", TrackerCategory.CRASH_REPORTING,
            listOf("newrelic.com", "mobile-collector.newrelic.com"),
            listOf("com.newrelic.agent.android")),

        TrackerEntry("Bugsnag", "Bugsnag Inc", TrackerCategory.CRASH_REPORTING,
            listOf("bugsnag.com", "notify.bugsnag.com", "sessions.bugsnag.com"),
            listOf("com.bugsnag.android")),

        TrackerEntry("Instabug", "Instabug Inc", TrackerCategory.CRASH_REPORTING,
            listOf("instabug.com", "api.instabug.com"),
            listOf("com.instabug.library")),

        TrackerEntry("Microsoft App Center", "Microsoft Corporation", TrackerCategory.CRASH_REPORTING,
            listOf("appcenter.ms", "in.appcenter.ms", "api.appcenter.ms"),
            listOf("com.microsoft.appcenter")),

        TrackerEntry("Datadog", "Datadog Inc", TrackerCategory.CRASH_REPORTING,
            listOf("datadoghq.com", "browser-intake-datadoghq.com"),
            listOf("com.datadog.android")),

        // ── Social ────────────────────────────────────────────────────────────

        TrackerEntry("Facebook SDK", "Meta Platforms", TrackerCategory.SOCIAL,
            listOf("facebook.com", "www.facebook.com", "m.facebook.com",
                "edge-chat.facebook.com", "graph.facebook.com"),
            listOf("com.facebook.FacebookSdk", "com.facebook.login")),

        TrackerEntry("Twitter SDK", "X Corp", TrackerCategory.SOCIAL,
            listOf("twitter.com", "t.co", "api.twitter.com", "analytics.twitter.com"),
            listOf("com.twitter.sdk.android")),

        TrackerEntry("LinkedIn", "Microsoft / LinkedIn", TrackerCategory.SOCIAL,
            listOf("linkedin.com", "api.linkedin.com", "px.ads.linkedin.com"),
            listOf("com.linkedin.android")),

        TrackerEntry("Pinterest", "Pinterest Inc", TrackerCategory.SOCIAL,
            listOf("pinterest.com", "api.pinterest.com"),
            listOf("com.pinterest")),

        TrackerEntry("Snapchat", "Snap Inc", TrackerCategory.SOCIAL,
            listOf("snapchat.com", "api.snapchat.com"),
            listOf("com.snapchat.analytics")),

        // ── Fingerprinting / Profiling ────────────────────────────────────────

        TrackerEntry("DoubleVerify", "DoubleVerify Inc", TrackerCategory.FINGERPRINTING,
            listOf("doubleverify.com", "cdn.doubleverify.com"),
            listOf()),

        TrackerEntry("Integral Ad Science (IAS)", "Integral Ad Science", TrackerCategory.FINGERPRINTING,
            listOf("integralads.com", "iasds01.com", "cdn.adsafeprotected.com"),
            listOf()),

        TrackerEntry("Oracle BlueKai", "Oracle Corporation", TrackerCategory.PROFILING,
            listOf("bluekai.com", "tags.bluekai.com", "oracle.com"),
            listOf()),

        TrackerEntry("Nielsen", "Nielsen Holdings", TrackerCategory.PROFILING,
            listOf("nielsen.com", "secure-us.imrworldwide.com"),
            listOf("com.nielsen.android.ta")),

        TrackerEntry("comScore", "comScore Inc", TrackerCategory.ANALYTICS,
            listOf("comscore.com", "scorecardresearch.com", "b.scorecardresearch.com"),
            listOf("com.comscore")),

        TrackerEntry("Quantcast", "Quantcast Corporation", TrackerCategory.PROFILING,
            listOf("quantcast.com", "pixel.quantserve.com"),
            listOf()),

        TrackerEntry("MOAT Analytics", "Oracle Corporation", TrackerCategory.FINGERPRINTING,
            listOf("moatads.com", "moat.com", "z.moatads.com"),
            listOf()),
    )

    // ── Indexes ───────────────────────────────────────────────────────────────

    private val domainIndex: Map<String, TrackerEntry> by lazy {
        buildMap {
            all.forEach { tracker ->
                tracker.domains.forEach { domain ->
                    put(domain.lowercase(), tracker)
                }
            }
        }
    }

    private val classIndex: Map<String, TrackerEntry> by lazy {
        buildMap {
            all.forEach { tracker ->
                tracker.classPatterns.forEach { pattern ->
                    put(pattern.lowercase(), tracker)
                }
            }
        }
    }

    fun lookupByDomain(host: String): TrackerEntry? {
        val h = host.lowercase().trimStart('.')
        domainIndex[h]?.let { return it }
        var dot = h.indexOf('.')
        while (dot != -1) {
            val parent = h.substring(dot + 1)
            domainIndex[parent]?.let { return it }
            dot = h.indexOf('.', dot + 1)
        }
        // Fall back to Exodus live data
        return ExodusUpdater.lookupByDomain(host)
    }

    fun lookupByClassPattern(className: String): TrackerEntry? {
        val c = className.lowercase()
        return classIndex.entries.firstOrNull { (pattern, _) -> c.startsWith(pattern) }?.value
            ?: ExodusUpdater.lookupByClassPattern(className)
    }

    fun companyForDomain(host: String): String =
        lookupByDomain(host)?.company ?: inferFallbackCompany(host)

    private fun inferFallbackCompany(host: String): String {
        val h = host.lowercase()
        return when {
            "google" in h || "gstatic" in h || "googleapis" in h || "goog" in h -> "Google"
            "facebook" in h || "fbcdn" in h || "fbsbx" in h -> "Meta"
            "amazon" in h || "aws" in h || "amazonaws" in h -> "Amazon"
            "microsoft" in h || "azure" in h || "msn" in h || "bing" in h -> "Microsoft"
            "apple" in h || "icloud" in h || "mzstatic" in h -> "Apple"
            "cloudflare" in h -> "Cloudflare"
            "akamai" in h || "akamaitechnologies" in h -> "Akamai"
            "twitter" in h || "twimg" in h -> "X Corp"
            "tiktok" in h || "bytedance" in h || "ttwstatic" in h -> "ByteDance"
            "snapchat" in h || "snap.com" in h -> "Snap"
            else -> "Network"
        }
    }
}
