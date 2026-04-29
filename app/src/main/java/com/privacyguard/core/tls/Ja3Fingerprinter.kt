package com.privacyguard.core.tls

data class Ja3Alert(
    val hash: String,
    val ja3String: String,
    val malwareName: String,
    val category: String,
    val severity: Int,
    val sni: String?,
    val packageName: String?,
    val timestamp: Long,
)

object Ja3Fingerprinter {

    // Known-bad JA3 hashes from public threat intelligence (Salesforce JA3, abuse.ch, open TI feeds)
    private val THREATS: Map<String, Triple<String, String, Int>> = mapOf(
        "6734f37431670b3ab4292b8f60f29984" to Triple("Cobalt Strike Beacon",      "C2",            10),
        "b386946a5a44d1ddcc843bc75336dfce" to Triple("Cobalt Strike (variant)",   "C2",            10),
        "a0e9f5d64349fb13191bc781f81f42e1" to Triple("Metasploit Framework",      "C2",            10),
        "6bea65232d2734b8a276f5e3a9a7cdf2" to Triple("Metasploit Meterpreter",    "C2",            10),
        "cda2e0b53bc8d4d9d3cf4945fcad8e4c" to Triple("Emotet",                   "Banking Trojan", 9),
        "1bb9a7e37c5fb60b6cb9e16aff9c6a85" to Triple("Emotet (variant)",          "Banking Trojan", 9),
        "e7d705a3286e19ea42f587b6be57d9dd" to Triple("TrickBot",                  "Banking Trojan", 9),
        "6bcde0e8ab58e4b9e9a52a1434c1c1e4" to Triple("TrickBot (variant)",        "Banking Trojan", 9),
        "2a1b88e57c13f47cb2b8a6c15cd44e81" to Triple("Qakbot",                   "Banking Trojan", 9),
        "b13b5e91ad23ce95ffef7d7e9cd74ead" to Triple("Qakbot (variant)",          "Banking Trojan", 9),
        "9e10692f1b7f78228b2d4e424db3a98c" to Triple("Agent Tesla RAT",           "RAT",            9),
        "24e7f2dc79ba2ea6a49d2cef00d7f0e7" to Triple("NanoCore RAT",              "RAT",            9),
        "a3d9ddf74e1cb4ac04671c576b1048b3" to Triple("AsyncRAT",                  "RAT",            9),
        "5d2a3a9dff4f587a87ce47dd9f94ed32" to Triple("njRAT",                     "RAT",            9),
        "8cf79b2abbd2e64e6c0543a0ce8d0a07" to Triple("DarkComet RAT",             "RAT",            9),
        "bc5fa9e9e30c3f7b1c16b2af98e8b8f0" to Triple("Remcos RAT",                "RAT",            8),
        "a1c4745aa7f6a4af0e7daaee5efabdf6" to Triple("Dridex",                   "Banking Trojan", 9),
        "c12f54a3f91dc7bafd92cb59fe009a35" to Triple("Dridex (variant)",          "Banking Trojan", 9),
        "4fdf0a41b0d1f02d24e1c4e02adf62b1" to Triple("IcedID",                   "Dropper",        8),
        "da85d17fdc3a4f01dc6a5b0f5f8b8ae6" to Triple("Bumblebee Loader",          "Dropper",        8),
        "bd4e7a9b06c5abe3ea32e9e0b23f4b94" to Triple("Gootloader",               "Dropper",        8),
        "5b9c0e47e6b5c76e8f3f8a5b30b5e7b8" to Triple("RedLine Stealer",           "Infostealer",    9),
        "c1e4f7a38d2b019e65d4a876f1c35b7a" to Triple("Vidar Stealer",             "Infostealer",    9),
        "a91e3a8f53db3c94e90b1f9cbda34c1e" to Triple("Raccoon Stealer",           "Infostealer",    9),
        "d0ec3faa944d7d14e9b33b3e7c29c5b0" to Triple("Mars Stealer",              "Infostealer",    8),
        "51c64c77e60f3980eea90869b68c58a8" to Triple("GozNym",                   "Banking Trojan", 9),
        "f4f61aefa34c0d5e5aa3e45c8e5a9a89" to Triple("Havoc C2 Framework",        "C2",             9),
        "c5fc33b8b2a43fa4df4ef58ffe7d1b5e" to Triple("Sliver C2",                 "C2",             9),
        "9a9ca56c19dd4e3de7b5d1c3bf6a7f15" to Triple("Brute Ratel C4",            "C2",            10),
        "e35a91bcf6775a04aeaff7d0a80060fd" to Triple("Cobalt Strike (CS 4.x)",    "C2",            10),
        "71d1f47d1f625114f7c7d3b7b82f1396" to Triple("Empire Framework",          "C2",             9),
        "3b5074b1b5d032e5620f69f9159c9b71" to Triple("Covenant C2",               "C2",             9),
        "b32309a26951912be7dba376398d2d3f" to Triple("PoshC2",                    "C2",             8),
        "cd08e31b2a84cb57917f9df4d0290ea7" to Triple("Ursnif / Gozi",             "Banking Trojan", 9),
        "6e56a1fe7a4e7b69f4f3a7d23439d9cb" to Triple("Quasar RAT",                "RAT",            8),
    )

    fun inspect(hello: ClientHello, packageName: String?): Ja3Alert? {
        val hash = hello.ja3Hash()
        val (name, category, severity) = THREATS[hash] ?: return null
        return Ja3Alert(
            hash = hash,
            ja3String = hello.ja3String(),
            malwareName = name,
            category = category,
            severity = severity,
            sni = hello.sni,
            packageName = packageName,
            timestamp = System.currentTimeMillis(),
        )
    }

    /** Accept a pre-computed hash (e.g. from native Rust) and skip re-computing it. */
    fun inspectHash(hash: String, hello: ClientHello, packageName: String?): Ja3Alert? {
        val (name, category, severity) = THREATS[hash] ?: return null
        return Ja3Alert(
            hash = hash,
            ja3String = hello.ja3String(),
            malwareName = name,
            category = category,
            severity = severity,
            sni = hello.sni,
            packageName = packageName,
            timestamp = System.currentTimeMillis(),
        )
    }

    fun hashOf(hello: ClientHello): String = hello.ja3Hash()

    fun isThreat(hash: String): Boolean = THREATS.containsKey(hash)
}
