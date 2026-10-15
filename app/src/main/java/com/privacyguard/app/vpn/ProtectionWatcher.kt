package com.privacyguard.app.vpn

import android.app.Service
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Binder
import android.os.IBinder
import android.os.Parcel
import android.os.Process
import android.util.Log

/**
 * Notices at once when the VPN process dies (crash, low-memory kill), with no
 * periodic wakeups while protection is healthy (V-18).
 *
 * [ProtectionWatcherService] runs in its own `:watchdog` process. While protection
 * starts or runs, the VPN service binds it, which keeps that process as important
 * as the foreground service, and hands it a token [Binder]. The watcher links to the
 * token's death: when the VPN process dies, the binder driver calls
 * [android.os.IBinder.DeathRecipient.binderDied] in the watcher within milliseconds,
 * and [ProtectionWatchdog.onProtectionProcessDied] arms one exact alarm that starts
 * protection again (or posts "Protection stopped" when it cannot).
 *
 * The `:watchdog` process does nothing else: PrivacyGuardApplication skips its
 * setup there (no database, SQLCipher, Keystore, blocklists or workers), and this
 * path must not touch SettingsPreferences, which unwraps Keystore secrets.
 */
object ProtectionWatcher {
    private const val TAG = "ProtectionWatcher"
    internal const val WATCH = IBinder.FIRST_CALL_TRANSACTION
    internal const val DISARM = IBinder.FIRST_CALL_TRANSACTION + 1

    // ── Watcher side (in :watchdog) ─────────────────────────────────────────

    private val lock = Any()
    private var watched: IBinder? = null
    private var recipient: IBinder.DeathRecipient? = null

    internal fun watch(context: Context, token: IBinder) = synchronized(lock) {
        unwatchLocked()
        val r = IBinder.DeathRecipient {
            val wasWatched = synchronized(lock) {
                (watched === token).also { if (it) { watched = null; recipient = null } }
            }
            if (wasWatched) {
                Log.w(TAG, "VPN process died")
                ProtectionWatchdog.onProtectionProcessDied(context)
            }
        }
        try {
            token.linkToDeath(r, 0)
            watched = token
            recipient = r
        } catch (e: android.os.RemoteException) {
            // Already dead between bind and this call.
            ProtectionWatchdog.onProtectionProcessDied(context)
        }
    }

    /** A deliberate stop: the coming death of the binding is not a crash. */
    internal fun disarm() = synchronized(lock) { unwatchLocked() }

    internal fun isWatching(): Boolean = synchronized(lock) { watched != null }

    private fun unwatchLocked() {
        val w = watched
        val r = recipient
        if (w != null && r != null) runCatching { w.unlinkToDeath(r, 0) }
        watched = null
        recipient = null
    }

    // ── VPN side (in the main process) ──────────────────────────────────────

    /** Held by the VPN service from start to stop. */
    class Client(private val context: Context) : ServiceConnection {
        /** Lives in this process: it dies with it, which is what the watcher waits for. */
        private val token = Binder()
        @Volatile private var remote: IBinder? = null
        private var bound = false

        fun bind() {
            if (bound) return
            bound = runCatching {
                context.bindService(Intent(context, ProtectionWatcherService::class.java), this, Context.BIND_AUTO_CREATE)
            }.getOrDefault(false)
            if (!bound) Log.w(TAG, "Could not bind the watcher")
        }

        /** Disarm first (synchronously), then unbind: a stop must never look like a crash. */
        fun stop() {
            send(DISARM, oneway = false)
            if (bound) runCatching { context.unbindService(this) }
            bound = false
            remote = null
        }

        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            remote = service
            send(WATCH, oneway = true)
        }

        // The watcher process died; BIND_AUTO_CREATE starts it again and
        // onServiceConnected hands it the token once more.
        override fun onServiceDisconnected(name: ComponentName?) {
            remote = null
        }

        private fun send(code: Int, oneway: Boolean) {
            val r = remote ?: return
            val data = Parcel.obtain()
            try {
                if (code == WATCH) data.writeStrongBinder(token)
                r.transact(code, data, null, if (oneway) IBinder.FLAG_ONEWAY else 0)
            } catch (e: android.os.RemoteException) {
                Log.w(TAG, "watcher call $code failed")
            } finally {
                data.recycle()
            }
        }
    }
}

/** Not exported; in `android:process=":watchdog"` (see the manifest). */
class ProtectionWatcherService : Service() {
    override fun onBind(intent: Intent): IBinder = WatcherBinder(applicationContext)
}

internal class WatcherBinder(private val context: Context) : Binder() {
    override fun onTransact(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean {
        // Only our own processes: the service is not exported, this is the second lock.
        if (Binder.getCallingUid() != Process.myUid()) return false
        return when (code) {
            ProtectionWatcher.WATCH -> {
                val token = data.readStrongBinder() ?: return false
                ProtectionWatcher.watch(context, token)
                true
            }
            ProtectionWatcher.DISARM -> {
                ProtectionWatcher.disarm()
                true
            }
            else -> super.onTransact(code, data, reply, flags)
        }
    }
}
