package com.privacyguard.app.data.db

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Batches [ConnectionEntity] inserts so the VPN hot path never waits on a DB transaction.
 *
 * Inserts are flushed when either:
 *  - [BATCH_SIZE] rows have accumulated, or
 *  - [FLUSH_INTERVAL_MS] has elapsed since the last flush (whichever comes first).
 *
 * [log] is fire-and-forget and never blocks. The channel capacity is large enough
 * that drops only occur if the DB is severely stalled.
 */
class ConnectionLogger(
    private val dao: ConnectionDao,
    scope: CoroutineScope,
) {
    private val channel = Channel<ConnectionEntity>(capacity = 2048)

    init {
        scope.launch(Dispatchers.IO) {
            val batch = ArrayList<ConnectionEntity>(BATCH_SIZE)
            var lastFlushMs = System.currentTimeMillis()

            while (true) {
                val remaining = FLUSH_INTERVAL_MS - (System.currentTimeMillis() - lastFlushMs)
                val item = if (remaining > 0) {
                    withTimeoutOrNull(remaining) { channel.receive() }
                } else {
                    null
                }

                if (item != null) batch += item

                val now = System.currentTimeMillis()
                val shouldFlush = batch.size >= BATCH_SIZE ||
                    (batch.isNotEmpty() && now - lastFlushMs >= FLUSH_INTERVAL_MS)

                if (shouldFlush) {
                    try { dao.insertAll(batch) } catch (_: Exception) {}
                    batch.clear()
                    lastFlushMs = now
                }
            }
        }
    }

    fun log(entity: ConnectionEntity) { channel.trySend(entity) }

    companion object {
        private const val BATCH_SIZE       = 50
        private const val FLUSH_INTERVAL_MS = 500L
    }
}
