package com.privacyguard.app.data.db

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "dns_queries",
    indices = [
        Index("timestamp"),
        Index("app_package"),
        Index("domain"),
        Index("phone_was_idle"),
    ],
)
data class DnsQueryEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val timestamp: Long,
    @ColumnInfo(name = "app_package")
    val appPackage: String,
    @ColumnInfo(name = "app_name")
    val appName: String,
    val domain: String,
    @ColumnInfo(name = "was_blocked")
    val wasBlocked: Boolean,
    @ColumnInfo(name = "phone_was_idle")
    val phoneWasIdle: Boolean,
)

