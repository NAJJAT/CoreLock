package com.privacyguard.app.data.repository

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * "Clear all collected data" must cover every table. A new table fails this test
 * until someone decides whether it is history (erased) or not (kept).
 */
class LocalDataEraserTest {

    @Test
    fun every_table_is_either_erased_or_deliberately_kept() {
        val dir = File("schemas/com.privacyguard.app.data.db.AppDatabase")
        val latest = dir.listFiles { f -> f.extension == "json" }!!.maxBy { it.nameWithoutExtension.toInt() }
        val entities = JSONObject(latest.readText()).getJSONObject("database").getJSONArray("entities")
        val tables = (0 until entities.length()).map { entities.getJSONObject(it).getString("tableName") }.toSet()

        val decided = LocalDataEraser.HISTORY_TABLES + LocalDataEraser.KEPT_TABLES
        assertEquals("tables listed twice", decided.size, decided.toSet().size)
        assertEquals("schema ${latest.name}", tables, decided.toSet())
    }

    @Test
    fun blocklists_are_kept_so_blocking_continues() {
        assertTrue("blocklist" !in LocalDataEraser.HISTORY_TABLES)
    }
}
