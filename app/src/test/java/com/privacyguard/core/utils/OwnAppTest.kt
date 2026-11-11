package com.privacyguard.core.utils

import com.privacyguard.app.BuildConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * CoreLock leaves its own traffic out of History, Connections, the Home count, payload
 * capture and the DNS anomaly checks. It is recognised by its exact application ID (or
 * its UID): any app can pick a package name that merely starts like ours, and a prefix
 * check hid that app's traffic from the user.
 */
class OwnAppTest {

    @Test
    fun our_exact_package_is_ours() {
        assertTrue(OwnApp.isOwnPackage(BuildConfig.APPLICATION_ID))
    }

    @Test
    fun a_look_alike_package_is_not_ours() {
        for (pkg in listOf("com.privacyguard.app", "com.privacyguard.appx", "com.privacyguard.app.enterprise.spy",
            BuildConfig.APPLICATION_ID + "x", BuildConfig.APPLICATION_ID.uppercase(), "")) {
            assertFalse(pkg, OwnApp.isOwnPackage(pkg))
        }
        assertFalse(OwnApp.isOwnPackage(null))
    }

    @Test
    fun no_code_recognises_us_by_a_package_prefix() {
        val offenders = listOf("src/main/java", "src/enterprise/java").flatMap { root ->
            File(root).walkTopDown().filter { it.isFile && it.extension == "kt" }.flatMap { f ->
                f.readLines().withIndex()
                    .filter { (_, line) -> Regex("""startsWith\(\s*"com\.privacyguard""").containsMatchIn(line) }
                    .map { (i, _) -> "${f.path}:${i + 1}" }
            }.toList()
        }
        assertEquals("package-prefix checks for our own app", emptyList<String>(), offenders)
    }
}
