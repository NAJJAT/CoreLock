package com.privacyguard.app.data.repository

import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.privacyguard.app.data.db.AppDatabase
import com.privacyguard.app.data.db.BlocklistEntity
import com.privacyguard.app.data.db.ConnectionEntity
import com.privacyguard.app.data.db.DnsAnomalyEntity
import com.privacyguard.app.data.db.DnsQueryEntity
import com.privacyguard.app.data.db.TlsAlertEntity
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * "Clear all collected data" on a real device, against an in-memory database and
 * a scratch folder, so the phone's own history is never touched.
 */
@RunWith(AndroidJUnit4::class)
class LocalDataEraserDeviceTest {

    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).build()
    private val scratch = File(context.cacheDir, "eraser-test").apply { mkdirs() }

    @After
    fun tearDown() {
        db.close()
        scratch.deleteRecursively()
    }

    @Test
    fun erases_history_and_exports_and_keeps_blocklists() = runBlocking {
        db.connectionDao().insert(ConnectionEntity(appUid = 10_001, packageName = "com.example", destinationIp = "1.2.3.4"))
        db.dnsQueryDao().insert(DnsQueryEntity(timestamp = 1, appPackage = "com.example", appName = "Example",
            domain = "ads.example", wasBlocked = true, phoneWasIdle = false))
        db.dnsAnomalyDao().insertRow(DnsAnomalyEntity(timestamp = 1, packageName = "com.example", domain = "x.example",
            anomalyType = "LONG_LABEL", description = "", severity = 5))
        db.tlsAlertDao().insertRow(TlsAlertEntity(timestamp = 1, alertType = "WEAK_CIPHER", severity = 6))
        db.blocklistDao().insert(BlocklistEntity(domain = "ads.example", source = "Test", category = "ads"))
        val export = File(scratch, "exports/pcap/capture.pcap").apply { parentFile!!.mkdirs(); writeText("x") }

        LocalDataEraser.eraseAll(context, db, scratch)

        val sql = db.openHelper.readableDatabase
        LocalDataEraser.HISTORY_TABLES.forEach { table ->
            sql.query("SELECT COUNT(*) FROM `$table`").use { it.moveToFirst(); assertEquals(table, 0, it.getInt(0)) }
        }
        sql.query("SELECT COUNT(*) FROM blocklist").use { it.moveToFirst(); assertEquals(1, it.getInt(0)) }
        assertFalse(export.exists())
        assertTrue(scratch.exists())
    }
}
