package com.privacyguard.app.core.geoip

import android.content.Context
import android.util.Log
import java.io.DataInputStream
import java.io.InputStream
import kotlin.concurrent.thread

/**
 * Country of an IPv4 address, from the regional internet registries' public
 * allocation files (assets/geoip_v4.bin, built by tools/geoip/GenerateGeoIp.java).
 *
 * This is the country the block is registered in, not where the server stands:
 * a cloud or CDN address shows the provider's home country. Lookups are a binary
 * search over sorted, non-overlapping ranges.
 */
object RegistryTable {

    private const val TAG = "RegistryTable"
    private const val ASSET = "geoip_v4.bin"

    private class Ranges(val first: IntArray, val last: IntArray, val cc: CharArray)

    @Volatile private var ranges: Ranges? = null
    @Volatile private var loading = false

    fun load(context: Context) {
        if (ranges != null || loading) return
        loading = true
        val app = context.applicationContext
        thread(name = "geoip-load", isDaemon = true) {
            runCatching { app.assets.open(ASSET).use { ranges = read(it) } }
                .onFailure { Log.w(TAG, "GeoIP table not loaded: ${it.message}") }
            loading = false
        }
    }

    /** For tests. */
    internal fun loadFrom(input: InputStream) { ranges = read(input) }

    /** Two-letter country code, or null when the address is not in any registered block. */
    fun countryOf(ip: Long): String? {
        val r = ranges ?: return null
        var lo = 0
        var hi = r.first.size - 1
        while (lo <= hi) {
            val mid = (lo + hi) ushr 1
            val start = r.first[mid].toLong() and 0xFFFFFFFFL
            val end = r.last[mid].toLong() and 0xFFFFFFFFL
            when {
                ip < start -> hi = mid - 1
                ip > end -> lo = mid + 1
                else -> return String(charArrayOf(r.cc[mid * 2], r.cc[mid * 2 + 1]))
            }
        }
        return null
    }

    private fun read(input: InputStream): Ranges {
        val data = DataInputStream(input.buffered())
        val n = data.readInt()
        val first = IntArray(n)
        val last = IntArray(n)
        val cc = CharArray(n * 2)
        for (i in 0 until n) {
            first[i] = data.readInt()
            last[i] = data.readInt()
            val code = data.readUnsignedShort()
            cc[i * 2] = (code ushr 8).toChar()
            cc[i * 2 + 1] = (code and 0xFF).toChar()
        }
        return Ranges(first, last, cc)
    }
}
