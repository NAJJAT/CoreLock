package com.privacyguard.app.data.db

import androidx.room.Entity

/**
 * A blocklist source or category the user switched off. Lives in config.db so the
 * choice survives list updates (which rewrite the downloaded rows) and backups;
 * the blocklist cache's isEnabled column is derived from these rows.
 */
@Entity(tableName = "blocklist_toggles", primaryKeys = ["scope", "name"])
data class BlocklistToggleEntity(
    val scope: String,
    val name: String,
    val enabled: Boolean,
) {
    companion object {
        const val SCOPE_SOURCE = "SOURCE"
        const val SCOPE_CATEGORY = "CATEGORY"
    }
}
