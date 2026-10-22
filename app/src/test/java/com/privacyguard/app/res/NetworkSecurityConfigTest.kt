package com.privacyguard.app.res

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.w3c.dom.Element
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

/**
 * PrivacyGuard's own connections: no cleartext (Android 7.0 to 8.1 allow it by default)
 * and only system CAs, so a user-installed CA cannot read blocklist or SIEM traffic.
 */
class NetworkSecurityConfigTest {

    private fun parse(path: String): Element {
        val file = File(path)
        assertTrue("missing $path", file.isFile)
        return DocumentBuilderFactory.newInstance().apply { isNamespaceAware = true }
            .newDocumentBuilder().parse(file).documentElement
    }

    @Test
    fun manifest_points_at_the_config() {
        val app = parse("src/main/AndroidManifest.xml").getElementsByTagName("application").item(0) as Element
        assertEquals("@xml/network_security_config",
            app.getAttributeNS("http://schemas.android.com/apk/res/android", "networkSecurityConfig"))
        assertFalse("usesCleartextTraffic would be ignored, but must not say true",
            app.getAttributeNS("http://schemas.android.com/apk/res/android", "usesCleartextTraffic") == "true")
    }

    @Test
    fun cleartext_off_and_only_system_cas() {
        val config = parse("src/main/res/xml/network_security_config.xml")
        val base = config.getElementsByTagName("base-config").item(0) as Element
        assertEquals("false", base.getAttribute("cleartextTrafficPermitted"))
        assertEquals("no per-domain exceptions", 0, config.getElementsByTagName("domain-config").length)
        assertEquals("no debug overrides", 0, config.getElementsByTagName("debug-overrides").length)
        val sources = (0 until config.getElementsByTagName("certificates").length)
            .map { (config.getElementsByTagName("certificates").item(it) as Element).getAttribute("src") }
        assertEquals(listOf("system"), sources)
    }
}
