package com.privacyguard.app.core.blocklist

import org.junit.Assert.assertEquals
import org.junit.Test

class EasyListParserTest {

    private fun parse(vararg lines: String) = BlocklistManager.parseEasyListFormat(lines.joinToString("\n"))

    @Test
    fun keepsWholeDomainRules() {
        assertEquals(
            listOf("ads.example.com", "tracker.example.net", "pixel.example.org", "beacon.example.io"),
            parse(
                "||ads.example.com^",
                "||tracker.example.net^\$important",
                "||pixel.example.org^|",
                "||beacon.example.io^\$all",
            ),
        )
    }

    /** Each of these once became a block on the whole site (google.com, github.com, …). */
    @Test
    fun skipsRulesScopedToAPathWildcardOrContext() {
        assertEquals(
            emptyList<String>(),
            parse(
                "||google.com/pagead/",
                "||github.com^\$third-party",
                "||wikipedia.org^\$script,domain=example.com",
                "||youtube.com/api/stats/*",
                "||amazon.*/gp/",
                "||ads.*.example.com^",
                "@@||allowed.example.com^",
                "! comment",
                "##.ad-banner",
            ),
        )
    }

    @Test
    fun lowercasesAndRejectsInvalidHosts() {
        assertEquals(listOf("ads.example.com"), parse("||ADS.Example.COM^", "||localhost^", "||1.2.3.4^"))
    }
}
