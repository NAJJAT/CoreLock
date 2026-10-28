package com.privacyguard.vpn

import android.util.Log
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.flow.collectLatest
import java.util.concurrent.atomic.AtomicInteger

/**
 * Keeps a failure on the packet path from killing the process. On Android an
 * uncaught exception on any thread, or in a coroutine without a handler (a
 * SupervisorJob does not catch it), ends the whole process, and with it protection:
 * on a test phone Android did not restart the service after such a crash.
 *
 *  - Coroutines: the failure is logged and counted; the coroutine is gone, the rest runs on.
 *  - The packet threads (TUN reader/writer, TCP/UDP selectors): the thread is lost,
 *    so [onWorkerDied] is told and the service rebuilds the tunnel.
 *  - Executor tasks: [guard] catches and counts.
 *
 * Logs name only the failure's class: messages can hold hosts or addresses.
 */
object WorkerGuard {
    private const val TAG = "WorkerGuard"

    /** Failures caught since the process started (Diagnostics, tests). */
    val failures = AtomicInteger()

    /** Set by the VPN service while it runs; called on the dying thread. */
    @Volatile var onWorkerDied: ((worker: String) -> Unit)? = null

    val coroutineHandler = CoroutineExceptionHandler { _, e ->
        failures.incrementAndGet()
        Log.e(TAG, "Coroutine failed: ${e.javaClass.simpleName}")
    }

    val threadHandler = Thread.UncaughtExceptionHandler { thread, e ->
        failures.incrementAndGet()
        Log.e(TAG, "Worker ${thread.name} died: ${e.javaClass.simpleName}")
        runCatching { onWorkerDied?.invoke(thread.name) }
    }

    /** A packet-path thread whose death is reported instead of ending the process. */
    fun thread(runnable: Runnable, name: String): Thread = Thread(runnable, name).also {
        it.isDaemon = true
        it.uncaughtExceptionHandler = threadHandler
    }

    /** Runs [block], counting instead of propagating an Exception (executor tasks). */
    inline fun guard(name: String, block: () -> Unit) {
        try {
            block()
        } catch (e: Exception) {
            failures.incrementAndGet()
            Log.e("WorkerGuard", "Task $name failed: ${e.javaClass.simpleName}")
        }
    }

    /**
     * Runs [apply] for every value of [flow] and never lets a failure end the
     * collection: a failed apply is counted and retried every [retryMs] until it
     * succeeds or a newer value replaces it. (A plain collect died on its first
     * exception, so later rule or level changes were saved but never applied.)
     */
    suspend fun <T> applyEach(
        flow: kotlinx.coroutines.flow.Flow<T>,
        name: String,
        retryMs: Long = 5_000L,
        apply: suspend (T) -> Unit,
    ) {
        flow.collectLatest { value ->
            while (true) {
                try {
                    apply(value)
                    return@collectLatest
                } catch (e: kotlinx.coroutines.CancellationException) {
                    throw e
                } catch (e: Exception) {
                    failures.incrementAndGet()
                    Log.w("WorkerGuard", "Applying $name failed, retrying: ${e.javaClass.simpleName}")
                    kotlinx.coroutines.delay(retryMs)
                }
            }
        }
    }
}

/**
 * At most [max] internal restarts within [windowMs]. A packet path that keeps
 * failing would otherwise rebuild the tunnel in a loop; past the budget,
 * protection stops and the user is told.
 */
class RestartBudget(private val max: Int = 3, private val windowMs: Long = 10 * 60_000L) {
    private val recent = ArrayDeque<Long>()

    @Synchronized
    fun tryAcquire(now: Long): Boolean {
        while (recent.isNotEmpty() && now - recent.first() >= windowMs) recent.removeFirst()
        if (recent.size >= max) return false
        recent.addLast(now)
        return true
    }
}
