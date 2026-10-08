package com.privacyguard.core.session

/**
 * Remembers which hostname each IP address was resolved for, from the DNS answers
 * the tunnel relays.
 *
 * An app resolves a name first and connects afterwards, so the answer always
 * arrives before the connection exists. Without this cache the name was only
 * applied to sessions already open at answer time — almost never the one it was
 * looked up for — and history showed bare IPs and "Unknown" for apps that do not
 * send SNI (WhatsApp's chat protocol, most UDP).
 */
object DnsNameCache {

    private const val CAPACITY = 8_192

    private val names = object : LinkedHashMap<String, String>(1_024, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, String>?) = size > CAPACITY
    }

    fun record(ip: String, hostname: String) {
        val name = hostname.trimEnd('.').lowercase()
        if (name.isEmpty()) return
        synchronized(names) { names[ip] = name }
    }

    fun nameFor(ip: String): String? = synchronized(names) { names[ip] }

    fun clear() = synchronized(names) { names.clear() }
}
