package com.privacyguard.app.data.db

import androidx.room.Room
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

/** A list refresh replaces its rows in one transaction (BlocklistDao.replaceSource). */
@RunWith(RobolectricTestRunner::class)
class BlocklistReplaceSourceTest {

    private lateinit var db: AppDatabase
    private lateinit var dao: BlocklistDao

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(RuntimeEnvironment.getApplication(), AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        dao = db.blocklistDao()
    }

    @After
    fun tearDown() = db.close()

    private fun row(domain: String, source: String = "Hagezi-Pro", category: String = "ads") =
        BlocklistEntity(domain = domain, source = source, category = category)

    private fun domainsOf(source: String) = runBlocking { dao.getEntriesBySource(source).map { it.domain }.toSet() }

    @Test
    fun replaces_only_that_source() = runBlocking {
        dao.insertAll(listOf(row("old.example"), row("kept.example", source = "OISD")))
        dao.replaceSource("Hagezi-Pro", listOf(row("new1.example"), row("new2.example")), emptyList(), emptyList())
        assertEquals(setOf("new1.example", "new2.example"), domainsOf("Hagezi-Pro"))
        assertEquals(setOf("kept.example"), domainsOf("OISD"))
    }

    @Test
    fun a_failure_part_way_keeps_the_old_rows() = runBlocking {
        dao.insertAll(listOf(row("old1.example"), row("old2.example")))
        // Fails while the new rows are being inserted, after the old ones were deleted.
        val failing = object : AbstractList<BlocklistEntity>() {
            override val size = 3
            override fun get(index: Int): BlocklistEntity =
                if (index < 2) row("new$index.example") else throw IllegalStateException("download cut off")
        }
        val error = runCatching { dao.replaceSource("Hagezi-Pro", failing, emptyList(), emptyList()) }.exceptionOrNull()
        assertTrue("the failure reaches the caller", error is IllegalStateException)
        assertEquals("rolled back", setOf("old1.example", "old2.example"), domainsOf("Hagezi-Pro"))
    }

    @Test
    fun new_rows_of_a_switched_off_source_stay_off() = runBlocking {
        dao.replaceSource("Hagezi-Pro", listOf(row("ads.example")), disabledSources = listOf("Hagezi-Pro"), disabledCategories = emptyList())
        assertFalse(dao.isBlocked("ads.example"))
        assertEquals(1, dao.count())
    }
}
