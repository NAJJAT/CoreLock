package com.privacyguard.app.ui.mitm

import android.content.Context
import android.content.Intent
import android.os.Environment
import androidx.core.content.FileProvider
import java.io.File
import java.nio.charset.StandardCharsets
import java.util.Base64

/**
 * Helper for exporting an enterprise inspection notice and opening the
 * certificate-install settings flow.
 *
 * Business reason:
 * Enterprise administrators often need a guided path for managed-device trust
 * distribution. This helper provides a safe UX scaffold without implementing
 * third-party HTTPS interception.
 *
 * Thread safety:
 * Stateless. File writes are per-call and safe for background execution.
 */
object CaInstallHelper {
    private const val WARNING_TEXT = """
        PrivacyGuard Enterprise Inspection Notice

        This enterprise build is intended for company-managed devices only.
        Any certificate installed for traffic inspection must only be used where
        employees have provided written consent and local law permits monitoring.
        Never install enterprise inspection trust material on personal devices.
    """

    fun exportNoticeFile(context: Context): File {
        val downloads = context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS)
            ?: context.filesDir
        val file = File(downloads, "privacyguard-enterprise-inspection-notice.txt")
        file.writeText(WARNING_TEXT.trimIndent(), StandardCharsets.UTF_8)
        return file
    }

    fun buildShareIntent(context: Context): Intent {
        val file = exportNoticeFile(context)
        val uri = FileProvider.getUriForFile(
            context,
            "${context.packageName}.fileprovider",
            file,
        )
        return Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
    }

    fun openSecuritySettingsIntent(): Intent =
        Intent(android.provider.Settings.ACTION_SECURITY_SETTINGS)

    fun getNoticeBase64(): String =
        Base64.getEncoder().encodeToString(WARNING_TEXT.trimIndent().toByteArray(StandardCharsets.UTF_8))
}
