package com.privacyguard.app.core.blocklist

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.CopyOnWriteArrayList

object BlocklistManager {

    private val entries = CopyOnWriteArrayList<BlocklistEntry>()
    private val _size = MutableStateFlow(0)
    val size: StateFlow<Int> = _size.asStateFlow()

    private val defaultEntries = listOf(
        BlocklistEntry("doubleclick.net", BlocklistSource.BUILTIN, BlocklistCategory.ADVERTISING),
        BlocklistEntry("googleadservices.com", BlocklistSource.BUILTIN, BlocklistCategory.ADVERTISING),
        BlocklistEntry("googlesyndication.com", BlocklistSource.BUILTIN, BlocklistCategory.ADVERTISING),
        BlocklistEntry("google-analytics.com", BlocklistSource.BUILTIN, BlocklistCategory.ANALYTICS),
        BlocklistEntry("googletagmanager.com", BlocklistSource.BUILTIN, BlocklistCategory.ANALYTICS),
        BlocklistEntry("app-measurement.com", BlocklistSource.BUILTIN, BlocklistCategory.ANALYTICS),
        BlocklistEntry("facebook.com", BlocklistSource.BUILTIN, BlocklistCategory.SOCIAL),
        BlocklistEntry("facebook.net", BlocklistSource.BUILTIN, BlocklistCategory.TRACKING),
        BlocklistEntry("scorecardresearch.com", BlocklistSource.BUILTIN, BlocklistCategory.TRACKING),
        BlocklistEntry("taboola.com", BlocklistSource.BUILTIN, BlocklistCategory.ADVERTISING),
        BlocklistEntry("outbrain.com", BlocklistSource.BUILTIN, BlocklistCategory.ADVERTISING),
        BlocklistEntry("amazon-adsystem.com", BlocklistSource.BUILTIN, BlocklistCategory.ADVERTISING),
        BlocklistEntry("criteo.com", BlocklistSource.BUILTIN, BlocklistCategory.ADVERTISING)
    )

    @Volatile
    private var initialized = false

    fun initialize(context: Context) {
        if (initialized) return
        synchronized(this) {
            if (initialized) return
            entries.clear()
            entries.addAll(defaultEntries)
            _size.value = entries.size
            initialized = true
        }
    }

    fun isBlocked(domain: String): Boolean = findMatch(domain) != null

    fun findMatch(domain: String): BlocklistEntry? {
        val normalized = domain.trim().lowercase()
        if (normalized.isBlank()) return null
        return entries.firstOrNull { it.matches(normalized) }
    }

    fun addCustomDomain(domain: String, category: BlocklistCategory = BlocklistCategory.TRACKING) {
        val normalized = domain.trim().lowercase()
        if (normalized.isBlank()) return
        val existing = entries.any { it.domain.equals(normalized, ignoreCase = true) }
        if (existing) return
        entries.add(BlocklistEntry(normalized, BlocklistSource.CUSTOM, category))
        _size.value = entries.size
    }

    fun getSize(): Int = _size.value
}
