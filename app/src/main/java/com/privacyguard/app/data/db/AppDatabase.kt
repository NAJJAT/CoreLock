package com.privacyguard.app.data.db

import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
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
        PayloadLogEntity::class,
        TlsAlertEntity::class,
    ],
    version = 3,
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
    abstract fun payloadLogDao(): PayloadLogDao
    abstract fun tlsAlertDao(): TlsAlertDao

    companion object {
        const val DATABASE_NAME = "privacyguard.db"

        private val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE rules ADD COLUMN matchBackground INTEGER")
            }
        }

        @Volatile
        private var INSTANCE: AppDatabase? = null

        fun getInstance(context: Context): AppDatabase {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    DATABASE_NAME
                ).addMigrations(MIGRATION_2_3).fallbackToDestructiveMigration().build().also {
                    INSTANCE = it
                }
            }
        }
    }
}
