package com.privacyguard.app.ui.settings

import com.privacyguard.app.data.local.preferences.SettingsPreferences
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Settings › Advanced (DESIGN_SPEC_1.1.md §6.6). */
class AdvancedLogicTest {

    @Test
    fun a_typed_server_is_other_and_the_known_ones_are_presets() {
        assertEquals(AdvancedLogic.DnsPreset.QUAD9, AdvancedLogic.presetOf(" 9.9.9.9 "))
        assertNull(AdvancedLogic.presetOf("192.168.1.1"))
    }

    @Test
    fun each_preset_sets_its_own_encrypted_provider_as_in_1_0() {
        assertEquals(SettingsPreferences.DOH_CLOUDFLARE, AdvancedLogic.DnsPreset.CLOUDFLARE.dohProvider)
        assertEquals(SettingsPreferences.DOH_GOOGLE, AdvancedLogic.DnsPreset.GOOGLE.dohProvider)
        assertEquals(SettingsPreferences.DOH_QUAD9, AdvancedLogic.DnsPreset.QUAD9.dohProvider)
        assertEquals("Quad9", AdvancedLogic.providerLabel(SettingsPreferences.DOH_QUAD9))
    }

    @Test
    fun only_a_real_port_is_saved() {
        // 1.0 saved 1080 the moment the field was empty, and accepted 70000.
        assertNull(AdvancedLogic.parsePort(""))
        assertNull(AdvancedLogic.parsePort("0"))
        assertNull(AdvancedLogic.parsePort("70000"))
        assertNull(AdvancedLogic.parsePort("-1"))
        assertEquals(9050, AdvancedLogic.parsePort("9050"))
        assertEquals(65535, AdvancedLogic.parsePort("65535"))
    }

    @Test
    fun a_proxy_host_is_a_name_or_address_not_a_url() {
        assertTrue(AdvancedLogic.isValidProxyHost("proxy.example.net"))
        assertTrue(AdvancedLogic.isValidProxyHost("10.0.0.2"))
        listOf("", "  ", "socks5://proxy.example.net", "user@proxy", "proxy example", "proxy/x")
            .forEach { assertFalse(it, AdvancedLogic.isValidProxyHost(it)) }
    }

    @Test
    fun the_chain_is_complete_only_with_both_servers() {
        assertTrue(AdvancedLogic.proxyChainComplete("a.example", 1080, "b.example", 1080))
        assertFalse(AdvancedLogic.proxyChainComplete("a.example", 1080, "", 1080))
        assertFalse(AdvancedLogic.proxyChainComplete("a.example", 0, "b.example", 1080))
    }

    @Test
    fun stored_list_keys_get_their_published_names_and_built_in_lists_their_category() {
        assertEquals(AdvancedLogic.StoredList.Named("HaGeZi TIF mini"), AdvancedLogic.storedList("Hagezi-TIF-mini"))
        assertEquals(AdvancedLogic.StoredList.Named("OISD small"), AdvancedLogic.storedList("OISD"))
        assertEquals(AdvancedLogic.StoredList.BuiltIn(AdvancedLogic.BuiltInCategory.MALWARE), AdvancedLogic.storedList("BuiltIn-malware"))
        // An unknown key is shown as stored, never hidden.
        assertEquals(AdvancedLogic.StoredList.Unknown("StevenBlack"), AdvancedLogic.storedList("StevenBlack"))
    }

    @Test
    fun every_downloaded_list_has_its_published_name() {
        SettingsLogic.DOWNLOADED_LISTS.forEach { source ->
            val name = AdvancedLogic.listName(source)
            assertTrue("$source → $name", name.startsWith("HaGeZi") || name.startsWith("OISD"))
        }
    }
}
