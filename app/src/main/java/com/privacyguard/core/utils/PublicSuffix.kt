package com.privacyguard.core.utils

import okhttp3.HttpUrl

/**
 * Public Suffix List lookups (co.uk, com.au, github.io…), from the copy OkHttp
 * ships (okhttp3/internal/publicsuffix/publicsuffixes.gz) and keeps current with
 * each release, so no second list needs maintaining here.
 */
object PublicSuffix {

    /**
     * The registrable domain of [host] ("bbc.co.uk" for "news.bbc.co.uk"), or null
     * when [host] is itself a public suffix, an IP address or not a hostname.
     */
    fun registrableDomain(host: String): String? = runCatching {
        HttpUrl.Builder().scheme("https").host(host.trimEnd('.')).build().topPrivateDomain()
    }.getOrNull()

    /** True for "com", "co.uk", "github.io": a block there would cover unrelated sites. */
    fun isPublicSuffix(host: String): Boolean = registrableDomain(host) == null
}
