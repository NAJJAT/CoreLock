package com.privacyguard.app.data.repository

import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.privacyguard.app.data.db.AppDatabase
import com.privacyguard.app.data.db.ConfigDatabase
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class BlocklistRepoTest {

    private lateinit var history: AppDatabase
    private lateinit var config: ConfigDatabase
    private lateinit var repo: BlocklistRepo

    @Before
    fun setUp() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        history = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).build()
        config = Room.inMemoryDatabaseBuilder(context, ConfigDatabase::class.java).build()
        repo = BlocklistRepo(history.blocklistDao(), config.blocklistToggleDao())
    }

    @After
    fun tearDown() {
        history.close()
        config.close()
    }

    @Test
    fun switchedOffSourceStaysOffAfterListUpdate() = runBlocking {
        repo.replaceSource("EasyList", "ads", listOf("a.example", "b.example"))
        repo.replaceSource("Hagezi", "ads", listOf("c.example"))

        repo.setSourceEnabled("EasyList", false)
        // A list update rewrites EasyList's rows; the user's choice must survive it.
        repo.replaceSource("EasyList", "ads", listOf("a.example", "b.example", "d.example"))

        assertEquals(listOf("c.example"), repo.allDomains())
    }

    @Test
    fun categoryAndMasterSwitches() = runBlocking {
        repo.replaceSource("EasyList", "ads", listOf("a.example"))
        repo.replaceSource("EasyPrivacy", "trackers", listOf("t.example"))

        repo.setCategoryEnabled("trackers", false)
        assertEquals(listOf("a.example"), repo.allDomains())

        repo.setAllEnabled(false)
        assertEquals(emptyList<String>(), repo.allDomains())

        repo.setAllEnabled(true)
        assertEquals(listOf("a.example", "t.example"), repo.allDomains())
    }
}
