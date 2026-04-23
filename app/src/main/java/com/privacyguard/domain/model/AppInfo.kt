package com.privacyguard.domain.model

data class AppInfo(
    val uid:         Int,
    val packageName: String,
    val label:       String,
    val isSystem:    Boolean,
) {
    companion object {
        fun unknown(uid: Int, packageName: String? = null): AppInfo = AppInfo(
            uid         = uid,
            packageName = packageName ?: "unknown",
            label       = "Unknown (uid=$uid)",
            isSystem    = false,
        )

        fun system(): AppInfo = AppInfo(
            uid         = 1000,
            packageName = "android",
            label       = "Android System",
            isSystem    = true,
        )
    }
}
