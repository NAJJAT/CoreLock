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
      protected override fun createQuery(): String = "INSERT OR IGNORE INTO `connections` (`id`,`appUid`,`appName`,`domain`,`destinationIp`,`destinationPort`,`protocol`,`wasBlocked`,`bytesSent`,`bytesReceived`,`timestamp`,`durationMs`) VALUES (nullif(?, 0),?,?,?,?,?,?,?,?,?,?,?)"

      protected override fun bind(statement: SQLiteStatement, entity: ConnectionEntity) {
        statement.bindLong(1, entity.id)
        statement.bindLong(2, entity.appUid.toLong())
        statement.bindText(3, entity.appName)
        val _tmpDomain: String? = entity.domain
        if (_tmpDomain == null) {
          statement.bindNull(4)
        } else {
          statement.bindText(4, _tmpDomain)
        }
        statement.bindText(5, entity.destinationIp)
        statement.bindLong(6, entity.destinationPort.toLong())
        statement.bindText(7, entity.protocol)
        val _tmp: Int = if (entity.wasBlocked) 1 else 0
        statement.bindLong(8, _tmp.toLong())
        statement.bindLong(9, entity.bytesSent)
        statement.bindLong(10, entity.bytesReceived)
        statement.bindLong(11, entity.timestamp)
        statement.bindLong(12, entity.durationMs)
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
        val _columnIndexOfDomain: Int = getColumnIndexOrThrow(_stmt, "domain")
        val _columnIndexOfDestinationIp: Int = getColumnIndexOrThrow(_stmt, "destinationIp")
        val _columnIndexOfDestinationPort: Int = getColumnIndexOrThrow(_stmt, "destinationPort")
        val _columnIndexOfProtocol: Int = getColumnIndexOrThrow(_stmt, "protocol")
        val _columnIndexOfWasBlocked: Int = getColumnIndexOrThrow(_stmt, "wasBlocked")
        val _columnIndexOfBytesSent: Int = getColumnIndexOrThrow(_stmt, "bytesSent")
        val _columnIndexOfBytesReceived: Int = getColumnIndexOrThrow(_stmt, "bytesReceived")
        val _columnIndexOfTimestamp: Int = getColumnIndexOrThrow(_stmt, "timestamp")
        val _columnIndexOfDurationMs: Int = getColumnIndexOrThrow(_stmt, "durationMs")
        val _result: MutableList<ConnectionEntity> = mutableListOf()
        while (_stmt.step()) {
          val _item: ConnectionEntity
          val _tmpId: Long
          _tmpId = _stmt.getLong(_columnIndexOfId)
          val _tmpAppUid: Int
          _tmpAppUid = _stmt.getLong(_columnIndexOfAppUid).toInt()
          val _tmpAppName: String
          _tmpAppName = _stmt.getText(_columnIndexOfAppName)
          val _tmpDomain: String?
          if (_stmt.isNull(_columnIndexOfDomain)) {
            _tmpDomain = null
          } else {
            _tmpDomain = _stmt.getText(_columnIndexOfDomain)
          }
          val _tmpDestinationIp: String
          _tmpDestinationIp = _stmt.getText(_columnIndexOfDestinationIp)
          val _tmpDestinationPort: Int
          _tmpDestinationPort = _stmt.getLong(_columnIndexOfDestinationPort).toInt()
          val _tmpProtocol: String
          _tmpProtocol = _stmt.getText(_columnIndexOfProtocol)
          val _tmpWasBlocked: Boolean
          val _tmp: Int
          _tmp = _stmt.getLong(_columnIndexOfWasBlocked).toInt()
          _tmpWasBlocked = _tmp != 0
          val _tmpBytesSent: Long
          _tmpBytesSent = _stmt.getLong(_columnIndexOfBytesSent)
          val _tmpBytesReceived: Long
          _tmpBytesReceived = _stmt.getLong(_columnIndexOfBytesReceived)
          val _tmpTimestamp: Long
          _tmpTimestamp = _stmt.getLong(_columnIndexOfTimestamp)
          val _tmpDurationMs: Long
          _tmpDurationMs = _stmt.getLong(_columnIndexOfDurationMs)
          _item = ConnectionEntity(_tmpId,_tmpAppUid,_tmpAppName,_tmpDomain,_tmpDestinationIp,_tmpDestinationPort,_tmpProtocol,_tmpWasBlocked,_tmpBytesSent,_tmpBytesReceived,_tmpTimestamp,_tmpDurationMs)
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
        val _columnIndexOfDomain: Int = getColumnIndexOrThrow(_stmt, "domain")
        val _columnIndexOfDestinationIp: Int = getColumnIndexOrThrow(_stmt, "destinationIp")
        val _columnIndexOfDestinationPort: Int = getColumnIndexOrThrow(_stmt, "destinationPort")
        val _columnIndexOfProtocol: Int = getColumnIndexOrThrow(_stmt, "protocol")
        val _columnIndexOfWasBlocked: Int = getColumnIndexOrThrow(_stmt, "wasBlocked")
        val _columnIndexOfBytesSent: Int = getColumnIndexOrThrow(_stmt, "bytesSent")
        val _columnIndexOfBytesReceived: Int = getColumnIndexOrThrow(_stmt, "bytesReceived")
        val _columnIndexOfTimestamp: Int = getColumnIndexOrThrow(_stmt, "timestamp")
        val _columnIndexOfDurationMs: Int = getColumnIndexOrThrow(_stmt, "durationMs")
        val _result: MutableList<ConnectionEntity> = mutableListOf()
        while (_stmt.step()) {
          val _item: ConnectionEntity
          val _tmpId: Long
          _tmpId = _stmt.getLong(_columnIndexOfId)
          val _tmpAppUid: Int
          _tmpAppUid = _stmt.getLong(_columnIndexOfAppUid).toInt()
          val _tmpAppName: String
          _tmpAppName = _stmt.getText(_columnIndexOfAppName)
          val _tmpDomain: String?
          if (_stmt.isNull(_columnIndexOfDomain)) {
            _tmpDomain = null
          } else {
            _tmpDomain = _stmt.getText(_columnIndexOfDomain)
          }
          val _tmpDestinationIp: String
          _tmpDestinationIp = _stmt.getText(_columnIndexOfDestinationIp)
          val _tmpDestinationPort: Int
          _tmpDestinationPort = _stmt.getLong(_columnIndexOfDestinationPort).toInt()
          val _tmpProtocol: String
          _tmpProtocol = _stmt.getText(_columnIndexOfProtocol)
          val _tmpWasBlocked: Boolean
          val _tmp: Int
          _tmp = _stmt.getLong(_columnIndexOfWasBlocked).toInt()
          _tmpWasBlocked = _tmp != 0
          val _tmpBytesSent: Long
          _tmpBytesSent = _stmt.getLong(_columnIndexOfBytesSent)
          val _tmpBytesReceived: Long
          _tmpBytesReceived = _stmt.getLong(_columnIndexOfBytesReceived)
          val _tmpTimestamp: Long
          _tmpTimestamp = _stmt.getLong(_columnIndexOfTimestamp)
          val _tmpDurationMs: Long
          _tmpDurationMs = _stmt.getLong(_columnIndexOfDurationMs)
          _item = ConnectionEntity(_tmpId,_tmpAppUid,_tmpAppName,_tmpDomain,_tmpDestinationIp,_tmpDestinationPort,_tmpProtocol,_tmpWasBlocked,_tmpBytesSent,_tmpBytesReceived,_tmpTimestamp,_tmpDurationMs)
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

  public companion object {
    public fun getRequiredConverters(): List<KClass<*>> = emptyList()
  }
}
