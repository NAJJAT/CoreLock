package com.privacyguard.app.data.db

import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import android.content.Context
import com.privacyguard.app.BuildConfig
import com.privacyguard.app.core.security.DatabaseEncryption

/**
 * History: connections, DNS queries, alerts, profiles, payload logs and the
 * downloaded blocklist cache. Large and disposable. The user's own configuration
 * lives in [ConfigDatabase].
 */
@Database(
    entities = [
        ConnectionEntity::class,
        BlocklistEntity::class,
        ConnectionProfileEntity::class,
        DnsAnomalyEntity::class,
        NetworkTrustEntity::class,
        AppStatsEntity::class,
        PayloadLogEntity::class,
        TlsAlertEntity::class,
        DnsQueryEntity::class,
        SensorEventEntity::class,
    ],
    version = 9,
    exportSchema = true,
)
abstract class AppDatabase : RoomDatabase() {

    abstract fun connectionDao(): ConnectionDao
    abstract fun blocklistDao(): BlocklistDao
    abstract fun connectionProfileDao(): ConnectionProfileDao
    abstract fun dnsAnomalyDao(): DnsAnomalyDao
    abstract fun networkTrustDao(): NetworkTrustDao
    abstract fun appStatsDao(): AppStatsDao
    abstract fun payloadLogDao(): PayloadLogDao
    abstract fun tlsAlertDao(): TlsAlertDao
    abstract fun dnsQueryDao(): DnsQueryDao
    abstract fun sensorEventDao(): SensorEventDao

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

        // Index names must match what Room generates for ConnectionEntity's indices,
        // or Room's schema check fails (see AppDatabaseMigrationTest).
        internal val MIGRATION_4_5 = object : Migration(4, 5) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_connections_timestamp` ON `connections` (`timestamp`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_connections_appUid_timestamp` ON `connections` (`appUid`, `timestamp`)")
            }
        }

        // Rules moved to config.db. The old table is deliberately left in place:
        // LegacyConfigImporter copies it on config.db's first open and only then
        // drops it, so a crash in between cannot lose the user's rules.
        internal val MIGRATION_5_6 = object : Migration(5, 6) {
            override fun migrate(db: SupportSQLiteDatabase) = Unit
        }

        // blocklist: unique per (domain, source) instead of per domain, so lists that
        // share a domain no longer overwrite each other's rows. Rows are kept.
        internal val MIGRATION_6_7 = object : Migration(6, 7) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("""
                    CREATE TABLE IF NOT EXISTS `blocklist_new` (
                        `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        `domain` TEXT NOT NULL, `source` TEXT NOT NULL, `category` TEXT NOT NULL,
                        `lastUpdated` INTEGER NOT NULL, `isEnabled` INTEGER NOT NULL)
                """)
                db.execSQL("""
                    INSERT INTO `blocklist_new` (id, domain, source, category, lastUpdated, isEnabled)
                    SELECT id, domain, source, category, lastUpdated, isEnabled FROM `blocklist`
                """)
                db.execSQL("DROP TABLE `blocklist`")
                db.execSQL("ALTER TABLE `blocklist_new` RENAME TO `blocklist`")
                db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS `index_blocklist_domain_source` ON `blocklist` (`domain`, `source`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_blocklist_source` ON `blocklist` (`source`)")
            }
        }

        // Measured timings: TCP connect time per connection, resolver time and first
        // answer per DNS query. Existing rows read as "not measured".
        internal val MIGRATION_7_8 = object : Migration(7, 8) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE `connections` ADD COLUMN `connectMs` INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE `dns_queries` ADD COLUMN `response_ms` INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE `dns_queries` ADD COLUMN `answer_ip` TEXT")
            }
        }

        // Camera & Mic Watch: one row per camera/microphone use. SQL copied from 9.json
        // so Room's schema check matches (see AppDatabaseMigrationTest).
        internal val MIGRATION_8_9 = object : Migration(8, 9) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `sensor_events` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                        "`sensor` TEXT NOT NULL, `startTime` INTEGER NOT NULL, `endTime` INTEGER, `source` TEXT NOT NULL, " +
                        "`packageName` TEXT, `confidence` TEXT NOT NULL, `attributionMethod` TEXT NOT NULL, " +
                        "`candidates` TEXT NOT NULL, `screenOn` INTEGER NOT NULL, `locked` INTEGER NOT NULL, " +
                        "`quietHours` INTEGER NOT NULL, `inCall` INTEGER NOT NULL, `openedRecently` INTEGER NOT NULL, " +
                        "`uploadBytes` INTEGER, `networkBurst` INTEGER NOT NULL, `severity` TEXT NOT NULL, " +
                        "`reason` TEXT NOT NULL, `userMarkedExpected` INTEGER NOT NULL DEFAULT 0)"
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_sensor_events_startTime` ON `sensor_events` (`startTime`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_sensor_events_packageName_startTime` ON `sensor_events` (`packageName`, `startTime`)")
            }
        }

        /**
         * Debug builds wipe on any schema mismatch so local iteration never gets stuck.
         * Release builds only wipe where no migration can exist (version 1, which
         * predates migrations, and downgrades); a missing migration for any other
         * version fails loudly instead of silently deleting the user's history.
         */
        private fun RoomDatabase.Builder<AppDatabase>.applyFallback() =
            if (BuildConfig.DEBUG) {
                fallbackToDestructiveMigration(dropAllTables = true)
            } else {
                fallbackToDestructiveMigrationFrom(dropAllTables = true, 1)
                    .fallbackToDestructiveMigrationOnDowngrade(dropAllTables = true)
            }

        @Volatile
        private var INSTANCE: AppDatabase? = null

        fun getInstance(context: Context): AppDatabase {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    DATABASE_NAME
                ).addMigrations(MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5, MIGRATION_5_6, MIGRATION_6_7, MIGRATION_7_8, MIGRATION_8_9)
                    .openHelperFactory(DatabaseEncryption.openHelperFactory(context, DATABASE_NAME))
                    .applyFallback()
                    .build().also {
                    INSTANCE = it
                }
            }
        }
    }
}
