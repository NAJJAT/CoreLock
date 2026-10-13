package com.privacyguard.app.data.repository

import android.content.Context
import com.privacyguard.app.data.db.AppDatabase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList

/**
 * "Clear all collected data" in Settings: everything PrivacyGuard has recorded about
 * this phone's traffic, on disk and in the running VPN.
 *
 * Kept: settings, your rules, and the downloaded blocklists (they are not history,
 * and removing them would stop blocking until the next download).
 */
object LocalDataEraser {

    /** Tables of observed traffic. A test fails when a table is added to the schema without being placed here or in [KEPT_TABLES]. */
    val HISTORY_TABLES = listOf(
        "connections", "connection_profiles", "dns_queries", "dns_anomalies", "network_trust",
        "app_stats", "payload_logs", "tls_alerts", "tls_app_profiles", "tls_fingerprints", "sensor_events",
    )
    val KEPT_TABLES = listOf("blocklist")

    /** The running VPN registers here to drop what it holds in memory. */
    fun interface Listener { fun onDataCleared() }
    private val listeners = CopyOnWriteArrayList<Listener>()
    fun register(l: Listener) { listeners += l }
    fun unregister(l: Listener) { listeners -= l }

    suspend fun eraseAll(context: Context) = eraseAll(context, AppDatabase.getInstance(context), context.filesDir)

    /** [db] and [filesDir] are parameters so a test can erase its own copies, not the user's. */
    internal suspend fun eraseAll(context: Context, db: AppDatabase, filesDir: File) = withContext(Dispatchers.IO) {
        com.privacyguard.app.core.pcap.PcapWriter.stopCapture()
        deleteTables(db)
        // The VPN saves its in-memory profiles every few minutes; one save may already
        // have been under way. Reset memory, then delete again so nothing comes back.
        listeners.forEach { runCatching { it.onDataCleared() } }
        deleteTables(db)
        com.privacyguard.app.core.pcap.PcapWriter.forgetLastCapture()
        // PCAPs, reports and CSV exports: plaintext copies of history.
        File(filesDir, "exports").deleteRecursively()
        com.privacyguard.core.tls.SharedPrefsCtBaselineStore.clear(context)
        context.getSharedPreferences(com.privacyguard.app.core.behavior.BehaviorNotifier.PREFS, Context.MODE_PRIVATE).edit().clear().apply()
        // Deleted rows stay in free pages until the file is rewritten.
        runCatching { db.openHelper.writableDatabase.execSQL("VACUUM") }
        Unit
    }

    private fun deleteTables(db: AppDatabase) {
        db.runInTransaction {
            val sql = db.openHelper.writableDatabase
            HISTORY_TABLES.forEach { sql.execSQL("DELETE FROM `$it`") }
        }
    }
}
