package com.privacyguard.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    version = 2,
    entities = [
        // Existing entities (add your existing ones here)
        // ConnectionEntity::class,
        // BlocklistEntity::class,
        // DnsAnomalyEntity::class,
        // RuleEntity::class,

        // NEW: MITM Payload Entity
        PayloadLogEntity::class
    ]
)
abstract class AppDatabase : RoomDatabase() {

    // Existing DAOs
    // abstract fun connectionDao(): ConnectionDao
    // abstract fun blocklistDao(): BlocklistDao
    // abstract fun dnsAnomalyDao(): DnsAnomalyDao
    // abstract fun rulesDao(): RulesDao

    // NEW: MITM Payload DAO
    abstract fun payloadLogDao(): PayloadLogDao

    companion object {
        @Volatile
        private var INSTANCE: AppDatabase? = null

        // Migration from version 1 to 2 (adds payload_logs table)
        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(database: SupportSQLiteDatabase) {
                // Create payload_logs table
                database.execSQL("""
                    CREATE TABLE IF NOT EXISTS payload_logs (
                        id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        timestamp INTEGER NOT NULL,
                        sessionId TEXT NOT NULL,
                        direction TEXT NOT NULL,
                        ownerPackage TEXT,
                        sniHostname TEXT,
                        destinationIp TEXT NOT NULL,
                        destinationPort INTEGER NOT NULL,
                        protocol TEXT NOT NULL,
                        method TEXT,
                        urlPath TEXT,
                        headers TEXT NOT NULL,
                        body TEXT,
                        bodyEncoding TEXT NOT NULL,
                        sizeBytes INTEGER NOT NULL,
                        piiRedacted INTEGER NOT NULL,
                        isMitmSuccess INTEGER NOT NULL
                    )
                """)

                // Create indexes for performance
                database.execSQL("CREATE INDEX IF NOT EXISTS index_payload_logs_sessionId ON payload_logs(sessionId)")
                database.execSQL("CREATE INDEX IF NOT EXISTS index_payload_logs_timestamp ON payload_logs(timestamp)")
                database.execSQL("CREATE INDEX IF NOT EXISTS index_payload_logs_ownerPackage ON payload_logs(ownerPackage)")
            }
        }

        fun getInstance(context: Context): AppDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "privacyguard_database"
                )
                    .addMigrations(MIGRATION_1_2)
                    .build()
                INSTANCE = instance
                instance
            }
        }

        fun destroyInstance() {
            INSTANCE = null
        }
    }
}