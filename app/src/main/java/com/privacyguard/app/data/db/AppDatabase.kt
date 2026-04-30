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
        DnsQueryEntity::class,
    ],
    version = 4,
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
    abstract fun dnsQueryDao(): DnsQueryDao

    companion object {
        const val DATABASE_NAME = "privacyguard.db"

        private val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE rules ADD COLUMN matchBackground INTEGER")
            }
        }

        private val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("""
                    CREATE TABLE IF NOT EXISTS dns_queries (
                        id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        timestamp INTEGER NOT NULL,
                        app_package TEXT NOT NULL,
                        app_name TEXT NOT NULL,
                        domain TEXT NOT NULL,
                        was_blocked INTEGER NOT NULL,
                        phone_was_idle INTEGER NOT NULL
                    )
                """)
                db.execSQL("CREATE INDEX IF NOT EXISTS index_dns_queries_timestamp ON dns_queries(timestamp)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_dns_queries_app_package ON dns_queries(app_package)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_dns_queries_domain ON dns_queries(domain)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_dns_queries_phone_was_idle ON dns_queries(phone_was_idle)")
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
                ).addMigrations(MIGRATION_2_3, MIGRATION_3_4).fallbackToDestructiveMigration().build().also {
                    INSTANCE = it
                }
            }
        }
    }
}
