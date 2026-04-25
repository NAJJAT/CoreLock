package com.privacyguard.app.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

@Database(
    entities = [
        ConnectionEntity::class,
        RuleEntity::class,
        BlocklistEntity::class,
        AppStatsEntity::class,
        ConnectionProfileEntity::class,
        DnsAnomalyEntity::class,
        NetworkTrustEntity::class,
    ],
    version = 5,
    exportSchema = false,
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun connectionDao(): ConnectionDao
    abstract fun rulesDao(): RulesDao
    abstract fun blocklistDao(): BlocklistDao
    abstract fun appStatsDao(): AppStatsDao
    abstract fun connectionProfileDao(): ConnectionProfileDao
    abstract fun dnsAnomalyDao(): DnsAnomalyDao
    abstract fun networkTrustDao(): NetworkTrustDao

    companion object {
        const val DATABASE_NAME = "privacyguard_legacy.db"

        @Volatile private var INSTANCE: AppDatabase? = null

        fun getInstance(context: Context): AppDatabase {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    DATABASE_NAME,
                ).fallbackToDestructiveMigration(dropAllTables = true).build().also { INSTANCE = it }
            }
        }
    }
}
