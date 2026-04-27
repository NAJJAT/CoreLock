package com.privacyguard.core.tls

import java.security.MessageDigest

data class ClientHello(
    val tlsVersion: Int,
    val cipherSuites: List<Int>,
    val compressionMethods: List<Int>,
    val extensions: List<Int>,
    val supportedGroups: List<Int>,
    val ecPointFormats: List<Int>,
    val sni: String?,
    val alpn: List<String>,
    val supportedVersions: List<Int>,
) {
    fun ja3String(): String {
        val version = tlsVersion.toString()
        val ciphers = cipherSuites.filter { !isGrease(it) }.joinToString("-")
        val exts = extensions.filter { !isGrease(it) }.joinToString("-")
        val curves = supportedGroups.filter { !isGrease(it) }.joinToString("-")
        val pointFormats = ecPointFormats.joinToString("-")
        return "$version,$ciphers,$exts,$curves,$pointFormats"
    }

    fun ja3Hash(): String {
        val md = MessageDigest.getInstance("MD5")
        return md.digest(ja3String().toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
    }

    private fun isGrease(value: Int): Boolean {
        val lo = value and 0xFF
        val hi = (value ushr 8) and 0xFF
        return hi == lo && (lo and 0x0F) == 0x0A
    }
}

object ClientHelloParser {

    fun parse(data: ByteArray): ClientHello? {
        if (data.size < 9) return null
        if (data[0].toInt() and 0xFF != 0x16) return null // not TLS handshake record
        var pos = 5
        if (pos >= data.size || data[pos].toInt() and 0xFF != 0x01) return null // not ClientHello
        pos += 4 // handshake type (1) + length (3)

        if (pos + 2 > data.size) return null
        val clientVersion = readU16(data, pos); pos += 2
        pos += 32 // random
        if (pos >= data.size) return null
        val sessionIdLen = data[pos].toInt() and 0xFF; pos += 1 + sessionIdLen

        if (pos + 2 > data.size) return null
        val cipherLen = readU16(data, pos); pos += 2
        val ciphers = mutableListOf<Int>()
        val cipherEnd = pos + cipherLen
        while (pos + 1 < cipherEnd && pos + 1 < data.size) {
            ciphers += readU16(data, pos); pos += 2
        }
        pos = cipherEnd

        if (pos >= data.size) return null
        val comprLen = data[pos].toInt() and 0xFF; pos++
        val comprMethods = (0 until comprLen).map { i ->
            if (pos + i < data.size) data[pos + i].toInt() and 0xFF else 0
        }
        pos += comprLen

        if (pos + 2 > data.size) return ClientHello(
            clientVersion, ciphers, comprMethods, emptyList(), emptyList(),
            emptyList(), null, emptyList(), emptyList()
        )

        val extTotal = readU16(data, pos); pos += 2
        val extEnd = (pos + extTotal).coerceAtMost(data.size)

        val extTypes = mutableListOf<Int>()
        val groups = mutableListOf<Int>()
        val pointFmts = mutableListOf<Int>()
        val versions = mutableListOf<Int>()
        var sni: String? = null
        val alpn = mutableListOf<String>()

        while (pos + 4 <= extEnd) {
            val eType = readU16(data, pos); pos += 2
            val eLen = readU16(data, pos); pos += 2
            val eEnd = (pos + eLen).coerceAtMost(data.size)
            extTypes += eType
            when (eType) {
                0x0000 -> sni = parseSni(data, pos, eEnd)
                0x000a -> parseGroups(data, pos, eEnd, groups)
                0x000b -> parsePointFormats(data, pos, eEnd, pointFmts)
                0x0010 -> parseAlpn(data, pos, eEnd, alpn)
                0x002b -> parseSupportedVersions(data, pos, eEnd, versions)
            }
            pos = eEnd
        }

        return ClientHello(clientVersion, ciphers, comprMethods, extTypes, groups,
            pointFmts, sni, alpn, versions)
    }

    private fun parseSni(data: ByteArray, start: Int, end: Int): String? {
        var p = start
        if (p + 2 > end) return null
        p += 2 // list length
        if (p >= end || data[p].toInt() and 0xFF != 0) return null // name type ≠ host_name
        p++
        if (p + 2 > end) return null
        val nameLen = readU16(data, p); p += 2
        if (p + nameLen > data.size) return null
        return String(data, p, nameLen, Charsets.UTF_8)
    }

    private fun parseGroups(data: ByteArray, start: Int, end: Int, out: MutableList<Int>) {
        var p = start
        if (p + 2 > end) return
        val listLen = readU16(data, p); p += 2
        val listEnd = (p + listLen).coerceAtMost(end)
        while (p + 1 < listEnd) { out += readU16(data, p); p += 2 }
    }

    private fun parsePointFormats(data: ByteArray, start: Int, end: Int, out: MutableList<Int>) {
        var p = start
        if (p >= end) return
        val len = data[p].toInt() and 0xFF; p++
        repeat(len) { i -> if (p + i < data.size) out += data[p + i].toInt() and 0xFF }
    }

    private fun parseAlpn(data: ByteArray, start: Int, end: Int, out: MutableList<String>) {
        var p = start
        if (p + 2 > end) return
        p += 2 // list length
        while (p < end) {
            if (p >= data.size) break
            val protoLen = data[p].toInt() and 0xFF; p++
            if (p + protoLen <= data.size) out += String(data, p, protoLen, Charsets.UTF_8)
            p += protoLen
        }
    }

    private fun parseSupportedVersions(data: ByteArray, start: Int, end: Int, out: MutableList<Int>) {
        var p = start
        if (p >= end) return
        val len = data[p].toInt() and 0xFF; p++
        var i = 0
        while (i + 1 < len && p + i + 1 < data.size) { out += readU16(data, p + i); i += 2 }
    }

    private fun readU16(data: ByteArray, off: Int): Int =
        ((data[off].toInt() and 0xFF) shl 8) or (data[off + 1].toInt() and 0xFF)
}
