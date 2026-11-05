package com.privacyguard.app.ui

import android.util.Log
import androidx.room.RoomDatabase
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.flow

/**
 * One signal now, then one after each change to [tables], at most one every
 * [minIntervalMs]. It replaces the 1.0 polling loops (CLAUDE.md §16.2 item 4): a screen
 * reads the database when something changed, not every few seconds. The engine writes in
 * batches (ConnectionLogger), and changes made during the wait are merged into one
 * signal, so a busy minute costs a few reads, never one per row.
 */
fun RoomDatabase.changesOf(vararg tables: String, minIntervalMs: Long = MIN_RELOAD_INTERVAL_MS): Flow<Unit> = flow {
    emit(Unit)
    invalidationTracker.createFlow(*tables, emitInitialState = false).conflate().collect {
        emit(Unit)
        delay(minIntervalMs)
    }
}

/** Fast enough to feel live, slow enough that a burst of writes is one read. */
const val MIN_RELOAD_INTERVAL_MS = 2_000L

/**
 * Runs [load] and says whether it worked. A failure is logged by type only (never a
 * name, address or package: CLAUDE.md §4.3) and returned, so the screen can say it
 * could not load instead of the error disappearing in an empty catch.
 */
suspend fun loadLogged(tag: String, load: suspend () -> Unit): Boolean = try {
    load()
    true
} catch (e: CancellationException) {
    throw e
} catch (e: Exception) {
    Log.w(tag, "Load failed: ${e.javaClass.simpleName}")
    false
}

/**
 * Reload on change, and say when a load failed: what each screen of 1.0 did with a
 * `while (true)` loop and an empty catch. [failed] stays true until a load works again;
 * the old numbers stay on screen meanwhile.
 */
class Reloader(private val tag: String) {
    private val retries = kotlinx.coroutines.flow.MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    private val _failed = kotlinx.coroutines.flow.MutableStateFlow(false)
    val failed: kotlinx.coroutines.flow.StateFlow<Boolean> = _failed

    /** "Try again": one more load now. */
    fun retry() { retries.tryEmit(Unit) }

    /** Loads on every signal of [changes] (the first one at once) and on every retry. */
    suspend fun runOn(changes: Flow<Unit>, load: suspend () -> Unit) {
        kotlinx.coroutines.flow.merge(changes, retries).conflate().collect {
            _failed.value = !loadLogged(tag, load)
        }
    }
}
