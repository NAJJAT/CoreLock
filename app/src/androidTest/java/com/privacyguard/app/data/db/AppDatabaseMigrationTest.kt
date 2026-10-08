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
    fun migrate7To8_keepsRowsAndMarksTimingsUnmeasured() {
        helper.createDatabase(dbName, 7).use { db ->
            db.execSQL(
                "INSERT INTO connections (appUid, appName, packageName, destinationIp, destinationPort, isIPv6, protocol, " +
                    "bytesSent, bytesReceived, timestamp, durationMs, wasBlocked, encryptionStatus, wasBackground) " +
                    "VALUES (10123, 'WhatsApp', 'com.whatsapp', '157.240.1.1', 443, 0, 'TCP', 10, 20, 1, 5, 0, 'TLS', 0)"
            )
            db.execSQL(
                "INSERT INTO dns_queries (timestamp, app_package, app_name, domain, was_blocked, phone_was_idle) " +
                    "VALUES (1, 'com.whatsapp', 'WhatsApp', 'g.whatsapp.net', 0, 0)"
            )
        }

        // Validates the new columns (and their defaults) against 8.json.
        val db = helper.runMigrationsAndValidate(dbName, 8, true, AppDatabase.MIGRATION_7_8)

        db.query("SELECT connectMs, bytesReceived FROM connections").use { c ->
            assertTrue(c.moveToFirst())
            assertEquals(0L, c.getLong(0))
            assertEquals(20L, c.getLong(1))
        }
        db.query("SELECT response_ms, answer_ip FROM dns_queries").use { c ->
            assertTrue(c.moveToFirst())
            assertEquals(0L, c.getLong(0))
            assertTrue(c.isNull(1))
        }
        db.close()
    }
}
