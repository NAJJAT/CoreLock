package com.privacyguard.app.core.blocklist

import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The built-in lists must never block the services that warn users about phishing
 * and malware: blocking safebrowsing.googleapis.com at Strict turned off Chrome's
 * warnings. A list entry also blocks every name under it, so a parent such as
 * googleapis.com counts too.
 */
class BuiltInSeedTest {

    private val protectedHosts = listOf(
        "safebrowsing.googleapis.com",
        "safebrowsing.google.com",
    )

    private fun blocks(entry: String, host: String): Boolean {
        val e = entry.lowercase().removePrefix("*.").trimEnd('.')
        return host == e || host.endsWith(".$e")
    }

    @Test
    fun no_category_blocks_safe_browsing() {
        val entries = BlocklistManager.BUILTIN_SEED.values.flatMap { cats -> cats.values.flatten() } + CuratedAdDomains.all
        val hits = entries.filter { entry -> protectedHosts.any { blocks(entry, it) } }
        assertTrue("Built-in entries block Safe Browsing: $hits", hits.isEmpty())
    }
}
