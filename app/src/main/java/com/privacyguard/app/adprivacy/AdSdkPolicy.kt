package com.privacyguard.app.adprivacy

/**
 * Third-party ad and tracking SDK traffic, blocked inside OTHER apps only.
 *
 * Blocking happens at DNS, so it sees host names, never URL paths. Hosts that
 * carry both tracking and something people need (login, push, deep links) are
 * listed in [AdSdkCatalog.neverBlock] instead, with the reason; they are kept
 * open even if a downloaded blocklist contains them.
 */
data class AdSdk(
    val id: String,
    val company: String,
    val domains: List<String>,
    /** The company's own apps: blocking its SDK hosts there breaks the app itself. */
    val ownerPackagePrefixes: List<String> = emptyList(),
)

data class NeverBlock(val domain: String, val reason: NeverBlockReason)

enum class NeverBlockReason { LOGIN, PUSH, DEEP_LINKS }

object AdSdkCatalog {

    private val META_APPS = listOf("com.facebook.", "com.instagram.", "com.whatsapp", "com.oculus.", "com.meta.")
    private val GOOGLE_APPS = listOf("com.google.", "com.android.vending")

    val sdks: List<AdSdk> = listOf(
        // Audience Network's mediation and SDK assets. Its main endpoint is
        // graph.facebook.com/network_ads_common, which shares a host with login.
        AdSdk("meta_audience_network", "Meta", listOf("an.facebook.com", "connect.facebook.net"), META_APPS),
        AdSdk("google_ads", "Google", listOf(
            "googleads.g.doubleclick.net", "pagead2.googlesyndication.com",
            "googleadservices.com", "doubleclick.net",
        ), GOOGLE_APPS),
        AdSdk("firebase_analytics", "Google", listOf("app-measurement.com"), GOOGLE_APPS),
        AdSdk("appsflyer", "AppsFlyer", listOf("appsflyer.com", "appsflyersdk.com")),
        AdSdk("adjust", "Adjust", listOf("adjust.com", "adjust.io", "adjust.net.in", "adjust.world")),
        AdSdk("applovin", "AppLovin", listOf("applovin.com", "applvn.com")),
        AdSdk("unity_ads", "Unity", listOf("unityads.unity3d.com")),
        AdSdk("ironsource", "Unity (ironSource)", listOf("supersonicads.com", "ironsrc.mobi")),
        AdSdk("vungle", "Liftoff (Vungle)", listOf("vungle.com")),
        AdSdk("inmobi", "InMobi", listOf("inmobi.com")),
        AdSdk("chartboost", "Chartboost", listOf("chartboost.com")),
        AdSdk("mintegral", "Mintegral", listOf("rayjump.com", "mintegral.net")),
        AdSdk("kochava", "Kochava", listOf("kochava.com")),
        AdSdk("singular", "Singular", listOf("sng.link", "singular.net")),
    )

    val neverBlock: List<NeverBlock> = listOf(
        // Facebook Login (and Meta app events, which ride the same host).
        NeverBlock("graph.facebook.com", NeverBlockReason.LOGIN),
        NeverBlock("m.facebook.com", NeverBlockReason.LOGIN),
        NeverBlock("www.facebook.com", NeverBlockReason.LOGIN),
        NeverBlock("accounts.google.com", NeverBlockReason.LOGIN),
        // Firebase Cloud Messaging needs an installation ID; without it apps stop receiving pushes.
        NeverBlock("firebaseinstallations.googleapis.com", NeverBlockReason.PUSH),
        NeverBlock("fcm.googleapis.com", NeverBlockReason.PUSH),
        NeverBlock("mtalk.google.com", NeverBlockReason.PUSH),
        // Branch links (app.link) and AppsFlyer OneLink open content inside apps.
        NeverBlock("api2.branch.io", NeverBlockReason.DEEP_LINKS),
        NeverBlock("branch.io", NeverBlockReason.DEEP_LINKS),
        NeverBlock("app.link", NeverBlockReason.DEEP_LINKS),
        NeverBlock("onelink.me", NeverBlockReason.DEEP_LINKS),
    )
}

sealed interface AdSdkVerdict {
    data class Block(val sdk: AdSdk) : AdSdkVerdict
    data class KeepOpen(val rule: NeverBlock) : AdSdkVerdict
    data object NoOpinion : AdSdkVerdict
}

class AdSdkPolicy(
    private val enabled: () -> Boolean,
    private val sdks: List<AdSdk> = AdSdkCatalog.sdks,
    private val neverBlock: List<NeverBlock> = AdSdkCatalog.neverBlock,
) {
    fun decide(ownerPackage: String?, domain: String): AdSdkVerdict {
        val host = domain.lowercase().trimEnd('.')
        // Exceptions apply even with SDK blocking off: they also shield these hosts
        // from downloaded blocklists.
        neverBlock.firstOrNull { matches(host, it.domain) }?.let { return AdSdkVerdict.KeepOpen(it) }
        if (!enabled()) return AdSdkVerdict.NoOpinion
        val sdk = sdks.firstOrNull { s -> s.domains.any { matches(host, it) } } ?: return AdSdkVerdict.NoOpinion
        if (ownerPackage != null && sdk.ownerPackagePrefixes.any { ownerPackage.startsWith(it) }) {
            return AdSdkVerdict.NoOpinion
        }
        return AdSdkVerdict.Block(sdk)
    }

    private fun matches(host: String, rule: String) = host == rule || host.endsWith(".$rule")
}
