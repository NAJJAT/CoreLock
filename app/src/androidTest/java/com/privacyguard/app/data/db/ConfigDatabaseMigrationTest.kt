package com.privacyguard.app.data.db

import androidx.room.testing.MigrationTestHelper
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** config.db holds the user's rules: an upgrade must keep every one of them. */
@RunWith(AndroidJUnit4::class)
class ConfigDatabaseMigrationTest {

    private val name = "config-migration.db"

    @get:Rule
    val helper = MigrationTestHelper(InstrumentationRegistry.getInstrumentation(), ConfigDatabase::class.java)

    @Test
    fun migrate1To2_keepsRulesAndNoneOverridesSecurity() {
        helper.createDatabase(name, 1).use { db ->
            for (i in 1..3) {
                db.execSQL(
                    """
                    INSERT INTO rules (id, type, value, matchDomain, action, enabled, priority,
                        description, createdAt, lastModified, hitCount, lastHit)
                    VALUES (?, 'USER', '', ?, 'ALLOW', 1, 100, '', 1, 2, 0, NULL)
                    """.trimIndent(),
                    arrayOf<Any>("rule-$i", "site$i.example"),
                )
            }
        }

        val db = helper.runMigrationsAndValidate(name, 2, true, ConfigDatabase.MIGRATION_1_2)

        db.query("SELECT COUNT(*), SUM(overridesSecurity) FROM rules").use {
            it.moveToFirst()
            assertEquals(3, it.getInt(0))
            assertEquals(0, it.getInt(1))
        }
        db.close()
    }
}
