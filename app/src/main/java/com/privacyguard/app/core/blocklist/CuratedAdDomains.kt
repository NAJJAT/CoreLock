package com.privacyguard.app.core.blocklist

object CuratedAdDomains {
    val snapchatAds: List<String> = listOf(
        "ads.snapchat.com",
        "adsapi.snapchat.com",
        "tr.snapchat.com",
        "snapads.com",
        "usc.adserver.snapads.com",
    )

    val snapchatTrackers: List<String> = listOf(
        "analytics.snapchat.com",
        "app-analytics.snapchat.com",
        "app-analytics-v2.snapchat.com",
        "sc-analytics.appspot.com",
    )

    val all: List<String> = snapchatAds + snapchatTrackers
}
