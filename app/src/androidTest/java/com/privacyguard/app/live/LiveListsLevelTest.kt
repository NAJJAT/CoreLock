package com.privacyguard.app.live

import android.util.Log
import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.privacyguard.app.adprivacy.AdSdkPolicy
import com.privacyguard.app.core.blocklist.BlocklistManager
import com.privacyguard.app.core.blocklist.BlocklistSource
import com.privacyguard.app.core.blocklist.BlocklistRejections
import com.privacyguard.app.core.blocklist.UpdateResult
import com.privacyguard.app.data.db.AppDatabase
import com.privacyguard.app.data.db.ConfigDatabase
import com.privacyguard.app.data.remote.BlocklistDownloader
import com.privacyguard.app.data.repository.BlocklistRepo
import com.privacyguard.core.filter.FilterEngine
import com.privacyguard.core.filter.FilterEngine.BlockLevel
import com.privacyguard.core.packet.DnsPacket
import com.privacyguard.core.packet.IpPacket
import com.privacyguard.core.packet.UdpPacket
import com.privacyguard.vpn.firewall.DomainFilter
import com.privacyguard.vpn.forwarder.DnsHandler
import com.privacyguard.vpn.forwarder.NameBlockPolicy
import com.privacyguard.vpn.tunnel.TunWriter
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import kotlin.random.Random

/**
 * Nightly only (plan task 2.1, `.github/workflows/nightly.yml`): today's live lists,
 * downloaded and parsed by the app's own code, decided per level by the service's own
 * [BlocklistRepo.levelLists] and [NameBlockPolicy], plus real DNS lookups through
 * [DnsHandler].
 *
 * This test uses the network on purpose, against CLAUDE.md §9's fixture rule: the
 * question is whether today's real lists still behave, which pinned excerpts
 * (ProtectionLevelMatrixTest) can't answer. It contacts only hosts the app already
 * contacts (the list sources and their mirror, and Cloudflare's 1.1.1.1). It is skipped
 * unless the run passes `-Pandroid.testInstrumentationRunnerArguments.liveLists=true`,
 * so pull requests never run it.
 */
@RunWith(AndroidJUnit4::class)
class LiveListsLevelTest {

    private lateinit var db: AppDatabase
    private lateinit var config: ConfigDatabase
    private lateinit var repo: BlocklistRepo
    private val live = HashMap<BlocklistSource, List<String>>()

    /** Must stay open at every level (the same list as ProtectionLevelMatrixTest). */
    private val alwaysOpen = listOf(
        "www.google.com", "wikipedia.org", "github.com", "safebrowsing.googleapis.com",
        "graph.facebook.com", "connectivitycheck.gstatic.com", "play.googleapis.com", "web.whatsapp.com",
    )

    private val noRejections = object : BlocklistRejections {
        override fun record(source: BlocklistSource, stored: Int, downloaded: Int, at: Long) = Unit
    }

    @Before
    fun setUp() = runBlocking {
        val args = InstrumentationRegistry.getArguments()
        assumeTrue("live lists run only nightly", args.getString("liveLists") == "true")
        val ctx = InstrumentationRegistry.getInstrumentation().targetContext
        db = Room.inMemoryDatabaseBuilder(ctx, AppDatabase::class.java).build()
        config = Room.inMemoryDatabaseBuilder(ctx, ConfigDatabase::class.java).build()
        repo = BlocklistRepo(db.blocklistDao(), config.blocklistToggleDao())
        val downloader = BlocklistDownloader()
        for (source in BlocklistSource.values()) {
            if (source.downloadUrl() == null) continue
            val names = downloader.downloadLines(source) { BlocklistManager.parseLines(source, it) }
            assertTrue("$source: download or parse failed", !names.isNullOrEmpty())
            val result = BlocklistManager.storeDownloaded(source, names!!, repo, noRejections)
            assertTrue("$source: not stored ($result)", result is UpdateResult.Success)
            live[source] = names.toList()
            Log.i(TAG, "$source: ${names.size} names")
        }
        BlocklistManager.BUILTIN_SEED.forEach { (source, cats) ->
            cats.forEach { (category, names) -> repo.replaceSource("$source-$category", category, names.distinct()) }
        }
    }

    @After
    fun tearDown() {
        if (::db.isInitialized) db.close()
        if (::config.isInitialized) config.close()
    }

    private val sdk = AdSdkPolicy(enabled = { false })

    private class Level(val policy: NameBlockPolicy, val blocked: DomainFilter, val security: DomainFilter)

    private fun level(level: BlockLevel): Level = runBlocking {
        val lists = repo.levelLists(level)
        val blocked = DomainFilter().apply { rebuild(lists.blocked) }
        val security = DomainFilter().apply { rebuild(lists.security) }
        Level(NameBlockPolicy(FilterEngine().also { it.blockLevel = level }, blocked, security, sdk), blocked, security)
    }

    private fun sample(source: BlocklistSource, n: Int = 300) =
        live.getValue(source).shuffled(Random(20261105)).take(n).filter { !it.startsWith("*.") }

    @Test
    fun todays_lists_block_at_the_right_levels_and_keep_essential_hosts_open() {
        val levels = BlockLevel.values().associateWith { level(it) }
        for ((lvl, l) in levels) {
            Log.i(TAG, "$lvl: ${l.blocked.size} + ${l.security.size} names, " +
                "${(l.blocked.heapBytes + l.security.heapBytes) / 1024} KiB in the filters")
        }
        val broken = mutableListOf<String>()
        fun expect(what: String, names: List<String>, blockedAt: Set<BlockLevel>) {
            for ((lvl, l) in levels) {
                // Ad-SDK hosts are kept open on purpose by AdSdkPolicy; not a failure.
                val wrong = names.filter { n ->
                    val blocked = l.policy.reasonFor(n, null) != null
                    val keptOpen = sdk.decide(null, n) is com.privacyguard.app.adprivacy.AdSdkVerdict.KeepOpen
                    if (lvl in blockedAt) !blocked && !keptOpen else blocked
                }
                if (wrong.isNotEmpty()) broken += "$what at $lvl: ${wrong.size} of ${names.size}, e.g. ${wrong.take(3)}"
            }
        }
        val all = BlockLevel.values().toSet()
        expect("malware (TIF mini)", sample(BlocklistSource.HAGEZI_TIF_MINI), all)
        expect("ads (Pro mini)", sample(BlocklistSource.HAGEZI_PRO_MINI), setOf(BlockLevel.STANDARD, BlockLevel.STRICT))
        val proMini = live.getValue(BlocklistSource.HAGEZI_PRO_MINI).toHashSet()
        val standard = levels.getValue(BlockLevel.STANDARD).policy
        val strictOnly = sample(BlocklistSource.HAGEZI_PRO, 2_000).filter { it !in proMini && standard.reasonFor(it, null) == null }.take(300)
        expect("Pro beyond Pro mini", strictOnly, setOf(BlockLevel.STRICT))
        expect("essential hosts", alwaysOpen, emptySet())
        assertTrue(broken.joinToString("\n"), broken.isEmpty())
    }

    @Test
    fun real_lookups_answer_open_names_and_stop_blocked_ones_locally() {
        val l = level(BlockLevel.STANDARD)
        val out = LinkedBlockingQueue<ByteArray>()
        val writer = TunWriter(object : TunWriter.PacketSink {
            override val isOpen = true
            override fun writePacket(packet: ByteArray): Boolean { out.put(packet); return true }
        }).also { it.start() }
        // No VPN runs here, so sockets need no protect(): the lookup goes straight out.
        val handler = DnsHandler(FilterEngine().also { it.blockLevel = BlockLevel.STANDARD }, writer,
            protectSocket = { true }, protectTcpSocket = { true }, domainFilter = l.blocked, securityFilter = l.security)
        try {
            val failures = mutableListOf<String>()
            fun ask(name: String, id: Int): DnsPacket? {
                val query = DnsPacket.buildQuery(name, id)
                handler.handle(ip(), UdpPacket(sourcePort = 40_000 + id, destinationPort = 53, length = 8 + query.size, checksum = 0, data = query))
                val packet = out.poll(10, TimeUnit.SECONDS) ?: return null
                return DnsPacket.parse(packet.copyOfRange(28, packet.size))
            }
            alwaysOpen.forEachIndexed { i, name ->
                val answer = ask(name, 100 + i)
                if (answer == null || answer.responseCode != 0 || answer.aRecords.isEmpty()) failures += "$name: no address (${answer?.responseCode})"
            }
            sample(BlocklistSource.HAGEZI_PRO_MINI, 40).filter { l.policy.reasonFor(it, null) != null }.take(20).forEachIndexed { i, name ->
                val answer = ask(name, 200 + i)
                if (answer == null || answer.responseCode != 3) failures += "$name: not NXDOMAIN (${answer?.responseCode})"
            }
            assertTrue(failures.joinToString("\n"), failures.isEmpty())
        } finally {
            handler.shutdown()
            writer.stop()
        }
    }

    private fun ip() = IpPacket(
        version = 4, headerLength = 20, dscp = 0, totalLength = 40,
        identification = 1, flagDf = false, flagMf = false, fragmentOffset = 0,
        ttl = 64, protocol = 17, headerChecksum = 0,
        sourceIp = "10.0.0.2", destinationIp = "10.0.0.1",
        options = ByteArray(0), rawPacket = ByteArray(40),
    )

    private companion object { const val TAG = "LiveListsLevelTest" }
}
