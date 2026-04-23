package com.privacyguard.app.core.app

data class AppInfo(
    val uid:         Int,
    val packageName: String,
    val label:       String,
    val isSystem:    Boolean = false,
)
