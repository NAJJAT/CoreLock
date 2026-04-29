package com.privacyguard.app.ui.mitm

import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
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
 * Strategy:
 *  - Primary path (all APIs): KeyChain.createInstallIntent() — no file write, opens
 *    the system credential installer directly with the cert pre-loaded.
 *  - Secondary path (file export): writes to Downloads for manual install via Settings.
 *      API ≤ 28  →  direct FileOutputStream + FileProvider URI
 *      API 29+   →  MediaStore insertion (scoped storage, no extra permission needed)
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

    // ── Sealed result carrying the export URI ─────────────────────────────────

    sealed class ExportResult {
        abstract val uri: Uri

        /** API ≤ 28: physical file written to Downloads, served through FileProvider. */
        data class LegacyFile(val file: File, override val uri: Uri) : ExportResult()

        /** API 29+: entry created in the MediaStore Downloads collection. */
        data class MediaStore(override val uri: Uri) : ExportResult()
    }

    // ── Primary install path ──────────────────────────────────────────────────

    /**
     * Returns an intent that opens the system credential installer with the CA
     * certificate pre-loaded. This is the cleanest path — no file write required.
     * The user only needs to confirm the install and give the cert a name.
     *
     * Returns null if [CaManager.initialize] has not been awaited yet.
     *
     * IMPORTANT: always call and await [CaManager.initialize] before this method.
     * The common failure mode is calling this from a Composable whose [CaManager]
     * instance was freshly constructed inside `remember { }` — that instance is
     * uninitialised and [CaManager.getCaCert] returns null.
     */
    fun getKeyChainInstallIntent(): Intent? {
        val cert = caManager.getCaCert()
        if (cert == null) {
            Log.e(TAG, "getKeyChainInstallIntent: getCaCert() returned null — " +
                "was CaManager.initialize() awaited before calling this?")
            return null
        }

        val der = cert.encoded
        Log.d(TAG, "getKeyChainInstallIntent: cert=${der.size}B " +
            "subject=${cert.subjectDN} issuer=${cert.issuerDN}")

        if (der.isEmpty()) {
            Log.e(TAG, "getKeyChainInstallIntent: cert.encoded is EMPTY — " +
                "certificate object is corrupt")
            return null
        }

        return KeyChain.createInstallIntent().apply {
            putExtra(KeyChain.EXTRA_CERTIFICATE, der)
            putExtra(KeyChain.EXTRA_NAME, "PrivacyGuard CA")
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }.also {
            Log.i(TAG, "getKeyChainInstallIntent: intent created with ${der.size}B DER")
        }
    }

    // ── Secondary install path (export to Downloads) ──────────────────────────

    /**
     * Writes the CA PEM to the device's Downloads folder.
     *
     * Returns an [ExportResult] on success (carries the URI to open/share the file),
     * or null if the CA is missing or the write fails.
     *
     * API routing:
     *  - API 29+ → MediaStore (scoped storage, no permission needed)
     *  - API ≤ 28 → direct FileOutputStream (WRITE_EXTERNAL_STORAGE capped at 28)
     */
    fun exportCaToDownloads(): ExportResult? {
        val certPem = caManager.getCaCertPem()
        if (certPem.isEmpty()) {
            Log.e(TAG, "exportCaToDownloads: CA not available — initialize CaManager first")
            return null
        }
        Log.d(TAG, "exportCaToDownloads: certPem.length=${certPem.length}, API=${Build.VERSION.SDK_INT}")

        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            exportViaMediaStore(certPem)
        } else {
            exportViaFilesystem(certPem)
        }
    }

    // ── API 29+ ───────────────────────────────────────────────────────────────

    @RequiresApi(Build.VERSION_CODES.Q)
    private fun exportViaMediaStore(certPem: String): ExportResult? {
        Log.d(TAG, "exportViaMediaStore: inserting into MediaStore.Downloads")
        val resolver = context.contentResolver

        // Remove any previous copy so the file manager doesn't show duplicates.
        resolver.query(
            android.provider.MediaStore.Downloads.EXTERNAL_CONTENT_URI,
            arrayOf(android.provider.MediaStore.Downloads._ID),
            "${android.provider.MediaStore.Downloads.DISPLAY_NAME} = ?",
            arrayOf(CA_FILENAME),
            null,
        )?.use { cursor ->
            while (cursor.moveToNext()) {
                val id = cursor.getLong(0)
                val deleteUri = Uri.withAppendedPath(
                    android.provider.MediaStore.Downloads.EXTERNAL_CONTENT_URI,
                    id.toString()
                )
                resolver.delete(deleteUri, null, null)
                Log.d(TAG, "exportViaMediaStore: deleted stale entry id=$id")
            }
        }

        val values = ContentValues().apply {
            put(android.provider.MediaStore.Downloads.DISPLAY_NAME, CA_FILENAME)
            put(android.provider.MediaStore.Downloads.MIME_TYPE, CA_MIME)
            put(android.provider.MediaStore.Downloads.IS_PENDING, 1)
        }

        val insertUri = resolver.insert(
            android.provider.MediaStore.Downloads.EXTERNAL_CONTENT_URI, values
        )
        if (insertUri == null) {
            Log.e(TAG, "exportViaMediaStore: MediaStore.insert returned null")
            return null
        }

        return try {
            resolver.openOutputStream(insertUri)?.use { out ->
                out.write(certPem.toByteArray(StandardCharsets.UTF_8))
                out.flush()
                Log.d(TAG, "exportViaMediaStore: wrote ${certPem.length} bytes")
            } ?: run {
                Log.e(TAG, "exportViaMediaStore: openOutputStream returned null")
                resolver.delete(insertUri, null, null)
                return null
            }

            // Mark as complete — makes it visible to other apps / file manager.
            values.clear()
            values.put(android.provider.MediaStore.Downloads.IS_PENDING, 0)
            resolver.update(insertUri, values, null, null)

            Log.i(TAG, "exportViaMediaStore: ✅ saved — uri=$insertUri")
            ExportResult.MediaStore(insertUri)
        } catch (e: Exception) {
            Log.e(TAG, "exportViaMediaStore: write failed", e)
            resolver.delete(insertUri, null, null)
            null
        }
    }

    // ── API ≤ 28 ──────────────────────────────────────────────────────────────

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
                context,
                "${context.packageName}.fileprovider",
                caFile,
            )
            Log.i(TAG, "exportViaFilesystem: ✅ saved — path=${caFile.absolutePath}")
            ExportResult.LegacyFile(caFile, fileUri)
        } catch (e: Exception) {
            Log.e(TAG, "exportViaFilesystem: failed", e)
            null
        }
    }

    // ── Convenience intents ───────────────────────────────────────────────────

    /**
     * ACTION_VIEW intent that opens the system certificate installer for the
     * exported file. Use as a fallback when [getKeyChainInstallIntent] is unavailable.
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
     * ACTION_SEND share intent — useful if neither install intent works on the device.
     * The user can open the cert from any file manager app.
     */
    fun getShareIntent(): Intent? {
        val result = exportCaToDownloads() ?: return null
        return Intent(Intent.ACTION_SEND).apply {
            type = CA_MIME
            putExtra(Intent.EXTRA_STREAM, result.uri)
            putExtra(Intent.EXTRA_SUBJECT, "PrivacyGuard CA Certificate")
            putExtra(Intent.EXTRA_TEXT,
                "Open this file and follow the prompts to install the " +
                "PrivacyGuard CA as a trusted certificate authority.")
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
    }

    /**
     * Opens the Security & privacy settings screen as a last resort.
     * The user can navigate to "Install a certificate" manually.
     */
    fun getSecuritySettingsIntent(): Intent =
        Intent(Settings.ACTION_SECURITY_SETTINGS).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }

    /** True if the CA key pair exists in the AndroidKeyStore. */
    fun isCaGenerated(): Boolean = caManager.getCaCert() != null

    /**
     * True if the CA certificate is installed in the device's trusted CA store
     * (i.e. Android will accept TLS certs signed by our CA).
     *
     * This is what controls whether HTTPS interception actually works — the CA
     * must be in the device trust store, not just in the AndroidKeyStore.
     */
    fun isCaTrustedByDevice(): Boolean {
        val ourCert = caManager.getCaCert() ?: return false
        return try {
            val ks = java.security.KeyStore.getInstance("AndroidCAStore").apply { load(null) }
            ks.aliases().asSequence().any { alias ->
                (ks.getCertificate(alias) as? java.security.cert.X509Certificate)
                    ?.encoded?.contentEquals(ourCert.encoded) == true
            }
        } catch (_: Exception) { false }
    }

    /** CA as Base64 DER for embedding in MDM/EMM configuration profiles. */
    fun getCaBase64ForMdm(): String = caManager.getCaCertBase64()
}
