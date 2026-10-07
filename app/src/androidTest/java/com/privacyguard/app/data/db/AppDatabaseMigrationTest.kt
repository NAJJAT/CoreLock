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
}
