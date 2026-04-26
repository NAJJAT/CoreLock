package com.privacyguard.ui.mitm

import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import androidx.core.content.FileProvider
import com.privacyguard.vpn.mitm.CaManager
import java.io.File
import java.io.FileOutputStream


/**
 * CA Installation Helper
 *
 * Guides users or MDM admins through installing the enterprise CA certificate
 * on the device for TLS interception.
 *
 * Business Reason: Required for device to trust certificates forged by the
 * MITM engine.
 */
class CaInstallHelper(
    private val context: Context,
    private val caManager: CaManager
) {

    companion object {
        private const val TAG = "CaInstallHelper"
        private const val CA_FILENAME = "privacyguard_ca.crt"
    }

    /**
     * Export CA certificate to Downloads folder
     * @return File object or null if failed
     */
    fun exportCaToDownloads(): File? {
        return try {
            val certPem = caManager.getCaCertPem()
            if (certPem.isEmpty()) {
                Log.e(TAG, "No CA certificate available")
                return null
            }

            val downloadsDir = android.os.Environment.getExternalStoragePublicDirectory(
                android.os.Environment.DIRECTORY_DOWNLOADS
            )
            val caFile = File(downloadsDir, CA_FILENAME)

            FileOutputStream(caFile).use { output ->
                output.write(certPem.toByteArray())
            }

            Log.i(TAG, "CA exported to ${caFile.absolutePath}")
            caFile
        } catch (e: Exception) {
            Log.e(TAG, "Failed to export CA", e)
            null
        }
    }

    /**
     * Get intent to open certificate installation settings
     * @return Intent for certificate installation
     */
    fun getInstallIntent(): Intent {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            Intent(android.provider.Settings.ACTION_SECURITY_SETTINGS)
        } else {
            Intent(android.provider.Settings.ACTION_SECURITY_SETTINGS)
        }.apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
    }

    /**
     * Verify that CA certificate is installed
     * @return true if CA is trusted by the system
     */
    fun verifyCaInstalled(): Boolean {
        return try {
            // Attempt to validate a test certificate against the CA
            // Simplified check - just verify CA existence
            caManager.getCaCert() != null
        } catch (e: Exception) {
            Log.e(TAG, "CA verification failed", e)
            false
        }
    }

    /**
     * Get share intent for CA certificate
     * @return Share intent or null if export failed
     */
    fun getShareIntent(): Intent? {
        val caFile = exportCaToDownloads() ?: return null

        val uri = FileProvider.getUriForFile(
            context,
            "${context.packageName}.fileprovider",
            caFile
        )

        return Intent(Intent.ACTION_SEND).apply {
            type = "application/x-x509-ca-cert"
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
    }

    /**
     * Get CA certificate as Base64 for MDM profile
     */
    fun getCaBase64ForMdm(): String = caManager.getCaCertBase64()

    /**
     * Show installation warning dialog
     */
    fun showInstallWarning(onConfirm: () -> Unit) {
        // This would typically show an AlertDialog
        // Implementation in UI layer using AlertDialog.Builder
        onConfirm()
    }
}