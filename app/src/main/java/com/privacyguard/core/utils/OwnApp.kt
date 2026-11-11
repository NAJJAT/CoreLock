package com.privacyguard.core.utils

import com.privacyguard.app.BuildConfig

/**
 * Recognises CoreLock's own traffic, which History, Connections, the Home count, payload
 * capture and the DNS anomaly checks leave out. By the exact application ID (or, where
 * one is known, the UID): any app can choose a package name that merely starts like ours,
 * and a prefix check hid that app from the user.
 */
object OwnApp {
    fun isOwnPackage(pkg: String?): Boolean = pkg == BuildConfig.APPLICATION_ID
}
