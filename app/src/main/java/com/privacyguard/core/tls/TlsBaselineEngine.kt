package com.privacyguard.core.tls

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

/** Why a fingerprint is part of an app's baseline. */
enum class FingerprintOrigin { BASELINE, AFTER_UPDATE, ALERTED, USER_TRUSTED }

/**
 * Per-app learning state. [envSignature] changes when the app or the system TLS
 * stack it relies on is updated; see [TlsBaselineEngine.observe].
 */
data class TlsAppProfile(
    val uid: Int,
    val packageName: String,
    val learningStartedAt: Long,
    val envSignature: String,
    val versionCode: Long,
    val lastUpdateAt: Long?,
)

data class KnownFingerprint(
    val uid: Int,
    val packageName: String,
    val ja4: String,
    val ja3: String,
    val firstSni: String?,
    val firstSeen: Long,
    val origin: FingerprintOrigin,
    val versionCode: Long,
)

interface TlsBaselineStore {
    suspend fun profile(uid: Int, packageName: String): TlsAppProfile?
    suspend fun upsertProfile(profile: TlsAppProfile)
    suspend fun isKnown(uid: Int, packageName: String, ja4: String): Boolean
    suspend fun insertFingerprint(fp: KnownFingerprint)
    suspend fun touch(uid: Int, packageName: String, ja4: String, now: Long)
}

/**
 * What the app currently looks like on disk: its versionCode, plus a signature
 * over everything that can legitimately change its TLS fingerprint.
 */
data class AppEnvironment(val versionCode: Long, val envSignature: String)

sealed interface BaselineVerdict {
    /** Already in the baseline. */
    data object Known : BaselineVerdict
    /** New, accepted silently (learning period or recent update). */
    data class Learned(val origin: FingerprintOrigin) : BaselineVerdict
    /** New, outside learning, not explained by an update: alert the user. */
    data class Alert(val fingerprint: KnownFingerprint) : BaselineVerdict
}

/**
 * Learns which TLS fingerprints each app normally uses and flags the ones that
 * appear later without explanation.
 *
 * Rules:
 *  - The first [learningMs] after CoreLock first sees an app: every fingerprint is learned.
 *  - Within [updateGraceMs] after an update: new fingerprints are learned silently.
 *  - Otherwise a new fingerprint is recorded once and raises one alert.
 *
 * "Update" means the app's versionCode changed, or the OS build / WebView /
 * Conscrypt version changed: those swap the TLS library inside every app that
 * uses them, without the app's own versionCode moving.
 */
class TlsBaselineEngine(
    private val store: TlsBaselineStore,
    private val clock: () -> Long = System::currentTimeMillis,
    private val learningMs: Long = TimeUnit.DAYS.toMillis(7),
    private val updateGraceMs: Long = TimeUnit.HOURS.toMillis(24),
) {
    // Serialises check-then-insert, so two parallel connections carrying the same
    // new fingerprint raise one alert, not two.
    private val mutex = Mutex()

    // Fast path: most connections reuse a known fingerprint; skip the database.
    private val knownCache = ConcurrentHashMap.newKeySet<String>()

    fun isCachedKnown(uid: Int, packageName: String, ja4: String) =
        knownCache.contains(cacheKey(uid, packageName, ja4))

    suspend fun observe(
        uid: Int,
        packageName: String,
        ja4: String,
        ja3: String,
        sni: String?,
        environment: AppEnvironment,
    ): BaselineVerdict = mutex.withLock {
        val now = clock()
        val key = cacheKey(uid, packageName, ja4)

        var profile = store.profile(uid, packageName)
            ?: TlsAppProfile(uid, packageName, now, environment.envSignature, environment.versionCode, null)
                .also { store.upsertProfile(it) }
        if (profile.envSignature != environment.envSignature) {
            profile = profile.copy(
                envSignature = environment.envSignature,
                versionCode = environment.versionCode,
                lastUpdateAt = now,
            )
            store.upsertProfile(profile)
        }

        if (store.isKnown(uid, packageName, ja4)) {
            store.touch(uid, packageName, ja4, now)
            knownCache += key
            return@withLock BaselineVerdict.Known
        }

        val origin = when {
            now - profile.learningStartedAt < learningMs -> FingerprintOrigin.BASELINE
            profile.lastUpdateAt != null && now - profile.lastUpdateAt < updateGraceMs ->
                FingerprintOrigin.AFTER_UPDATE
            else -> FingerprintOrigin.ALERTED
        }
        val fp = KnownFingerprint(uid, packageName, ja4, ja3, sni, now, origin, environment.versionCode)
        // Stored even when alerting, so the same fingerprint never alerts twice.
        store.insertFingerprint(fp)
        knownCache += key

        if (origin == FingerprintOrigin.ALERTED) BaselineVerdict.Alert(fp)
        else BaselineVerdict.Learned(origin)
    }

    fun forget(uid: Int, packageName: String) {
        val prefix = "$uid|$packageName|"
        knownCache.removeAll { it.startsWith(prefix) }
    }

    private fun cacheKey(uid: Int, packageName: String, ja4: String) = "$uid|$packageName|$ja4"
}
