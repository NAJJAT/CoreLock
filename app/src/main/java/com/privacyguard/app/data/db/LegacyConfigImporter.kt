package com.privacyguard.app.data.db

import android.content.ContentValues
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.util.Log
import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * Moves configuration out of privacyguard.db (schema 5 and earlier) into config.db.
 *
 * AppDatabase's 5→6 migration leaves the old `rules` table in place; its existence
 * is the "not done yet" marker. The copy runs in one config.db transaction and is
 * checked row for row before the old table is dropped, so a crash at any point
 * leaves either the old table (redo next open; INSERT OR REPLACE makes that
 * harmless) or a finished copy.
 */
internal object LegacyConfigImporter {
    private const val TAG = "LegacyConfigImporter"

    fun import(history: SupportSQLiteDatabase, config: SupportSQLiteDatabase) {
        if (!tableExists(history, "rules")) return
        try {
            config.beginTransaction()
            try {
                val expected = count(history, "SELECT COUNT(*) FROM rules")
                var copied = 0
                history.query("SELECT * FROM rules").use { c ->
                    while (c.moveToNext()) {
                        if (config.insert("rules", SQLiteDatabase.CONFLICT_REPLACE, c.toContentValues()) != -1L) copied++
                    }
                }
                check(copied == expected) { "copied $copied of $expected rules" }
                copyBlocklistToggles(history, config)
                config.setTransactionSuccessful()
            } finally {
                config.endTransaction()
            }
            history.execSQL("DROP TABLE IF EXISTS rules")
            Log.i(TAG, "Moved rules to config.db")
        } catch (e: Exception) {
            // The old table is kept, so the next open retries.
            Log.e(TAG, "Moving rules to config.db failed; will retry", e)
        }
    }

    /**
     * Before the split, a switched-off source or category was only recorded as
     * isEnabled = 0 on its cached rows. A category counts as switched off only if its
     * rows are off in sources that are still on, so a disabled source does not also
     * disable every category it contributes to.
     */
    private fun copyBlocklistToggles(history: SupportSQLiteDatabase, config: SupportSQLiteDatabase) {
        if (!tableExists(history, "blocklist")) return
        val sources = strings(history, "SELECT source FROM blocklist GROUP BY source HAVING SUM(isEnabled) = 0")
        val placeholders = sources.joinToString(",") { "?" }
        val categories = strings(
            history,
            "SELECT category FROM blocklist WHERE source NOT IN ($placeholders) " +
                "GROUP BY category HAVING SUM(isEnabled) = 0",
            sources.toTypedArray(),
        )
        sources.forEach { insertToggle(config, BlocklistToggleEntity.SCOPE_SOURCE, it) }
        categories.forEach { insertToggle(config, BlocklistToggleEntity.SCOPE_CATEGORY, it) }
    }

    private fun insertToggle(config: SupportSQLiteDatabase, scope: String, name: String) {
        val values = ContentValues().apply {
            put("scope", scope)
            put("name", name)
            put("enabled", 0)
        }
        config.insert("blocklist_toggles", SQLiteDatabase.CONFLICT_REPLACE, values)
    }

    private fun tableExists(db: SupportSQLiteDatabase, table: String): Boolean =
        count(db, "SELECT COUNT(*) FROM sqlite_master WHERE type = 'table' AND name = ?", arrayOf(table)) > 0

    private fun count(db: SupportSQLiteDatabase, sql: String, args: Array<Any?> = emptyArray()): Int =
        db.query(sql, args).use { it.moveToFirst(); it.getInt(0) }

    private fun strings(db: SupportSQLiteDatabase, sql: String, args: Array<Any?> = emptyArray()): List<String> =
        db.query(sql, args).use { c -> buildList { while (c.moveToNext()) add(c.getString(0)) } }

    private fun Cursor.toContentValues() = ContentValues().also { values ->
        for (i in 0 until columnCount) {
            val name = getColumnName(i)
            when (getType(i)) {
                Cursor.FIELD_TYPE_NULL -> values.putNull(name)
                Cursor.FIELD_TYPE_INTEGER -> values.put(name, getLong(i))
                Cursor.FIELD_TYPE_FLOAT -> values.put(name, getDouble(i))
                Cursor.FIELD_TYPE_BLOB -> values.put(name, getBlob(i))
                else -> values.put(name, getString(i))
            }
        }
    }
}
