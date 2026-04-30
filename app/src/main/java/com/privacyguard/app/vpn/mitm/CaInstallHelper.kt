package com.privacyguard.ui.mitm

import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.Settings
import android.security.KeyChain
import android.util.Log
import androidx.annotation.RequiresApi
import androidx.core.content.FileProvider
import com.privacyguard.vpn.mitm.CaManager
import java.io.File
import java.io.FileOutputStream
import java.nio.charset.StandardCharsets

/**
 * Handles CA certificate export and installation on all supported API levels.
 *
 * Install strategy (try in order):
 *  1. [getKeyChainInstallIntent] — uses Android KeyChain API, no file write needed.
 *     Opens the system credential installer with the cert pre-loaded.
 *  2. [getFileInstallIntent] — exports to Downloads then opens via ACTION_VIEW.
 *  3. [getShareIntent] — share sheet fallback if the device's installer won't open.
 *  4. [getSecuritySettingsIntent] — opens Security settings for manual install.
 *
 * Export (Downloads) strategy by API level:
 *  API ≤ 28  →  direct FileOutputStream (WRITE_EXTERNAL_STORAGE capped at maxSdkVersion 28)
 *  API 29+   →  MediaStore.Downloads insertion (scoped storage, no extra permission needed)
 */
class CaInstallHelper(
    private val context: Context,
    private val caManager: CaManager,
) {

    companion object {
        private const val TAG = "CaInstallHelper"
        const val CA_FILENAME = "privacyguard_ca.crt"
        private const val CA_MIME = "application/x-x509-ca-cert"
    }

    // ── Sealed result ─────────────────────────────────────────────────────────

    sealed class ExportResult {
        abstract val uri: Uri

        /** API ≤ 28: written to public Downloads, served through FileProvider. */
        data class LegacyFile(val file: File, override val uri: Uri) : ExportResult()

        /** API 29+: inserted into the MediaStore Downloads collection. */
        data class MediaStoreEntry(override val uri: Uri) : ExportResult()
    }

    // ── Primary path: KeyChain (no file write required) ───────────────────────

    /**
     * Returns an Intent that opens the system credential installer with the
     * PrivacyGuard CA pre-loaded. This is the recommended install path.
     *
     * Returns null if the CA has not been generated yet (call CaManager.initialize() first).
     */
    /**
     * Returns true if the device has a screen lock (PIN / pattern / password).
     * Android refuses to install CA certificates without one.
     */
    fun isScreenLockSet(): Boolean {
        val km = context.getSystemService(android.app.KeyguardManager::class.java)
        return km?.isDeviceSecure == true
    }

    fun getKeyChainInstallIntent(): Intent? {
        val cert = caManager.getCaCert()
        if (cert == null) {
            Log.e(TAG, "getKeyChainInstallIntent: getCaCert() null — initialize() not yet awaited?")
            return null
        }
        val der = cert.encoded
        Log.d(TAG, "getKeyChainInstallIntent: cert ${der.size}B subject=${cert.subjectDN}")
        if (der.isEmpty()) {
            Log.e(TAG, "getKeyChainInstallIntent: DER is empty — certificate object corrupt")
            return null
        }
        return KeyChain.createInstallIntent().apply {
            putExtra(KeyChain.EXTRA_CERTIFICATE, der)
            putExtra(KeyChain.EXTRA_NAME, "PrivacyGuard CA")
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
    }

    /**
     * Writes the CA cert as a DER binary file to app-private cache, then opens it
     * via ACTION_VIEW with the x-x509-ca-cert MIME type.
     *
     * More reliable than KeyChain on Samsung/MIUI devices.
     * DER binary is more universally recognised than PEM text.
     */
    fun getDerFileInstallIntent(): Intent? {
        val cert = caManager.getCaCert() ?: return null
        return try {
            val cacheDir = File(context.cacheDir, "certs").also { it.mkdirs() }
            val derFile  = File(cacheDir, "privacyguard_ca.der")
            derFile.writeBytes(cert.encoded)
            Log.d(TAG, "getDerFileInstallIntent: wrote ${cert.encoded.size}B DER to ${derFile.absolutePath}")
            val uri = androidx.core.content.FileProvider.getUriForFile(
                context, "${context.packageName}.fileprovider", derFile
            )
            Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, CA_MIME)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
        } catch (e: Exception) {
            Log.e(TAG, "getDerFileInstallIntent: failed — ${e.message}", e)
            null
        }
    }

    // ── Secondary path: export file to Downloads ──────────────────────────────

    /**
     * Writes the CA PEM to the device's Downloads folder.
     *
     * Returns [ExportResult] on success or null on failure.
     *
     *  API ≤ 28 → [ExportResult.LegacyFile] (FileProvider URI)
     *  API 29+  → [ExportResult.MediaStoreEntry] (MediaStore URI)
     */
    fun exportCaToDownloads(): ExportResult? {
        val certPem = caManager.getCaCertPem()
        if (certPem.isEmpty()) {
            Log.e(TAG, "exportCaToDownloads: CA PEM empty — initialize CaManager first")
            return null
        }
        Log.d(TAG, "exportCaToDownloads: API=${Build.VERSION.SDK_INT} pem.length=${certPem.length}")
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            exportViaMediaStore(certPem)
        } else {
            exportViaFilesystem(certPem)
        }
    }

    @RequiresApi(Build.VERSION_CODES.Q)
    private fun exportViaMediaStore(certPem: String): ExportResult? {
        Log.d(TAG, "exportViaMediaStore: inserting into MediaStore.Downloads")
        val resolver = context.contentResolver

        // Remove stale copies so the file manager doesn't accumulate duplicates.
        resolver.query(
            android.provider.MediaStore.Downloads.EXTERNAL_CONTENT_URI,
            arrayOf(android.provider.MediaStore.Downloads._ID),
            "${android.provider.MediaStore.Downloads.DISPLAY_NAME} = ?",
            arrayOf(CA_FILENAME), null,
        )?.use { cursor ->
            while (cursor.moveToNext()) {
                val id = cursor.getLong(0)
                val deleteUri = Uri.withAppendedPath(
                    android.provider.MediaStore.Downloads.EXTERNAL_CONTENT_URI, id.toString()
                )
                resolver.delete(deleteUri, null, null)
                Log.d(TAG, "exportViaMediaStore: removed stale entry id=$id")
            }
        }

        val values = ContentValues().apply {
            put(android.provider.MediaStore.Downloads.DISPLAY_NAME, CA_FILENAME)
            put(android.provider.MediaStore.Downloads.MIME_TYPE, CA_MIME)
            put(android.provider.MediaStore.Downloads.IS_PENDING, 1)
        }
        val insertUri = resolver.insert(
            android.provider.MediaStore.Downloads.EXTERNAL_CONTENT_URI, values
        ) ?: run {
            Log.e(TAG, "exportViaMediaStore: MediaStore.insert returned null")
            return null
        }

        return try {
            resolver.openOutputStream(insertUri)?.use { out ->
                out.write(certPem.toByteArray(StandardCharsets.UTF_8))
                out.flush()
            } ?: run {
                Log.e(TAG, "exportViaMediaStore: openOutputStream returned null")
                resolver.delete(insertUri, null, null)
                return null
            }
            // Mark as complete — makes it visible in the file manager.
            values.clear()
            values.put(android.provider.MediaStore.Downloads.IS_PENDING, 0)
            resolver.update(insertUri, values, null, null)
            Log.i(TAG, "exportViaMediaStore: ✅ uri=$insertUri")
            ExportResult.MediaStoreEntry(insertUri)
        } catch (e: Exception) {
            Log.e(TAG, "exportViaMediaStore: write failed", e)
            resolver.delete(insertUri, null, null)
            null
        }
    }

    private fun exportViaFilesystem(certPem: String): ExportResult? {
        Log.d(TAG, "exportViaFilesystem: writing to public Downloads")
        return try {
            val downloadsDir = Environment.getExternalStoragePublicDirectory(
                Environment.DIRECTORY_DOWNLOADS
            )
            if (!downloadsDir.exists()) downloadsDir.mkdirs()
            val caFile = File(downloadsDir, CA_FILENAME)
            FileOutputStream(caFile).use { out ->
                out.write(certPem.toByteArray(StandardCharsets.UTF_8))
                out.flush()
            }
            val fileUri = FileProvider.getUriForFile(
                context, "${context.packageName}.fileprovider", caFile,
            )
            Log.i(TAG, "exportViaFilesystem: ✅ path=${caFile.absolutePath}")
            ExportResult.LegacyFile(caFile, fileUri)
        } catch (e: Exception) {
            Log.e(TAG, "exportViaFilesystem: failed", e)
            null
        }
    }

    // ── Convenience intents ───────────────────────────────────────────────────

    /**
     * Exports the CA to Downloads then returns an ACTION_VIEW intent pointing to it.
     * Use as fallback when [getKeyChainInstallIntent] is unavailable.
     */
    fun getFileInstallIntent(): Intent? {
        val result = exportCaToDownloads() ?: return null
        return Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(result.uri, CA_MIME)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
    }

    /**
     * Exports the CA to Downloads then returns an ACTION_SEND share intent.
     * Use when neither KeyChain nor ACTION_VIEW opens the installer.
     */
    fun getShareIntent(): Intent? {
        val result = exportCaToDownloads() ?: return null
        return Intent(Intent.ACTION_SEND).apply {
            type = CA_MIME
            putExtra(Intent.EXTRA_STREAM, result.uri)
            putExtra(Intent.EXTRA_SUBJECT, "PrivacyGuard CA Certificate")
            putExtra(Intent.EXTRA_TEXT,
                "Install this file as a CA certificate to enable HTTPS payload inspection.")
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
    }

    /** Opens Security settings — last resort for manual installation. */
    fun getSecuritySettingsIntent(): Intent =
        Intent(Settings.ACTION_SECURITY_SETTINGS).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }

    /** True if the CA keypair exists in the AndroidKeyStore. */
    fun isCaGenerated(): Boolean = caManager.getCaCert() != null
    /** ADDED: verifies whether Android currently trusts this CA certificate. */
    fun isCaTrustedByDevice(): Boolean {
        val ourCert = caManager.getCaCert() ?: return false
        return try {
            val keyStore = java.security.KeyStore.getInstance("AndroidCAStore").apply { load(null) }
            keyStore.aliases().asSequence().any { alias ->
                (keyStore.getCertificate(alias) as? java.security.cert.X509Certificate)
                    ?.encoded?.contentEquals(ourCert.encoded) == true
            }
        } catch (e: Exception) {
            Log.w(TAG, "isCaTrustedByDevice: unable to inspect AndroidCAStore: ${e.message}")
            false
        }
    }

    /** CA as Base64 DER for embedding in MDM/EMM configuration profiles. */
    fun getCaBase64ForMdm(): String = caManager.getCaCertBase64()
}
