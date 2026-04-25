package com.privacyguard.app.core.apk

import android.content.Context
import android.content.pm.PackageManager
import com.privacyguard.app.core.tracker.TrackerDatabase
import com.privacyguard.app.core.tracker.TrackerEntry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.zip.ZipFile

object ApkScanner {

    suspend fun scanPackage(context: Context, packageName: String): List<TrackerEntry> =
        withContext(Dispatchers.IO) {
            try {
                val info = context.packageManager
                    .getApplicationInfo(packageName, PackageManager.GET_META_DATA)
                scanApkFile(info.sourceDir)
            } catch (_: Exception) {
                emptyList()
            }
        }

    fun scanApkFile(apkPath: String): List<TrackerEntry> {
        val found = mutableSetOf<TrackerEntry>()
        runCatching {
            ZipFile(File(apkPath)).use { zip ->
                val dexEntries = zip.entries().asSequence()
                    .filter { it.name.matches(Regex("classes\\d*\\.dex")) }
                for (entry in dexEntries) {
                    zip.getInputStream(entry).use { stream ->
                        val bytes = stream.readBytes()
                        found += scanDexBytes(bytes)
                    }
                }
            }
        }
        return found.toList()
    }

    private fun scanDexBytes(dex: ByteArray): List<TrackerEntry> {
        // DEX class names are stored as MUTF-8 strings prefixed with 'L' and using '/' separators.
        // We do a single-pass scan for known tracker class name prefixes.
        val raw = String(dex, Charsets.ISO_8859_1)
        return TrackerDatabase.all.filter { tracker ->
            tracker.classPatterns.any { pattern ->
                // Convert "com.google.firebase.analytics" → "Lcom/google/firebase/analytics"
                val dexPrefix = "L" + pattern.replace('.', '/')
                raw.contains(dexPrefix)
            }
        }
    }

    suspend fun scanAllInstalledApps(context: Context): Map<String, List<TrackerEntry>> =
        withContext(Dispatchers.IO) {
            val pm = context.packageManager
            val apps = pm.getInstalledApplications(PackageManager.GET_META_DATA)
            buildMap {
                for (app in apps) {
                    val trackers = try { scanApkFile(app.sourceDir) } catch (_: Exception) { emptyList() }
                    if (trackers.isNotEmpty()) put(app.packageName, trackers)
                }
            }
        }
}
