package com.privacyguard.app.data.db

import androidx.room.testing.MigrationTestHelper
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The database is opened with fallbackToDestructiveMigration(), so a migration
 * that does not match the exported schema silently wipes the connection log.
 * This runs each migration against the committed schema JSON in app/schemas.
 */
@RunWith(AndroidJUnit4::class)
class AppDatabaseMigrationTest {

    private val dbName = "migration-test.db"

    @get:Rule
    val helper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        AppDatabase::class.java,
    )

    @Test
    fun migrate4To5_keepsConnectionsAndAddsIndexes() {
        helper.createDatabase(dbName, 4).use { db ->
            for (i in 1..3) {
                db.execSQL(
                    """
                    INSERT INTO connections (id, appUid, appName, packageName, destinationIp,
                        destinationPort, isIPv6, protocol, bytesSent, bytesReceived, timestamp,
                        durationMs, wasBlocked, encryptionStatus, wasBackground)
                    VALUES (?, 10123, 'Example', 'com.example', '93.184.216.34',
                        443, 0, 'TCP', 100, 200, ?, 50, ?, 'TLS_1_3', 0)
                    """.trimIndent(),
                    arrayOf<Any>(i, 1_700_000_000_000L + i, if (i == 2) 1 else 0),
                )
            }
        }

        // Validates the migrated schema (columns and indexes) against 5.json.
        val db = helper.runMigrationsAndValidate(dbName, 5, true, AppDatabase.MIGRATION_4_5)

        db.query("SELECT COUNT(*), SUM(wasBlocked) FROM connections").use { c ->
            c.moveToFirst()
            assertEquals(3, c.getInt(0))
            assertEquals(1, c.getInt(1))
        }
        val indexes = mutableSetOf<String>()
        db.query("PRAGMA index_list(`connections`)").use { c ->
            while (c.moveToNext()) indexes += c.getString(c.getColumnIndexOrThrow("name"))
        }
        assertTrue(indexes.containsAll(setOf("index_connections_timestamp", "index_connections_appUid_timestamp")))
        db.close()
    }

    @Test
    fun migrate6To7_keepsBlocklistRowsAndAllowsSharedDomains() {
        helper.createDatabase(dbName, 6).use { db ->
            db.execSQL(
                "INSERT INTO blocklist (domain, source, category, lastUpdated, isEnabled) VALUES ('ads.example.com', 'EasyList', 'ads', 1, 0)"
            )
        }

        // Validates the rebuilt table and its indexes against 7.json.
        val db = helper.runMigrationsAndValidate(dbName, 7, true, AppDatabase.MIGRATION_6_7)

        db.query("SELECT source, isEnabled FROM blocklist WHERE domain = 'ads.example.com'").use { c ->
            assertTrue(c.moveToFirst())
            assertEquals("EasyList", c.getString(0))
            assertEquals(0, c.getInt(1))
        }
        // A second list may now hold the same domain without replacing the first row.
        db.execSQL(
            "INSERT INTO blocklist (domain, source, category, lastUpdated, isEnabled) VALUES ('ads.example.com', 'BuiltIn-ads', 'ads', 2, 1)"
        )
        db.query("SELECT COUNT(*) FROM blocklist WHERE domain = 'ads.example.com'").use { c ->
            c.moveToFirst(); assertEquals(2, c.getInt(0))
        }
        db.close()
    }

    @Test
    fun migrate7To8_addsTlsBaselineTablesAndKeepsAlerts() {
        helper.createDatabase(dbName, 7).use { db ->
            db.execSQL("INSERT INTO tls_alerts (timestamp, alertType, severity) VALUES (1, 'WEAK_CIPHER', 6)")
        }

        // Validates both new tables and the unique index against 8.json.
        val db = helper.runMigrationsAndValidate(dbName, 8, true, AppDatabase.MIGRATION_7_8)

        db.query("SELECT COUNT(*) FROM tls_alerts").use { c -> c.moveToFirst(); assertEquals(1, c.getInt(0)) }
        val insert = "INSERT OR IGNORE INTO tls_fingerprints (uid, packageName, ja4, ja3, firstSeen, lastSeen, seenCount, origin, versionCode) " +
            "VALUES (10123, 'com.example', 't13d1516h2_a_b', 'x', 1, 1, 1, 'BASELINE', 1)"
        db.execSQL(insert)
        db.execSQL(insert) // the unique index must swallow the duplicate
        db.query("SELECT COUNT(*) FROM tls_fingerprints").use { c -> c.moveToFirst(); assertEquals(1, c.getInt(0)) }
        db.close()
    }

    @Test
    fun migrate8To9_addsSensorEvents() {
        helper.createDatabase(dbName, 8).close()
        val db = helper.runMigrationsAndValidate(dbName, 9, true, AppDatabase.MIGRATION_8_9)
        db.execSQL("INSERT INTO sensor_events (sensor, startedAt, endedAt, screenOnAtStart) VALUES ('MIC', 1, NULL, 0)")
        db.query("SELECT COUNT(*) FROM sensor_events WHERE endedAt IS NULL").use { c ->
            c.moveToFirst(); assertEquals(1, c.getInt(0))
        }
        db.close()
    }

    @Test
    fun migrate9To10_keepsRowsAndLeavesNewStateUnknown() {
        helper.createDatabase(dbName, 9).apply {
            execSQL("INSERT INTO connections (appUid, appName, packageName, destinationIp, destinationPort, isIPv6, protocol, bytesSent, bytesReceived, timestamp, durationMs, wasBlocked, encryptionStatus, wasBackground) VALUES (10123, 'App', 'com.example', '1.2.3.4', 443, 0, 'TCP', 10, 20, 1000, 5, 0, 'TLS', 1)")
            execSQL("INSERT INTO dns_queries (timestamp, app_package, app_name, domain, was_blocked, phone_was_idle) VALUES (1000, 'com.example', 'App', 'example.com', 0, 1)")
            close()
        }
        val db = helper.runMigrationsAndValidate(dbName, 10, true, AppDatabase.MIGRATION_9_10)
        // Old rows predate the columns: unknown, never a guessed "foreground".
        db.query("SELECT COUNT(*) FROM connections WHERE foreground IS NULL AND screenOn IS NULL AND wasBackground = 1").use { c ->
            c.moveToFirst(); assertEquals(1, c.getInt(0))
        }
        db.query("SELECT COUNT(*) FROM dns_queries WHERE app_foreground IS NULL AND phone_was_idle = 1").use { c ->
            c.moveToFirst(); assertEquals(1, c.getInt(0))
        }
        db.close()
    }

    @Test
    fun migrate10To11_keepsRowsWithNoNetwork() {
        helper.createDatabase(dbName, 10).apply {
            execSQL("INSERT INTO connections (appUid, appName, packageName, destinationIp, destinationPort, isIPv6, protocol, bytesSent, bytesReceived, timestamp, durationMs, wasBlocked, encryptionStatus, wasBackground) VALUES (10123, 'App', 'com.example', '1.2.3.4', 80, 0, 'TCP', 10, 20, 1000, 5, 0, 'CLEARTEXT', 0)")
            execSQL("INSERT INTO dns_anomalies (timestamp, packageName, domain, anomalyType, description, severity) VALUES (1000, 'com.example', 'x.example', 'DGA_BEACON', '', 5)")
            close()
        }
        val db = helper.runMigrationsAndValidate(dbName, 11, true, AppDatabase.MIGRATION_10_11)
        // Rows from before the column existed belong to no network: trust never guesses.
        db.query("SELECT COUNT(*) FROM connections WHERE networkKey IS NULL").use { c ->
            c.moveToFirst(); assertEquals(1, c.getInt(0))
        }
        db.query("SELECT COUNT(*) FROM dns_anomalies WHERE networkKey IS NULL").use { c ->
            c.moveToFirst(); assertEquals(1, c.getInt(0))
        }
        // Port 80 was labelled cleartext without looking at the bytes: now unknown.
        db.query("SELECT encryptionStatus FROM connections").use { c ->
            c.moveToFirst(); assertEquals("UNKNOWN", c.getString(0))
        }
        db.close()
    }

    @Test
    fun migrate11To12_recreatesNetworkTrustWithNetworkSignals() {
        helper.createDatabase(dbName, 11).apply {
            execSQL("INSERT INTO network_trust (networkKey, networkLabel, trustScore, trustLevel, cleartextCount, dnsAnomalyCount, weakTlsCount, blockedCount, lastSeen) VALUES ('wi-fi network', 'Wi-Fi', 35, 'Reduced', 36, 0, 14, 40, 1000)")
            close()
        }
        val db = helper.runMigrationsAndValidate(dbName, 12, true, AppDatabase.MIGRATION_11_12)
        // Old scores came from app behaviour; the cache starts empty and is rebuilt.
        db.query("SELECT COUNT(*) FROM network_trust").use { c -> c.moveToFirst(); assertEquals(0, c.getInt(0)) }
        db.close()
    }

    @Test
    fun migrate12To13_addsNullableBlockSource() {
        helper.createDatabase(dbName, 12).apply {
            execSQL("INSERT INTO connections (id, appUid, appName, packageName, destinationIp, destinationPort, isIPv6, protocol, bytesSent, bytesReceived, timestamp, durationMs, wasBlocked, encryptionStatus, wasBackground) VALUES (1, 10123, 'Example', 'com.example', '93.184.216.34', 443, 0, 'TCP', 0, 0, 1000, 0, 1, 'TLS_1_3', 0)")
            close()
        }
        val db = helper.runMigrationsAndValidate(dbName, 13, true, AppDatabase.MIGRATION_12_13)
        db.query("SELECT wasBlocked, blockSource FROM connections WHERE id = 1").use { c ->
            assertTrue(c.moveToFirst())
            assertEquals(1, c.getInt(0))
            assertTrue(c.isNull(1))
        }
        db.close()
    }
}
