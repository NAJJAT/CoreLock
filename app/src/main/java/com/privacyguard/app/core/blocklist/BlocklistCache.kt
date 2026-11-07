package com.privacyguard.app.core.blocklist

import android.content.Context
import com.privacyguard.app.core.utils.CappedInputStream
import com.privacyguard.app.data.remote.BlocklistDownloader
import com.privacyguard.vpn.firewall.DomainFilter
import com.privacyguard.vpn.firewall.NameFingerprints
import java.io.BufferedOutputStream
import java.io.DataOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.RandomAccessFile
import java.io.Reader
import java.nio.ByteBuffer
import java.util.concurrent.Callable
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.zip.CRC32
import java.util.zip.GZIPInputStream

/**
 * What the VPN last blocked, per protection level, so a VPN start does not wait half a
 * minute for the encrypted database. These are the public lists only: no rules, no
 * history, nothing about the user.
 *
 * Version 2 (ADR 0014) stores the filters' sorted fingerprint arrays, not the names: a
 * start reads the file in one go and copies it into the arrays, instead of parsing
 * hundreds of thousands of lines. The file is checked completely (header, length,
 * order, checksum) before anything is used, so a cut-off or damaged file is ignored and
 * the database is read instead.
 *
 * Version 1 (gzip text, written by 1.0.0) is read once, at the first start after the
 * update, so that start does not wait for the database either; then it is deleted.
 *
 * Layout, big-endian: magic, version, 4 counts (blocked names, blocked wildcards,
 * security names, security wildcards), the 4 arrays, then CRC-32 of everything before it.
 */
object BlocklistCache {

    private const val MAGIC = 0x50474243           // "PGBC"
    private const val VERSION = 2
    private const val HEADER_BYTES = 4 + 4 + 4 * 4
    /** Far above STRICT (about 500k names): a larger count is a damaged file, not a list. */
    internal const val MAX_PER_ARRAY = 8_000_000

    private const val LEGACY_HEADER = "privacyguard-blocklist-cache 1"
    private const val LEGACY_SECURITY_MARK = "--security--"
    /**
     * The 1.0.0 file holds two sections (the level's lists, then the malware lists), each
     * bounded like one downloaded list: [BlocklistDownloader.MAX_LIST_BYTES] of text and
     * [ListEntryValidator.MAX_ENTRIES_PER_SOURCE] entries. A gzip bomb stops at this cap.
     */
    internal const val MAX_LEGACY_TEXT_BYTES = 2 * BlocklistDownloader.MAX_LIST_BYTES
    /** A name is at most 253 characters; a longer line is skipped without being held whole. */
    private const val MAX_LEGACY_LINE_CHARS = 1_024

    /** The four arrays of a cache file, sorted and unique, ready for [DomainFilter.replaceWithFingerprints]. */
    class Snapshot(
        val blockedNames: LongArray, val blockedWildcards: LongArray,
        val securityNames: LongArray, val securityWildcards: LongArray,
        /** From the 1.0.0 file: the start after the update. */
        val fromLegacyFile: Boolean = false,
    ) {
        fun applyTo(blocked: DomainFilter, security: DomainFilter) {
            blocked.replaceWithFingerprints(blockedNames, blockedWildcards)
            security.replaceWithFingerprints(securityNames, securityWildcards)
        }
    }

    private fun file(context: Context, level: String) = File(context.noBackupFilesDir, "blocklist_$level.v2.bin")
    private fun legacyFile(context: Context, level: String) = File(context.noBackupFilesDir, "blocklist_$level.txt.gz")

    fun exists(context: Context, level: String): Boolean = file(context, level).isFile

    fun write(context: Context, level: String, blocked: DomainFilter, security: DomainFilter) {
        write(file(context, level), blocked, security)
        legacyFile(context, level).delete()
    }

    /**
     * From fingerprint arrays taken earlier ([DomainFilter.fingerprints]), so a write on
     * another thread stores what was loaded for [level], even if the filters change meanwhile.
     */
    fun write(context: Context, level: String, blocked: Pair<LongArray, LongArray>, security: Pair<LongArray, LongArray>) {
        write(file(context, level), arrayOf(blocked.first, blocked.second, security.first, security.second))
        legacyFile(context, level).delete()
    }

    /** From names, where no filter exists yet (lists downloaded while protection was off). */
    fun write(context: Context, level: String, blocked: Collection<String>, security: Collection<String>) =
        write(context, level, DomainFilter().apply { rebuild(blocked) }, DomainFilter().apply { rebuild(security) })

    /**
     * The file for a VPN start at [level]: version 2, or else, once after the update from
     * 1.0.0, the version-1 file. Null means: read the database. The version-1 file is
     * deleted whatever it held, so a bad one is not parsed again at every start.
     */
    fun read(context: Context, level: String): Snapshot? {
        val legacy = legacyFile(context, level)
        val current = read(file(context, level))
        if (current != null) { legacy.delete(); return current }
        if (!legacy.exists()) return null
        return try {
            readLegacy(legacy).also { if (it == null) android.util.Log.w("BlocklistCache", "1.0 start-up file not usable; reading the database") }
        } finally {
            legacy.delete()
        }
    }

    internal fun write(target: File, blocked: DomainFilter, security: DomainFilter) {
        val (bn, bw) = blocked.fingerprints()
        val (sn, sw) = security.fingerprints()
        write(target, arrayOf(bn, bw, sn, sw))
    }

    /** One lock per file: the list worker and the VPN service (one process) may write the same level at once. */
    private val writeLocks = ConcurrentHashMap<String, Any>()

    private fun write(target: File, arrays: Array<LongArray>) = synchronized(writeLocks.getOrPut(target.path) { Any() }) {
        val tmp = File(target.parentFile, target.name + ".tmp")
        try {
            val crc = CRC32()
            FileOutputStream(tmp).use { fos ->
                val out = DataOutputStream(BufferedOutputStream(fos, 1 shl 16))
                val header = ByteBuffer.allocate(HEADER_BYTES).putInt(MAGIC).putInt(VERSION)
                arrays.forEach { header.putInt(it.size) }
                out.write(header.array()); crc.update(header.array())
                val chunk = ByteBuffer.allocate(8 * 4_096)
                for (a in arrays) {
                    var i = 0
                    while (i < a.size) {
                        chunk.clear()
                        val n = minOf(4_096, a.size - i)
                        chunk.asLongBuffer().put(a, i, n)
                        out.write(chunk.array(), 0, 8 * n); crc.update(chunk.array(), 0, 8 * n)
                        i += n
                    }
                }
                out.writeInt(crc.value.toInt())
                out.flush()
                // On disk before the rename: after a power cut the name points at a whole file.
                fos.fd.sync()
            }
            // Rename is atomic on Android: a crash mid-write leaves the old file, never half
            // of one. Some desktop file systems refuse to rename over a file; a start in that
            // gap finds no file and reads the database, which is safe.
            if (!tmp.renameTo(target)) {
                target.delete()
                if (!tmp.renameTo(target)) throw IOException("start-up cache not renamed")
            }
        } finally {
            tmp.delete()   // nothing left after a successful rename
        }
    }

    /** Null when there is no complete, undamaged version-2 file to trust. */
    internal fun read(source: File): Snapshot? {
        if (!source.isFile) return null
        val length = source.length()
        if (length < HEADER_BYTES + 4) return null
        val bytes = RandomAccessFile(source, "r").use { raf ->
            // Checked against the counts below; this only bounds the one allocation.
            if (length > HEADER_BYTES + 4 + 8L * 4 * MAX_PER_ARRAY) return null
            ByteArray(length.toInt()).also { raf.readFully(it) }
        }
        val buf = ByteBuffer.wrap(bytes)
        if (buf.getInt() != MAGIC || buf.getInt() != VERSION) return null
        val counts = IntArray(4) { buf.getInt() }
        if (counts.any { it < 0 || it > MAX_PER_ARRAY }) return null
        if (length != HEADER_BYTES + 8L * counts.sum() + 4) return null
        val crc = CRC32().apply { update(bytes, 0, bytes.size - 4) }
        if (ByteBuffer.wrap(bytes, bytes.size - 4, 4).getInt() != crc.value.toInt()) return null
        val longs = buf.asLongBuffer()
        val arrays = Array(4) { i ->
            LongArray(counts[i]).also { a ->
                longs.get(a)
                // Binary search needs strictly ascending values: refuse anything else.
                for (k in 1 until a.size) if (a[k] <= a[k - 1]) return null
            }
        }
        return Snapshot(arrays[0], arrays[1], arrays[2], arrays[3])
    }

    /**
     * The 1.0.0 file (header line, the level's names, "--security--", the malware names),
     * read as untrusted input with the checks of a downloaded list: every name through
     * [ListEntryValidator] (invalid ones skipped, as in a download), size and entry caps,
     * and the gzip checksum, which is verified when the stream reaches its end. Null for
     * anything else: another header, no or two security marks, no malware names (the
     * database start keeps malware protection), a cut-off or damaged file, a cap reached.
     * Names go straight into fingerprints, without a list of strings.
     */
    internal fun readLegacy(
        source: File,
        maxTextBytes: Long = MAX_LEGACY_TEXT_BYTES,
        maxEntries: Int = ListEntryValidator.MAX_ENTRIES_PER_SOURCE,
        workers: Int = Runtime.getRuntime().availableProcessors().coerceIn(1, MAX_LEGACY_WORKERS),
    ): Snapshot? {
        if (!source.isFile || source.length() == 0L || source.length() > BlocklistDownloader.MAX_LIST_BYTES) return null
        val sections = arrayOf(Fingerprints(), Fingerprints())   // the level's lists, malware
        var section = 0
        // The checks cost about 3 µs a name on a desktop JVM, almost all of it the public-
        // suffix lookup, and this read holds the tunnel back: batches are checked on all
        // cores. At most 2 batches per worker wait, so memory stays bounded.
        val pool = Executors.newFixedThreadPool(workers) { r -> Thread(r, "legacy-cache").apply { isDaemon = true } }
        val pending = ArrayDeque<Pair<Int, Future<Batch>>>()
        var batch = ArrayList<String>(LEGACY_BATCH_LINES)
        fun submit() {
            if (batch.isEmpty()) return
            val lines = batch
            pending.addLast(section to pool.submit(Callable { validate(lines) }))
            batch = ArrayList(LEGACY_BATCH_LINES)
        }
        /** Merges checked batches in order until at most [keep] wait; false once a section is over its cap. */
        fun merge(keep: Int): Boolean {
            while (pending.size > keep) {
                val (sec, done) = pending.removeFirst()
                if (!sections[sec].addAll(done.get(), maxEntries)) return false
            }
            return true
        }
        try {
            // The file stream is owned from the start: GZIPInputStream throws in its
            // constructor for a file that is not gzip, and an open file can't be deleted.
            source.inputStream().use { file ->
                val stream = CappedInputStream(GZIPInputStream(file.buffered(1 shl 16), 1 shl 16), maxTextBytes)
                val lines = BoundedLines(stream.reader(Charsets.UTF_8), MAX_LEGACY_LINE_CHARS)
                if (lines.next()?.trim() != LEGACY_HEADER) return null
                while (true) {
                    val line = lines.next()?.trim() ?: break
                    if (line == LEGACY_SECURITY_MARK) {
                        if (section == 1) return null
                        submit()
                        section = 1
                        continue
                    }
                    if (line.isEmpty() || line.startsWith('#')) continue
                    batch.add(line)
                    if (batch.size == LEGACY_BATCH_LINES) { submit(); if (!merge(2 * workers)) return null }
                }
            }
            submit()
            if (!merge(0)) return null
        } catch (_: IOException) {
            return null   // cut off, damaged (gzip checksum) or over the size cap
        } catch (_: java.util.concurrent.ExecutionException) {
            return null   // a check failed unexpectedly: the database path is the safe one
        } finally {
            pool.shutdownNow()
        }
        if (section != 1 || sections[1].count == 0) return null
        val (bn, bw) = sections[0].sorted()
        val (sn, sw) = sections[1].sorted()
        return Snapshot(bn, bw, sn, sw, fromLegacyFile = true)
    }

    private const val MAX_LEGACY_WORKERS = 8
    private const val LEGACY_BATCH_LINES = 8_192

    /** The fingerprints of the valid names in [lines], each checked like a line of a downloaded list. */
    private fun validate(lines: List<String>): Batch {
        val out = Batch(lines.size)
        for (line in lines) {
            // 1.0.0 stored wildcard entries as "*.name", as DomainFilter reads them.
            val wildcard = line.startsWith("*.")
            val name = ListEntryValidator.normalize(if (wildcard) line.substring(2) else line) ?: continue
            out.add(NameFingerprints.of(name), wildcard)
        }
        return out
    }

    private class Batch(capacity: Int) {
        val names = LongArray(capacity)
        val wildcards = LongArray(capacity)
        var n = 0
        var w = 0
        fun add(fp: Long, wildcard: Boolean) { if (wildcard) wildcards[w++] = fp else names[n++] = fp }
    }

    private class Fingerprints {
        private var names = LongArray(1_024)
        private var wildcards = LongArray(64)
        private var n = 0
        private var w = 0
        var count = 0
            private set

        /** False once more than [max] entries were added. */
        fun addAll(b: Batch, max: Int): Boolean {
            count += b.n + b.w
            if (count > max) return false
            if (n + b.n > names.size) names = names.copyOf(maxOf(names.size * 2, n + b.n))
            if (w + b.w > wildcards.size) wildcards = wildcards.copyOf(maxOf(wildcards.size * 2, w + b.w))
            System.arraycopy(b.names, 0, names, n, b.n); n += b.n
            System.arraycopy(b.wildcards, 0, wildcards, w, b.w); w += b.w
            return true
        }

        fun sorted(): Pair<LongArray, LongArray> =
            NameFingerprints.sortedUnique(names, n) to NameFingerprints.sortedUnique(wildcards, w)
    }

    /**
     * Lines of at most [max] characters, read in blocks; a longer line comes back empty
     * (skipped), never held whole.
     */
    private class BoundedLines(private val reader: Reader, private val max: Int) {
        private val buf = CharArray(1 shl 16)
        private var pos = 0
        private var end = 0
        private val sb = StringBuilder()

        fun next(): String? {
            sb.setLength(0)
            var tooLong = false
            var any = false
            while (true) {
                if (pos == end) {
                    end = reader.read(buf, 0, buf.size)
                    pos = 0
                    if (end <= 0) { end = 0; return if (!any) null else if (tooLong) "" else sb.toString() }
                }
                any = true
                var i = pos
                while (i < end && buf[i] != '\n') i++
                if (!tooLong) {
                    if (sb.length + (i - pos) > max) { tooLong = true; sb.setLength(0) } else sb.append(buf, pos, i - pos)
                }
                if (i < end) { pos = i + 1; return if (tooLong) "" else sb.toString() }
                pos = end
            }
        }
    }
}
