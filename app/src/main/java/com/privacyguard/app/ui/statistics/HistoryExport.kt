package com.privacyguard.app.ui.statistics

import android.content.Context
import android.content.Intent
import androidx.core.content.FileProvider
import com.privacyguard.app.data.db.AppDatabase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * The history CSV (last 7 days), for Settings › Data › Export history and Statistics.
 * Every field goes through [ConnectionsCsv], which escapes formula injection. The file is
 * written under `files/exports`, which the FileProvider shares and "Clear all data" deletes.
 */
object HistoryExport {

    const val DAYS = 7

    suspend fun writeCsv(context: Context, now: Long = System.currentTimeMillis()): File = withContext(Dispatchers.IO) {
        val since = now - DAYS * 24L * 60L * 60L * 1000L
        val connections = AppDatabase.getInstance(context).connectionDao().allSince(since)
        val stamp = java.text.SimpleDateFormat("yyyyMMdd_HHmmss", java.util.Locale.US).format(java.util.Date(now))
        val dir = File(context.filesDir, "exports/csv").apply { mkdirs() }
        File(dir, "connections_$stamp.csv").also { file ->
            file.bufferedWriter().use { w ->
                w.write(ConnectionsCsv.HEADER)
                connections.forEach { c -> w.write(ConnectionsCsv.row(c)) }
            }
        }
    }

    /** A chooser to save or send [file]; read access only, to the app the user picks. */
    fun shareIntent(context: Context, file: File, chooserTitle: String): Intent {
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "text/csv"
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        return Intent.createChooser(send, chooserTitle)
    }
}
