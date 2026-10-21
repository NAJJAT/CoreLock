package com.privacyguard.app.data.db

import android.content.Context
import androidx.room.Room
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import java.io.File

/**
 * The 12→13 migration on the JVM (AppDatabaseMigrationTest needs a device). A
 * version-12 database is built from the committed schema JSON, then opened by Room
 * with the migration: Room checks the result against the version-13 entities and
 * refuses to open on any difference, as MigrationTestHelper does.
 */
@RunWith(RobolectricTestRunner::class)
@org.robolectric.annotation.ConscryptMode(org.robolectric.annotation.ConscryptMode.Mode.OFF)
class AppDatabaseMigration12To13Test {

    private val context: Context = RuntimeEnvironment.getApplication()
    private val dbName = "migration-12-13.db"

    @After
    fun tearDown() {
        context.deleteDatabase(dbName)
    }

    @Test
    fun keeps_connections_and_adds_a_nullable_block_source() {
        createVersion12 { db ->
            db.execSQL(
                """
                INSERT INTO connections (id, appUid, appName, packageName, destinationIp, destinationPort,
                    isIPv6, protocol, bytesSent, bytesReceived, timestamp, durationMs, wasBlocked,
                    encryptionStatus, wasBackground)
                VALUES (7, 10123, 'Example', 'com.example', '93.184.216.34', 443, 0, 'TCP', 0, 0,
                    1700000000000, 0, 1, 'TLS_1_3', 0)
                """.trimIndent()
            )
        }

        val room = openWithRoom(AppDatabase.MIGRATION_12_13)
        try {
            val db = room.openHelper.writableDatabase   // runs and validates the migration
            db.query("SELECT appName, wasBlocked, blockSource FROM connections WHERE id = 7").use { c ->
                assertTrue("row kept", c.moveToFirst())
                assertEquals("Example", c.getString(0))
                assertEquals(1, c.getInt(1))
                assertTrue("old rows have no source", c.isNull(2))
            }
            db.execSQL("UPDATE connections SET blockSource = 'SNI' WHERE id = 7")
            val row = kotlinx.coroutines.runBlocking { room.connectionDao().allSince(0) }.single()
            assertEquals("SNI", row.blockSource)
        } finally {
            room.close()
        }
    }

    /** Shows the check above is real: a migration that forgets the column is refused. */
    @Test
    fun a_migration_without_the_column_is_refused() {
        createVersion12 { }
        val noOp = object : Migration(12, 13) {
            override fun migrate(db: SupportSQLiteDatabase) = Unit
        }
        val room = openWithRoom(noOp)
        try {
            room.openHelper.writableDatabase
            fail("Room accepted a schema without blockSource")
        } catch (e: IllegalStateException) {
            assertTrue(e.message.orEmpty(), e.message.orEmpty().contains("connections"))
        } finally {
            room.close()
        }
    }

    @Test
    fun new_rows_default_to_no_source() {
        assertNull(ConnectionEntity(appUid = 1).blockSource)
    }

    // ─────────────────────────────────────────────────────────────────────────

    private fun openWithRoom(migration: Migration): AppDatabase =
        Room.databaseBuilder(context, AppDatabase::class.java, dbName)
            .addMigrations(migration)
            .allowMainThreadQueries()
            .build()

    /** Creates [dbName] exactly as version 12 described it, with Room's identity hash. */
    private fun createVersion12(seed: (SupportSQLiteDatabase) -> Unit) {
        context.deleteDatabase(dbName)
        val schema = schemaJson(12)
        val config = SupportSQLiteOpenHelper.Configuration.builder(context)
            .name(dbName)
            .callback(object : SupportSQLiteOpenHelper.Callback(12) {
                override fun onCreate(db: SupportSQLiteDatabase) {
                    val entities = schema.getJSONArray("entities")
                    for (i in 0 until entities.length()) {
                        val e = entities.getJSONObject(i)
                        val table = e.getString("tableName")
                        db.execSQL(e.getString("createSql").replace("\${TABLE_NAME}", table))
                        val indices = e.optJSONArray("indices") ?: continue
                        for (j in 0 until indices.length()) {
                            db.execSQL(indices.getJSONObject(j).getString("createSql").replace("\${TABLE_NAME}", table))
                        }
                    }
                    val setup = schema.getJSONArray("setupQueries")
                    for (i in 0 until setup.length()) db.execSQL(setup.getString(i))
                }

                override fun onUpgrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit
            })
            .build()
        FrameworkSQLiteOpenHelperFactory().create(config).use { helper ->
            seed(helper.writableDatabase)
        }
    }

    private fun schemaJson(version: Int): JSONObject {
        val path = "schemas/com.privacyguard.app.data.db.AppDatabase/$version.json"
        val file = listOf(File(path), File("app/$path")).first { it.exists() }
        return JSONObject(file.readText()).getJSONObject("database")
    }
}
