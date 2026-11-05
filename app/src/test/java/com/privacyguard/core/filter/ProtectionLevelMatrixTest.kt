package com.privacyguard.core.filter

import androidx.room.Room
import com.privacyguard.app.adprivacy.AdSdkPolicy
import com.privacyguard.app.adprivacy.AdSdkVerdict
import com.privacyguard.app.core.blocklist.BlocklistManager
import com.privacyguard.app.core.blocklist.BlocklistSource
import com.privacyguard.app.core.blocklist.CuratedAdDomains
import com.privacyguard.app.data.db.AppDatabase
import com.privacyguard.app.data.db.ConfigDatabase
import com.privacyguard.app.data.repository.BlocklistRepo
import com.privacyguard.core.filter.FilterEngine.BlockLevel
import com.privacyguard.vpn.firewall.DnsBypassGuard
import com.privacyguard.vpn.firewall.DomainFilter
import com.privacyguard.vpn.forwarder.DnsBlockDecision
import com.privacyguard.vpn.forwarder.NameBlockPolicy
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

/**
 * Every protection level against pinned excerpts of the real lists (plan task 1.7).
 *
 * The excerpts (`src/test/resources/levels/`, sampled 2026-11-05) are stored as the app
 * stores downloads; the filters are built by [BlocklistRepo.levelLists], the function the
 * VPN service uses; each name is decided by the real [NameBlockPolicy]. So a change to a
 * level's lists, categories, sources or the decision order shows up here as a broken cell.
 * No network: the excerpts are data in the repository.
 */
@RunWith(RobolectricTestRunner::class)
class ProtectionLevelMatrixTest {

    private lateinit var db: AppDatabase
    private lateinit var config: ConfigDatabase
    private lateinit var repo: BlocklistRepo

    private fun excerpt(name: String): List<String> =
        javaClass.classLoader!!.getResourceAsStream("levels/$name.txt")!!.bufferedReader()
            .readLines().filter { it.isNotBlank() && !it.startsWith("#") }

    private val tif = excerpt("hagezi-tif-mini")
    private val proMini = excerpt("hagezi-pro-mini")
    private val proOnly = excerpt("hagezi-pro-only")
    private val oisdOnly = excerpt("oisd-small-only")
    private val doh = excerpt("hagezi-doh")

    /** Must stay open at every level: everyday sites, Safe Browsing, login and connectivity checks. */
    private val alwaysOpen = listOf(
        "www.google.com", "wikipedia.org", "github.com", "safebrowsing.googleapis.com",
        "graph.facebook.com", "connectivitycheck.gstatic.com", "play.googleapis.com", "web.whatsapp.com",
    )

    private val telemetry = BlocklistManager.BUILTIN_SEED.getValue(BlocklistRepo.SOURCE_BUILTIN).getValue(BlocklistRepo.CAT_TELEMETRY)

    @Before
    fun setUp() = runBlocking {
        db = Room.inMemoryDatabaseBuilder(RuntimeEnvironment.getApplication(), AppDatabase::class.java)
            .allowMainThreadQueries().build()
        config = Room.inMemoryDatabaseBuilder(RuntimeEnvironment.getApplication(), ConfigDatabase::class.java)
            .allowMainThreadQueries().build()
        repo = BlocklistRepo(db.blocklistDao(), config.blocklistToggleDao())
        fun store(source: BlocklistSource, names: List<String>) {
            val (key, category) = BlocklistManager.sourceMetadata(source)
            runBlocking { repo.replaceSource(key, category, names) }
        }
        store(BlocklistSource.HAGEZI_TIF_MINI, tif)
        store(BlocklistSource.HAGEZI_PRO_MINI, proMini)
        store(BlocklistSource.HAGEZI_PRO, proMini + proOnly)      // Pro contains Pro mini
        store(BlocklistSource.OISD_BASIC, oisdOnly)
        store(BlocklistSource.HAGEZI_DOH, doh)
        BlocklistManager.BUILTIN_SEED.forEach { (source, cats) ->
            cats.forEach { (category, names) -> repo.replaceSource("$source-$category", category, names.distinct()) }
        }
    }

    @After
    fun tearDown() {
        db.close()
        config.close()
    }

    private val sdk = AdSdkPolicy(enabled = { false })

    private fun policyAt(level: BlockLevel): NameBlockPolicy = runBlocking {
        val lists = repo.levelLists(level)
        NameBlockPolicy(
            FilterEngine().also { it.blockLevel = level },
            DomainFilter().apply { rebuild(lists.blocked) },
            DomainFilter().apply { rebuild(lists.security) },
            sdk,
        )
    }

    /** Names an ad list blocks unless the Ad-SDK policy keeps them open on purpose. */
    private fun keptOpen(name: String) = sdk.decide(null, name) is AdSdkVerdict.KeepOpen

    private data class Row(val what: String, val names: List<String>, val minimal: Boolean, val standard: Boolean, val strict: Boolean)

    /**
     * Names the built-in and curated ad hosts already cover from Standard up (a subdomain of
     * doubleclick.net, say): rightly blocked at Standard, so not "Strict only" names.
     */
    private val standardExtras = DomainFilter().apply {
        val seed = BlocklistManager.BUILTIN_SEED.getValue(BlocklistRepo.SOURCE_BUILTIN)
        rebuild(seed.getValue(BlocklistRepo.CAT_ADS) + seed.getValue(BlocklistRepo.CAT_TRACKERS) + CuratedAdDomains.all)
    }

    private fun strictOnly(names: List<String>) = names.filterNot { standardExtras.isBlocked(it) }

    @Test
    fun each_level_blocks_exactly_its_lists() {
        // The excerpts must still be big enough to mean something after that filter.
        assertTrue(strictOnly(proOnly).size > 200 && strictOnly(oisdOnly).size > 200)
        val rows = listOf(
            Row("malware and phishing (HaGeZi TIF mini)", tif, minimal = true, standard = true, strict = true),
            Row("ads and trackers (HaGeZi Pro mini)", proMini, minimal = false, standard = true, strict = true),
            Row("HaGeZi Pro beyond Pro mini", strictOnly(proOnly), minimal = false, standard = false, strict = true),
            Row("OISD small beyond HaGeZi", strictOnly(oisdOnly), minimal = false, standard = false, strict = true),
            Row("curated ad hosts", CuratedAdDomains.all.take(40), minimal = false, standard = true, strict = true),
            Row("built-in telemetry", telemetry, minimal = false, standard = false, strict = true),
            Row("encrypted DNS endpoints (not a DNS block)", doh, minimal = false, standard = false, strict = false),
            Row("everyday and essential hosts", alwaysOpen, minimal = false, standard = false, strict = false),
        )
        val policies = BlockLevel.values().associateWith { policyAt(it) }
        val broken = mutableListOf<String>()
        for (row in rows) for (level in BlockLevel.values()) {
            val expected = when (level) { BlockLevel.MINIMAL -> row.minimal; BlockLevel.STANDARD -> row.standard; BlockLevel.STRICT -> row.strict }
            val wrong = row.names.filter { name ->
                val shouldBlock = expected && !(keptOpen(name) && row.names !== tif)
                (policies.getValue(level).reasonFor(name, null) != null) != shouldBlock
            }
            if (wrong.isNotEmpty()) broken += "${row.what} at $level: ${wrong.size} of ${row.names.size} wrong, e.g. ${wrong.take(3)}"
        }
        assertTrue(broken.joinToString("\n"), broken.isEmpty())
    }

    @Test
    fun malware_wins_over_an_ad_level_allow_only_after_the_warning() {
        val name = tif.first()
        for (level in BlockLevel.values()) {
            val policy = runBlocking {
                val lists = repo.levelLists(level)
                val engine = FilterEngine().also { it.blockLevel = level; it.addRule(FilterRule.allowDomain(name)) }
                NameBlockPolicy(engine, DomainFilter().apply { rebuild(lists.blocked) }, DomainFilter().apply { rebuild(lists.security) }, sdk)
            }
            assertEquals("$level", DnsBlockDecision.Reason.SECURITY_LIST, policy.reasonFor(name, null))
        }
    }

    @Test
    fun the_doh_lists_feed_the_bypass_guard_at_every_level() = runBlocking {
        DnsBypassGuard.update(repo.domainsIn(setOf(BlocklistRepo.CAT_DOH)), emptyList())
        try {
            val missed = doh.filterNot { DnsBypassGuard.isDohHost(it) }
            assertTrue("not seen as DoH: ${missed.take(3)}", missed.isEmpty())
        } finally {
            DnsBypassGuard.update(emptyList(), emptyList())
        }
    }

    @Test
    fun quic_and_unreadable_handshakes_follow_the_level() {
        for (level in BlockLevel.values()) {
            val gate = com.privacyguard.vpn.firewall.QuicGate<Int>(
                userChoice = { null }, level = { level }, interceptionBlocksQuic = { false },
            )
            assertEquals("QUIC off by default at $level", level == BlockLevel.STRICT, gate.dropsAllQuic())
            assertEquals("fail closed at $level", level >= BlockLevel.STANDARD, gate.failsClosed())
        }
    }
}
