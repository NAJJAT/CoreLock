package com.privacyguard.app.core.utils

import android.content.Context
import android.os.Build
import java.text.DecimalFormat

/**
 * The one way bytes are shown: decimal units (1 MB = 1,000,000 bytes, as Android's
 * own storage screens count), with the user's digits and unit names when a
 * Context is known. Every screen, report and notification uses this, so the same
 * traffic never reads "100 MB" in one place and "105 MB" in another.
 */
object ByteFormat {

    /** Set once by the Application; screens and workers can then format without one. */
    @Volatile var appContext: Context? = null

    fun format(bytes: Long): String {
        val ctx = appContext
        // Android's formatter is decimal from Android 8; on 7.x it counts 1024s.
        return if (ctx != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            android.text.format.Formatter.formatShortFileSize(ctx, bytes.coerceAtLeast(0))
        } else {
            plain(bytes)
        }
    }

    /** Decimal units with Latin unit names: logs, IT reports and tests. */
    fun plain(bytes: Long): String {
        val b = bytes.coerceAtLeast(0)
        val df = DecimalFormat("0.#")
        return when {
            b >= 1_000_000_000L -> "${df.format(b / 1e9)} GB"
            b >= 1_000_000L -> "${df.format(b / 1e6)} MB"
            b >= 1_000L -> "${df.format(b / 1e3)} kB"
            else -> "$b B"
        }
    }
}
