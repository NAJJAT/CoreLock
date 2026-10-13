package com.privacyguard.vpn.firewall

import java.util.concurrent.ConcurrentHashMap

/**
 * Maps Android app UIDs to package names and vice-versa.
 *
 * In the VPN packet pipeline, we receive UID annotations from the Linux kernel
 * via [android.net.VpnService] (socket protect / ownership tracking). This class
 * converts those numeric UIDs into human-readable package names that the
 * [com.privacyguard.core.filter.FilterEngine] can match against named rules.
 *
 * Caches lookups so the hot path (per-packet) never calls into PackageManager.
 * The cache is invalidated whenever packages are installed/removed (register a
 * [android.content.BroadcastReceiver] for ACTION_PACKAGE_ADDED / REMOVED and
 * call [invalidate]).
 *
 * NOTE: This class is Android-aware (it uses Android API types in the constructor
 * parameter), but the core filter logic stays pure-Kotlin in
 * [com.privacyguard.core.filter.FilterEngine].
 */
class AppFilter(
    /**
     * Callback used to resolve a UID to a package name.
     * In production, pass `{ uid -> context.packageManager.getNameForUid(uid) }`.
     * In tests, pass a lambda that returns predictable values.
     */
    private val resolveUid: (Int) -> String?,
) {
    // ─────────────────────────────────────────────────────────────────────────
    // Cache
    // ─────────────────────────────────────────────────────────────────────────

    /** uid → package name cache. */
    private val uidToPackage = ConcurrentHashMap<Int, String>(64)

    /** package name → uid reverse-lookup cache. */
    private val packageToUid = ConcurrentHashMap<String, Int>(64)

    // ─────────────────────────────────────────────────────────────────────────
    // Lookup
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Returns the primary package name for [uid], or null if the UID is unknown.
     *
     * Results are cached in-memory after the first lookup so that PackageManager
     * is only queried once per unique UID.
     */
    fun packageForUid(uid: Int): String? {
        if (uid < 0) return null
        if (isSystemUid(uid)) return SYSTEM_PACKAGE

        return uidToPackage.getOrPut(uid) {
            val name = resolveUid(uid) ?: return null
            // Build reverse mapping at the same time
            packageToUid[name] = uid
            name
        }
    }

    /**
     * Returns the cached UID for [packageName], or -1 if not yet resolved.
     * Use [packageForUid] to populate the cache first.
     */
    fun uidForPackage(packageName: String): Int =
        packageToUid[packageName] ?: -1

    /**
     * Pre-populates the cache with the given [uid] → [packageName] mapping.
     * Useful for seeding the cache from a known app list.
     */
    fun register(uid: Int, packageName: String) {
        uidToPackage[uid]          = packageName
        packageToUid[packageName]  = uid
    }

    /**
     * Removes a specific [uid] from the cache (e.g. after app uninstall).
     */
    fun invalidate(uid: Int) {
        val pkg = uidToPackage.remove(uid) ?: return
        packageToUid.remove(pkg)
    }

    /**
     * Removes a specific [packageName] from the cache.
     */
    fun invalidate(packageName: String) {
        val uid = packageToUid.remove(packageName) ?: return
        uidToPackage.remove(uid)
    }

    /**
     * Clears the entire UID/package cache.
     * Call when the package list may have changed significantly (e.g. device restore).
     */
    fun invalidateAll() {
        uidToPackage.clear()
        packageToUid.clear()
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Utility
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Returns true if [uid] belongs to a well-known Android system process
     * (kernel, init, netd, …) that should not be attributed to a user app.
     */
    fun isSystemUid(uid: Int): Boolean =
        uid == UID_ROOT     ||
        uid == UID_SYSTEM   ||
        uid == UID_RADIO    ||
        uid == UID_BLUETOOTH||
        uid == UID_DNS      ||
        uid in 1000..1999   // Android system UIDs range

    /**
     * Returns a snapshot of all currently cached mappings.
     */
    fun cachedMappings(): Map<Int, String> = uidToPackage.toMap()

    /** Number of distinct UIDs in the cache. */
    val cacheSize: Int get() = uidToPackage.size

    // ─────────────────────────────────────────────────────────────────────────
    // Companion — Constants
    // ─────────────────────────────────────────────────────────────────────────

    companion object {
        const val SYSTEM_PACKAGE = "android"

        // Android reserved UIDs
        const val UID_ROOT      = 0
        const val UID_SYSTEM    = 1000
        const val UID_RADIO     = 1001
        const val UID_BLUETOOTH = 1002
        const val UID_DNS       = 1004

        // UID reported when the kernel cannot associate a socket with a UID
        const val UID_UNKNOWN   = -1
    }
}