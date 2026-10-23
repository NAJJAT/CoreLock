package com.privacyguard.app.res

import org.junit.Assert.assertEquals
import org.junit.Test
import org.w3c.dom.Element
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

/**
 * Exports are app-private files shared through FileProvider, so the shared manifest asks
 * for no storage access. Only the HTTPS-inspection CA export on API ≤ 28 writes Downloads
 * (its permission is in the enterprise manifest).
 */
class StoragePermissionTest {

    private val androidNs = "http://schemas.android.com/apk/res/android"

    /** Storage permissions declared in [manifest], with their maxSdkVersion. */
    private fun storagePermissions(manifest: String): Map<String, String> {
        val doc = DocumentBuilderFactory.newInstance().apply { isNamespaceAware = true }
            .newDocumentBuilder().parse(File(manifest))
        val nodes = doc.getElementsByTagName("uses-permission")
        return (0 until nodes.length).map { nodes.item(it) as Element }
            .filter { it.getAttributeNS(androidNs, "name").endsWith("_EXTERNAL_STORAGE") }
            .associate { it.getAttributeNS(androidNs, "name") to it.getAttributeNS(androidNs, "maxSdkVersion") }
    }

    @Test
    fun main_manifest_asks_for_no_storage() {
        assertEquals(emptyMap<String, String>(), storagePermissions("src/main/AndroidManifest.xml"))
    }

    /** The `path` of every entry in a FileProvider paths file. */
    private fun sharedPaths(file: String): List<String> {
        val doc = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(File(file))
        val children = doc.documentElement.childNodes
        return (0 until children.length).map { children.item(it) }
            .filterIsInstance<Element>().map { "${it.tagName}:${it.getAttribute("path")}" }
    }

    @Test
    fun file_provider_shares_only_the_export_and_ca_folders() {
        // Nothing else of files/ or cache/ (database, prefs, payload logs) may get a URI.
        assertEquals(
            listOf("files-path:exports/", "cache-path:certs/", "external-path:Download/PrivacyGuard/"),
            sharedPaths("src/main/res/xml/file_paths.xml"),
        )
        // One file only: a flavor copy would silently replace this one.
        assertEquals(false, File("src/enterprise/res/xml/file_paths.xml").exists())
    }

    @Test
    fun enterprise_writes_downloads_only_up_to_android_9() {
        assertEquals(
            mapOf("android.permission.WRITE_EXTERNAL_STORAGE" to "28"),
            storagePermissions("src/enterprise/AndroidManifest.xml"),
        )
    }
}
