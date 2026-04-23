package com.privacyguard.`data`.db

import androidx.room.EntityInsertAdapter
import androidx.room.RoomDatabase
import androidx.room.util.getColumnIndexOrThrow
import androidx.room.util.getTotalChangedRows
import androidx.room.util.performSuspending
import androidx.sqlite.SQLiteStatement
import javax.`annotation`.processing.Generated
import kotlin.Float
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
public class ConnectionProfileDao_Impl(
  __db: RoomDatabase,
) : ConnectionProfileDao {
  private val __db: RoomDatabase

  private val __insertAdapterOfConnectionProfileEntity: EntityInsertAdapter<ConnectionProfileEntity>
  init {
    this.__db = __db
    this.__insertAdapterOfConnectionProfileEntity = object : EntityInsertAdapter<ConnectionProfileEntity>() {
      protected override fun createQuery(): String = "INSERT OR REPLACE INTO `connection_profiles` (`id`,`packageName`,`hostname`,`destinationIp`,`destinationPort`,`connectionCount`,`totalBytesOut`,`totalBytesIn`,`avgBytesPerConnection`,`firstSeen`,`lastSeen`,`avgIntervalMs`,`minIntervalMs`,`backgroundRatio`,`hourlyDistribution`,`encryptionStatus`,`tlsVersion`,`sniHostname`,`riskScore`,`riskSignalCodes`,`updatedAt`) VALUES (nullif(?, 0),?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)"

      protected override fun bind(statement: SQLiteStatement, entity: ConnectionProfileEntity) {
        statement.bindLong(1, entity.id)
        statement.bindText(2, entity.packageName)
        statement.bindText(3, entity.hostname)
        statement.bindText(4, entity.destinationIp)
        statement.bindLong(5, entity.destinationPort.toLong())
        statement.bindLong(6, entity.connectionCount)
        statement.bindLong(7, entity.totalBytesOut)
        statement.bindLong(8, entity.totalBytesIn)
        statement.bindLong(9, entity.avgBytesPerConnection)
        statement.bindLong(10, entity.firstSeen)
        statement.bindLong(11, entity.lastSeen)
        statement.bindLong(12, entity.avgIntervalMs)
        statement.bindLong(13, entity.minIntervalMs)
        statement.bindDouble(14, entity.backgroundRatio.toDouble())
        statement.bindText(15, entity.hourlyDistribution)
        statement.bindText(16, entity.encryptionStatus)
        val _tmpTlsVersion: String? = entity.tlsVersion
        if (_tmpTlsVersion == null) {
          statement.bindNull(17)
        } else {
          statement.bindText(17, _tmpTlsVersion)
        }
        val _tmpSniHostname: String? = entity.sniHostname
        if (_tmpSniHostname == null) {
          statement.bindNull(18)
        } else {
          statement.bindText(18, _tmpSniHostname)
        }
        statement.bindLong(19, entity.riskScore.toLong())
        statement.bindText(20, entity.riskSignalCodes)
        statement.bindLong(21, entity.updatedAt)
      }
    }
  }

  public override suspend fun upsert(p: ConnectionProfileEntity): Unit = performSuspending(__db, false, true) { _connection ->
    __insertAdapterOfConnectionProfileEntity.insert(_connection, p)
  }

  public override suspend fun upsertAll(ps: List<ConnectionProfileEntity>): Unit = performSuspending(__db, false, true) { _connection ->
    __insertAdapterOfConnectionProfileEntity.insert(_connection, ps)
  }

  public override suspend fun allProfiles(): List<ConnectionProfileEntity> {
    val _sql: String = "SELECT * FROM connection_profiles ORDER BY riskScore DESC"
    return performSuspending(__db, true, false) { _connection ->
      val _stmt: SQLiteStatement = _connection.prepare(_sql)
      try {
        val _columnIndexOfId: Int = getColumnIndexOrThrow(_stmt, "id")
        val _columnIndexOfPackageName: Int = getColumnIndexOrThrow(_stmt, "packageName")
        val _columnIndexOfHostname: Int = getColumnIndexOrThrow(_stmt, "hostname")
        val _columnIndexOfDestinationIp: Int = getColumnIndexOrThrow(_stmt, "destinationIp")
        val _columnIndexOfDestinationPort: Int = getColumnIndexOrThrow(_stmt, "destinationPort")
        val _columnIndexOfConnectionCount: Int = getColumnIndexOrThrow(_stmt, "connectionCount")
        val _columnIndexOfTotalBytesOut: Int = getColumnIndexOrThrow(_stmt, "totalBytesOut")
        val _columnIndexOfTotalBytesIn: Int = getColumnIndexOrThrow(_stmt, "totalBytesIn")
        val _columnIndexOfAvgBytesPerConnection: Int = getColumnIndexOrThrow(_stmt, "avgBytesPerConnection")
        val _columnIndexOfFirstSeen: Int = getColumnIndexOrThrow(_stmt, "firstSeen")
        val _columnIndexOfLastSeen: Int = getColumnIndexOrThrow(_stmt, "lastSeen")
        val _columnIndexOfAvgIntervalMs: Int = getColumnIndexOrThrow(_stmt, "avgIntervalMs")
        val _columnIndexOfMinIntervalMs: Int = getColumnIndexOrThrow(_stmt, "minIntervalMs")
        val _columnIndexOfBackgroundRatio: Int = getColumnIndexOrThrow(_stmt, "backgroundRatio")
        val _columnIndexOfHourlyDistribution: Int = getColumnIndexOrThrow(_stmt, "hourlyDistribution")
        val _columnIndexOfEncryptionStatus: Int = getColumnIndexOrThrow(_stmt, "encryptionStatus")
        val _columnIndexOfTlsVersion: Int = getColumnIndexOrThrow(_stmt, "tlsVersion")
        val _columnIndexOfSniHostname: Int = getColumnIndexOrThrow(_stmt, "sniHostname")
        val _columnIndexOfRiskScore: Int = getColumnIndexOrThrow(_stmt, "riskScore")
        val _columnIndexOfRiskSignalCodes: Int = getColumnIndexOrThrow(_stmt, "riskSignalCodes")
        val _columnIndexOfUpdatedAt: Int = getColumnIndexOrThrow(_stmt, "updatedAt")
        val _result: MutableList<ConnectionProfileEntity> = mutableListOf()
        while (_stmt.step()) {
          val _item: ConnectionProfileEntity
          val _tmpId: Long
          _tmpId = _stmt.getLong(_columnIndexOfId)
          val _tmpPackageName: String
          _tmpPackageName = _stmt.getText(_columnIndexOfPackageName)
          val _tmpHostname: String
          _tmpHostname = _stmt.getText(_columnIndexOfHostname)
          val _tmpDestinationIp: String
          _tmpDestinationIp = _stmt.getText(_columnIndexOfDestinationIp)
          val _tmpDestinationPort: Int
          _tmpDestinationPort = _stmt.getLong(_columnIndexOfDestinationPort).toInt()
          val _tmpConnectionCount: Long
          _tmpConnectionCount = _stmt.getLong(_columnIndexOfConnectionCount)
          val _tmpTotalBytesOut: Long
          _tmpTotalBytesOut = _stmt.getLong(_columnIndexOfTotalBytesOut)
          val _tmpTotalBytesIn: Long
          _tmpTotalBytesIn = _stmt.getLong(_columnIndexOfTotalBytesIn)
          val _tmpAvgBytesPerConnection: Long
          _tmpAvgBytesPerConnection = _stmt.getLong(_columnIndexOfAvgBytesPerConnection)
          val _tmpFirstSeen: Long
          _tmpFirstSeen = _stmt.getLong(_columnIndexOfFirstSeen)
          val _tmpLastSeen: Long
          _tmpLastSeen = _stmt.getLong(_columnIndexOfLastSeen)
          val _tmpAvgIntervalMs: Long
          _tmpAvgIntervalMs = _stmt.getLong(_columnIndexOfAvgIntervalMs)
          val _tmpMinIntervalMs: Long
          _tmpMinIntervalMs = _stmt.getLong(_columnIndexOfMinIntervalMs)
          val _tmpBackgroundRatio: Float
          _tmpBackgroundRatio = _stmt.getDouble(_columnIndexOfBackgroundRatio).toFloat()
          val _tmpHourlyDistribution: String
          _tmpHourlyDistribution = _stmt.getText(_columnIndexOfHourlyDistribution)
          val _tmpEncryptionStatus: String
          _tmpEncryptionStatus = _stmt.getText(_columnIndexOfEncryptionStatus)
          val _tmpTlsVersion: String?
          if (_stmt.isNull(_columnIndexOfTlsVersion)) {
            _tmpTlsVersion = null
          } else {
            _tmpTlsVersion = _stmt.getText(_columnIndexOfTlsVersion)
          }
          val _tmpSniHostname: String?
          if (_stmt.isNull(_columnIndexOfSniHostname)) {
            _tmpSniHostname = null
          } else {
            _tmpSniHostname = _stmt.getText(_columnIndexOfSniHostname)
          }
          val _tmpRiskScore: Int
          _tmpRiskScore = _stmt.getLong(_columnIndexOfRiskScore).toInt()
          val _tmpRiskSignalCodes: String
          _tmpRiskSignalCodes = _stmt.getText(_columnIndexOfRiskSignalCodes)
          val _tmpUpdatedAt: Long
          _tmpUpdatedAt = _stmt.getLong(_columnIndexOfUpdatedAt)
          _item = ConnectionProfileEntity(_tmpId,_tmpPackageName,_tmpHostname,_tmpDestinationIp,_tmpDestinationPort,_tmpConnectionCount,_tmpTotalBytesOut,_tmpTotalBytesIn,_tmpAvgBytesPerConnection,_tmpFirstSeen,_tmpLastSeen,_tmpAvgIntervalMs,_tmpMinIntervalMs,_tmpBackgroundRatio,_tmpHourlyDistribution,_tmpEncryptionStatus,_tmpTlsVersion,_tmpSniHostname,_tmpRiskScore,_tmpRiskSignalCodes,_tmpUpdatedAt)
          _result.add(_item)
        }
        _result
      } finally {
        _stmt.close()
      }
    }
  }

  public override suspend fun profilesForApp(pkg: String): List<ConnectionProfileEntity> {
    val _sql: String = "SELECT * FROM connection_profiles WHERE packageName = ? ORDER BY riskScore DESC"
    return performSuspending(__db, true, false) { _connection ->
      val _stmt: SQLiteStatement = _connection.prepare(_sql)
      try {
        var _argIndex: Int = 1
        _stmt.bindText(_argIndex, pkg)
        val _columnIndexOfId: Int = getColumnIndexOrThrow(_stmt, "id")
        val _columnIndexOfPackageName: Int = getColumnIndexOrThrow(_stmt, "packageName")
        val _columnIndexOfHostname: Int = getColumnIndexOrThrow(_stmt, "hostname")
        val _columnIndexOfDestinationIp: Int = getColumnIndexOrThrow(_stmt, "destinationIp")
        val _columnIndexOfDestinationPort: Int = getColumnIndexOrThrow(_stmt, "destinationPort")
        val _columnIndexOfConnectionCount: Int = getColumnIndexOrThrow(_stmt, "connectionCount")
        val _columnIndexOfTotalBytesOut: Int = getColumnIndexOrThrow(_stmt, "totalBytesOut")
        val _columnIndexOfTotalBytesIn: Int = getColumnIndexOrThrow(_stmt, "totalBytesIn")
        val _columnIndexOfAvgBytesPerConnection: Int = getColumnIndexOrThrow(_stmt, "avgBytesPerConnection")
        val _columnIndexOfFirstSeen: Int = getColumnIndexOrThrow(_stmt, "firstSeen")
        val _columnIndexOfLastSeen: Int = getColumnIndexOrThrow(_stmt, "lastSeen")
        val _columnIndexOfAvgIntervalMs: Int = getColumnIndexOrThrow(_stmt, "avgIntervalMs")
        val _columnIndexOfMinIntervalMs: Int = getColumnIndexOrThrow(_stmt, "minIntervalMs")
        val _columnIndexOfBackgroundRatio: Int = getColumnIndexOrThrow(_stmt, "backgroundRatio")
        val _columnIndexOfHourlyDistribution: Int = getColumnIndexOrThrow(_stmt, "hourlyDistribution")
        val _columnIndexOfEncryptionStatus: Int = getColumnIndexOrThrow(_stmt, "encryptionStatus")
        val _columnIndexOfTlsVersion: Int = getColumnIndexOrThrow(_stmt, "tlsVersion")
        val _columnIndexOfSniHostname: Int = getColumnIndexOrThrow(_stmt, "sniHostname")
        val _columnIndexOfRiskScore: Int = getColumnIndexOrThrow(_stmt, "riskScore")
        val _columnIndexOfRiskSignalCodes: Int = getColumnIndexOrThrow(_stmt, "riskSignalCodes")
        val _columnIndexOfUpdatedAt: Int = getColumnIndexOrThrow(_stmt, "updatedAt")
        val _result: MutableList<ConnectionProfileEntity> = mutableListOf()
        while (_stmt.step()) {
          val _item: ConnectionProfileEntity
          val _tmpId: Long
          _tmpId = _stmt.getLong(_columnIndexOfId)
          val _tmpPackageName: String
          _tmpPackageName = _stmt.getText(_columnIndexOfPackageName)
          val _tmpHostname: String
          _tmpHostname = _stmt.getText(_columnIndexOfHostname)
          val _tmpDestinationIp: String
          _tmpDestinationIp = _stmt.getText(_columnIndexOfDestinationIp)
          val _tmpDestinationPort: Int
          _tmpDestinationPort = _stmt.getLong(_columnIndexOfDestinationPort).toInt()
          val _tmpConnectionCount: Long
          _tmpConnectionCount = _stmt.getLong(_columnIndexOfConnectionCount)
          val _tmpTotalBytesOut: Long
          _tmpTotalBytesOut = _stmt.getLong(_columnIndexOfTotalBytesOut)
          val _tmpTotalBytesIn: Long
          _tmpTotalBytesIn = _stmt.getLong(_columnIndexOfTotalBytesIn)
          val _tmpAvgBytesPerConnection: Long
          _tmpAvgBytesPerConnection = _stmt.getLong(_columnIndexOfAvgBytesPerConnection)
          val _tmpFirstSeen: Long
          _tmpFirstSeen = _stmt.getLong(_columnIndexOfFirstSeen)
          val _tmpLastSeen: Long
          _tmpLastSeen = _stmt.getLong(_columnIndexOfLastSeen)
          val _tmpAvgIntervalMs: Long
          _tmpAvgIntervalMs = _stmt.getLong(_columnIndexOfAvgIntervalMs)
          val _tmpMinIntervalMs: Long
          _tmpMinIntervalMs = _stmt.getLong(_columnIndexOfMinIntervalMs)
          val _tmpBackgroundRatio: Float
          _tmpBackgroundRatio = _stmt.getDouble(_columnIndexOfBackgroundRatio).toFloat()
          val _tmpHourlyDistribution: String
          _tmpHourlyDistribution = _stmt.getText(_columnIndexOfHourlyDistribution)
          val _tmpEncryptionStatus: String
          _tmpEncryptionStatus = _stmt.getText(_columnIndexOfEncryptionStatus)
          val _tmpTlsVersion: String?
          if (_stmt.isNull(_columnIndexOfTlsVersion)) {
            _tmpTlsVersion = null
          } else {
            _tmpTlsVersion = _stmt.getText(_columnIndexOfTlsVersion)
          }
          val _tmpSniHostname: String?
          if (_stmt.isNull(_columnIndexOfSniHostname)) {
            _tmpSniHostname = null
          } else {
            _tmpSniHostname = _stmt.getText(_columnIndexOfSniHostname)
          }
          val _tmpRiskScore: Int
          _tmpRiskScore = _stmt.getLong(_columnIndexOfRiskScore).toInt()
          val _tmpRiskSignalCodes: String
          _tmpRiskSignalCodes = _stmt.getText(_columnIndexOfRiskSignalCodes)
          val _tmpUpdatedAt: Long
          _tmpUpdatedAt = _stmt.getLong(_columnIndexOfUpdatedAt)
          _item = ConnectionProfileEntity(_tmpId,_tmpPackageName,_tmpHostname,_tmpDestinationIp,_tmpDestinationPort,_tmpConnectionCount,_tmpTotalBytesOut,_tmpTotalBytesIn,_tmpAvgBytesPerConnection,_tmpFirstSeen,_tmpLastSeen,_tmpAvgIntervalMs,_tmpMinIntervalMs,_tmpBackgroundRatio,_tmpHourlyDistribution,_tmpEncryptionStatus,_tmpTlsVersion,_tmpSniHostname,_tmpRiskScore,_tmpRiskSignalCodes,_tmpUpdatedAt)
          _result.add(_item)
        }
        _result
      } finally {
        _stmt.close()
      }
    }
  }

  public override suspend fun riskyProfiles(min: Int): List<ConnectionProfileEntity> {
    val _sql: String = "SELECT * FROM connection_profiles WHERE riskScore >= ? ORDER BY riskScore DESC"
    return performSuspending(__db, true, false) { _connection ->
      val _stmt: SQLiteStatement = _connection.prepare(_sql)
      try {
        var _argIndex: Int = 1
        _stmt.bindLong(_argIndex, min.toLong())
        val _columnIndexOfId: Int = getColumnIndexOrThrow(_stmt, "id")
        val _columnIndexOfPackageName: Int = getColumnIndexOrThrow(_stmt, "packageName")
        val _columnIndexOfHostname: Int = getColumnIndexOrThrow(_stmt, "hostname")
        val _columnIndexOfDestinationIp: Int = getColumnIndexOrThrow(_stmt, "destinationIp")
        val _columnIndexOfDestinationPort: Int = getColumnIndexOrThrow(_stmt, "destinationPort")
        val _columnIndexOfConnectionCount: Int = getColumnIndexOrThrow(_stmt, "connectionCount")
        val _columnIndexOfTotalBytesOut: Int = getColumnIndexOrThrow(_stmt, "totalBytesOut")
        val _columnIndexOfTotalBytesIn: Int = getColumnIndexOrThrow(_stmt, "totalBytesIn")
        val _columnIndexOfAvgBytesPerConnection: Int = getColumnIndexOrThrow(_stmt, "avgBytesPerConnection")
        val _columnIndexOfFirstSeen: Int = getColumnIndexOrThrow(_stmt, "firstSeen")
        val _columnIndexOfLastSeen: Int = getColumnIndexOrThrow(_stmt, "lastSeen")
        val _columnIndexOfAvgIntervalMs: Int = getColumnIndexOrThrow(_stmt, "avgIntervalMs")
        val _columnIndexOfMinIntervalMs: Int = getColumnIndexOrThrow(_stmt, "minIntervalMs")
        val _columnIndexOfBackgroundRatio: Int = getColumnIndexOrThrow(_stmt, "backgroundRatio")
        val _columnIndexOfHourlyDistribution: Int = getColumnIndexOrThrow(_stmt, "hourlyDistribution")
        val _columnIndexOfEncryptionStatus: Int = getColumnIndexOrThrow(_stmt, "encryptionStatus")
        val _columnIndexOfTlsVersion: Int = getColumnIndexOrThrow(_stmt, "tlsVersion")
        val _columnIndexOfSniHostname: Int = getColumnIndexOrThrow(_stmt, "sniHostname")
        val _columnIndexOfRiskScore: Int = getColumnIndexOrThrow(_stmt, "riskScore")
        val _columnIndexOfRiskSignalCodes: Int = getColumnIndexOrThrow(_stmt, "riskSignalCodes")
        val _columnIndexOfUpdatedAt: Int = getColumnIndexOrThrow(_stmt, "updatedAt")
        val _result: MutableList<ConnectionProfileEntity> = mutableListOf()
        while (_stmt.step()) {
          val _item: ConnectionProfileEntity
          val _tmpId: Long
          _tmpId = _stmt.getLong(_columnIndexOfId)
          val _tmpPackageName: String
          _tmpPackageName = _stmt.getText(_columnIndexOfPackageName)
          val _tmpHostname: String
          _tmpHostname = _stmt.getText(_columnIndexOfHostname)
          val _tmpDestinationIp: String
          _tmpDestinationIp = _stmt.getText(_columnIndexOfDestinationIp)
          val _tmpDestinationPort: Int
          _tmpDestinationPort = _stmt.getLong(_columnIndexOfDestinationPort).toInt()
          val _tmpConnectionCount: Long
          _tmpConnectionCount = _stmt.getLong(_columnIndexOfConnectionCount)
          val _tmpTotalBytesOut: Long
          _tmpTotalBytesOut = _stmt.getLong(_columnIndexOfTotalBytesOut)
          val _tmpTotalBytesIn: Long
          _tmpTotalBytesIn = _stmt.getLong(_columnIndexOfTotalBytesIn)
          val _tmpAvgBytesPerConnection: Long
          _tmpAvgBytesPerConnection = _stmt.getLong(_columnIndexOfAvgBytesPerConnection)
          val _tmpFirstSeen: Long
          _tmpFirstSeen = _stmt.getLong(_columnIndexOfFirstSeen)
          val _tmpLastSeen: Long
          _tmpLastSeen = _stmt.getLong(_columnIndexOfLastSeen)
          val _tmpAvgIntervalMs: Long
          _tmpAvgIntervalMs = _stmt.getLong(_columnIndexOfAvgIntervalMs)
          val _tmpMinIntervalMs: Long
          _tmpMinIntervalMs = _stmt.getLong(_columnIndexOfMinIntervalMs)
          val _tmpBackgroundRatio: Float
          _tmpBackgroundRatio = _stmt.getDouble(_columnIndexOfBackgroundRatio).toFloat()
          val _tmpHourlyDistribution: String
          _tmpHourlyDistribution = _stmt.getText(_columnIndexOfHourlyDistribution)
          val _tmpEncryptionStatus: String
          _tmpEncryptionStatus = _stmt.getText(_columnIndexOfEncryptionStatus)
          val _tmpTlsVersion: String?
          if (_stmt.isNull(_columnIndexOfTlsVersion)) {
            _tmpTlsVersion = null
          } else {
            _tmpTlsVersion = _stmt.getText(_columnIndexOfTlsVersion)
          }
          val _tmpSniHostname: String?
          if (_stmt.isNull(_columnIndexOfSniHostname)) {
            _tmpSniHostname = null
          } else {
            _tmpSniHostname = _stmt.getText(_columnIndexOfSniHostname)
          }
          val _tmpRiskScore: Int
          _tmpRiskScore = _stmt.getLong(_columnIndexOfRiskScore).toInt()
          val _tmpRiskSignalCodes: String
          _tmpRiskSignalCodes = _stmt.getText(_columnIndexOfRiskSignalCodes)
          val _tmpUpdatedAt: Long
          _tmpUpdatedAt = _stmt.getLong(_columnIndexOfUpdatedAt)
          _item = ConnectionProfileEntity(_tmpId,_tmpPackageName,_tmpHostname,_tmpDestinationIp,_tmpDestinationPort,_tmpConnectionCount,_tmpTotalBytesOut,_tmpTotalBytesIn,_tmpAvgBytesPerConnection,_tmpFirstSeen,_tmpLastSeen,_tmpAvgIntervalMs,_tmpMinIntervalMs,_tmpBackgroundRatio,_tmpHourlyDistribution,_tmpEncryptionStatus,_tmpTlsVersion,_tmpSniHostname,_tmpRiskScore,_tmpRiskSignalCodes,_tmpUpdatedAt)
          _result.add(_item)
        }
        _result
      } finally {
        _stmt.close()
      }
    }
  }

  public override suspend fun cleartextProfiles(): List<ConnectionProfileEntity> {
    val _sql: String = "SELECT * FROM connection_profiles WHERE encryptionStatus = 'CLEARTEXT'"
    return performSuspending(__db, true, false) { _connection ->
      val _stmt: SQLiteStatement = _connection.prepare(_sql)
      try {
        val _columnIndexOfId: Int = getColumnIndexOrThrow(_stmt, "id")
        val _columnIndexOfPackageName: Int = getColumnIndexOrThrow(_stmt, "packageName")
        val _columnIndexOfHostname: Int = getColumnIndexOrThrow(_stmt, "hostname")
        val _columnIndexOfDestinationIp: Int = getColumnIndexOrThrow(_stmt, "destinationIp")
        val _columnIndexOfDestinationPort: Int = getColumnIndexOrThrow(_stmt, "destinationPort")
        val _columnIndexOfConnectionCount: Int = getColumnIndexOrThrow(_stmt, "connectionCount")
        val _columnIndexOfTotalBytesOut: Int = getColumnIndexOrThrow(_stmt, "totalBytesOut")
        val _columnIndexOfTotalBytesIn: Int = getColumnIndexOrThrow(_stmt, "totalBytesIn")
        val _columnIndexOfAvgBytesPerConnection: Int = getColumnIndexOrThrow(_stmt, "avgBytesPerConnection")
        val _columnIndexOfFirstSeen: Int = getColumnIndexOrThrow(_stmt, "firstSeen")
        val _columnIndexOfLastSeen: Int = getColumnIndexOrThrow(_stmt, "lastSeen")
        val _columnIndexOfAvgIntervalMs: Int = getColumnIndexOrThrow(_stmt, "avgIntervalMs")
        val _columnIndexOfMinIntervalMs: Int = getColumnIndexOrThrow(_stmt, "minIntervalMs")
        val _columnIndexOfBackgroundRatio: Int = getColumnIndexOrThrow(_stmt, "backgroundRatio")
        val _columnIndexOfHourlyDistribution: Int = getColumnIndexOrThrow(_stmt, "hourlyDistribution")
        val _columnIndexOfEncryptionStatus: Int = getColumnIndexOrThrow(_stmt, "encryptionStatus")
        val _columnIndexOfTlsVersion: Int = getColumnIndexOrThrow(_stmt, "tlsVersion")
        val _columnIndexOfSniHostname: Int = getColumnIndexOrThrow(_stmt, "sniHostname")
        val _columnIndexOfRiskScore: Int = getColumnIndexOrThrow(_stmt, "riskScore")
        val _columnIndexOfRiskSignalCodes: Int = getColumnIndexOrThrow(_stmt, "riskSignalCodes")
        val _columnIndexOfUpdatedAt: Int = getColumnIndexOrThrow(_stmt, "updatedAt")
        val _result: MutableList<ConnectionProfileEntity> = mutableListOf()
        while (_stmt.step()) {
          val _item: ConnectionProfileEntity
          val _tmpId: Long
          _tmpId = _stmt.getLong(_columnIndexOfId)
          val _tmpPackageName: String
          _tmpPackageName = _stmt.getText(_columnIndexOfPackageName)
          val _tmpHostname: String
          _tmpHostname = _stmt.getText(_columnIndexOfHostname)
          val _tmpDestinationIp: String
          _tmpDestinationIp = _stmt.getText(_columnIndexOfDestinationIp)
          val _tmpDestinationPort: Int
          _tmpDestinationPort = _stmt.getLong(_columnIndexOfDestinationPort).toInt()
          val _tmpConnectionCount: Long
          _tmpConnectionCount = _stmt.getLong(_columnIndexOfConnectionCount)
          val _tmpTotalBytesOut: Long
          _tmpTotalBytesOut = _stmt.getLong(_columnIndexOfTotalBytesOut)
          val _tmpTotalBytesIn: Long
          _tmpTotalBytesIn = _stmt.getLong(_columnIndexOfTotalBytesIn)
          val _tmpAvgBytesPerConnection: Long
          _tmpAvgBytesPerConnection = _stmt.getLong(_columnIndexOfAvgBytesPerConnection)
          val _tmpFirstSeen: Long
          _tmpFirstSeen = _stmt.getLong(_columnIndexOfFirstSeen)
          val _tmpLastSeen: Long
          _tmpLastSeen = _stmt.getLong(_columnIndexOfLastSeen)
          val _tmpAvgIntervalMs: Long
          _tmpAvgIntervalMs = _stmt.getLong(_columnIndexOfAvgIntervalMs)
          val _tmpMinIntervalMs: Long
          _tmpMinIntervalMs = _stmt.getLong(_columnIndexOfMinIntervalMs)
          val _tmpBackgroundRatio: Float
          _tmpBackgroundRatio = _stmt.getDouble(_columnIndexOfBackgroundRatio).toFloat()
          val _tmpHourlyDistribution: String
          _tmpHourlyDistribution = _stmt.getText(_columnIndexOfHourlyDistribution)
          val _tmpEncryptionStatus: String
          _tmpEncryptionStatus = _stmt.getText(_columnIndexOfEncryptionStatus)
          val _tmpTlsVersion: String?
          if (_stmt.isNull(_columnIndexOfTlsVersion)) {
            _tmpTlsVersion = null
          } else {
            _tmpTlsVersion = _stmt.getText(_columnIndexOfTlsVersion)
          }
          val _tmpSniHostname: String?
          if (_stmt.isNull(_columnIndexOfSniHostname)) {
            _tmpSniHostname = null
          } else {
            _tmpSniHostname = _stmt.getText(_columnIndexOfSniHostname)
          }
          val _tmpRiskScore: Int
          _tmpRiskScore = _stmt.getLong(_columnIndexOfRiskScore).toInt()
          val _tmpRiskSignalCodes: String
          _tmpRiskSignalCodes = _stmt.getText(_columnIndexOfRiskSignalCodes)
          val _tmpUpdatedAt: Long
          _tmpUpdatedAt = _stmt.getLong(_columnIndexOfUpdatedAt)
          _item = ConnectionProfileEntity(_tmpId,_tmpPackageName,_tmpHostname,_tmpDestinationIp,_tmpDestinationPort,_tmpConnectionCount,_tmpTotalBytesOut,_tmpTotalBytesIn,_tmpAvgBytesPerConnection,_tmpFirstSeen,_tmpLastSeen,_tmpAvgIntervalMs,_tmpMinIntervalMs,_tmpBackgroundRatio,_tmpHourlyDistribution,_tmpEncryptionStatus,_tmpTlsVersion,_tmpSniHostname,_tmpRiskScore,_tmpRiskSignalCodes,_tmpUpdatedAt)
          _result.add(_item)
        }
        _result
      } finally {
        _stmt.close()
      }
    }
  }

  public override suspend fun search(q: String): List<ConnectionProfileEntity> {
    val _sql: String = "SELECT * FROM connection_profiles WHERE hostname LIKE '%' || ? || '%' OR packageName LIKE '%' || ? || '%'"
    return performSuspending(__db, true, false) { _connection ->
      val _stmt: SQLiteStatement = _connection.prepare(_sql)
      try {
        var _argIndex: Int = 1
        _stmt.bindText(_argIndex, q)
        _argIndex = 2
        _stmt.bindText(_argIndex, q)
        val _columnIndexOfId: Int = getColumnIndexOrThrow(_stmt, "id")
        val _columnIndexOfPackageName: Int = getColumnIndexOrThrow(_stmt, "packageName")
        val _columnIndexOfHostname: Int = getColumnIndexOrThrow(_stmt, "hostname")
        val _columnIndexOfDestinationIp: Int = getColumnIndexOrThrow(_stmt, "destinationIp")
        val _columnIndexOfDestinationPort: Int = getColumnIndexOrThrow(_stmt, "destinationPort")
        val _columnIndexOfConnectionCount: Int = getColumnIndexOrThrow(_stmt, "connectionCount")
        val _columnIndexOfTotalBytesOut: Int = getColumnIndexOrThrow(_stmt, "totalBytesOut")
        val _columnIndexOfTotalBytesIn: Int = getColumnIndexOrThrow(_stmt, "totalBytesIn")
        val _columnIndexOfAvgBytesPerConnection: Int = getColumnIndexOrThrow(_stmt, "avgBytesPerConnection")
        val _columnIndexOfFirstSeen: Int = getColumnIndexOrThrow(_stmt, "firstSeen")
        val _columnIndexOfLastSeen: Int = getColumnIndexOrThrow(_stmt, "lastSeen")
        val _columnIndexOfAvgIntervalMs: Int = getColumnIndexOrThrow(_stmt, "avgIntervalMs")
        val _columnIndexOfMinIntervalMs: Int = getColumnIndexOrThrow(_stmt, "minIntervalMs")
        val _columnIndexOfBackgroundRatio: Int = getColumnIndexOrThrow(_stmt, "backgroundRatio")
        val _columnIndexOfHourlyDistribution: Int = getColumnIndexOrThrow(_stmt, "hourlyDistribution")
        val _columnIndexOfEncryptionStatus: Int = getColumnIndexOrThrow(_stmt, "encryptionStatus")
        val _columnIndexOfTlsVersion: Int = getColumnIndexOrThrow(_stmt, "tlsVersion")
        val _columnIndexOfSniHostname: Int = getColumnIndexOrThrow(_stmt, "sniHostname")
        val _columnIndexOfRiskScore: Int = getColumnIndexOrThrow(_stmt, "riskScore")
        val _columnIndexOfRiskSignalCodes: Int = getColumnIndexOrThrow(_stmt, "riskSignalCodes")
        val _columnIndexOfUpdatedAt: Int = getColumnIndexOrThrow(_stmt, "updatedAt")
        val _result: MutableList<ConnectionProfileEntity> = mutableListOf()
        while (_stmt.step()) {
          val _item: ConnectionProfileEntity
          val _tmpId: Long
          _tmpId = _stmt.getLong(_columnIndexOfId)
          val _tmpPackageName: String
          _tmpPackageName = _stmt.getText(_columnIndexOfPackageName)
          val _tmpHostname: String
          _tmpHostname = _stmt.getText(_columnIndexOfHostname)
          val _tmpDestinationIp: String
          _tmpDestinationIp = _stmt.getText(_columnIndexOfDestinationIp)
          val _tmpDestinationPort: Int
          _tmpDestinationPort = _stmt.getLong(_columnIndexOfDestinationPort).toInt()
          val _tmpConnectionCount: Long
          _tmpConnectionCount = _stmt.getLong(_columnIndexOfConnectionCount)
          val _tmpTotalBytesOut: Long
          _tmpTotalBytesOut = _stmt.getLong(_columnIndexOfTotalBytesOut)
          val _tmpTotalBytesIn: Long
          _tmpTotalBytesIn = _stmt.getLong(_columnIndexOfTotalBytesIn)
          val _tmpAvgBytesPerConnection: Long
          _tmpAvgBytesPerConnection = _stmt.getLong(_columnIndexOfAvgBytesPerConnection)
          val _tmpFirstSeen: Long
          _tmpFirstSeen = _stmt.getLong(_columnIndexOfFirstSeen)
          val _tmpLastSeen: Long
          _tmpLastSeen = _stmt.getLong(_columnIndexOfLastSeen)
          val _tmpAvgIntervalMs: Long
          _tmpAvgIntervalMs = _stmt.getLong(_columnIndexOfAvgIntervalMs)
          val _tmpMinIntervalMs: Long
          _tmpMinIntervalMs = _stmt.getLong(_columnIndexOfMinIntervalMs)
          val _tmpBackgroundRatio: Float
          _tmpBackgroundRatio = _stmt.getDouble(_columnIndexOfBackgroundRatio).toFloat()
          val _tmpHourlyDistribution: String
          _tmpHourlyDistribution = _stmt.getText(_columnIndexOfHourlyDistribution)
          val _tmpEncryptionStatus: String
          _tmpEncryptionStatus = _stmt.getText(_columnIndexOfEncryptionStatus)
          val _tmpTlsVersion: String?
          if (_stmt.isNull(_columnIndexOfTlsVersion)) {
            _tmpTlsVersion = null
          } else {
            _tmpTlsVersion = _stmt.getText(_columnIndexOfTlsVersion)
          }
          val _tmpSniHostname: String?
          if (_stmt.isNull(_columnIndexOfSniHostname)) {
            _tmpSniHostname = null
          } else {
            _tmpSniHostname = _stmt.getText(_columnIndexOfSniHostname)
          }
          val _tmpRiskScore: Int
          _tmpRiskScore = _stmt.getLong(_columnIndexOfRiskScore).toInt()
          val _tmpRiskSignalCodes: String
          _tmpRiskSignalCodes = _stmt.getText(_columnIndexOfRiskSignalCodes)
          val _tmpUpdatedAt: Long
          _tmpUpdatedAt = _stmt.getLong(_columnIndexOfUpdatedAt)
          _item = ConnectionProfileEntity(_tmpId,_tmpPackageName,_tmpHostname,_tmpDestinationIp,_tmpDestinationPort,_tmpConnectionCount,_tmpTotalBytesOut,_tmpTotalBytesIn,_tmpAvgBytesPerConnection,_tmpFirstSeen,_tmpLastSeen,_tmpAvgIntervalMs,_tmpMinIntervalMs,_tmpBackgroundRatio,_tmpHourlyDistribution,_tmpEncryptionStatus,_tmpTlsVersion,_tmpSniHostname,_tmpRiskScore,_tmpRiskSignalCodes,_tmpUpdatedAt)
          _result.add(_item)
        }
        _result
      } finally {
        _stmt.close()
      }
    }
  }

  public override suspend fun trackedPackages(): List<String> {
    val _sql: String = "SELECT DISTINCT packageName FROM connection_profiles ORDER BY packageName ASC"
    return performSuspending(__db, true, false) { _connection ->
      val _stmt: SQLiteStatement = _connection.prepare(_sql)
      try {
        val _result: MutableList<String> = mutableListOf()
        while (_stmt.step()) {
          val _item: String
          _item = _stmt.getText(0)
          _result.add(_item)
        }
        _result
      } finally {
        _stmt.close()
      }
    }
  }

  public override suspend fun deleteOlderThan(before: Long): Int {
    val _sql: String = "DELETE FROM connection_profiles WHERE lastSeen < ?"
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
    val _sql: String = "DELETE FROM connection_profiles"
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
