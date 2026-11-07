package com.privacyguard.app.core.blocklist

import androidx.room.Room
import com.privacyguard.app.data.db.AppDatabase
import com.privacyguard.app.data.db.ConfigDatabase
import com.privacyguard.app.data.local.preferences.SettingsPreferences
import com.privacyguard.app.data.repository.BlocklistRepo
import com.privacyguard.core.filter.FilterEngine.BlockLevel
import com.privacyguard.vpn.firewall.DomainFilter
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.zip.GZIPOutputStream

/**
 * The start-up cache after the update from 1.0.0 (fix A) and for every level (fix B).
 *
 * A: 1.0.0 wrote `blocklist_<LEVEL>.txt.gz`; 1.2 read only `.v2.bin`, so the first start
 * after the update waited for the encrypted database (9 to 13 s on the emulator). The old
 * file is now read once, with the bounds and checks of a downloaded list, then deleted;
 * anything wrong means the database path, never a start with less than it would load.
 *
 * B: the cache existed only for the level last used, so a level changed while protection
 * was off waited for the database too. List changes now write the file of every level.
 */
@RunWith(RobolectricTestRunner::class)
class BlocklistStartupCacheTest {

    private val context = RuntimeEnvironment.getApplication()
    private val dir: File get() = context.noBackupFilesDir
    private fun legacy(level: String = "STANDARD") = File(dir, "blocklist_$level.txt.gz")
    private fun v2(level: String = "STANDARD") = File(dir, "blocklist_$level.v2.bin")

    private lateinit var db: AppDatabase
    private lateinit var config: ConfigDatabase
    private lateinit var repo: BlocklistRepo

    @Before
    fun setUp() {
        dir.listFiles()?.forEach { it.delete() }
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).allowMainThreadQueries().build()
        config = Room.inMemoryDatabaseBuilder(context, ConfigDatabase::class.java).allowMainThreadQueries().build()
        repo = BlocklistRepo(db.blocklistDao(), config.blocklistToggleDao())
    }

    @After
    fun tearDown() {
        db.close()
        config.close()
    }

    /** Exactly what 1.0.0's BlocklistCache.write produced (git show v1.0.0). */
    private fun writeLegacy(target: File, blocked: List<String>, security: List<String>, header: String = "privacyguard-blocklist-cache 1") {
        GZIPOutputStream(target.outputStream()).bufferedWriter().use { w ->
            w.write(header); w.newLine()
            blocked.forEach { w.write(it); w.newLine() }
            w.write("--security--"); w.newLine()
            security.forEach { w.write(it); w.newLine() }
        }
    }

    private fun filtersOf(snap: BlocklistCache.Snapshot): Pair<DomainFilter, DomainFilter> =
        (DomainFilter() to DomainFilter()).also { (b, s) -> snap.applyTo(b, s) }

    // ── A: the 1.0.0 file ────────────────────────────────────────────────────

    @Test
    fun the_first_start_after_the_update_uses_the_1_0_0_file_and_deletes_it() {
        writeLegacy(legacy(), listOf("ads.example", "*.wild.example"), listOf("bad.example"))

        val snap = BlocklistCache.read(context, "STANDARD")

        assertNotNull("the 1.0.0 file must seed the filters", snap)
        val (blocked, security) = filtersOf(snap!!)
        assertTrue(blocked.isBlocked("ads.example") && blocked.isBlocked("x.ads.example"))
        assertTrue(blocked.isBlocked("a.wild.example"))
        assertTrue(security.isBlocked("bad.example"))
        assertFalse(security.isBlocked("ads.example"))
        assertTrue(snap.fromLegacyFile)
        assertFalse("read once, then deleted", legacy().exists())
    }

    @Test
    fun the_1_0_0_file_gets_the_same_checks_as_a_downloaded_list() {
        // A public suffix, an IP literal, a single label, a space, a look-alike (Latin "g"
        // with Cyrillic "о"): each is skipped, as a downloaded list skips it.
        val refused = listOf("com", "1.2.3.4", "intranet", "bad name.example", "gооgle.example")
        writeLegacy(legacy(), listOf("ads.example") + refused, listOf("bad.example"))

        val (blocked, _) = filtersOf(BlocklistCache.read(context, "STANDARD")!!)

        assertTrue(blocked.isBlocked("ads.example"))
        assertFalse("a public suffix would block every .com site", blocked.isBlocked("shop.com"))
        assertFalse(blocked.isBlocked("1.2.3.4"))
        assertFalse(blocked.isBlocked("intranet"))
        assertEquals("only the valid name is loaded", 1L, blocked.size)
    }

    @Test
    fun the_1_0_0_file_loads_what_the_database_path_would_load_for_the_same_names() {
        val names = (1..2_000).map { "host$it.ads.example" } + listOf("*.cdn.example", "MiXeD.Example.", "xn--bcher-kva.example")
        val bad = (1..500).map { "m$it.bad.example" }
        writeLegacy(legacy(), names, bad)

        val snap = BlocklistCache.read(context, "STANDARD")!!

        val expectBlocked = DomainFilter().apply { rebuild(names.map { it.lowercase().trimEnd('.') }) }
        val expectSecurity = DomainFilter().apply { rebuild(bad) }
        assertArrayEquals(expectBlocked.fingerprints().first, snap.blockedNames)
        assertArrayEquals(expectBlocked.fingerprints().second, snap.blockedWildcards)
        assertArrayEquals(expectSecurity.fingerprints().first, snap.securityNames)
    }

    @Test
    fun checking_on_several_cores_loads_the_same_as_on_one() {
        // Several batches per section, the security mark in the middle of one, and a
        // wildcard and an invalid name in each batch.
        val names = (1..30_001).map { if (it % 5_000 == 0) "*.w$it.example" else if (it % 4_999 == 0) "com" else "h$it.ads.example" }
        val bad = (1..17_000).map { "m$it.bad.example" }
        writeLegacy(legacy(), names, bad)
        val copy = File(dir, "copy.txt.gz").also { legacy().copyTo(it, overwrite = true) }

        val one = BlocklistCache.readLegacy(legacy(), workers = 1)!!
        val four = BlocklistCache.readLegacy(copy, workers = 4)!!

        assertArrayEquals(one.blockedNames, four.blockedNames)
        assertArrayEquals(one.blockedWildcards, four.blockedWildcards)
        assertArrayEquals(one.securityNames, four.securityNames)
        assertEquals(6, four.blockedWildcards.size)
        assertEquals(17_000, four.securityNames.size)
        val expected = DomainFilter().apply { rebuild(names.filter { it != "com" }) }
        assertArrayEquals(expected.fingerprints().first, four.blockedNames)
    }

    @Test
    fun a_damaged_or_foreign_1_0_0_file_falls_back_to_the_database_and_is_deleted() {
        val good = { writeLegacy(legacy(), listOf("ads.example"), listOf("bad.example")) }
        val cases: Map<String, () -> Unit> = mapOf(
            "cut off" to { good(); legacy().writeBytes(legacy().readBytes().let { it.copyOf(it.size - 9) }) },
            "flipped byte" to { good(); legacy().writeBytes(legacy().readBytes().also { it[it.size / 2] = (it[it.size / 2].toInt() xor 0x40).toByte() }) },
            "not gzip" to { legacy().writeText("privacyguard-blocklist-cache 1\nads.example\n--security--\nbad.example\n") },
            "other header" to { writeLegacy(legacy(), listOf("ads.example"), listOf("bad.example"), header = "something else 1") },
            "no security mark" to { GZIPOutputStream(legacy().outputStream()).bufferedWriter().use { it.write("privacyguard-blocklist-cache 1\nads.example\n") } },
            "two security marks" to { writeLegacy(legacy(), listOf("ads.example", "--security--"), listOf("bad.example")) },
            // An empty security list would start without malware protection: the database
            // (with the bundled copy at worst) is the better start.
            "no malware names" to { writeLegacy(legacy(), listOf("ads.example"), emptyList()) },
            "empty file" to { legacy().writeBytes(ByteArray(0)) },
        )
        for ((name, write) in cases) {
            write()
            assertNull(name, BlocklistCache.read(context, "STANDARD"))
            assertFalse("$name: deleted, so it is not parsed again at every start", legacy().exists())
        }
    }

    @Test
    fun the_1_0_0_file_is_bounded_like_a_download() {
        // Text beyond the cap (a gzip bomb compresses to a few kB): refused.
        GZIPOutputStream(legacy().outputStream()).use { out ->
            out.write("privacyguard-blocklist-cache 1\n".toByteArray())
            val zeros = ByteArray(1 shl 16) { 'a'.code.toByte() }
            repeat(40) { out.write(zeros) }
        }
        assertNull(BlocklistCache.readLegacy(legacy(), maxTextBytes = 1L shl 20, maxEntries = 1_000))

        // More entries than allowed in one section: refused.
        writeLegacy(legacy(), (1..1_001).map { "h$it.example" }, listOf("bad.example"))
        assertNull(BlocklistCache.readLegacy(legacy(), maxTextBytes = 1L shl 20, maxEntries = 1_000))

        // A very long line is skipped without being held whole; the rest still loads.
        writeLegacy(legacy(), listOf("a".repeat(5_000_000) + ".example", "ads.example"), listOf("bad.example"))
        val snap = BlocklistCache.readLegacy(legacy())
        assertNotNull(snap)
        assertEquals(1, snap!!.blockedNames.size)
    }

    @Test
    fun a_version_2_file_wins_and_the_old_file_is_removed() {
        val blocked = DomainFilter().apply { rebuild(listOf("new.example")) }
        val security = DomainFilter().apply { rebuild(listOf("bad.example")) }
        BlocklistCache.write(context, "STANDARD", blocked, security)
        writeLegacy(legacy(), listOf("old.example"), listOf("bad.example"))

        val (b, _) = filtersOf(BlocklistCache.read(context, "STANDARD")!!)

        assertTrue(b.isBlocked("new.example"))
        assertFalse(b.isBlocked("old.example"))
        assertFalse(legacy().exists())
    }

    // ── B: every level ───────────────────────────────────────────────────────

    @Test
    fun a_list_change_writes_the_start_up_file_of_every_level() = runBlocking {
        SettingsPreferences.getInstance(context).setProtectionLevel(BlockLevel.STANDARD.name)
        repo.replaceSource(BlocklistRepo.SOURCE_HAGEZI_TIF_MINI, BlocklistRepo.CAT_MALWARE, listOf("bad.example"))
        repo.replaceSource(BlocklistRepo.SOURCE_HAGEZI_PRO_MINI, BlocklistRepo.CAT_ADS, listOf("mini.ads.example"))
        repo.replaceSource(BlocklistRepo.SOURCE_HAGEZI_PRO, BlocklistRepo.CAT_ADS, listOf("mini.ads.example", "pro.ads.example"))

        BlocklistManager.writeStartupCache(context, repo)

        for (level in BlockLevel.values()) {
            assertTrue("${level.name} file missing", v2(level.name).isFile)
            val snap = BlocklistCache.read(context, level.name)!!
            // What the running VPN would load at that level, nothing more or less.
            val lists = repo.levelLists(level)
            assertArrayEquals(level.name, DomainFilter().apply { rebuild(lists.blocked) }.fingerprints().first, snap.blockedNames)
            assertArrayEquals(level.name, DomainFilter().apply { rebuild(lists.security) }.fingerprints().first, snap.securityNames)
            val (_, security) = filtersOf(snap)
            assertTrue(security.isBlocked("bad.example"))
        }
        val (strict, _) = filtersOf(BlocklistCache.read(context, "STRICT")!!)
        val (minimal, _) = filtersOf(BlocklistCache.read(context, "MINIMAL")!!)
        assertTrue(strict.isBlocked("pro.ads.example"))
        assertFalse(minimal.isBlocked("mini.ads.example"))
    }

    @Test
    fun writers_at_the_same_time_leave_one_complete_file() {
        // The list worker and the VPN service can write the same level at once.
        val a = DomainFilter().apply { rebuild((1..20_000).map { "a$it.example" }) }
        val b = DomainFilter().apply { rebuild((1..30_000).map { "b$it.example" }) }
        val sec = DomainFilter().apply { rebuild(listOf("bad.example")) }
        val pool = Executors.newFixedThreadPool(4)
        val errors = java.util.concurrent.ConcurrentLinkedQueue<Throwable>()
        repeat(15) {
            val go = CountDownLatch(1)
            val done = CountDownLatch(4)
            repeat(4) { i ->
                pool.execute {
                    try { go.await(); BlocklistCache.write(context, "STANDARD", if (i % 2 == 0) a else b, sec) }
                    catch (t: Throwable) { errors += t }
                    finally { done.countDown() }
                }
            }
            go.countDown()
            assertTrue(done.await(60, TimeUnit.SECONDS))
            val snap = BlocklistCache.read(context, "STANDARD")
            assertNotNull("a complete file after concurrent writes", snap)
            assertTrue(snap!!.blockedNames.size == 20_000 || snap.blockedNames.size == 30_000)
        }
        pool.shutdown()
        assertTrue("no write failed: $errors", errors.isEmpty())
        assertTrue("no temp files left", dir.listFiles()!!.none { it.name.endsWith(".tmp") })
    }
}
