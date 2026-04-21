/**
 * AppDatabase.kt
 * 
 * Room Database for PrivacyGuard
 * 
 * What it does:
 * =============
 * Manages local storage for:
 * - Connection history (for statistics)
 * - Firewall rules (user-defined)
 * - Blocklist entries (from external sources)
 * - App statistics (data usage per app)
 * 
 * Why Room?
 * =========
 * - Type-safe SQL queries
 * - Compile-time validation
 * - Built-in coroutine support
 * - Migration management
 * 
 * @author PrivacyGuard Engineering Team
 * @since 1.0.0
 */

package com.privacyguard.app.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

// ============================================================
// Database Version History
// ============================================================

/**
 * Version 1: Initial schema
 * - connections table
 * - rules table
 * - blocklist table
 * - app_stats table
 */

@Database(
    entities = [
        ConnectionEntity::class,
        RuleEntity::class,
        BlocklistEntity::class,
        AppStatsEntity::class
    ],
    version = 1,
    exportSchema = true
)
@TypeConverters(Converters::class)
abstract class AppDatabase : RoomDatabase() {
    
    abstract fun connectionDao(): ConnectionDao
    abstract fun rulesDao(): RulesDao
    abstract fun blocklistDao(): BlocklistDao
    abstract fun appStatsDao(): AppStatsDao
    
    companion object {
        private const val DATABASE_NAME = "privacyguard.db"
        
        @Volatile
        private var INSTANCE: AppDatabase? = null
        
        /**
         * Gets singleton instance of the database
         */
        fun getInstance(context: Context): AppDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    DATABASE_NAME
                )
                .addCallback(DatabaseCallback())
                .build()
                INSTANCE = instance
                instance
            }
        }
        
        /**
         * Database migration callbacks
         */
        private class DatabaseCallback : RoomDatabase.Callback() {
            override fun onCreate(db: SupportSQLiteDatabase) {
                super.onCreate(db)
                // Initial data insertion can be done here
            }
            
            override fun onOpen(db: SupportSQLiteDatabase) {
                super.onOpen(db)
                // Enable foreign key constraints
                db.execSQL("PRAGMA foreign_keys = ON")
            }
        }
    }
}

// ============================================================
// Type Converters
// ============================================================

/**
 * Converts between database types and Kotlin types
 */
class Converters {
    
    @androidx.room.TypeConverter
    fun fromTimestamp(value: Long?): Long? = value
    
    @androidx.room.TypeConverter
    fun dateToTimestamp(date: Long?): Long? = date
    
    @androidx.room.TypeConverter
    fun fromStringList(value: String): List<String> {
        return if (value.isEmpty()) emptyList() else value.split(",")
    }
    
    @androidx.room.TypeConverter
    fun fromStringList(list: List<String>): String {
        return list.joinToString(",")
    }
    
    @androidx.room.TypeConverter
    fun fromBlocklistSource(source: BlocklistSource): String {
        return source.name
    }
    
    @androidx.room.TypeConverter
    fun toBlocklistSource(source: String): BlocklistSource {
        return try {
            BlocklistSource.valueOf(source)
        } catch (e: IllegalArgumentException) {
            BlocklistSource.CUSTOM
        }
    }
    
    @androidx.room.TypeConverter
    fun fromRuleAction(action: RuleAction): String {
        return action.name
    }
    
    @androidx.room.TypeConverter
    fun toRuleAction(action: String): RuleAction {
        return RuleAction.valueOf(action)
    }
    
    @androidx.room.TypeConverter
    fun fromRuleType(type: RuleType): String {
        return type.name
    }
    
    @androidx.room.TypeConverter
    fun toRuleType(type: String): RuleType {
        return RuleType.valueOf(type)
    }
}

// ============================================================
// Entity Definitions
// ============================================================

/**
 * Connection history entity
 */
@Entity(
    tableName = "connections",
    indices = [
        Index(value = ["timestamp"]),
        Index(value = ["app_uid"]),
        Index(value = ["destination_ip"])
    ]
)
data class ConnectionEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val timestamp: Long = System.currentTimeMillis(),
    val appUid: Int,
    val appName: String,
    val destinationIp: String,
    val destinationPort: Int,
    val protocol: String,  // "TCP" or "UDP"
    val domain: String? = null,
    val bytesSent: Long = 0,
    val bytesReceived: Long = 0,
    val wasBlocked: Boolean = false,
    val blockReason: String? = null
)

/**
 * Firewall rule entity
 */
@Entity(
    tableName = "rules",
    indices = [Index(value = ["type", "value"], unique = true)]
)
data class RuleEntity(
    @PrimaryKey
    val id: String,
    val type: String,        // "APP", "DOMAIN", "IP"
    val value: String,       // Package name, domain, or IP
    val action: String,      // "ALLOW" or "BLOCK"
    val enabled: Boolean = true,
    val priority: Int = 50,
    val description: String = "",
    val createdAt: Long = System.currentTimeMillis(),
    val lastModified: Long = System.currentTimeMillis(),
    val hitCount: Long = 0,
    val lastHit: Long = 0
)

/**
 * Blocklist entry entity
 */
@Entity(
    tableName = "blocklist",
    indices = [Index(value = ["domain"], unique = true)]
)
data class BlocklistEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val domain: String,
    val source: String,      // "STEVENBLACK", "EASYLIST", etc.
    val category: String,    // "ADVERTISING", "ANALYTICS", etc.
    val lastUpdated: Long = System.currentTimeMillis()
)

/**
 * App statistics entity
 */
@Entity(
    tableName = "app_stats",
    primaryKeys = ["appUid", "date"]
)
data class AppStatsEntity(
    val appUid: Int,
    val date: String,        // YYYY-MM-DD
    val appName: String,
    val bytesSent: Long = 0,
    val bytesReceived: Long = 0,
    val packetsSent: Long = 0,
    val packetsReceived: Long = 0,
    val blockedCount: Long = 0
)