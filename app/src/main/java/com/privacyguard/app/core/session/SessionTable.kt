package com.privacyguard.app.core.session

import java.net.Socket
import java.util.concurrent.ConcurrentHashMap

/**
 * 🔴 أهم كلاس في التطبيق
 * يربط بين الاتصال الوهمي والـ socket الحقيقي
 */
class SessionTable {

    private val forwardMap = ConcurrentHashMap<ConnectionKey, Session>()
    private val reverseMap = ConcurrentHashMap<ConnectionKey, ConnectionKey>()

    fun register(key: ConnectionKey, socket: Socket): Session {
        val session = Session(key, socket)
        forwardMap[key] = session

        val reverseKey = ConnectionKey(
            srcIp = key.dstIp,
            srcPort = key.dstPort,
            dstIp = key.srcIp,
            dstPort = key.srcPort,
            protocol = key.protocol
        )
        reverseMap[reverseKey] = key

        return session
    }

    fun getOutgoing(key: ConnectionKey): Session? = forwardMap[key]

    fun getIncoming(srcIp: Int, srcPort: Int, dstIp: Int, dstPort: Int, protocol: Int): Session? {
        val reverseKey = ConnectionKey(srcIp, srcPort, dstIp, dstPort, protocol)
        val originalKey = reverseMap[reverseKey]
        return originalKey?.let { forwardMap[it] }
    }

    fun remove(key: ConnectionKey) {
        forwardMap.remove(key)?.close()
        val iterator = reverseMap.entries.iterator()
        while (iterator.hasNext()) {
            if (iterator.next().value == key) {
                iterator.remove()
                break
            }
        }
    }

    fun clear() {
        forwardMap.values.forEach { it.close() }
        forwardMap.clear()
        reverseMap.clear()
    }

    fun size(): Int = forwardMap.size
}

data class ConnectionKey(
    val srcIp: Int,
    val srcPort: Int,
    val dstIp: Int,
    val dstPort: Int,
    val protocol: Int
)

data class Session(
    val key: ConnectionKey,
    val socket: Socket,
    var lastActivityTime: Long = System.currentTimeMillis(),
    var bytesSent: Long = 0,
    var bytesReceived: Long = 0
) {
    fun close() {
        try {
            if (!socket.isClosed) socket.close()
        } catch (e: Exception) { }
    }

    fun isIdle(timeoutMillis: Long = 30000): Boolean {
        return System.currentTimeMillis() - lastActivityTime > timeoutMillis
    }
}