package com.privacyguard.app.`data`.db

import androidx.room.EntityInsertAdapter
import androidx.room.RoomDatabase
import androidx.room.util.getColumnIndexOrThrow
import androidx.room.util.performSuspending
import androidx.sqlite.SQLiteStatement
import javax.`annotation`.processing.Generated
import kotlin.Boolean
import kotlin.Int
import kotlin.Long
import kotlin.String
import kotlin.Suppress
import kotlin.Unit
import kotlin.collections.List
import kotlin.collections.MutableList
import kotlin.collections.mutableListOf
import kotlin.reflect.KClass

@Generated(value = ["androidx.room.RoomProcessor"])
@Suppress(names = ["UNCHECKED_CAST", "DEPRECATION", "REDUNDANT_PROJECTION", "REMOVAL"])
public class ConnectionDao_Impl(
  __db: RoomDatabase,
) : ConnectionDao {
  private val __db: RoomDatabase

  private val __insertAdapterOfConnectionEntity: EntityInsertAdapter<ConnectionEntity>
  init {
    this.__db = __db
    this.__insertAdapterOfConnectionEntity = object : EntityInsertAdapter<ConnectionEntity>() {
      protected override fun createQuery(): String = "INSERT OR IGNORE INTO `connections` (`id`,`appUid`,`appName`,`packageName`,`destinationIp`,`destinationPort`,`destinationIpv6`,`isIPv6`,`domain`,`sniHostname`,`protocol`,`bytesSent`,`bytesReceived`,`timestamp`,`durationMs`,`wasBlocked`,`encryptionStatus`,`tlsVersion`,`wasBackground`) VALUES (nullif(?, 0),?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)"

      protected override fun bind(statement: SQLiteStatement, entity: ConnectionEntity) {
        statement.bindLong(1, entity.id)
        statement.bindLong(2, entity.appUid.toLong())
        statement.bindText(3, entity.appName)
        statement.bindText(4, entity.packageName)
        statement.bindText(5, entity.destinationIp)
        statement.bindLong(6, entity.destinationPort.toLong())
        val _tmpDestinationIpv6: String? = entity.destinationIpv6
        if (_tmpDestinationIpv6 == null) {
          statement.bindNull(7)
        } else {
          statement.bindText(7, _tmpDestinationIpv6)
        }
        val _tmp: Int = if (entity.isIPv6) 1 else 0
        statement.bindLong(8, _tmp.toLong())
        val _tmpDomain: String? = entity.domain
        if (_tmpDomain == null) {
          statement.bindNull(9)
        } else {
          statement.bindText(9, _tmpDomain)
        }
        val _tmpSniHostname: String? = entity.sniHostname
        if (_tmpSniHostname == null) {
          statement.bindNull(10)
        } else {
          statement.bindText(10, _tmpSniHostname)
        }
        statement.bindText(11, entity.protocol)
        statement.bindLong(12, entity.bytesSent)
        statement.bindLong(13, entity.bytesReceived)
        statement.bindLong(14, entity.timestamp)
        statement.bindLong(15, entity.durationMs)
        val _tmp_1: Int = if (entity.wasBlocked) 1 else 0
        statement.bindLong(16, _tmp_1.toLong())
        statement.bindText(17, entity.encryptionStatus)
        val _tmpTlsVersion: String? = entity.tlsVersion
        if (_tmpTlsVersion == null) {
          statement.bindNull(18)
        } else {
          statement.bindText(18, _tmpTlsVersion)
        }
        val _tmp_2: Int = if (entity.wasBackground) 1 else 0
        statement.bindLong(19, _tmp_2.toLong())
      }
    }
  }

  public override suspend fun insert(connection: ConnectionEntity): Unit = performSuspending(__db, false, true) { _connection ->
    __insertAdapterOfConnectionEntity.insert(_connection, connection)
  }

  public override suspend fun getRecentConnections(since: Long, limit: Int): List<ConnectionEntity> {
    val _sql: String = "SELECT * FROM connections WHERE timestamp > ? ORDER BY timestamp DESC LIMIT ?"
    return performSuspending(__db, true, false) { _connection ->
      val _stmt: SQLiteStatement = _connection.prepare(_sql)
      try {
        var _argIndex: Int = 1
        _stmt.bindLong(_argIndex, since)
        _argIndex = 2
        _stmt.bindLong(_argIndex, limit.toLong())
        val _columnIndexOfId: Int = getColumnIndexOrThrow(_stmt, "id")
        val _columnIndexOfAppUid: Int = getColumnIndexOrThrow(_stmt, "appUid")
        val _columnIndexOfAppName: Int = getColumnIndexOrThrow(_stmt, "appName")
        val _columnIndexOfPackageName: Int = getColumnIndexOrThrow(_stmt, "packageName")
        val _columnIndexOfDestinationIp: Int = getColumnIndexOrThrow(_stmt, "destinationIp")
        val _columnIndexOfDestinationPort: Int = getColumnIndexOrThrow(_stmt, "destinationPort")
        val _columnIndexOfDestinationIpv6: Int = getColumnIndexOrThrow(_stmt, "destinationIpv6")
        val _columnIndexOfIsIPv6: Int = getColumnIndexOrThrow(_stmt, "isIPv6")
        val _columnIndexOfDomain: Int = getColumnIndexOrThrow(_stmt, "domain")
        val _columnIndexOfSniHostname: Int = getColumnIndexOrThrow(_stmt, "sniHostname")
        val _columnIndexOfProtocol: Int = getColumnIndexOrThrow(_stmt, "protocol")
        val _columnIndexOfBytesSent: Int = getColumnIndexOrThrow(_stmt, "bytesSent")
        val _columnIndexOfBytesReceived: Int = getColumnIndexOrThrow(_stmt, "bytesReceived")
        val _columnIndexOfTimestamp: Int = getColumnIndexOrThrow(_stmt, "timestamp")
        val _columnIndexOfDurationMs: Int = getColumnIndexOrThrow(_stmt, "durationMs")
        val _columnIndexOfWasBlocked: Int = getColumnIndexOrThrow(_stmt, "wasBlocked")
        val _columnIndexOfEncryptionStatus: Int = getColumnIndexOrThrow(_stmt, "encryptionStatus")
        val _columnIndexOfTlsVersion: Int = getColumnIndexOrThrow(_stmt, "tlsVersion")
        val _columnIndexOfWasBackground: Int = getColumnIndexOrThrow(_stmt, "wasBackground")
        val _result: MutableList<ConnectionEntity> = mutableListOf()
        while (_stmt.step()) {
          val _item: ConnectionEntity
          val _tmpId: Long
          _tmpId = _stmt.getLong(_columnIndexOfId)
          val _tmpAppUid: Int
          _tmpAppUid = _stmt.getLong(_columnIndexOfAppUid).toInt()
          val _tmpAppName: String
          _tmpAppName = _stmt.getText(_columnIndexOfAppName)
          val _tmpPackageName: String
          _tmpPackageName = _stmt.getText(_columnIndexOfPackageName)
          val _tmpDestinationIp: String
          _tmpDestinationIp = _stmt.getText(_columnIndexOfDestinationIp)
          val _tmpDestinationPort: Int
          _tmpDestinationPort = _stmt.getLong(_columnIndexOfDestinationPort).toInt()
          val _tmpDestinationIpv6: String?
          if (_stmt.isNull(_columnIndexOfDestinationIpv6)) {
            _tmpDestinationIpv6 = null
          } else {
            _tmpDestinationIpv6 = _stmt.getText(_columnIndexOfDestinationIpv6)
          }
          val _tmpIsIPv6: Boolean
          val _tmp: Int
          _tmp = _stmt.getLong(_columnIndexOfIsIPv6).toInt()
          _tmpIsIPv6 = _tmp != 0
          val _tmpDomain: String?
          if (_stmt.isNull(_columnIndexOfDomain)) {
            _tmpDomain = null
          } else {
            _tmpDomain = _stmt.getText(_columnIndexOfDomain)
          }
          val _tmpSniHostname: String?
          if (_stmt.isNull(_columnIndexOfSniHostname)) {
            _tmpSniHostname = null
          } else {
            _tmpSniHostname = _stmt.getText(_columnIndexOfSniHostname)
          }
          val _tmpProtocol: String
          _tmpProtocol = _stmt.getText(_columnIndexOfProtocol)
          val _tmpBytesSent: Long
          _tmpBytesSent = _stmt.getLong(_columnIndexOfBytesSent)
          val _tmpBytesReceived: Long
          _tmpBytesReceived = _stmt.getLong(_columnIndexOfBytesReceived)
          val _tmpTimestamp: Long
          _tmpTimestamp = _stmt.getLong(_columnIndexOfTimestamp)
          val _tmpDurationMs: Long
          _tmpDurationMs = _stmt.getLong(_columnIndexOfDurationMs)
          val _tmpWasBlocked: Boolean
          val _tmp_1: Int
          _tmp_1 = _stmt.getLong(_columnIndexOfWasBlocked).toInt()
          _tmpWasBlocked = _tmp_1 != 0
          val _tmpEncryptionStatus: String
          _tmpEncryptionStatus = _stmt.getText(_columnIndexOfEncryptionStatus)
          val _tmpTlsVersion: String?
          if (_stmt.isNull(_columnIndexOfTlsVersion)) {
            _tmpTlsVersion = null
          } else {
            _tmpTlsVersion = _stmt.getText(_columnIndexOfTlsVersion)
          }
          val _tmpWasBackground: Boolean
          val _tmp_2: Int
          _tmp_2 = _stmt.getLong(_columnIndexOfWasBackground).toInt()
          _tmpWasBackground = _tmp_2 != 0
          _item = ConnectionEntity(_tmpId,_tmpAppUid,_tmpAppName,_tmpPackageName,_tmpDestinationIp,_tmpDestinationPort,_tmpDestinationIpv6,_tmpIsIPv6,_tmpDomain,_tmpSniHostname,_tmpProtocol,_tmpBytesSent,_tmpBytesReceived,_tmpTimestamp,_tmpDurationMs,_tmpWasBlocked,_tmpEncryptionStatus,_tmpTlsVersion,_tmpWasBackground)
          _result.add(_item)
        }
        _result
      } finally {
        _stmt.close()
      }
    }
  }

  public override suspend fun getConnectionsForApp(uid: Int, limit: Int): List<ConnectionEntity> {
    val _sql: String = "SELECT * FROM connections WHERE appUid = ? ORDER BY timestamp DESC LIMIT ?"
    return performSuspending(__db, true, false) { _connection ->
      val _stmt: SQLiteStatement = _connection.prepare(_sql)
      try {
        var _argIndex: Int = 1
        _stmt.bindLong(_argIndex, uid.toLong())
        _argIndex = 2
        _stmt.bindLong(_argIndex, limit.toLong())
        val _columnIndexOfId: Int = getColumnIndexOrThrow(_stmt, "id")
        val _columnIndexOfAppUid: Int = getColumnIndexOrThrow(_stmt, "appUid")
        val _columnIndexOfAppName: Int = getColumnIndexOrThrow(_stmt, "appName")
        val _columnIndexOfPackageName: Int = getColumnIndexOrThrow(_stmt, "packageName")
        val _columnIndexOfDestinationIp: Int = getColumnIndexOrThrow(_stmt, "destinationIp")
        val _columnIndexOfDestinationPort: Int = getColumnIndexOrThrow(_stmt, "destinationPort")
        val _columnIndexOfDestinationIpv6: Int = getColumnIndexOrThrow(_stmt, "destinationIpv6")
        val _columnIndexOfIsIPv6: Int = getColumnIndexOrThrow(_stmt, "isIPv6")
        val _columnIndexOfDomain: Int = getColumnIndexOrThrow(_stmt, "domain")
        val _columnIndexOfSniHostname: Int = getColumnIndexOrThrow(_stmt, "sniHostname")
        val _columnIndexOfProtocol: Int = getColumnIndexOrThrow(_stmt, "protocol")
        val _columnIndexOfBytesSent: Int = getColumnIndexOrThrow(_stmt, "bytesSent")
        val _columnIndexOfBytesReceived: Int = getColumnIndexOrThrow(_stmt, "bytesReceived")
        val _columnIndexOfTimestamp: Int = getColumnIndexOrThrow(_stmt, "timestamp")
        val _columnIndexOfDurationMs: Int = getColumnIndexOrThrow(_stmt, "durationMs")
        val _columnIndexOfWasBlocked: Int = getColumnIndexOrThrow(_stmt, "wasBlocked")
        val _columnIndexOfEncryptionStatus: Int = getColumnIndexOrThrow(_stmt, "encryptionStatus")
        val _columnIndexOfTlsVersion: Int = getColumnIndexOrThrow(_stmt, "tlsVersion")
        val _columnIndexOfWasBackground: Int = getColumnIndexOrThrow(_stmt, "wasBackground")
        val _result: MutableList<ConnectionEntity> = mutableListOf()
        while (_stmt.step()) {
          val _item: ConnectionEntity
          val _tmpId: Long
          _tmpId = _stmt.getLong(_columnIndexOfId)
          val _tmpAppUid: Int
          _tmpAppUid = _stmt.getLong(_columnIndexOfAppUid).toInt()
          val _tmpAppName: String
          _tmpAppName = _stmt.getText(_columnIndexOfAppName)
          val _tmpPackageName: String
          _tmpPackageName = _stmt.getText(_columnIndexOfPackageName)
          val _tmpDestinationIp: String
          _tmpDestinationIp = _stmt.getText(_columnIndexOfDestinationIp)
          val _tmpDestinationPort: Int
          _tmpDestinationPort = _stmt.getLong(_columnIndexOfDestinationPort).toInt()
          val _tmpDestinationIpv6: String?
          if (_stmt.isNull(_columnIndexOfDestinationIpv6)) {
            _tmpDestinationIpv6 = null
          } else {
            _tmpDestinationIpv6 = _stmt.getText(_columnIndexOfDestinationIpv6)
          }
          val _tmpIsIPv6: Boolean
          val _tmp: Int
          _tmp = _stmt.getLong(_columnIndexOfIsIPv6).toInt()
          _tmpIsIPv6 = _tmp != 0
          val _tmpDomain: String?
          if (_stmt.isNull(_columnIndexOfDomain)) {
            _tmpDomain = null
          } else {
            _tmpDomain = _stmt.getText(_columnIndexOfDomain)
          }
          val _tmpSniHostname: String?
          if (_stmt.isNull(_columnIndexOfSniHostname)) {
            _tmpSniHostname = null
          } else {
            _tmpSniHostname = _stmt.getText(_columnIndexOfSniHostname)
          }
          val _tmpProtocol: String
          _tmpProtocol = _stmt.getText(_columnIndexOfProtocol)
          val _tmpBytesSent: Long
          _tmpBytesSent = _stmt.getLong(_columnIndexOfBytesSent)
          val _tmpBytesReceived: Long
          _tmpBytesReceived = _stmt.getLong(_columnIndexOfBytesReceived)
          val _tmpTimestamp: Long
          _tmpTimestamp = _stmt.getLong(_columnIndexOfTimestamp)
          val _tmpDurationMs: Long
          _tmpDurationMs = _stmt.getLong(_columnIndexOfDurationMs)
          val _tmpWasBlocked: Boolean
          val _tmp_1: Int
          _tmp_1 = _stmt.getLong(_columnIndexOfWasBlocked).toInt()
          _tmpWasBlocked = _tmp_1 != 0
          val _tmpEncryptionStatus: String
          _tmpEncryptionStatus = _stmt.getText(_columnIndexOfEncryptionStatus)
          val _tmpTlsVersion: String?
          if (_stmt.isNull(_columnIndexOfTlsVersion)) {
            _tmpTlsVersion = null
          } else {
            _tmpTlsVersion = _stmt.getText(_columnIndexOfTlsVersion)
          }
          val _tmpWasBackground: Boolean
          val _tmp_2: Int
          _tmp_2 = _stmt.getLong(_columnIndexOfWasBackground).toInt()
          _tmpWasBackground = _tmp_2 != 0
          _item = ConnectionEntity(_tmpId,_tmpAppUid,_tmpAppName,_tmpPackageName,_tmpDestinationIp,_tmpDestinationPort,_tmpDestinationIpv6,_tmpIsIPv6,_tmpDomain,_tmpSniHostname,_tmpProtocol,_tmpBytesSent,_tmpBytesReceived,_tmpTimestamp,_tmpDurationMs,_tmpWasBlocked,_tmpEncryptionStatus,_tmpTlsVersion,_tmpWasBackground)
          _result.add(_item)
        }
        _result
      } finally {
        _stmt.close()
      }
    }
  }

  public override suspend fun getDomainsForPackage(packageName: String): List<TopBlockedDomain> {
    val _sql: String = """
        |
        |        SELECT
        |            COALESCE(domain, destinationIp) AS domain,
        |            COUNT(*)                        AS count
        |        FROM connections
        |        WHERE packageName = ?
        |        GROUP BY COALESCE(domain, destinationIp)
        |        ORDER BY count DESC
        |        LIMIT 100
        |    
        """.trimMargin()
    return performSuspending(__db, true, false) { _connection ->
      val _stmt: SQLiteStatement = _connection.prepare(_sql)
      try {
        var _argIndex: Int = 1
        _stmt.bindText(_argIndex, packageName)
        val _columnIndexOfDomain: Int = 0
        val _columnIndexOfCount: Int = 1
        val _result: MutableList<TopBlockedDomain> = mutableListOf()
        while (_stmt.step()) {
          val _item: TopBlockedDomain
          val _tmpDomain: String
          _tmpDomain = _stmt.getText(_columnIndexOfDomain)
          val _tmpCount: Int
          _tmpCount = _stmt.getLong(_columnIndexOfCount).toInt()
          _item = TopBlockedDomain(_tmpDomain,_tmpCount)
          _result.add(_item)
        }
        _result
      } finally {
        _stmt.close()
      }
    }
  }

  public override suspend fun getRecentConnectionsForPackage(packageName: String, limit: Int): List<ConnectionEntity> {
    val _sql: String = """
        |
        |        SELECT * FROM connections
        |        WHERE packageName = ?
        |        ORDER BY timestamp DESC
        |        LIMIT ?
        |    
        """.trimMargin()
    return performSuspending(__db, true, false) { _connection ->
      val _stmt: SQLiteStatement = _connection.prepare(_sql)
      try {
        var _argIndex: Int = 1
        _stmt.bindText(_argIndex, packageName)
        _argIndex = 2
        _stmt.bindLong(_argIndex, limit.toLong())
        val _columnIndexOfId: Int = getColumnIndexOrThrow(_stmt, "id")
        val _columnIndexOfAppUid: Int = getColumnIndexOrThrow(_stmt, "appUid")
        val _columnIndexOfAppName: Int = getColumnIndexOrThrow(_stmt, "appName")
        val _columnIndexOfPackageName: Int = getColumnIndexOrThrow(_stmt, "packageName")
        val _columnIndexOfDestinationIp: Int = getColumnIndexOrThrow(_stmt, "destinationIp")
        val _columnIndexOfDestinationPort: Int = getColumnIndexOrThrow(_stmt, "destinationPort")
        val _columnIndexOfDestinationIpv6: Int = getColumnIndexOrThrow(_stmt, "destinationIpv6")
        val _columnIndexOfIsIPv6: Int = getColumnIndexOrThrow(_stmt, "isIPv6")
        val _columnIndexOfDomain: Int = getColumnIndexOrThrow(_stmt, "domain")
        val _columnIndexOfSniHostname: Int = getColumnIndexOrThrow(_stmt, "sniHostname")
        val _columnIndexOfProtocol: Int = getColumnIndexOrThrow(_stmt, "protocol")
        val _columnIndexOfBytesSent: Int = getColumnIndexOrThrow(_stmt, "bytesSent")
        val _columnIndexOfBytesReceived: Int = getColumnIndexOrThrow(_stmt, "bytesReceived")
        val _columnIndexOfTimestamp: Int = getColumnIndexOrThrow(_stmt, "timestamp")
        val _columnIndexOfDurationMs: Int = getColumnIndexOrThrow(_stmt, "durationMs")
        val _columnIndexOfWasBlocked: Int = getColumnIndexOrThrow(_stmt, "wasBlocked")
        val _columnIndexOfEncryptionStatus: Int = getColumnIndexOrThrow(_stmt, "encryptionStatus")
        val _columnIndexOfTlsVersion: Int = getColumnIndexOrThrow(_stmt, "tlsVersion")
        val _columnIndexOfWasBackground: Int = getColumnIndexOrThrow(_stmt, "wasBackground")
        val _result: MutableList<ConnectionEntity> = mutableListOf()
        while (_stmt.step()) {
          val _item: ConnectionEntity
          val _tmpId: Long
          _tmpId = _stmt.getLong(_columnIndexOfId)
          val _tmpAppUid: Int
          _tmpAppUid = _stmt.getLong(_columnIndexOfAppUid).toInt()
          val _tmpAppName: String
          _tmpAppName = _stmt.getText(_columnIndexOfAppName)
          val _tmpPackageName: String
          _tmpPackageName = _stmt.getText(_columnIndexOfPackageName)
          val _tmpDestinationIp: String
          _tmpDestinationIp = _stmt.getText(_columnIndexOfDestinationIp)
          val _tmpDestinationPort: Int
          _tmpDestinationPort = _stmt.getLong(_columnIndexOfDestinationPort).toInt()
          val _tmpDestinationIpv6: String?
          if (_stmt.isNull(_columnIndexOfDestinationIpv6)) {
            _tmpDestinationIpv6 = null
          } else {
            _tmpDestinationIpv6 = _stmt.getText(_columnIndexOfDestinationIpv6)
          }
          val _tmpIsIPv6: Boolean
          val _tmp: Int
          _tmp = _stmt.getLong(_columnIndexOfIsIPv6).toInt()
          _tmpIsIPv6 = _tmp != 0
          val _tmpDomain: String?
          if (_stmt.isNull(_columnIndexOfDomain)) {
            _tmpDomain = null
          } else {
            _tmpDomain = _stmt.getText(_columnIndexOfDomain)
          }
          val _tmpSniHostname: String?
          if (_stmt.isNull(_columnIndexOfSniHostname)) {
            _tmpSniHostname = null
          } else {
            _tmpSniHostname = _stmt.getText(_columnIndexOfSniHostname)
          }
          val _tmpProtocol: String
          _tmpProtocol = _stmt.getText(_columnIndexOfProtocol)
          val _tmpBytesSent: Long
          _tmpBytesSent = _stmt.getLong(_columnIndexOfBytesSent)
          val _tmpBytesReceived: Long
          _tmpBytesReceived = _stmt.getLong(_columnIndexOfBytesReceived)
          val _tmpTimestamp: Long
          _tmpTimestamp = _stmt.getLong(_columnIndexOfTimestamp)
          val _tmpDurationMs: Long
          _tmpDurationMs = _stmt.getLong(_columnIndexOfDurationMs)
          val _tmpWasBlocked: Boolean
          val _tmp_1: Int
          _tmp_1 = _stmt.getLong(_columnIndexOfWasBlocked).toInt()
          _tmpWasBlocked = _tmp_1 != 0
          val _tmpEncryptionStatus: String
          _tmpEncryptionStatus = _stmt.getText(_columnIndexOfEncryptionStatus)
          val _tmpTlsVersion: String?
          if (_stmt.isNull(_columnIndexOfTlsVersion)) {
            _tmpTlsVersion = null
          } else {
            _tmpTlsVersion = _stmt.getText(_columnIndexOfTlsVersion)
          }
          val _tmpWasBackground: Boolean
          val _tmp_2: Int
          _tmp_2 = _stmt.getLong(_columnIndexOfWasBackground).toInt()
          _tmpWasBackground = _tmp_2 != 0
          _item = ConnectionEntity(_tmpId,_tmpAppUid,_tmpAppName,_tmpPackageName,_tmpDestinationIp,_tmpDestinationPort,_tmpDestinationIpv6,_tmpIsIPv6,_tmpDomain,_tmpSniHostname,_tmpProtocol,_tmpBytesSent,_tmpBytesReceived,_tmpTimestamp,_tmpDurationMs,_tmpWasBlocked,_tmpEncryptionStatus,_tmpTlsVersion,_tmpWasBackground)
          _result.add(_item)
        }
        _result
      } finally {
        _stmt.close()
      }
    }
  }

  public override suspend fun getBlockedCountToday(since: Long): Int {
    val _sql: String = "SELECT COUNT(*) FROM connections WHERE wasBlocked = 1 AND timestamp > ?"
    return performSuspending(__db, true, false) { _connection ->
      val _stmt: SQLiteStatement = _connection.prepare(_sql)
      try {
        var _argIndex: Int = 1
        _stmt.bindLong(_argIndex, since)
        val _result: Int
        if (_stmt.step()) {
          val _tmp: Int
          _tmp = _stmt.getLong(0).toInt()
          _result = _tmp
        } else {
          _result = 0
        }
        _result
      } finally {
        _stmt.close()
      }
    }
  }

  public override suspend fun getTotalDataToday(since: Long): Long {
    val _sql: String = "SELECT SUM(bytesSent + bytesReceived) FROM connections WHERE timestamp > ?"
    return performSuspending(__db, true, false) { _connection ->
      val _stmt: SQLiteStatement = _connection.prepare(_sql)
      try {
        var _argIndex: Int = 1
        _stmt.bindLong(_argIndex, since)
        val _result: Long
        if (_stmt.step()) {
          val _tmp: Long
          _tmp = _stmt.getLong(0)
          _result = _tmp
        } else {
          _result = 0L
        }
        _result
      } finally {
        _stmt.close()
      }
    }
  }

  public override suspend fun getTopBlockedDomains(since: Long, limit: Int): List<TopBlockedDomain> {
    val _sql: String = """
        |
        |        SELECT domain, COUNT(*) as count 
        |        FROM connections 
        |        WHERE wasBlocked = 1 AND domain IS NOT NULL AND timestamp > ? 
        |        GROUP BY domain 
        |        ORDER BY count DESC 
        |        LIMIT ?
        |    
        """.trimMargin()
    return performSuspending(__db, true, false) { _connection ->
      val _stmt: SQLiteStatement = _connection.prepare(_sql)
      try {
        var _argIndex: Int = 1
        _stmt.bindLong(_argIndex, since)
        _argIndex = 2
        _stmt.bindLong(_argIndex, limit.toLong())
        val _columnIndexOfDomain: Int = 0
        val _columnIndexOfCount: Int = 1
        val _result: MutableList<TopBlockedDomain> = mutableListOf()
        while (_stmt.step()) {
          val _item: TopBlockedDomain
          val _tmpDomain: String
          _tmpDomain = _stmt.getText(_columnIndexOfDomain)
          val _tmpCount: Int
          _tmpCount = _stmt.getLong(_columnIndexOfCount).toInt()
          _item = TopBlockedDomain(_tmpDomain,_tmpCount)
          _result.add(_item)
        }
        _result
      } finally {
        _stmt.close()
      }
    }
  }

  public override suspend fun getCount(): Int {
    val _sql: String = "SELECT COUNT(*) FROM connections"
    return performSuspending(__db, true, false) { _connection ->
      val _stmt: SQLiteStatement = _connection.prepare(_sql)
      try {
        val _result: Int
        if (_stmt.step()) {
          val _tmp: Int
          _tmp = _stmt.getLong(0).toInt()
          _result = _tmp
        } else {
          _result = 0
        }
        _result
      } finally {
        _stmt.close()
      }
    }
  }

  public override suspend fun getHourlyStats(since: Long): List<HourlyStats> {
    val _sql: String = """
        |
        |        SELECT 
        |            strftime('%H', datetime(timestamp/1000, 'unixepoch')) as hour,
        |            COUNT(*) as totalConnections,
        |            SUM(CASE WHEN wasBlocked = 1 THEN 1 ELSE 0 END) as blockedConnections,
        |            SUM(bytesSent + bytesReceived) as totalBytes
        |        FROM connections 
        |        WHERE timestamp > ? 
        |        GROUP BY hour
        |        ORDER BY hour
        |    
        """.trimMargin()
    return performSuspending(__db, true, false) { _connection ->
      val _stmt: SQLiteStatement = _connection.prepare(_sql)
      try {
        var _argIndex: Int = 1
        _stmt.bindLong(_argIndex, since)
        val _columnIndexOfHour: Int = 0
        val _columnIndexOfTotalConnections: Int = 1
        val _columnIndexOfBlockedConnections: Int = 2
        val _columnIndexOfTotalBytes: Int = 3
        val _result: MutableList<HourlyStats> = mutableListOf()
        while (_stmt.step()) {
          val _item: HourlyStats
          val _tmpHour: String
          _tmpHour = _stmt.getText(_columnIndexOfHour)
          val _tmpTotalConnections: Int
          _tmpTotalConnections = _stmt.getLong(_columnIndexOfTotalConnections).toInt()
          val _tmpBlockedConnections: Int
          _tmpBlockedConnections = _stmt.getLong(_columnIndexOfBlockedConnections).toInt()
          val _tmpTotalBytes: Long
          _tmpTotalBytes = _stmt.getLong(_columnIndexOfTotalBytes)
          _item = HourlyStats(_tmpHour,_tmpTotalConnections,_tmpBlockedConnections,_tmpTotalBytes)
          _result.add(_item)
        }
        _result
      } finally {
        _stmt.close()
      }
    }
  }

  public override suspend fun getDailyStats(since: Long): List<DailyStats> {
    val _sql: String = """
        |
        |        SELECT 
        |            strftime('%Y-%m-%d', datetime(timestamp/1000, 'unixepoch')) as date,
        |            COUNT(*) as totalConnections,
        |            SUM(CASE WHEN wasBlocked = 1 THEN 1 ELSE 0 END) as blockedConnections,
        |            SUM(bytesSent + bytesReceived) as totalBytes
        |        FROM connections 
        |        WHERE timestamp > ? 
        |        GROUP BY date
        |        ORDER BY date DESC
        |    
        """.trimMargin()
    return performSuspending(__db, true, false) { _connection ->
      val _stmt: SQLiteStatement = _connection.prepare(_sql)
      try {
        var _argIndex: Int = 1
        _stmt.bindLong(_argIndex, since)
        val _columnIndexOfDate: Int = 0
        val _columnIndexOfTotalConnections: Int = 1
        val _columnIndexOfBlockedConnections: Int = 2
        val _columnIndexOfTotalBytes: Int = 3
        val _result: MutableList<DailyStats> = mutableListOf()
        while (_stmt.step()) {
          val _item: DailyStats
          val _tmpDate: String
          _tmpDate = _stmt.getText(_columnIndexOfDate)
          val _tmpTotalConnections: Int
          _tmpTotalConnections = _stmt.getLong(_columnIndexOfTotalConnections).toInt()
          val _tmpBlockedConnections: Int
          _tmpBlockedConnections = _stmt.getLong(_columnIndexOfBlockedConnections).toInt()
          val _tmpTotalBytes: Long
          _tmpTotalBytes = _stmt.getLong(_columnIndexOfTotalBytes)
          _item = DailyStats(_tmpDate,_tmpTotalConnections,_tmpBlockedConnections,_tmpTotalBytes)
          _result.add(_item)
        }
        _result
      } finally {
        _stmt.close()
      }
    }
  }

  public override suspend fun deleteOldConnections(cutoff: Long) {
    val _sql: String = "DELETE FROM connections WHERE timestamp < ?"
    return performSuspending(__db, false, true) { _connection ->
      val _stmt: SQLiteStatement = _connection.prepare(_sql)
      try {
        var _argIndex: Int = 1
        _stmt.bindLong(_argIndex, cutoff)
        _stmt.step()
      } finally {
        _stmt.close()
      }
    }
  }

  public override suspend fun deleteAll() {
    val _sql: String = "DELETE FROM connections"
    return performSuspending(__db, false, true) { _connection ->
      val _stmt: SQLiteStatement = _connection.prepare(_sql)
      try {
        _stmt.step()
      } finally {
        _stmt.close()
      }
    }
  }

  public companion object {
    public fun getRequiredConverters(): List<KClass<*>> = emptyList()
  }
}
