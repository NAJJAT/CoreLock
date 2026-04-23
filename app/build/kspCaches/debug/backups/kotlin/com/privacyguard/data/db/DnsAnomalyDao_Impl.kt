package com.privacyguard.`data`.db

import androidx.room.EntityInsertAdapter
import androidx.room.RoomDatabase
import androidx.room.util.getColumnIndexOrThrow
import androidx.room.util.getTotalChangedRows
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
public class DnsAnomalyDao_Impl(
  __db: RoomDatabase,
) : DnsAnomalyDao {
  private val __db: RoomDatabase

  private val __insertAdapterOfDnsAnomalyEntity: EntityInsertAdapter<DnsAnomalyEntity>
  init {
    this.__db = __db
    this.__insertAdapterOfDnsAnomalyEntity = object : EntityInsertAdapter<DnsAnomalyEntity>() {
      protected override fun createQuery(): String = "INSERT OR ABORT INTO `dns_anomalies` (`id`,`timestamp`,`packageName`,`domain`,`anomalyType`,`description`,`severity`) VALUES (nullif(?, 0),?,?,?,?,?,?)"

      protected override fun bind(statement: SQLiteStatement, entity: DnsAnomalyEntity) {
        statement.bindLong(1, entity.id)
        statement.bindLong(2, entity.timestamp)
        statement.bindText(3, entity.packageName)
        statement.bindText(4, entity.domain)
        statement.bindText(5, entity.anomalyType)
        statement.bindText(6, entity.description)
        statement.bindLong(7, entity.severity.toLong())
      }
    }
  }

  public override suspend fun insert(a: DnsAnomalyEntity): Unit = performSuspending(__db, false, true) { _connection ->
    __insertAdapterOfDnsAnomalyEntity.insert(_connection, a)
  }

  public override suspend fun recent(limit: Int): List<DnsAnomalyEntity> {
    val _sql: String = "SELECT * FROM dns_anomalies ORDER BY timestamp DESC LIMIT ?"
    return performSuspending(__db, true, false) { _connection ->
      val _stmt: SQLiteStatement = _connection.prepare(_sql)
      try {
        var _argIndex: Int = 1
        _stmt.bindLong(_argIndex, limit.toLong())
        val _columnIndexOfId: Int = getColumnIndexOrThrow(_stmt, "id")
        val _columnIndexOfTimestamp: Int = getColumnIndexOrThrow(_stmt, "timestamp")
        val _columnIndexOfPackageName: Int = getColumnIndexOrThrow(_stmt, "packageName")
        val _columnIndexOfDomain: Int = getColumnIndexOrThrow(_stmt, "domain")
        val _columnIndexOfAnomalyType: Int = getColumnIndexOrThrow(_stmt, "anomalyType")
        val _columnIndexOfDescription: Int = getColumnIndexOrThrow(_stmt, "description")
        val _columnIndexOfSeverity: Int = getColumnIndexOrThrow(_stmt, "severity")
        val _result: MutableList<DnsAnomalyEntity> = mutableListOf()
        while (_stmt.step()) {
          val _item: DnsAnomalyEntity
          val _tmpId: Long
          _tmpId = _stmt.getLong(_columnIndexOfId)
          val _tmpTimestamp: Long
          _tmpTimestamp = _stmt.getLong(_columnIndexOfTimestamp)
          val _tmpPackageName: String
          _tmpPackageName = _stmt.getText(_columnIndexOfPackageName)
          val _tmpDomain: String
          _tmpDomain = _stmt.getText(_columnIndexOfDomain)
          val _tmpAnomalyType: String
          _tmpAnomalyType = _stmt.getText(_columnIndexOfAnomalyType)
          val _tmpDescription: String
          _tmpDescription = _stmt.getText(_columnIndexOfDescription)
          val _tmpSeverity: Int
          _tmpSeverity = _stmt.getLong(_columnIndexOfSeverity).toInt()
          _item = DnsAnomalyEntity(_tmpId,_tmpTimestamp,_tmpPackageName,_tmpDomain,_tmpAnomalyType,_tmpDescription,_tmpSeverity)
          _result.add(_item)
        }
        _result
      } finally {
        _stmt.close()
      }
    }
  }

  public override suspend fun forPackage(pkg: String): List<DnsAnomalyEntity> {
    val _sql: String = "SELECT * FROM dns_anomalies WHERE packageName = ? ORDER BY timestamp DESC"
    return performSuspending(__db, true, false) { _connection ->
      val _stmt: SQLiteStatement = _connection.prepare(_sql)
      try {
        var _argIndex: Int = 1
        _stmt.bindText(_argIndex, pkg)
        val _columnIndexOfId: Int = getColumnIndexOrThrow(_stmt, "id")
        val _columnIndexOfTimestamp: Int = getColumnIndexOrThrow(_stmt, "timestamp")
        val _columnIndexOfPackageName: Int = getColumnIndexOrThrow(_stmt, "packageName")
        val _columnIndexOfDomain: Int = getColumnIndexOrThrow(_stmt, "domain")
        val _columnIndexOfAnomalyType: Int = getColumnIndexOrThrow(_stmt, "anomalyType")
        val _columnIndexOfDescription: Int = getColumnIndexOrThrow(_stmt, "description")
        val _columnIndexOfSeverity: Int = getColumnIndexOrThrow(_stmt, "severity")
        val _result: MutableList<DnsAnomalyEntity> = mutableListOf()
        while (_stmt.step()) {
          val _item: DnsAnomalyEntity
          val _tmpId: Long
          _tmpId = _stmt.getLong(_columnIndexOfId)
          val _tmpTimestamp: Long
          _tmpTimestamp = _stmt.getLong(_columnIndexOfTimestamp)
          val _tmpPackageName: String
          _tmpPackageName = _stmt.getText(_columnIndexOfPackageName)
          val _tmpDomain: String
          _tmpDomain = _stmt.getText(_columnIndexOfDomain)
          val _tmpAnomalyType: String
          _tmpAnomalyType = _stmt.getText(_columnIndexOfAnomalyType)
          val _tmpDescription: String
          _tmpDescription = _stmt.getText(_columnIndexOfDescription)
          val _tmpSeverity: Int
          _tmpSeverity = _stmt.getLong(_columnIndexOfSeverity).toInt()
          _item = DnsAnomalyEntity(_tmpId,_tmpTimestamp,_tmpPackageName,_tmpDomain,_tmpAnomalyType,_tmpDescription,_tmpSeverity)
          _result.add(_item)
        }
        _result
      } finally {
        _stmt.close()
      }
    }
  }

  public override suspend fun countHighSeverity(min: Int): Long {
    val _sql: String = "SELECT COUNT(*) FROM dns_anomalies WHERE severity >= ?"
    return performSuspending(__db, true, false) { _connection ->
      val _stmt: SQLiteStatement = _connection.prepare(_sql)
      try {
        var _argIndex: Int = 1
        _stmt.bindLong(_argIndex, min.toLong())
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

  public override suspend fun deleteOlderThan(before: Long): Int {
    val _sql: String = "DELETE FROM dns_anomalies WHERE timestamp < ?"
    return performSuspending(__db, false, true) { _connection ->
      val _stmt: SQLiteStatement = _connection.prepare(_sql)
      try {
        var _argIndex: Int = 1
        _stmt.bindLong(_argIndex, before)
        _stmt.step()
        getTotalChangedRows(_connection)
      } finally {
        _stmt.close()
      }
    }
  }

  public override suspend fun deleteAll() {
    val _sql: String = "DELETE FROM dns_anomalies"
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
