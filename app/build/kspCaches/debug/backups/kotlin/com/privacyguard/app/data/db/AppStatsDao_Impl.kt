package com.privacyguard.app.`data`.db

import androidx.room.EntityInsertAdapter
import androidx.room.RoomDatabase
import androidx.room.util.getColumnIndexOrThrow
import androidx.room.util.performSuspending
import androidx.sqlite.SQLiteStatement
import javax.`annotation`.processing.Generated
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
public class AppStatsDao_Impl(
  __db: RoomDatabase,
) : AppStatsDao {
  private val __db: RoomDatabase

  private val __insertAdapterOfAppStatsEntity: EntityInsertAdapter<AppStatsEntity>
  init {
    this.__db = __db
    this.__insertAdapterOfAppStatsEntity = object : EntityInsertAdapter<AppStatsEntity>() {
      protected override fun createQuery(): String = "INSERT OR REPLACE INTO `app_stats` (`id`,`appUid`,`appName`,`date`,`bytesSent`,`bytesReceived`,`packetsSent`,`packetsReceived`,`blockedCount`) VALUES (nullif(?, 0),?,?,?,?,?,?,?,?)"

      protected override fun bind(statement: SQLiteStatement, entity: AppStatsEntity) {
        statement.bindLong(1, entity.id)
        statement.bindLong(2, entity.appUid.toLong())
        statement.bindText(3, entity.appName)
        statement.bindText(4, entity.date)
        statement.bindLong(5, entity.bytesSent)
        statement.bindLong(6, entity.bytesReceived)
        statement.bindLong(7, entity.packetsSent.toLong())
        statement.bindLong(8, entity.packetsReceived.toLong())
        statement.bindLong(9, entity.blockedCount.toLong())
      }
    }
  }

  public override suspend fun upsert(stats: AppStatsEntity): Unit = performSuspending(__db, false, true) { _connection ->
    __insertAdapterOfAppStatsEntity.insert(_connection, stats)
  }

  public override suspend fun getStatsForApp(uid: Int, date: String): AppStatsEntity? {
    val _sql: String = "SELECT * FROM app_stats WHERE appUid = ? AND date = ?"
    return performSuspending(__db, true, false) { _connection ->
      val _stmt: SQLiteStatement = _connection.prepare(_sql)
      try {
        var _argIndex: Int = 1
        _stmt.bindLong(_argIndex, uid.toLong())
        _argIndex = 2
        _stmt.bindText(_argIndex, date)
        val _columnIndexOfId: Int = getColumnIndexOrThrow(_stmt, "id")
        val _columnIndexOfAppUid: Int = getColumnIndexOrThrow(_stmt, "appUid")
        val _columnIndexOfAppName: Int = getColumnIndexOrThrow(_stmt, "appName")
        val _columnIndexOfDate: Int = getColumnIndexOrThrow(_stmt, "date")
        val _columnIndexOfBytesSent: Int = getColumnIndexOrThrow(_stmt, "bytesSent")
        val _columnIndexOfBytesReceived: Int = getColumnIndexOrThrow(_stmt, "bytesReceived")
        val _columnIndexOfPacketsSent: Int = getColumnIndexOrThrow(_stmt, "packetsSent")
        val _columnIndexOfPacketsReceived: Int = getColumnIndexOrThrow(_stmt, "packetsReceived")
        val _columnIndexOfBlockedCount: Int = getColumnIndexOrThrow(_stmt, "blockedCount")
        val _result: AppStatsEntity?
        if (_stmt.step()) {
          val _tmpId: Long
          _tmpId = _stmt.getLong(_columnIndexOfId)
          val _tmpAppUid: Int
          _tmpAppUid = _stmt.getLong(_columnIndexOfAppUid).toInt()
          val _tmpAppName: String
          _tmpAppName = _stmt.getText(_columnIndexOfAppName)
          val _tmpDate: String
          _tmpDate = _stmt.getText(_columnIndexOfDate)
          val _tmpBytesSent: Long
          _tmpBytesSent = _stmt.getLong(_columnIndexOfBytesSent)
          val _tmpBytesReceived: Long
          _tmpBytesReceived = _stmt.getLong(_columnIndexOfBytesReceived)
          val _tmpPacketsSent: Int
          _tmpPacketsSent = _stmt.getLong(_columnIndexOfPacketsSent).toInt()
          val _tmpPacketsReceived: Int
          _tmpPacketsReceived = _stmt.getLong(_columnIndexOfPacketsReceived).toInt()
          val _tmpBlockedCount: Int
          _tmpBlockedCount = _stmt.getLong(_columnIndexOfBlockedCount).toInt()
          _result = AppStatsEntity(_tmpId,_tmpAppUid,_tmpAppName,_tmpDate,_tmpBytesSent,_tmpBytesReceived,_tmpPacketsSent,_tmpPacketsReceived,_tmpBlockedCount)
        } else {
          _result = null
        }
        _result
      } finally {
        _stmt.close()
      }
    }
  }

  public override suspend fun getStatsForAppLastDays(uid: Int, days: Int): List<AppStatsEntity> {
    val _sql: String = "SELECT * FROM app_stats WHERE appUid = ? ORDER BY date DESC LIMIT ?"
    return performSuspending(__db, true, false) { _connection ->
      val _stmt: SQLiteStatement = _connection.prepare(_sql)
      try {
        var _argIndex: Int = 1
        _stmt.bindLong(_argIndex, uid.toLong())
        _argIndex = 2
        _stmt.bindLong(_argIndex, days.toLong())
        val _columnIndexOfId: Int = getColumnIndexOrThrow(_stmt, "id")
        val _columnIndexOfAppUid: Int = getColumnIndexOrThrow(_stmt, "appUid")
        val _columnIndexOfAppName: Int = getColumnIndexOrThrow(_stmt, "appName")
        val _columnIndexOfDate: Int = getColumnIndexOrThrow(_stmt, "date")
        val _columnIndexOfBytesSent: Int = getColumnIndexOrThrow(_stmt, "bytesSent")
        val _columnIndexOfBytesReceived: Int = getColumnIndexOrThrow(_stmt, "bytesReceived")
        val _columnIndexOfPacketsSent: Int = getColumnIndexOrThrow(_stmt, "packetsSent")
        val _columnIndexOfPacketsReceived: Int = getColumnIndexOrThrow(_stmt, "packetsReceived")
        val _columnIndexOfBlockedCount: Int = getColumnIndexOrThrow(_stmt, "blockedCount")
        val _result: MutableList<AppStatsEntity> = mutableListOf()
        while (_stmt.step()) {
          val _item: AppStatsEntity
          val _tmpId: Long
          _tmpId = _stmt.getLong(_columnIndexOfId)
          val _tmpAppUid: Int
          _tmpAppUid = _stmt.getLong(_columnIndexOfAppUid).toInt()
          val _tmpAppName: String
          _tmpAppName = _stmt.getText(_columnIndexOfAppName)
          val _tmpDate: String
          _tmpDate = _stmt.getText(_columnIndexOfDate)
          val _tmpBytesSent: Long
          _tmpBytesSent = _stmt.getLong(_columnIndexOfBytesSent)
          val _tmpBytesReceived: Long
          _tmpBytesReceived = _stmt.getLong(_columnIndexOfBytesReceived)
          val _tmpPacketsSent: Int
          _tmpPacketsSent = _stmt.getLong(_columnIndexOfPacketsSent).toInt()
          val _tmpPacketsReceived: Int
          _tmpPacketsReceived = _stmt.getLong(_columnIndexOfPacketsReceived).toInt()
          val _tmpBlockedCount: Int
          _tmpBlockedCount = _stmt.getLong(_columnIndexOfBlockedCount).toInt()
          _item = AppStatsEntity(_tmpId,_tmpAppUid,_tmpAppName,_tmpDate,_tmpBytesSent,_tmpBytesReceived,_tmpPacketsSent,_tmpPacketsReceived,_tmpBlockedCount)
          _result.add(_item)
        }
        _result
      } finally {
        _stmt.close()
      }
    }
  }

  public override suspend fun getTodayStats(today: String): List<AppStatsEntity> {
    val _sql: String = "SELECT * FROM app_stats WHERE date = ? ORDER BY bytesSent + bytesReceived DESC"
    return performSuspending(__db, true, false) { _connection ->
      val _stmt: SQLiteStatement = _connection.prepare(_sql)
      try {
        var _argIndex: Int = 1
        _stmt.bindText(_argIndex, today)
        val _columnIndexOfId: Int = getColumnIndexOrThrow(_stmt, "id")
        val _columnIndexOfAppUid: Int = getColumnIndexOrThrow(_stmt, "appUid")
        val _columnIndexOfAppName: Int = getColumnIndexOrThrow(_stmt, "appName")
        val _columnIndexOfDate: Int = getColumnIndexOrThrow(_stmt, "date")
        val _columnIndexOfBytesSent: Int = getColumnIndexOrThrow(_stmt, "bytesSent")
        val _columnIndexOfBytesReceived: Int = getColumnIndexOrThrow(_stmt, "bytesReceived")
        val _columnIndexOfPacketsSent: Int = getColumnIndexOrThrow(_stmt, "packetsSent")
        val _columnIndexOfPacketsReceived: Int = getColumnIndexOrThrow(_stmt, "packetsReceived")
        val _columnIndexOfBlockedCount: Int = getColumnIndexOrThrow(_stmt, "blockedCount")
        val _result: MutableList<AppStatsEntity> = mutableListOf()
        while (_stmt.step()) {
          val _item: AppStatsEntity
          val _tmpId: Long
          _tmpId = _stmt.getLong(_columnIndexOfId)
          val _tmpAppUid: Int
          _tmpAppUid = _stmt.getLong(_columnIndexOfAppUid).toInt()
          val _tmpAppName: String
          _tmpAppName = _stmt.getText(_columnIndexOfAppName)
          val _tmpDate: String
          _tmpDate = _stmt.getText(_columnIndexOfDate)
          val _tmpBytesSent: Long
          _tmpBytesSent = _stmt.getLong(_columnIndexOfBytesSent)
          val _tmpBytesReceived: Long
          _tmpBytesReceived = _stmt.getLong(_columnIndexOfBytesReceived)
          val _tmpPacketsSent: Int
          _tmpPacketsSent = _stmt.getLong(_columnIndexOfPacketsSent).toInt()
          val _tmpPacketsReceived: Int
          _tmpPacketsReceived = _stmt.getLong(_columnIndexOfPacketsReceived).toInt()
          val _tmpBlockedCount: Int
          _tmpBlockedCount = _stmt.getLong(_columnIndexOfBlockedCount).toInt()
          _item = AppStatsEntity(_tmpId,_tmpAppUid,_tmpAppName,_tmpDate,_tmpBytesSent,_tmpBytesReceived,_tmpPacketsSent,_tmpPacketsReceived,_tmpBlockedCount)
          _result.add(_item)
        }
        _result
      } finally {
        _stmt.close()
      }
    }
  }

  public override suspend fun getTopApps(
    startDate: String,
    endDate: String,
    limit: Int,
  ): List<TopAppStats> {
    val _sql: String = """
        |
        |        SELECT 
        |            appUid, 
        |            appName, 
        |            SUM(bytesSent) as totalBytesSent,
        |            SUM(bytesReceived) as totalBytesReceived,
        |            SUM(blockedCount) as totalBlocked
        |        FROM app_stats 
        |        WHERE date >= ? AND date <= ?
        |        GROUP BY appUid
        |        ORDER BY totalBytesSent + totalBytesReceived DESC
        |        LIMIT ?
        |    
        """.trimMargin()
    return performSuspending(__db, true, false) { _connection ->
      val _stmt: SQLiteStatement = _connection.prepare(_sql)
      try {
        var _argIndex: Int = 1
        _stmt.bindText(_argIndex, startDate)
        _argIndex = 2
        _stmt.bindText(_argIndex, endDate)
        _argIndex = 3
        _stmt.bindLong(_argIndex, limit.toLong())
        val _columnIndexOfAppUid: Int = 0
        val _columnIndexOfAppName: Int = 1
        val _columnIndexOfTotalBytesSent: Int = 2
        val _columnIndexOfTotalBytesReceived: Int = 3
        val _columnIndexOfTotalBlocked: Int = 4
        val _result: MutableList<TopAppStats> = mutableListOf()
        while (_stmt.step()) {
          val _item: TopAppStats
          val _tmpAppUid: Int
          _tmpAppUid = _stmt.getLong(_columnIndexOfAppUid).toInt()
          val _tmpAppName: String
          _tmpAppName = _stmt.getText(_columnIndexOfAppName)
          val _tmpTotalBytesSent: Long
          _tmpTotalBytesSent = _stmt.getLong(_columnIndexOfTotalBytesSent)
          val _tmpTotalBytesReceived: Long
          _tmpTotalBytesReceived = _stmt.getLong(_columnIndexOfTotalBytesReceived)
          val _tmpTotalBlocked: Long
          _tmpTotalBlocked = _stmt.getLong(_columnIndexOfTotalBlocked)
          _item = TopAppStats(_tmpAppUid,_tmpAppName,_tmpTotalBytesSent,_tmpTotalBytesReceived,_tmpTotalBlocked)
          _result.add(_item)
        }
        _result
      } finally {
        _stmt.close()
      }
    }
  }

  public override suspend fun updateConnectionStats(
    uid: Int,
    date: String,
    sent: Long,
    received: Long,
    packetsSent: Int,
    packetsReceived: Int,
  ) {
    val _sql: String = """
        |
        |        UPDATE app_stats 
        |        SET bytesSent = bytesSent + ?,
        |            bytesReceived = bytesReceived + ?,
        |            packetsSent = packetsSent + ?,
        |            packetsReceived = packetsReceived + ?
        |        WHERE appUid = ? AND date = ?
        |    
        """.trimMargin()
    return performSuspending(__db, false, true) { _connection ->
      val _stmt: SQLiteStatement = _connection.prepare(_sql)
      try {
        var _argIndex: Int = 1
        _stmt.bindLong(_argIndex, sent)
        _argIndex = 2
        _stmt.bindLong(_argIndex, received)
        _argIndex = 3
        _stmt.bindLong(_argIndex, packetsSent.toLong())
        _argIndex = 4
        _stmt.bindLong(_argIndex, packetsReceived.toLong())
        _argIndex = 5
        _stmt.bindLong(_argIndex, uid.toLong())
        _argIndex = 6
        _stmt.bindText(_argIndex, date)
        _stmt.step()
      } finally {
        _stmt.close()
      }
    }
  }

  public override suspend fun incrementBlockedCount(uid: Int, date: String) {
    val _sql: String = """
        |
        |        UPDATE app_stats 
        |        SET blockedCount = blockedCount + 1
        |        WHERE appUid = ? AND date = ?
        |    
        """.trimMargin()
    return performSuspending(__db, false, true) { _connection ->
      val _stmt: SQLiteStatement = _connection.prepare(_sql)
      try {
        var _argIndex: Int = 1
        _stmt.bindLong(_argIndex, uid.toLong())
        _argIndex = 2
        _stmt.bindText(_argIndex, date)
        _stmt.step()
      } finally {
        _stmt.close()
      }
    }
  }

  public override suspend fun deleteOldStats(cutoff: String) {
    val _sql: String = "DELETE FROM app_stats WHERE date < ?"
    return performSuspending(__db, false, true) { _connection ->
      val _stmt: SQLiteStatement = _connection.prepare(_sql)
      try {
        var _argIndex: Int = 1
        _stmt.bindText(_argIndex, cutoff)
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
