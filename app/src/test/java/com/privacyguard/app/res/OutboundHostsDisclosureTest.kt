package com.privacyguard.app.res

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Onboarding lists every server the app itself contacts. When someone adds a new
 * download or lookup in one of these files, this test fails until the text says so,
 * in every language and in both flavours.
 */
class OutboundHostsDisclosureTest {

    /** Files that make network requests of their own (not links the user opens). */
    private val senders = listOf(
        "src/main/java/com/privacyguard/app/core/blocklist/BlocklistSource.kt",
        "src/main/java/com/privacyguard/app/core/tracker/ExodusUpdater.kt",
        "src/main/java/com/privacyguard/core/tls/CtMonitor.kt",
        "src/main/java/com/privacyguard/app/ui/settings/SettingsViewModel.kt",
        "src/main/java/com/privacyguard/vpn/forwarder/DnsHandler.kt",
    ).map { File(it) }

    private val onboardingFiles = listOf("src/main/res", "src/enterprise/res")
        .flatMap { root -> File(root).listFiles { f -> f.name.startsWith("values") }.orEmpty().toList() }
        .map { File(it, "strings_onboarding.xml") }
        .filter { it.isFile }

    @Test
    fun every_contacted_host_is_named_in_onboarding() {
        val hosts = senders.flatMap { f ->
            assertTrue("missing ${f.path}", f.isFile)
            Regex("https://([A-Za-z0-9.-]+)").findAll(f.readText()).map { it.groupValues[1] }.toList()
        }.toSet()
        assertTrue(hosts.isNotEmpty())
        assertTrue(onboardingFiles.size >= 8)

        val missing = onboardingFiles.flatMap { file ->
            val body = Regex("<string name=\"onb_c_net_body\"[^>]*>(.*?)</string>", RegexOption.DOT_MATCHES_ALL)
                .find(file.readText())?.groupValues?.get(1) ?: return@flatMap listOf("${file.path}: no onb_c_net_body")
            // "oisd.nl" in the text covers basic.oisd.nl and full.oisd.nl.
            hosts.filter { h -> generateSequence(h) { it.substringAfter('.', "").takeIf { s -> s.contains('.') } }.none { body.contains(it) } }
                .map { "${file.path}: $it" }
        }
        assertTrue("Hosts not disclosed in onboarding:\n" + missing.joinToString("\n"), missing.isEmpty())
    }
}
