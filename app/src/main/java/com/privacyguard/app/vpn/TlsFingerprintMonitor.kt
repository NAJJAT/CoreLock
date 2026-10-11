package com.privacyguard.app.vpn

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import android.webkit.WebView
import com.privacyguard.app.data.db.AppDatabase
import com.privacyguard.app.data.db.TlsAlertEntity
import com.privacyguard.app.data.db.TlsAppProfileEntity
import com.privacyguard.app.data.db.TlsBaselineDao
import com.privacyguard.app.data.db.TlsFingerprintEntity
import com.privacyguard.core.tls.AppEnvironment
import com.privacyguard.core.tls.BaselineVerdict
import com.privacyguard.core.tls.ClientHello
import com.privacyguard.core.tls.FingerprintOrigin
import com.privacyguard.core.tls.Ja4Fingerprinter
import com.privacyguard.core.tls.KnownFingerprint
import com.privacyguard.core.tls.TlsAppProfile
import com.privacyguard.core.tls.TlsBaselineEngine
import com.privacyguard.core.tls.TlsBaselineStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * Feeds each app's ClientHello into the per-app [TlsBaselineEngine] and raises a
 * plain-language alert when an app uses a fingerprint it has never used before
 * and was not updated. Passive: works on the handshake bytes only.
 */
class TlsFingerprintMonitor(
    private val context: Context,
    private val db: AppDatabase,
    private val scope: CoroutineScope,
    private val onAlert: (appLabel: String, packageName: String, sni: String?) -> Unit,
    private val labelFor: (uid: Int, packageName: String) -> String,
) {
    private val engine = TlsBaselineEngine(RoomTlsBaselineStore(db.tlsBaselineDao()))

    // Platform part of the environment signature. Recomputed at most every 10 min:
    // a WebView or Conscrypt update lands mid-session rarely, and this runs per new fingerprint.
    @Volatile private var platformSig: Pair<Long, String>? = null

    /** Called from the TCP forwarder thread with the complete first TLS record. */
    fun onClientHello(uid: Int, packageName: String?, hello: ClientHello) {
        // Without an owner there is no baseline to compare against.
        if (uid < 0 || packageName.isNullOrBlank() || packageName == context.packageName) return
        val ja4 = Ja4Fingerprinter.ja4(hello)
        if (engine.isCachedKnown(uid, packageName, ja4)) return
        val ja3 = hello.ja3Hash()
        scope.launch {
            try {
                val verdict = engine.observe(uid, packageName, ja4, ja3, hello.sni, environmentFor(packageName))
                if (verdict is BaselineVerdict.Alert) raise(verdict.fingerprint)
            } catch (e: Exception) {
                Log.w(TAG, "TLS baseline update failed for $packageName: ${e.message}")
            }
        }
    }

    private suspend fun raise(fp: KnownFingerprint) {
        db.tlsAlertDao().insert(
            TlsAlertEntity(
                timestamp = fp.firstSeen,
                alertType = TlsAlertEntity.TYPE_TLS_FP_CHANGE,
                hash = fp.ja4,
                ja3String = fp.ja3,
                category = "TLS_BASELINE",
                severity = 7,
                sni = fp.firstSni,
                packageName = fp.packageName,
            )
        )
        onAlert(labelFor(fp.uid, fp.packageName), fp.packageName, fp.firstSni)
    }

    private fun environmentFor(packageName: String): AppEnvironment {
        val versionCode = runCatching {
            val info = context.packageManager.getPackageInfo(packageName, 0)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) info.longVersionCode
            else @Suppress("DEPRECATION") info.versionCode.toLong()
        }.getOrDefault(-1L)
        return AppEnvironment(versionCode, "$versionCode|${platformSignature()}")
    }

    /**
     * OS build, WebView and Conscrypt each ship TLS code that apps use without
     * their own versionCode changing; updating any of them counts as an update.
     * Google Play services is left out on purpose: it updates weekly, and treating
     * that as an update would keep the alert window shut most of the time.
     */
    private fun platformSignature(): String {
        val now = System.currentTimeMillis()
        platformSig?.let { (at, sig) -> if (now - at < 10 * 60_000L) return sig }
        val webView = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
            runCatching { WebView.getCurrentWebViewPackage()?.let { "${it.packageName}:${it.longVersionCodeCompat()}" } }.getOrNull()
            else null
        val conscrypt = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) runCatching {
            context.packageManager.getPackageInfo("com.google.android.conscrypt", PackageManager.MATCH_APEX)
                .longVersionCodeCompat().toString()
        }.getOrNull() else null
        val sig = "${Build.FINGERPRINT}|wv=$webView|cs=$conscrypt"
        platformSig = now to sig
        return sig
    }

    private fun android.content.pm.PackageInfo.longVersionCodeCompat(): Long =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) longVersionCode
        else @Suppress("DEPRECATION") versionCode.toLong()

    private companion object { const val TAG = "TlsFingerprintMonitor" }
}

internal class RoomTlsBaselineStore(private val dao: TlsBaselineDao) : TlsBaselineStore {
    override suspend fun profile(uid: Int, packageName: String) =
        dao.profile(uid, packageName)?.let {
            TlsAppProfile(it.uid, it.packageName, it.learningStartedAt, it.envSignature, it.versionCode, it.lastUpdateAt)
        }

    override suspend fun upsertProfile(profile: TlsAppProfile) = dao.upsertProfile(
        TlsAppProfileEntity(
            profile.uid, profile.packageName, profile.learningStartedAt,
            profile.envSignature, profile.versionCode, profile.lastUpdateAt,
        )
    )

    override suspend fun isKnown(uid: Int, packageName: String, ja4: String) = dao.isKnown(uid, packageName, ja4)

    override suspend fun insertFingerprint(fp: KnownFingerprint) = dao.insertFingerprint(
        TlsFingerprintEntity(
            uid = fp.uid, packageName = fp.packageName, ja4 = fp.ja4, ja3 = fp.ja3,
            firstSni = fp.firstSni, firstSeen = fp.firstSeen, lastSeen = fp.firstSeen,
            seenCount = 1, origin = fp.origin.name, versionCode = fp.versionCode,
        )
    )

    override suspend fun touch(uid: Int, packageName: String, ja4: String, now: Long) =
        dao.touch(uid, packageName, ja4, now)
}

/** Kept here so UI code can parse stored origins without importing core internals. */
fun TlsFingerprintEntity.originEnum(): FingerprintOrigin =
    runCatching { FingerprintOrigin.valueOf(origin) }.getOrDefault(FingerprintOrigin.BASELINE)
