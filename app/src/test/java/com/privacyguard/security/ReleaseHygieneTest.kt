package com.privacyguard.security

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Regressions for V-13 to V-15 (security test 2026-10-14). Log.w / Log.e survive R8
 * and end up in logcat and bug reports, so on the network paths they must never
 * carry addresses, hosts, package names or exception messages (which quote hosts).
 */
class ReleaseHygieneTest {

    private val networkPaths = listOf(
        "src/main/java/com/privacyguard/vpn",
        "src/main/java/com/privacyguard/core/tls",
        "src/main/java/com/privacyguard/app/vpn",
        "src/main/java/com/privacyguard/app/core/tracker",
        "src/enterprise/java/com/privacyguard/vpn/mitm",
    ).map(::File)

    private val forbidden = listOf(
        Regex("""\.message\b"""), Regex("""\$\{?key\b"""), Regex("""session\.key"""), Regex("""\$\{?(host|server|domain|sni|hostname|packageName|pkg|dstIp|url)\b"""),
        Regex(""",\s*(e|it|t|throwable)\s*\)\s*$"""),
    )

    @Test
    fun network_warnings_and_errors_carry_no_hosts_addresses_or_messages() {
        val files = networkPaths.filter { it.isDirectory }.flatMap { d -> d.walkTopDown().filter { it.extension == "kt" }.toList() }
        assertTrue(files.size > 20)
        val offenders = files.flatMap { f ->
            f.readLines().withIndex()
                .filter { (_, line) -> line.contains("Log.w(") || line.contains("Log.e(") }
                .filter { (_, line) -> forbidden.any { it.containsMatchIn(line) } }
                .map { (i, line) -> "${f.path}:${i + 1}: ${line.trim()}" }
        }
        assertEquals("Logs that leak into release logcat:\n" + offenders.joinToString("\n"), emptyList<String>(), offenders)
    }

    @Test
    fun release_builds_carry_no_vcs_info_and_no_vulnerable_guava() {
        val gradle = File("build.gradle.kts").readText()
        assertTrue("V-15", gradle.contains("vcsInfo.include = false"))
        assertFalse("V-14: guava 31 came in through coroutines-guava", gradle.contains("coroutines.guava"))
    }

    @Test
    fun dependency_verification_is_on() {
        val meta = File("../gradle/verification-metadata.xml")
        assertTrue("V-14: gradle/verification-metadata.xml missing", meta.isFile)
        val text = meta.readText()
        assertTrue(text.contains("<verify-metadata>true</verify-metadata>"))
        assertTrue(text.contains("<sha256 "))
    }
}
