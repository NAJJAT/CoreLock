package com.privacyguard.app.data.db

import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Batches [ConnectionEntity] inserts so the VPN hot path never waits on a DB transaction.
 *
 * The loop sleeps until a row arrives, then collects more for up to
 * [FLUSH_INTERVAL_MS] or until [BATCH_SIZE] rows, and writes them in one insert.
 * (An earlier version stopped receiving after its first idle interval and spun
 * a CPU core forever without ever writing a row.)
 *
 * [log] is fire-and-forget and never blocks. The channel capacity is large enough
 * that drops only occur if the DB is severely stalled.
 */
class ConnectionLogger(
    private val dao: ConnectionDao,
    scope: CoroutineScope,
) {
    private val channel = Channel<ConnectionEntity>(capacity = 2048)
    private var failedBatches = 0

    init {
        scope.launch(Dispatchers.IO) {
            val batch = ArrayList<ConnectionEntity>(BATCH_SIZE)
            while (true) {
                batch += channel.receive()   // suspends while idle: no CPU use
                val deadline = System.currentTimeMillis() + FLUSH_INTERVAL_MS
                while (batch.size < BATCH_SIZE) {
                    val remaining = deadline - System.currentTimeMillis()
                    if (remaining <= 0) break
                    batch += withTimeoutOrNull(remaining) { channel.receive() } ?: break
                }
                flush(batch)
                batch.clear()
            }
        }
    }

    private suspend fun flush(batch: List<ConnectionEntity>) {
        // A failed batch is dropped (history is disposable, e.g. while a VACUUM
        // holds the lock), but never silently: a persistent failure would otherwise
        // leave every history screen empty with no trace.
        try {
            dao.insertAll(batch)
        } catch (e: Exception) {
            if (failedBatches++ % 100 == 0) {
                Log.w(TAG, "Dropped ${batch.size} connection rows (failure #$failedBatches)", e)
            }
        }
    }

    fun log(entity: ConnectionEntity) { channel.trySend(entity) }

    companion object {
        private const val TAG              = "ConnectionLogger"
        private const val BATCH_SIZE       = 50
        private const val FLUSH_INTERVAL_MS = 500L
    }
}
