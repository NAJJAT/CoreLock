package com.privacyguard.app.core.app

import java.util.concurrent.ConcurrentHashMap

object AppDatabase {

    private val appsByUid = ConcurrentHashMap<Int, AppInfo>()

    fun put(appInfo: AppInfo) {
        appsByUid[appInfo.uid] = appInfo
    }

    fun get(uid: Int): AppInfo? = appsByUid[uid]

    fun getAll(): List<AppInfo> = appsByUid.values.toList()

    fun clear() {
        appsByUid.clear()
    }
}
