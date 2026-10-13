package com.privacyguard.app.core.blocklist

import android.content.Context
import java.io.File
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream

/**
 * The names the VPN last blocked, per protection level, in a plain gzip file, so a
 * VPN start does not wait half a minute for the encrypted database. These are the
 * public lists only: no rules, no history, nothing about the user.
 */
object BlocklistCache {

    private const val HEADER = "privacyguard-blocklist-cache 1"
    private const val SECURITY_MARK = "--security--"

    private fun file(context: Context, level: String) = File(context.noBackupFilesDir, "blocklist_$level.txt.gz")

    fun write(context: Context, level: String, blocked: Collection<String>, security: Collection<String>) =
        write(file(context, level), blocked, security)

    fun read(context: Context, level: String): Pair<List<String>, List<String>>? = read(file(context, level))

    internal fun write(target: File, blocked: Collection<String>, security: Collection<String>) {
        val tmp = File(target.parentFile, target.name + ".tmp")
        GZIPOutputStream(tmp.outputStream().buffered()).bufferedWriter().use { w ->
            w.write(HEADER); w.newLine()
            blocked.forEach { w.write(it); w.newLine() }
            w.write(SECURITY_MARK); w.newLine()
            security.forEach { w.write(it); w.newLine() }
        }
        // Rename is atomic: a crash mid-write leaves the old file, never half of one.
        if (!tmp.renameTo(target)) { target.delete(); tmp.renameTo(target) }
    }

    /** Null when there is no complete file to trust. */
    internal fun read(source: File): Pair<List<String>, List<String>>? {
        if (!source.isFile) return null
        val blocked = ArrayList<String>()
        val security = ArrayList<String>()
        var sawMark = false
        GZIPInputStream(source.inputStream().buffered()).bufferedReader().useLines { lines ->
            val it = lines.iterator()
            if (!it.hasNext() || it.next() != HEADER) return null
            for (line in it) {
                when {
                    line == SECURITY_MARK -> sawMark = true
                    sawMark -> security += line
                    else -> blocked += line
                }
            }
        }
        return if (sawMark) blocked to security else null
    }
}
