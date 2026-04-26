package com.privacyguard.app.ui.mitm

import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Environment
import android.provider.Settings
import android.util.Log
import androidx.annotation.RequiresApi
import androidx.core.content.FileProvider
import com.privacyguard.vpn.mitm.CaManager
import java.io.File
import java.io.FileOutputStream
import java.nio.charset.StandardCharsets
import java.util.Base64

class CaInstallHelper(
    private val context: Context,
    private val caManager: CaManager
) {

    companion object {
        private const val TAG = "CaInstallHelper"
        private const val CA_FILENAME = "privacyguard_ca.crt"
        private const val NOTICE_FILENAME = "privacyguard-enterprise-inspection-notice.txt"
        private const val CERTIFICATE_INSTALLER_ACTION = "android.settings.CERTIFICATE_INSTALLER"

        const val WARNING_TEXT = """
PrivacyGuard Enterprise Inspection Notice

This feature intercepts and logs all HTTPS traffic including usernames, 
passwords, messages, and personal data.

This enterprise build is intended for company-managed devices only.
Any certificate installed for traffic inspection must only be used where
employees have provided written consent and local law permits monitoring.
Never install enterprise inspection trust material on personal devices.

By installing this certificate, you acknowledge that:
1. All HTTPS traffic will be decrypted and inspected
2. Payloads may be stored locally and/or shipped to a SIEM system
3. You have obtained necessary legal consent for monitoring

If you do not agree, do NOT install this certificate.
        """
    }

    fun exportCaToDownloads(): File? {
        return try {
            val certPem = caManager.getCaCertPem()
            if (certPem.isEmpty()) {
                Log.e(TAG, "No CA certificate available")
                return null
            }

            val downloadsDir = Environment.getExternalStoragePublicDirectory(
                Environment.DIRECTORY_DOWNLOADS
            )
            if (!downloadsDir.exists()) {
                downloadsDir.mkdirs()
            }

            val caFile = File(downloadsDir, CA_FILENAME)

            FileOutputStream(caFile).use { output ->
                output.write(certPem.toByteArray(StandardCharsets.UTF_8))
            }

            Log.i(TAG, "CA exported to ${caFile.absolutePath}")
            caFile
        } catch (e: Exception) {
            Log.e(TAG, "Failed to export CA", e)
            null
        }
    }

    fun exportNoticeFile(): File {
        val downloadsDir = Environment.getExternalStoragePublicDirectory(
            Environment.DIRECTORY_DOWNLOADS
        )
        if (!downloadsDir.exists()) {
            downloadsDir.mkdirs()
        }
        val file = File(downloadsDir, NOTICE_FILENAME)
        file.writeText(WARNING_TEXT.trimIndent(), StandardCharsets.UTF_8)
        return file
    }

    fun getInstallIntent(): Intent {
        return Intent(Settings.ACTION_SECURITY_SETTINGS).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
    }

    fun getCertificateInstallerIntent(): Intent? {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            Intent(CERTIFICATE_INSTALLER_ACTION).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
        } else {
            null
        }
    }

    fun verifyCaInstalled(): Boolean {
        return try {
            caManager.getCaCert() != null
        } catch (e: Exception) {
            Log.e(TAG, "CA verification failed", e)
            false
        }
    }

    fun getCaShareIntent(): Intent? {
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

    fun getNoticeShareIntent(): Intent {
        val file = exportNoticeFile()
        val uri = FileProvider.getUriForFile(
            context,
            "${context.packageName}.fileprovider",
            file
        )
        return Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
    }

    @RequiresApi(Build.VERSION_CODES.O)
    fun getCaBase64ForMdm(): String {
        return try {
            val cert = caManager.getCaCert()
            if (cert != null) {
                Base64.getEncoder().encodeToString(cert.encoded)
            } else {
                ""
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to encode CA certificate", e)
            ""
        }
    }

    @RequiresApi(Build.VERSION_CODES.O)
    fun getNoticeBase64(): String = Base64.getEncoder().encodeToString(
        WARNING_TEXT.trimIndent().toByteArray(StandardCharsets.UTF_8)
    )

    fun showInstallWarning(onConfirm: () -> Unit) {
        onConfirm()
    }
}