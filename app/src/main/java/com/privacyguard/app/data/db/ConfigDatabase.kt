package com.privacyguard.app.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.sqlite.db.SupportSQLiteDatabase
import com.privacyguard.app.core.security.DatabaseEncryption

/**
 * The user's own configuration: firewall rules and blocklist switches. Small and
 * irreplaceable, so unlike [AppDatabase] it never falls back to a destructive
 * migration: a missing migration must fail loudly rather than delete rules.
 */
@Database(
    entities = [
        RuleEntity::class,
        BlocklistToggleEntity::class,
    ],
    version = 2,
    exportSchema = true,
)
abstract class ConfigDatabase : RoomDatabase() {

    abstract fun rulesDao(): RulesDao
    abstract fun blocklistToggleDao(): BlocklistToggleDao

    companion object {
        const val DATABASE_NAME = "config.db"

        /** v2: an Allow rule may override malware/phishing lists after a warning. */
        val MIGRATION_1_2 = object : androidx.room.migration.Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE rules ADD COLUMN overridesSecurity INTEGER NOT NULL DEFAULT 0")
            }
        }

        @Volatile
        private var INSTANCE: ConfigDatabase? = null

        fun getInstance(context: Context): ConfigDatabase {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: build(context.applicationContext).also { INSTANCE = it }
            }
        }

        private fun build(context: Context): ConfigDatabase =
            Room.databaseBuilder(context, ConfigDatabase::class.java, DATABASE_NAME)
                .openHelperFactory(DatabaseEncryption.lazyOpenHelperFactory(context, DATABASE_NAME))
                .addMigrations(MIGRATION_1_2)
                .addCallback(object : Callback() {
                    // Runs before the first query is served, so nothing reads an empty
                    // rules table while an upgrade's rules are still in the old file.
                    override fun onOpen(db: SupportSQLiteDatabase) {
                        val history = AppDatabase.getInstance(context).openHelper.writableDatabase
                        LegacyConfigImporter.import(history, db)
                    }
                })
                .build()
    }
}
