package com.privacyguard.`data`.db

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

  private val _connectionProfileDao: Lazy<ConnectionProfileDao> = lazy {
    ConnectionProfileDao_Impl(this)
  }

  private val _dnsAnomalyDao: Lazy<DnsAnomalyDao> = lazy {
    DnsAnomalyDao_Impl(this)
  }

  protected override fun createOpenDelegate(): RoomOpenDelegate {
    val _openDelegate: RoomOpenDelegate = object : RoomOpenDelegate(2, "915fd82be9bde5fe706f072cf016d16e", "135b0bcf382ac1c8e300012a13ce32e1") {
      public override fun createAllTables(connection: SQLiteConnection) {
        connection.execSQL("CREATE TABLE IF NOT EXISTS `connections` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `timestamp` INTEGER NOT NULL, `protocol` INTEGER NOT NULL, `sourceIp` TEXT NOT NULL, `sourcePort` INTEGER NOT NULL, `destinationIp` TEXT NOT NULL, `destinationPort` INTEGER NOT NULL, `hostname` TEXT, `ownerUid` INTEGER NOT NULL, `ownerPackage` TEXT, `bytesOut` INTEGER NOT NULL, `bytesIn` INTEGER NOT NULL, `wasBlocked` INTEGER NOT NULL, `matchedRuleId` TEXT, `durationMs` INTEGER NOT NULL, `encryptionStatus` TEXT NOT NULL, `tlsVersion` TEXT, `sniHostname` TEXT, `wasBackground` INTEGER NOT NULL)")
        connection.execSQL("CREATE TABLE IF NOT EXISTS `rules` (`id` TEXT NOT NULL, `label` TEXT NOT NULL, `action` TEXT NOT NULL, `source` TEXT NOT NULL, `priority` INTEGER NOT NULL, `isEnabled` INTEGER NOT NULL, `matchUid` INTEGER, `matchPackage` TEXT, `matchDomain` TEXT, `matchIp` TEXT, `matchPort` INTEGER, `matchProtocol` TEXT, `matchEncryption` TEXT, `createdAt` INTEGER NOT NULL, PRIMARY KEY(`id`))")
        connection.execSQL("CREATE TABLE IF NOT EXISTS `blocklist` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `domain` TEXT NOT NULL, `category` TEXT NOT NULL, `listSource` TEXT NOT NULL, `updatedAt` INTEGER NOT NULL)")
        connection.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS `index_blocklist_domain` ON `blocklist` (`domain`)")
        connection.execSQL("CREATE TABLE IF NOT EXISTS `connection_profiles` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `packageName` TEXT NOT NULL, `hostname` TEXT NOT NULL, `destinationIp` TEXT NOT NULL, `destinationPort` INTEGER NOT NULL, `connectionCount` INTEGER NOT NULL, `totalBytesOut` INTEGER NOT NULL, `totalBytesIn` INTEGER NOT NULL, `avgBytesPerConnection` INTEGER NOT NULL, `firstSeen` INTEGER NOT NULL, `lastSeen` INTEGER NOT NULL, `avgIntervalMs` INTEGER NOT NULL, `minIntervalMs` INTEGER NOT NULL, `backgroundRatio` REAL NOT NULL, `hourlyDistribution` TEXT NOT NULL, `encryptionStatus` TEXT NOT NULL, `tlsVersion` TEXT, `sniHostname` TEXT, `riskScore` INTEGER NOT NULL, `riskSignalCodes` TEXT NOT NULL, `updatedAt` INTEGER NOT NULL)")
        connection.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS `index_connection_profiles_packageName_destinationIp_destinationPort` ON `connection_profiles` (`packageName`, `destinationIp`, `destinationPort`)")
        connection.execSQL("CREATE TABLE IF NOT EXISTS `dns_anomalies` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `timestamp` INTEGER NOT NULL, `packageName` TEXT NOT NULL, `domain` TEXT NOT NULL, `anomalyType` TEXT NOT NULL, `description` TEXT NOT NULL, `severity` INTEGER NOT NULL)")
        connection.execSQL("CREATE TABLE IF NOT EXISTS room_master_table (id INTEGER PRIMARY KEY,identity_hash TEXT)")
        connection.execSQL("INSERT OR REPLACE INTO room_master_table (id,identity_hash) VALUES(42, '915fd82be9bde5fe706f072cf016d16e')")
      }

      public override fun dropAllTables(connection: SQLiteConnection) {
        connection.execSQL("DROP TABLE IF EXISTS `connections`")
        connection.execSQL("DROP TABLE IF EXISTS `rules`")
        connection.execSQL("DROP TABLE IF EXISTS `blocklist`")
        connection.execSQL("DROP TABLE IF EXISTS `connection_profiles`")
        connection.execSQL("DROP TABLE IF EXISTS `dns_anomalies`")
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
        _columnsConnections.put("timestamp", TableInfo.Column("timestamp", "INTEGER", true, 0, null, TableInfo.CREATED_FROM_ENTITY))
        _columnsConnections.put("protocol", TableInfo.Column("protocol", "INTEGER", true, 0, null, TableInfo.CREATED_FROM_ENTITY))
        _columnsConnections.put("sourceIp", TableInfo.Column("sourceIp", "TEXT", true, 0, null, TableInfo.CREATED_FROM_ENTITY))
        _columnsConnections.put("sourcePort", TableInfo.Column("sourcePort", "INTEGER", true, 0, null, TableInfo.CREATED_FROM_ENTITY))
        _columnsConnections.put("destinationIp", TableInfo.Column("destinationIp", "TEXT", true, 0, null, TableInfo.CREATED_FROM_ENTITY))
        _columnsConnections.put("destinationPort", TableInfo.Column("destinationPort", "INTEGER", true, 0, null, TableInfo.CREATED_FROM_ENTITY))
        _columnsConnections.put("hostname", TableInfo.Column("hostname", "TEXT", false, 0, null, TableInfo.CREATED_FROM_ENTITY))
        _columnsConnections.put("ownerUid", TableInfo.Column("ownerUid", "INTEGER", true, 0, null, TableInfo.CREATED_FROM_ENTITY))
        _columnsConnections.put("ownerPackage", TableInfo.Column("ownerPackage", "TEXT", false, 0, null, TableInfo.CREATED_FROM_ENTITY))
        _columnsConnections.put("bytesOut", TableInfo.Column("bytesOut", "INTEGER", true, 0, null, TableInfo.CREATED_FROM_ENTITY))
        _columnsConnections.put("bytesIn", TableInfo.Column("bytesIn", "INTEGER", true, 0, null, TableInfo.CREATED_FROM_ENTITY))
        _columnsConnections.put("wasBlocked", TableInfo.Column("wasBlocked", "INTEGER", true, 0, null, TableInfo.CREATED_FROM_ENTITY))
        _columnsConnections.put("matchedRuleId", TableInfo.Column("matchedRuleId", "TEXT", false, 0, null, TableInfo.CREATED_FROM_ENTITY))
        _columnsConnections.put("durationMs", TableInfo.Column("durationMs", "INTEGER", true, 0, null, TableInfo.CREATED_FROM_ENTITY))
        _columnsConnections.put("encryptionStatus", TableInfo.Column("encryptionStatus", "TEXT", true, 0, null, TableInfo.CREATED_FROM_ENTITY))
        _columnsConnections.put("tlsVersion", TableInfo.Column("tlsVersion", "TEXT", false, 0, null, TableInfo.CREATED_FROM_ENTITY))
        _columnsConnections.put("sniHostname", TableInfo.Column("sniHostname", "TEXT", false, 0, null, TableInfo.CREATED_FROM_ENTITY))
        _columnsConnections.put("wasBackground", TableInfo.Column("wasBackground", "INTEGER", true, 0, null, TableInfo.CREATED_FROM_ENTITY))
        val _foreignKeysConnections: MutableSet<TableInfo.ForeignKey> = mutableSetOf()
        val _indicesConnections: MutableSet<TableInfo.Index> = mutableSetOf()
        val _infoConnections: TableInfo = TableInfo("connections", _columnsConnections, _foreignKeysConnections, _indicesConnections)
        val _existingConnections: TableInfo = read(connection, "connections")
        if (!_infoConnections.equals(_existingConnections)) {
          return RoomOpenDelegate.ValidationResult(false, """
              |connections(com.privacyguard.data.db.ConnectionEntity).
              | Expected:
              |""".trimMargin() + _infoConnections + """
              |
              | Found:
              |""".trimMargin() + _existingConnections)
        }
        val _columnsRules: MutableMap<String, TableInfo.Column> = mutableMapOf()
        _columnsRules.put("id", TableInfo.Column("id", "TEXT", true, 1, null, TableInfo.CREATED_FROM_ENTITY))
        _columnsRules.put("label", TableInfo.Column("label", "TEXT", true, 0, null, TableInfo.CREATED_FROM_ENTITY))
        _columnsRules.put("action", TableInfo.Column("action", "TEXT", true, 0, null, TableInfo.CREATED_FROM_ENTITY))
        _columnsRules.put("source", TableInfo.Column("source", "TEXT", true, 0, null, TableInfo.CREATED_FROM_ENTITY))
        _columnsRules.put("priority", TableInfo.Column("priority", "INTEGER", true, 0, null, TableInfo.CREATED_FROM_ENTITY))
        _columnsRules.put("isEnabled", TableInfo.Column("isEnabled", "INTEGER", true, 0, null, TableInfo.CREATED_FROM_ENTITY))
        _columnsRules.put("matchUid", TableInfo.Column("matchUid", "INTEGER", false, 0, null, TableInfo.CREATED_FROM_ENTITY))
        _columnsRules.put("matchPackage", TableInfo.Column("matchPackage", "TEXT", false, 0, null, TableInfo.CREATED_FROM_ENTITY))
        _columnsRules.put("matchDomain", TableInfo.Column("matchDomain", "TEXT", false, 0, null, TableInfo.CREATED_FROM_ENTITY))
        _columnsRules.put("matchIp", TableInfo.Column("matchIp", "TEXT", false, 0, null, TableInfo.CREATED_FROM_ENTITY))
        _columnsRules.put("matchPort", TableInfo.Column("matchPort", "INTEGER", false, 0, null, TableInfo.CREATED_FROM_ENTITY))
        _columnsRules.put("matchProtocol", TableInfo.Column("matchProtocol", "TEXT", false, 0, null, TableInfo.CREATED_FROM_ENTITY))
        _columnsRules.put("matchEncryption", TableInfo.Column("matchEncryption", "TEXT", false, 0, null, TableInfo.CREATED_FROM_ENTITY))
        _columnsRules.put("createdAt", TableInfo.Column("createdAt", "INTEGER", true, 0, null, TableInfo.CREATED_FROM_ENTITY))
        val _foreignKeysRules: MutableSet<TableInfo.ForeignKey> = mutableSetOf()
        val _indicesRules: MutableSet<TableInfo.Index> = mutableSetOf()
        val _infoRules: TableInfo = TableInfo("rules", _columnsRules, _foreignKeysRules, _indicesRules)
        val _existingRules: TableInfo = read(connection, "rules")
        if (!_infoRules.equals(_existingRules)) {
          return RoomOpenDelegate.ValidationResult(false, """
              |rules(com.privacyguard.data.db.RuleEntity).
              | Expected:
              |""".trimMargin() + _infoRules + """
              |
              | Found:
              |""".trimMargin() + _existingRules)
        }
        val _columnsBlocklist: MutableMap<String, TableInfo.Column> = mutableMapOf()
        _columnsBlocklist.put("id", TableInfo.Column("id", "INTEGER", true, 1, null, TableInfo.CREATED_FROM_ENTITY))
        _columnsBlocklist.put("domain", TableInfo.Column("domain", "TEXT", true, 0, null, TableInfo.CREATED_FROM_ENTITY))
        _columnsBlocklist.put("category", TableInfo.Column("category", "TEXT", true, 0, null, TableInfo.CREATED_FROM_ENTITY))
        _columnsBlocklist.put("listSource", TableInfo.Column("listSource", "TEXT", true, 0, null, TableInfo.CREATED_FROM_ENTITY))
        _columnsBlocklist.put("updatedAt", TableInfo.Column("updatedAt", "INTEGER", true, 0, null, TableInfo.CREATED_FROM_ENTITY))
        val _foreignKeysBlocklist: MutableSet<TableInfo.ForeignKey> = mutableSetOf()
        val _indicesBlocklist: MutableSet<TableInfo.Index> = mutableSetOf()
        _indicesBlocklist.add(TableInfo.Index("index_blocklist_domain", true, listOf("domain"), listOf("ASC")))
        val _infoBlocklist: TableInfo = TableInfo("blocklist", _columnsBlocklist, _foreignKeysBlocklist, _indicesBlocklist)
        val _existingBlocklist: TableInfo = read(connection, "blocklist")
        if (!_infoBlocklist.equals(_existingBlocklist)) {
          return RoomOpenDelegate.ValidationResult(false, """
              |blocklist(com.privacyguard.data.db.BlocklistEntity).
              | Expected:
              |""".trimMargin() + _infoBlocklist + """
              |
              | Found:
              |""".trimMargin() + _existingBlocklist)
        }
        val _columnsConnectionProfiles: MutableMap<String, TableInfo.Column> = mutableMapOf()
        _columnsConnectionProfiles.put("id", TableInfo.Column("id", "INTEGER", true, 1, null, TableInfo.CREATED_FROM_ENTITY))
        _columnsConnectionProfiles.put("packageName", TableInfo.Column("packageName", "TEXT", true, 0, null, TableInfo.CREATED_FROM_ENTITY))
        _columnsConnectionProfiles.put("hostname", TableInfo.Column("hostname", "TEXT", true, 0, null, TableInfo.CREATED_FROM_ENTITY))
        _columnsConnectionProfiles.put("destinationIp", TableInfo.Column("destinationIp", "TEXT", true, 0, null, TableInfo.CREATED_FROM_ENTITY))
        _columnsConnectionProfiles.put("destinationPort", TableInfo.Column("destinationPort", "INTEGER", true, 0, null, TableInfo.CREATED_FROM_ENTITY))
        _columnsConnectionProfiles.put("connectionCount", TableInfo.Column("connectionCount", "INTEGER", true, 0, null, TableInfo.CREATED_FROM_ENTITY))
        _columnsConnectionProfiles.put("totalBytesOut", TableInfo.Column("totalBytesOut", "INTEGER", true, 0, null, TableInfo.CREATED_FROM_ENTITY))
        _columnsConnectionProfiles.put("totalBytesIn", TableInfo.Column("totalBytesIn", "INTEGER", true, 0, null, TableInfo.CREATED_FROM_ENTITY))
        _columnsConnectionProfiles.put("avgBytesPerConnection", TableInfo.Column("avgBytesPerConnection", "INTEGER", true, 0, null, TableInfo.CREATED_FROM_ENTITY))
        _columnsConnectionProfiles.put("firstSeen", TableInfo.Column("firstSeen", "INTEGER", true, 0, null, TableInfo.CREATED_FROM_ENTITY))
        _columnsConnectionProfiles.put("lastSeen", TableInfo.Column("lastSeen", "INTEGER", true, 0, null, TableInfo.CREATED_FROM_ENTITY))
        _columnsConnectionProfiles.put("avgIntervalMs", TableInfo.Column("avgIntervalMs", "INTEGER", true, 0, null, TableInfo.CREATED_FROM_ENTITY))
        _columnsConnectionProfiles.put("minIntervalMs", TableInfo.Column("minIntervalMs", "INTEGER", true, 0, null, TableInfo.CREATED_FROM_ENTITY))
        _columnsConnectionProfiles.put("backgroundRatio", TableInfo.Column("backgroundRatio", "REAL", true, 0, null, TableInfo.CREATED_FROM_ENTITY))
        _columnsConnectionProfiles.put("hourlyDistribution", TableInfo.Column("hourlyDistribution", "TEXT", true, 0, null, TableInfo.CREATED_FROM_ENTITY))
        _columnsConnectionProfiles.put("encryptionStatus", TableInfo.Column("encryptionStatus", "TEXT", true, 0, null, TableInfo.CREATED_FROM_ENTITY))
        _columnsConnectionProfiles.put("tlsVersion", TableInfo.Column("tlsVersion", "TEXT", false, 0, null, TableInfo.CREATED_FROM_ENTITY))
        _columnsConnectionProfiles.put("sniHostname", TableInfo.Column("sniHostname", "TEXT", false, 0, null, TableInfo.CREATED_FROM_ENTITY))
        _columnsConnectionProfiles.put("riskScore", TableInfo.Column("riskScore", "INTEGER", true, 0, null, TableInfo.CREATED_FROM_ENTITY))
        _columnsConnectionProfiles.put("riskSignalCodes", TableInfo.Column("riskSignalCodes", "TEXT", true, 0, null, TableInfo.CREATED_FROM_ENTITY))
        _columnsConnectionProfiles.put("updatedAt", TableInfo.Column("updatedAt", "INTEGER", true, 0, null, TableInfo.CREATED_FROM_ENTITY))
        val _foreignKeysConnectionProfiles: MutableSet<TableInfo.ForeignKey> = mutableSetOf()
        val _indicesConnectionProfiles: MutableSet<TableInfo.Index> = mutableSetOf()
        _indicesConnectionProfiles.add(TableInfo.Index("index_connection_profiles_packageName_destinationIp_destinationPort", true, listOf("packageName", "destinationIp", "destinationPort"), listOf("ASC", "ASC", "ASC")))
        val _infoConnectionProfiles: TableInfo = TableInfo("connection_profiles", _columnsConnectionProfiles, _foreignKeysConnectionProfiles, _indicesConnectionProfiles)
        val _existingConnectionProfiles: TableInfo = read(connection, "connection_profiles")
        if (!_infoConnectionProfiles.equals(_existingConnectionProfiles)) {
          return RoomOpenDelegate.ValidationResult(false, """
              |connection_profiles(com.privacyguard.data.db.ConnectionProfileEntity).
              | Expected:
              |""".trimMargin() + _infoConnectionProfiles + """
              |
              | Found:
              |""".trimMargin() + _existingConnectionProfiles)
        }
        val _columnsDnsAnomalies: MutableMap<String, TableInfo.Column> = mutableMapOf()
        _columnsDnsAnomalies.put("id", TableInfo.Column("id", "INTEGER", true, 1, null, TableInfo.CREATED_FROM_ENTITY))
        _columnsDnsAnomalies.put("timestamp", TableInfo.Column("timestamp", "INTEGER", true, 0, null, TableInfo.CREATED_FROM_ENTITY))
        _columnsDnsAnomalies.put("packageName", TableInfo.Column("packageName", "TEXT", true, 0, null, TableInfo.CREATED_FROM_ENTITY))
        _columnsDnsAnomalies.put("domain", TableInfo.Column("domain", "TEXT", true, 0, null, TableInfo.CREATED_FROM_ENTITY))
        _columnsDnsAnomalies.put("anomalyType", TableInfo.Column("anomalyType", "TEXT", true, 0, null, TableInfo.CREATED_FROM_ENTITY))
        _columnsDnsAnomalies.put("description", TableInfo.Column("description", "TEXT", true, 0, null, TableInfo.CREATED_FROM_ENTITY))
        _columnsDnsAnomalies.put("severity", TableInfo.Column("severity", "INTEGER", true, 0, null, TableInfo.CREATED_FROM_ENTITY))
        val _foreignKeysDnsAnomalies: MutableSet<TableInfo.ForeignKey> = mutableSetOf()
        val _indicesDnsAnomalies: MutableSet<TableInfo.Index> = mutableSetOf()
        val _infoDnsAnomalies: TableInfo = TableInfo("dns_anomalies", _columnsDnsAnomalies, _foreignKeysDnsAnomalies, _indicesDnsAnomalies)
        val _existingDnsAnomalies: TableInfo = read(connection, "dns_anomalies")
        if (!_infoDnsAnomalies.equals(_existingDnsAnomalies)) {
          return RoomOpenDelegate.ValidationResult(false, """
              |dns_anomalies(com.privacyguard.data.db.DnsAnomalyEntity).
              | Expected:
              |""".trimMargin() + _infoDnsAnomalies + """
              |
              | Found:
              |""".trimMargin() + _existingDnsAnomalies)
        }
        return RoomOpenDelegate.ValidationResult(true, null)
      }
    }
    return _openDelegate
  }

  protected override fun createInvalidationTracker(): InvalidationTracker {
    val _shadowTablesMap: MutableMap<String, String> = mutableMapOf()
    val _viewTables: MutableMap<String, Set<String>> = mutableMapOf()
    return InvalidationTracker(this, _shadowTablesMap, _viewTables, "connections", "rules", "blocklist", "connection_profiles", "dns_anomalies")
  }

  public override fun clearAllTables() {
    super.performClear(false, "connections", "rules", "blocklist", "connection_profiles", "dns_anomalies")
  }

  protected override fun getRequiredTypeConverterClasses(): Map<KClass<*>, List<KClass<*>>> {
    val _typeConvertersMap: MutableMap<KClass<*>, List<KClass<*>>> = mutableMapOf()
    _typeConvertersMap.put(ConnectionDao::class, ConnectionDao_Impl.getRequiredConverters())
    _typeConvertersMap.put(RulesDao::class, RulesDao_Impl.getRequiredConverters())
    _typeConvertersMap.put(BlocklistDao::class, BlocklistDao_Impl.getRequiredConverters())
    _typeConvertersMap.put(ConnectionProfileDao::class, ConnectionProfileDao_Impl.getRequiredConverters())
    _typeConvertersMap.put(DnsAnomalyDao::class, DnsAnomalyDao_Impl.getRequiredConverters())
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

  public override fun connectionProfileDao(): ConnectionProfileDao = _connectionProfileDao.value

  public override fun dnsAnomalyDao(): DnsAnomalyDao = _dnsAnomalyDao.value
}
