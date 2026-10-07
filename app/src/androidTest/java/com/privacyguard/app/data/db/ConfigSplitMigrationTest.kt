package com.privacyguard.app.data.db

import androidx.room.testing.MigrationTestHelper
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The 5→6 upgrade moves rules (and blocklist switches) from privacyguard.db into
 * config.db. Both sides are built from the committed schema JSON.
 */
@RunWith(AndroidJUnit4::class)
class ConfigSplitMigrationTest {

    private val historyName = "split-history.db"
    private val configName = "split-config.db"

    @get:Rule
    val history = MigrationTestHelper(InstrumentationRegistry.getInstrumentation(), AppDatabase::class.java)

    @get:Rule
    val config = MigrationTestHelper(InstrumentationRegistry.getInstrumentation(), ConfigDatabase::class.java)

    private fun SupportSQLiteDatabase.count(sql: String): Int =
        query(sql).use { it.moveToFirst(); it.getInt(0) }

    private fun SupportSQLiteDatabase.tableExists(name: String): Boolean =
        count("SELECT COUNT(*) FROM sqlite_master WHERE type = 'table' AND name = '$name'") > 0

    // validateDroppedTables = false: the legacy rules table is meant to survive the
    // migration until LegacyConfigImporter has copied it.
    private fun migrateTo6() =
        history.runMigrationsAndValidate(historyName, 6, false, AppDatabase.MIGRATION_5_6)

    private fun createVersion5History() {
        history.createDatabase(historyName, 5).use { db ->
            for (i in 1..3) {
                db.execSQL(
                    """
                    INSERT INTO rules (id, type, value, matchDomain, action, enabled, priority,
                        description, createdAt, lastModified, hitCount, lastHit)
                    VALUES (?, 'USER', '', ?, 'DENY', 1, 100, '', 1, 2, ?, NULL)
                    """.trimIndent(),
                    arrayOf<Any>("rule-$i", "tracker$i.example", i * 10),
                )
            }
            // EasyList switched off entirely; within the remaining sources only the
            // "trackers" category is off. Hagezi's "ads" rows are on, so "ads" stays on
            // even though EasyList's "ads" rows are off.
            val rows = listOf(
                Triple("EasyList", "ads", 0), Triple("EasyList", "trackers", 0),
                Triple("Hagezi", "ads", 1), Triple("Hagezi", "trackers", 0),
            )
            rows.forEachIndexed { i, (source, category, enabled) ->
                db.execSQL(
                    "INSERT INTO blocklist (domain, source, category, lastUpdated, isEnabled) VALUES (?, ?, ?, 0, ?)",
                    arrayOf<Any>("d$i.example", source, category, enabled),
                )
            }
        }
    }

    @Test
    fun migrate5To6_keepsLegacyRulesUntilImported() {
        createVersion5History()

        val db = migrateTo6()

        assertEquals(3, db.count("SELECT COUNT(*) FROM rules"))
        db.close()
    }

    @Test
    fun importer_movesRulesAndSwitchesThenDropsLegacyTable() {
        createVersion5History()
        val historyDb = migrateTo6()
        val configDb = config.createDatabase(configName, 1)

        LegacyConfigImporter.import(historyDb, configDb)

        assertFalse(historyDb.tableExists("rules"))
        assertEquals(3, configDb.count("SELECT COUNT(*) FROM rules"))
        assertEquals(60, configDb.count("SELECT SUM(hitCount) FROM rules"))
        assertEquals(
            listOf("CATEGORY:trackers", "SOURCE:EasyList"),
            configDb.query("SELECT scope || ':' || name FROM blocklist_toggles WHERE enabled = 0 ORDER BY 1")
                .use { c -> buildList { while (c.moveToNext()) add(c.getString(0)) } },
        )

        // A second open finds no legacy table and leaves config alone.
        configDb.execSQL("DELETE FROM rules WHERE id = 'rule-1'")
        LegacyConfigImporter.import(historyDb, configDb)
        assertEquals(2, configDb.count("SELECT COUNT(*) FROM rules"))

        historyDb.close()
        configDb.close()
    }

    @Test
    fun importer_redoesInterruptedCopy() {
        createVersion5History()
        val historyDb = migrateTo6()
        val configDb = config.createDatabase(configName, 1)
        // A run that committed part of the copy but was killed before dropping the
        // legacy table: the redo must neither fail nor duplicate.
        configDb.execSQL(
            """
            INSERT INTO rules (id, type, value, action, enabled, priority, description,
                createdAt, lastModified, hitCount)
            VALUES ('rule-1', 'USER', '', 'DENY', 1, 100, '', 1, 2, 10)
            """.trimIndent()
        )

        LegacyConfigImporter.import(historyDb, configDb)

        assertEquals(3, configDb.count("SELECT COUNT(*) FROM rules"))
        assertFalse(historyDb.tableExists("rules"))
        assertTrue(configDb.tableExists("blocklist_toggles"))
        historyDb.close()
        configDb.close()
    }
}
