package com.privacyguard.app.`data`.db

import androidx.room.InvalidationTracker
import androidx.room.RoomOpenDelegate
import androidx.room.migration.AutoMigrationSpec
import androidx.room.migration.Migration
import androidx.room.util.TableInfo
import androidx.room.util.TableInfo.Companion.read
import androidx.room.util.dropFtsSyncTriggers
import androidx.sqlite.SQLiteConnection
import androidx.sqlite.execSQL
import javax.`annotation`.processing.Generated
import kotlin.Lazy
import kotlin.String
import kotlin.Suppress
import kotlin.collections.List
import kotlin.collections.Map
import kotlin.collections.MutableList
import kotlin.collections.MutableMap
import kotlin.collections.MutableSet
import kotlin.collections.Set
import kotlin.collections.mutableListOf
import kotlin.collections.mutableMapOf
import kotlin.collections.mutableSetOf
import kotlin.reflect.KClass

@Generated(value = ["androidx.room.RoomProcessor"])
@Suppress(names = ["UNCHECKED_CAST", "DEPRECATION", "REDUNDANT_PROJECTION", "REMOVAL"])
public class AppDatabase_Impl : AppDatabase() {
  private val _connectionDao: Lazy<ConnectionDao> = lazy {
    ConnectionDao_Impl(this)
  }

  private val _rulesDao: Lazy<RulesDao> = lazy {
    RulesDao_Impl(this)
  }

  private val _blocklistDao: Lazy<BlocklistDao> = lazy {
    BlocklistDao_Impl(this)
  }

  private val _appStatsDao: Lazy<AppStatsDao> = lazy {
    AppStatsDao_Impl(this)
  }

  protected override fun createOpenDelegate(): RoomOpenDelegate {
    val _openDelegate: RoomOpenDelegate = object : RoomOpenDelegate(1, "03f23e1d3f38f6838589eb1f16f208ee", "0806e6582b7d978bcf61a969f7c4907d") {
      public override fun createAllTables(connection: SQLiteConnection) {
        connection.execSQL("CREATE TABLE IF NOT EXISTS `connections` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `appUid` INTEGER NOT NULL, `appName` TEXT NOT NULL, `domain` TEXT, `destinationIp` TEXT NOT NULL, `destinationPort` INTEGER NOT NULL, `protocol` TEXT NOT NULL, `wasBlocked` INTEGER NOT NULL, `bytesSent` INTEGER NOT NULL, `bytesReceived` INTEGER NOT NULL, `timestamp` INTEGER NOT NULL, `durationMs` INTEGER NOT NULL)")
        connection.execSQL("CREATE TABLE IF NOT EXISTS `rules` (`id` TEXT NOT NULL, `type` TEXT NOT NULL, `value` TEXT NOT NULL, `action` TEXT NOT NULL, `enabled` INTEGER NOT NULL, `priority` INTEGER NOT NULL, `description` TEXT NOT NULL, `createdAt` INTEGER NOT NULL, `lastModified` INTEGER NOT NULL, `hitCount` INTEGER NOT NULL, `lastHit` INTEGER, PRIMARY KEY(`id`))")
        connection.execSQL("CREATE TABLE IF NOT EXISTS `blocklist` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `domain` TEXT NOT NULL, `source` TEXT NOT NULL, `category` TEXT NOT NULL, `lastUpdated` INTEGER NOT NULL, `isEnabled` INTEGER NOT NULL)")
        connection.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS `index_blocklist_domain` ON `blocklist` (`domain`)")
        connection.execSQL("CREATE TABLE IF NOT EXISTS `app_stats` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `appUid` INTEGER NOT NULL, `appName` TEXT NOT NULL, `date` TEXT NOT NULL, `bytesSent` INTEGER NOT NULL, `bytesReceived` INTEGER NOT NULL, `packetsSent` INTEGER NOT NULL, `packetsReceived` INTEGER NOT NULL, `blockedCount` INTEGER NOT NULL)")
        connection.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS `index_app_stats_appUid_date` ON `app_stats` (`appUid`, `date`)")
        connection.execSQL("CREATE TABLE IF NOT EXISTS room_master_table (id INTEGER PRIMARY KEY,identity_hash TEXT)")
        connection.execSQL("INSERT OR REPLACE INTO room_master_table (id,identity_hash) VALUES(42, '03f23e1d3f38f6838589eb1f16f208ee')")
      }

      public override fun dropAllTables(connection: SQLiteConnection) {
        connection.execSQL("DROP TABLE IF EXISTS `connections`")
        connection.execSQL("DROP TABLE IF EXISTS `rules`")
        connection.execSQL("DROP TABLE IF EXISTS `blocklist`")
        connection.execSQL("DROP TABLE IF EXISTS `app_stats`")
      }

      public override fun onCreate(connection: SQLiteConnection) {
      }

      public override fun onOpen(connection: SQLiteConnection) {
        internalInitInvalidationTracker(connection)
      }

      public override fun onPreMigrate(connection: SQLiteConnection) {
        dropFtsSyncTriggers(connection)
      }

      public override fun onPostMigrate(connection: SQLiteConnection) {
      }

      public override fun onValidateSchema(connection: SQLiteConnection): RoomOpenDelegate.ValidationResult {
        val _columnsConnections: MutableMap<String, TableInfo.Column> = mutableMapOf()
        _columnsConnections.put("id", TableInfo.Column("id", "INTEGER", true, 1, null, TableInfo.CREATED_FROM_ENTITY))
        _columnsConnections.put("appUid", TableInfo.Column("appUid", "INTEGER", true, 0, null, TableInfo.CREATED_FROM_ENTITY))
        _columnsConnections.put("appName", TableInfo.Column("appName", "TEXT", true, 0, null, TableInfo.CREATED_FROM_ENTITY))
        _columnsConnections.put("domain", TableInfo.Column("domain", "TEXT", false, 0, null, TableInfo.CREATED_FROM_ENTITY))
        _columnsConnections.put("destinationIp", TableInfo.Column("destinationIp", "TEXT", true, 0, null, TableInfo.CREATED_FROM_ENTITY))
        _columnsConnections.put("destinationPort", TableInfo.Column("destinationPort", "INTEGER", true, 0, null, TableInfo.CREATED_FROM_ENTITY))
        _columnsConnections.put("protocol", TableInfo.Column("protocol", "TEXT", true, 0, null, TableInfo.CREATED_FROM_ENTITY))
        _columnsConnections.put("wasBlocked", TableInfo.Column("wasBlocked", "INTEGER", true, 0, null, TableInfo.CREATED_FROM_ENTITY))
        _columnsConnections.put("bytesSent", TableInfo.Column("bytesSent", "INTEGER", true, 0, null, TableInfo.CREATED_FROM_ENTITY))
        _columnsConnections.put("bytesReceived", TableInfo.Column("bytesReceived", "INTEGER", true, 0, null, TableInfo.CREATED_FROM_ENTITY))
        _columnsConnections.put("timestamp", TableInfo.Column("timestamp", "INTEGER", true, 0, null, TableInfo.CREATED_FROM_ENTITY))
        _columnsConnections.put("durationMs", TableInfo.Column("durationMs", "INTEGER", true, 0, null, TableInfo.CREATED_FROM_ENTITY))
        val _foreignKeysConnections: MutableSet<TableInfo.ForeignKey> = mutableSetOf()
        val _indicesConnections: MutableSet<TableInfo.Index> = mutableSetOf()
        val _infoConnections: TableInfo = TableInfo("connections", _columnsConnections, _foreignKeysConnections, _indicesConnections)
        val _existingConnections: TableInfo = read(connection, "connections")
        if (!_infoConnections.equals(_existingConnections)) {
          return RoomOpenDelegate.ValidationResult(false, """
              |connections(com.privacyguard.app.data.db.ConnectionEntity).
              | Expected:
              |""".trimMargin() + _infoConnections + """
              |
              | Found:
              |""".trimMargin() + _existingConnections)
        }
        val _columnsRules: MutableMap<String, TableInfo.Column> = mutableMapOf()
        _columnsRules.put("id", TableInfo.Column("id", "TEXT", true, 1, null, TableInfo.CREATED_FROM_ENTITY))
        _columnsRules.put("type", TableInfo.Column("type", "TEXT", true, 0, null, TableInfo.CREATED_FROM_ENTITY))
        _columnsRules.put("value", TableInfo.Column("value", "TEXT", true, 0, null, TableInfo.CREATED_FROM_ENTITY))
        _columnsRules.put("action", TableInfo.Column("action", "TEXT", true, 0, null, TableInfo.CREATED_FROM_ENTITY))
        _columnsRules.put("enabled", TableInfo.Column("enabled", "INTEGER", true, 0, null, TableInfo.CREATED_FROM_ENTITY))
        _columnsRules.put("priority", TableInfo.Column("priority", "INTEGER", true, 0, null, TableInfo.CREATED_FROM_ENTITY))
        _columnsRules.put("description", TableInfo.Column("description", "TEXT", true, 0, null, TableInfo.CREATED_FROM_ENTITY))
        _columnsRules.put("createdAt", TableInfo.Column("createdAt", "INTEGER", true, 0, null, TableInfo.CREATED_FROM_ENTITY))
        _columnsRules.put("lastModified", TableInfo.Column("lastModified", "INTEGER", true, 0, null, TableInfo.CREATED_FROM_ENTITY))
        _columnsRules.put("hitCount", TableInfo.Column("hitCount", "INTEGER", true, 0, null, TableInfo.CREATED_FROM_ENTITY))
        _columnsRules.put("lastHit", TableInfo.Column("lastHit", "INTEGER", false, 0, null, TableInfo.CREATED_FROM_ENTITY))
        val _foreignKeysRules: MutableSet<TableInfo.ForeignKey> = mutableSetOf()
        val _indicesRules: MutableSet<TableInfo.Index> = mutableSetOf()
        val _infoRules: TableInfo = TableInfo("rules", _columnsRules, _foreignKeysRules, _indicesRules)
        val _existingRules: TableInfo = read(connection, "rules")
        if (!_infoRules.equals(_existingRules)) {
          return RoomOpenDelegate.ValidationResult(false, """
              |rules(com.privacyguard.app.data.db.RuleEntity).
              | Expected:
              |""".trimMargin() + _infoRules + """
              |
              | Found:
              |""".trimMargin() + _existingRules)
        }
        val _columnsBlocklist: MutableMap<String, TableInfo.Column> = mutableMapOf()
        _columnsBlocklist.put("id", TableInfo.Column("id", "INTEGER", true, 1, null, TableInfo.CREATED_FROM_ENTITY))
        _columnsBlocklist.put("domain", TableInfo.Column("domain", "TEXT", true, 0, null, TableInfo.CREATED_FROM_ENTITY))
        _columnsBlocklist.put("source", TableInfo.Column("source", "TEXT", true, 0, null, TableInfo.CREATED_FROM_ENTITY))
        _columnsBlocklist.put("category", TableInfo.Column("category", "TEXT", true, 0, null, TableInfo.CREATED_FROM_ENTITY))
        _columnsBlocklist.put("lastUpdated", TableInfo.Column("lastUpdated", "INTEGER", true, 0, null, TableInfo.CREATED_FROM_ENTITY))
        _columnsBlocklist.put("isEnabled", TableInfo.Column("isEnabled", "INTEGER", true, 0, null, TableInfo.CREATED_FROM_ENTITY))
        val _foreignKeysBlocklist: MutableSet<TableInfo.ForeignKey> = mutableSetOf()
        val _indicesBlocklist: MutableSet<TableInfo.Index> = mutableSetOf()
        _indicesBlocklist.add(TableInfo.Index("index_blocklist_domain", true, listOf("domain"), listOf("ASC")))
        val _infoBlocklist: TableInfo = TableInfo("blocklist", _columnsBlocklist, _foreignKeysBlocklist, _indicesBlocklist)
        val _existingBlocklist: TableInfo = read(connection, "blocklist")
        if (!_infoBlocklist.equals(_existingBlocklist)) {
          return RoomOpenDelegate.ValidationResult(false, """
              |blocklist(com.privacyguard.app.data.db.BlocklistEntity).
              | Expected:
              |""".trimMargin() + _infoBlocklist + """
              |
              | Found:
              |""".trimMargin() + _existingBlocklist)
        }
        val _columnsAppStats: MutableMap<String, TableInfo.Column> = mutableMapOf()
        _columnsAppStats.put("id", TableInfo.Column("id", "INTEGER", true, 1, null, TableInfo.CREATED_FROM_ENTITY))
        _columnsAppStats.put("appUid", TableInfo.Column("appUid", "INTEGER", true, 0, null, TableInfo.CREATED_FROM_ENTITY))
        _columnsAppStats.put("appName", TableInfo.Column("appName", "TEXT", true, 0, null, TableInfo.CREATED_FROM_ENTITY))
        _columnsAppStats.put("date", TableInfo.Column("date", "TEXT", true, 0, null, TableInfo.CREATED_FROM_ENTITY))
        _columnsAppStats.put("bytesSent", TableInfo.Column("bytesSent", "INTEGER", true, 0, null, TableInfo.CREATED_FROM_ENTITY))
        _columnsAppStats.put("bytesReceived", TableInfo.Column("bytesReceived", "INTEGER", true, 0, null, TableInfo.CREATED_FROM_ENTITY))
        _columnsAppStats.put("packetsSent", TableInfo.Column("packetsSent", "INTEGER", true, 0, null, TableInfo.CREATED_FROM_ENTITY))
        _columnsAppStats.put("packetsReceived", TableInfo.Column("packetsReceived", "INTEGER", true, 0, null, TableInfo.CREATED_FROM_ENTITY))
        _columnsAppStats.put("blockedCount", TableInfo.Column("blockedCount", "INTEGER", true, 0, null, TableInfo.CREATED_FROM_ENTITY))
        val _foreignKeysAppStats: MutableSet<TableInfo.ForeignKey> = mutableSetOf()
        val _indicesAppStats: MutableSet<TableInfo.Index> = mutableSetOf()
        _indicesAppStats.add(TableInfo.Index("index_app_stats_appUid_date", true, listOf("appUid", "date"), listOf("ASC", "ASC")))
        val _infoAppStats: TableInfo = TableInfo("app_stats", _columnsAppStats, _foreignKeysAppStats, _indicesAppStats)
        val _existingAppStats: TableInfo = read(connection, "app_stats")
        if (!_infoAppStats.equals(_existingAppStats)) {
          return RoomOpenDelegate.ValidationResult(false, """
              |app_stats(com.privacyguard.app.data.db.AppStatsEntity).
              | Expected:
              |""".trimMargin() + _infoAppStats + """
              |
              | Found:
              |""".trimMargin() + _existingAppStats)
        }
        return RoomOpenDelegate.ValidationResult(true, null)
      }
    }
    return _openDelegate
  }

  protected override fun createInvalidationTracker(): InvalidationTracker {
    val _shadowTablesMap: MutableMap<String, String> = mutableMapOf()
    val _viewTables: MutableMap<String, Set<String>> = mutableMapOf()
    return InvalidationTracker(this, _shadowTablesMap, _viewTables, "connections", "rules", "blocklist", "app_stats")
  }

  public override fun clearAllTables() {
    super.performClear(false, "connections", "rules", "blocklist", "app_stats")
  }

  protected override fun getRequiredTypeConverterClasses(): Map<KClass<*>, List<KClass<*>>> {
    val _typeConvertersMap: MutableMap<KClass<*>, List<KClass<*>>> = mutableMapOf()
    _typeConvertersMap.put(ConnectionDao::class, ConnectionDao_Impl.getRequiredConverters())
    _typeConvertersMap.put(RulesDao::class, RulesDao_Impl.getRequiredConverters())
    _typeConvertersMap.put(BlocklistDao::class, BlocklistDao_Impl.getRequiredConverters())
    _typeConvertersMap.put(AppStatsDao::class, AppStatsDao_Impl.getRequiredConverters())
    return _typeConvertersMap
  }

  public override fun getRequiredAutoMigrationSpecClasses(): Set<KClass<out AutoMigrationSpec>> {
    val _autoMigrationSpecsSet: MutableSet<KClass<out AutoMigrationSpec>> = mutableSetOf()
    return _autoMigrationSpecsSet
  }

  public override fun createAutoMigrations(autoMigrationSpecs: Map<KClass<out AutoMigrationSpec>, AutoMigrationSpec>): List<Migration> {
    val _autoMigrations: MutableList<Migration> = mutableListOf()
    return _autoMigrations
  }

  public override fun connectionDao(): ConnectionDao = _connectionDao.value

  public override fun rulesDao(): RulesDao = _rulesDao.value

  public override fun blocklistDao(): BlocklistDao = _blocklistDao.value

  public override fun appStatsDao(): AppStatsDao = _appStatsDao.value
}
