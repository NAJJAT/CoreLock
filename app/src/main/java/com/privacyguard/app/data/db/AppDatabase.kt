package com.privacyguard.app.data.db

import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import android.content.Context

// ADD THESE IMPORTS
import com.privacyguard.app.data.db.PayloadLogDao
import com.privacyguard.app.data.db.PayloadLogEntity

@Database(
    entities = [
        ConnectionEntity::class,
        RuleEntity::class,
        BlocklistEntity::class,
        ConnectionProfileEntity::class,
        DnsAnomalyEntity::class,
        NetworkTrustEntity::class,
        AppStatsEntity::class,
        // ADDED
        PayloadLogEntity::class,
    ],
    version = 1,
    exportSchema = false,
)
abstract class AppDatabase : RoomDatabase() {

    abstract fun connectionDao(): ConnectionDao
    abstract fun rulesDao(): RulesDao
    abstract fun blocklistDao(): BlocklistDao
    abstract fun connectionProfileDao(): ConnectionProfileDao
    abstract fun dnsAnomalyDao(): DnsAnomalyDao
    abstract fun networkTrustDao(): NetworkTrustDao
    abstract fun appStatsDao(): AppStatsDao

    // ADDED - MITM Payload DAO
    abstract fun payloadLogDao(): PayloadLogDao

    companion object {
        const val DATABASE_NAME = "privacyguard.db"

        @Volatile
        private var INSTANCE: AppDatabase? = null

        fun getInstance(context: Context): AppDatabase {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    DATABASE_NAME
                ).fallbackToDestructiveMigration().build().also {
                    INSTANCE = it
                }
            }
        }
    }
}