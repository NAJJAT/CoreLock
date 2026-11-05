package com.privacyguard.app.core.blocklist

import android.content.Context
import com.privacyguard.vpn.firewall.DomainFilter
import java.io.DataOutputStream
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.util.zip.CRC32

/**
 * What the VPN last blocked, per protection level, so a VPN start does not wait half a
 * minute for the encrypted database. These are the public lists only: no rules, no
 * history, nothing about the user.
 *
 * Version 2 (ADR 0014) stores the filters' sorted fingerprint arrays, not the names: a
 * start reads the file in one go and copies it into the arrays, instead of parsing
 * hundreds of thousands of lines. The file is checked completely (header, length,
 * order, checksum) before anything is used, so a cut-off or damaged file is ignored and
 * the database is read instead. Version-1 files (gzip text) are deleted on the next write.
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

    /** The four arrays of a cache file, sorted and unique, ready for [DomainFilter.replaceWithFingerprints]. */
    class Snapshot(
        val blockedNames: LongArray, val blockedWildcards: LongArray,
        val securityNames: LongArray, val securityWildcards: LongArray,
    ) {
        fun applyTo(blocked: DomainFilter, security: DomainFilter) {
            blocked.replaceWithFingerprints(blockedNames, blockedWildcards)
            security.replaceWithFingerprints(securityNames, securityWildcards)
        }
    }

    private fun file(context: Context, level: String) = File(context.noBackupFilesDir, "blocklist_$level.v2.bin")
    private fun legacyFile(context: Context, level: String) = File(context.noBackupFilesDir, "blocklist_$level.txt.gz")

    fun write(context: Context, level: String, blocked: DomainFilter, security: DomainFilter) {
        write(file(context, level), blocked, security)
        legacyFile(context, level).delete()
    }

    /** From names, where no filter exists yet (lists downloaded while protection was off). */
    fun write(context: Context, level: String, blocked: Collection<String>, security: Collection<String>) =
        write(context, level, DomainFilter().apply { rebuild(blocked) }, DomainFilter().apply { rebuild(security) })

    fun read(context: Context, level: String): Snapshot? = read(file(context, level))

    internal fun write(target: File, blocked: DomainFilter, security: DomainFilter) {
        val (bn, bw) = blocked.fingerprints()
        val (sn, sw) = security.fingerprints()
        val arrays = arrayOf(bn, bw, sn, sw)
        val tmp = File(target.parentFile, target.name + ".tmp")
        val crc = CRC32()
        DataOutputStream(tmp.outputStream().buffered(1 shl 16)).use { out ->
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
        }
        // Rename is atomic: a crash mid-write leaves the old file, never half of one.
        if (!tmp.renameTo(target)) { target.delete(); tmp.renameTo(target) }
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
}
