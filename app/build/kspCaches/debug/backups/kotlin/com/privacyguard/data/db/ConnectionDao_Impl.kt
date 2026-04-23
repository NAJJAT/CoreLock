package com.privacyguard.`data`.db

import androidx.room.EntityInsertAdapter
import androidx.room.RoomDatabase
import androidx.room.util.getColumnIndexOrThrow
import androidx.room.util.getTotalChangedRows
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
      protected override fun createQuery(): String = "INSERT OR REPLACE INTO `connections` (`id`,`timestamp`,`protocol`,`sourceIp`,`sourcePort`,`destinationIp`,`destinationPort`,`hostname`,`ownerUid`,`ownerPackage`,`bytesOut`,`bytesIn`,`wasBlocked`,`matchedRuleId`,`durationMs`,`encryptionStatus`,`tlsVersion`,`sniHostname`,`wasBackground`) VALUES (nullif(?, 0),?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)"

      protected override fun bind(statement: SQLiteStatement, entity: ConnectionEntity) {
        statement.bindLong(1, entity.id)
        statement.bindLong(2, entity.timestamp)
        statement.bindLong(3, entity.protocol.toLong())
        statement.bindText(4, entity.sourceIp)
        statement.bindLong(5, entity.sourcePort.toLong())
        statement.bindText(6, entity.destinationIp)
        statement.bindLong(7, entity.destinationPort.toLong())
        val _tmpHostname: String? = entity.hostname
        if (_tmpHostname == null) {
          statement.bindNull(8)
        } else {
          statement.bindText(8, _tmpHostname)
        }
        statement.bindLong(9, entity.ownerUid.toLong())
        val _tmpOwnerPackage: String? = entity.ownerPackage
        if (_tmpOwnerPackage == null) {
          statement.bindNull(10)
        } else {
          statement.bindText(10, _tmpOwnerPackage)
        }
        statement.bindLong(11, entity.bytesOut)
        statement.bindLong(12, entity.bytesIn)
        val _tmp: Int = if (entity.wasBlocked) 1 else 0
        statement.bindLong(13, _tmp.toLong())
        val _tmpMatchedRuleId: String? = entity.matchedRuleId
        if (_tmpMatchedRuleId == null) {
          statement.bindNull(14)
        } else {
          statement.bindText(14, _tmpMatchedRuleId)
        }
        statement.bindLong(15, entity.durationMs)
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
        val _tmp_1: Int = if (entity.wasBackground) 1 else 0
        statement.bindLong(19, _tmp_1.toLong())
      }
    }
  }

  public override suspend fun insert(c: ConnectionEntity): Long = performSuspending(__db, false, true) { _connection ->
    val _result: Long = __insertAdapterOfConnectionEntity.insertAndReturnId(_connection, c)
    _result
  }

  public override suspend fun insertAll(cs: List<ConnectionEntity>): Unit = performSuspending(__db, false, true) { _connection ->
    __insertAdapterOfConnectionEntity.insert(_connection, cs)
  }

  public override suspend fun recent(limit: Int): List<ConnectionEntity> {
    val _sql: String = "SELECT * FROM connections ORDER BY timestamp DESC LIMIT ?"
    return performSuspending(__db, true, false) { _connection ->
      val _stmt: SQLiteStatement = _connection.prepare(_sql)
      try {
        var _argIndex: Int = 1
        _stmt.bindLong(_argIndex, limit.toLong())
        val _columnIndexOfId: Int = getColumnIndexOrThrow(_stmt, "id")
        val _columnIndexOfTimestamp: Int = getColumnIndexOrThrow(_stmt, "timestamp")
        val _columnIndexOfProtocol: Int = getColumnIndexOrThrow(_stmt, "protocol")
        val _columnIndexOfSourceIp: Int = getColumnIndexOrThrow(_stmt, "sourceIp")
        val _columnIndexOfSourcePort: Int = getColumnIndexOrThrow(_stmt, "sourcePort")
        val _columnIndexOfDestinationIp: Int = getColumnIndexOrThrow(_stmt, "destinationIp")
        val _columnIndexOfDestinationPort: Int = getColumnIndexOrThrow(_stmt, "destinationPort")
        val _columnIndexOfHostname: Int = getColumnIndexOrThrow(_stmt, "hostname")
        val _columnIndexOfOwnerUid: Int = getColumnIndexOrThrow(_stmt, "ownerUid")
        val _columnIndexOfOwnerPackage: Int = getColumnIndexOrThrow(_stmt, "ownerPackage")
        val _columnIndexOfBytesOut: Int = getColumnIndexOrThrow(_stmt, "bytesOut")
        val _columnIndexOfBytesIn: Int = getColumnIndexOrThrow(_stmt, "bytesIn")
        val _columnIndexOfWasBlocked: Int = getColumnIndexOrThrow(_stmt, "wasBlocked")
        val _columnIndexOfMatchedRuleId: Int = getColumnIndexOrThrow(_stmt, "matchedRuleId")
        val _columnIndexOfDurationMs: Int = getColumnIndexOrThrow(_stmt, "durationMs")
        val _columnIndexOfEncryptionStatus: Int = getColumnIndexOrThrow(_stmt, "encryptionStatus")
        val _columnIndexOfTlsVersion: Int = getColumnIndexOrThrow(_stmt, "tlsVersion")
        val _columnIndexOfSniHostname: Int = getColumnIndexOrThrow(_stmt, "sniHostname")
        val _columnIndexOfWasBackground: Int = getColumnIndexOrThrow(_stmt, "wasBackground")
        val _result: MutableList<ConnectionEntity> = mutableListOf()
        while (_stmt.step()) {
          val _item: ConnectionEntity
          val _tmpId: Long
          _tmpId = _stmt.getLong(_columnIndexOfId)
          val _tmpTimestamp: Long
          _tmpTimestamp = _stmt.getLong(_columnIndexOfTimestamp)
          val _tmpProtocol: Int
          _tmpProtocol = _stmt.getLong(_columnIndexOfProtocol).toInt()
          val _tmpSourceIp: String
          _tmpSourceIp = _stmt.getText(_columnIndexOfSourceIp)
          val _tmpSourcePort: Int
          _tmpSourcePort = _stmt.getLong(_columnIndexOfSourcePort).toInt()
          val _tmpDestinationIp: String
          _tmpDestinationIp = _stmt.getText(_columnIndexOfDestinationIp)
          val _tmpDestinationPort: Int
          _tmpDestinationPort = _stmt.getLong(_columnIndexOfDestinationPort).toInt()
          val _tmpHostname: String?
          if (_stmt.isNull(_columnIndexOfHostname)) {
            _tmpHostname = null
          } else {
            _tmpHostname = _stmt.getText(_columnIndexOfHostname)
          }
          val _tmpOwnerUid: Int
          _tmpOwnerUid = _stmt.getLong(_columnIndexOfOwnerUid).toInt()
          val _tmpOwnerPackage: String?
          if (_stmt.isNull(_columnIndexOfOwnerPackage)) {
            _tmpOwnerPackage = null
          } else {
            _tmpOwnerPackage = _stmt.getText(_columnIndexOfOwnerPackage)
          }
          val _tmpBytesOut: Long
          _tmpBytesOut = _stmt.getLong(_columnIndexOfBytesOut)
          val _tmpBytesIn: Long
          _tmpBytesIn = _stmt.getLong(_columnIndexOfBytesIn)
          val _tmpWasBlocked: Boolean
          val _tmp: Int
          _tmp = _stmt.getLong(_columnIndexOfWasBlocked).toInt()
          _tmpWasBlocked = _tmp != 0
          val _tmpMatchedRuleId: String?
          if (_stmt.isNull(_columnIndexOfMatchedRuleId)) {
            _tmpMatchedRuleId = null
          } else {
            _tmpMatchedRuleId = _stmt.getText(_columnIndexOfMatchedRuleId)
          }
          val _tmpDurationMs: Long
          _tmpDurationMs = _stmt.getLong(_columnIndexOfDurationMs)
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
          val _tmpWasBackground: Boolean
          val _tmp_1: Int
          _tmp_1 = _stmt.getLong(_columnIndexOfWasBackground).toInt()
          _tmpWasBackground = _tmp_1 != 0
          _item = ConnectionEntity(_tmpId,_tmpTimestamp,_tmpProtocol,_tmpSourceIp,_tmpSourcePort,_tmpDestinationIp,_tmpDestinationPort,_tmpHostname,_tmpOwnerUid,_tmpOwnerPackage,_tmpBytesOut,_tmpBytesIn,_tmpWasBlocked,_tmpMatchedRuleId,_tmpDurationMs,_tmpEncryptionStatus,_tmpTlsVersion,_tmpSniHostname,_tmpWasBackground)
          _result.add(_item)
        }
        _result
      } finally {
        _stmt.close()
      }
    }
  }

  public override suspend fun forPackage(pkg: String, limit: Int): List<ConnectionEntity> {
    val _sql: String = "SELECT * FROM connections WHERE ownerPackage = ? ORDER BY timestamp DESC LIMIT ?"
    return performSuspending(__db, true, false) { _connection ->
      val _stmt: SQLiteStatement = _connection.prepare(_sql)
      try {
        var _argIndex: Int = 1
        _stmt.bindText(_argIndex, pkg)
        _argIndex = 2
        _stmt.bindLong(_argIndex, limit.toLong())
        val _columnIndexOfId: Int = getColumnIndexOrThrow(_stmt, "id")
        val _columnIndexOfTimestamp: Int = getColumnIndexOrThrow(_stmt, "timestamp")
        val _columnIndexOfProtocol: Int = getColumnIndexOrThrow(_stmt, "protocol")
        val _columnIndexOfSourceIp: Int = getColumnIndexOrThrow(_stmt, "sourceIp")
        val _columnIndexOfSourcePort: Int = getColumnIndexOrThrow(_stmt, "sourcePort")
        val _columnIndexOfDestinationIp: Int = getColumnIndexOrThrow(_stmt, "destinationIp")
        val _columnIndexOfDestinationPort: Int = getColumnIndexOrThrow(_stmt, "destinationPort")
        val _columnIndexOfHostname: Int = getColumnIndexOrThrow(_stmt, "hostname")
        val _columnIndexOfOwnerUid: Int = getColumnIndexOrThrow(_stmt, "ownerUid")
        val _columnIndexOfOwnerPackage: Int = getColumnIndexOrThrow(_stmt, "ownerPackage")
        val _columnIndexOfBytesOut: Int = getColumnIndexOrThrow(_stmt, "bytesOut")
        val _columnIndexOfBytesIn: Int = getColumnIndexOrThrow(_stmt, "bytesIn")
        val _columnIndexOfWasBlocked: Int = getColumnIndexOrThrow(_stmt, "wasBlocked")
        val _columnIndexOfMatchedRuleId: Int = getColumnIndexOrThrow(_stmt, "matchedRuleId")
        val _columnIndexOfDurationMs: Int = getColumnIndexOrThrow(_stmt, "durationMs")
        val _columnIndexOfEncryptionStatus: Int = getColumnIndexOrThrow(_stmt, "encryptionStatus")
        val _columnIndexOfTlsVersion: Int = getColumnIndexOrThrow(_stmt, "tlsVersion")
        val _columnIndexOfSniHostname: Int = getColumnIndexOrThrow(_stmt, "sniHostname")
        val _columnIndexOfWasBackground: Int = getColumnIndexOrThrow(_stmt, "wasBackground")
        val _result: MutableList<ConnectionEntity> = mutableListOf()
        while (_stmt.step()) {
          val _item: ConnectionEntity
          val _tmpId: Long
          _tmpId = _stmt.getLong(_columnIndexOfId)
          val _tmpTimestamp: Long
          _tmpTimestamp = _stmt.getLong(_columnIndexOfTimestamp)
          val _tmpProtocol: Int
          _tmpProtocol = _stmt.getLong(_columnIndexOfProtocol).toInt()
          val _tmpSourceIp: String
          _tmpSourceIp = _stmt.getText(_columnIndexOfSourceIp)
          val _tmpSourcePort: Int
          _tmpSourcePort = _stmt.getLong(_columnIndexOfSourcePort).toInt()
          val _tmpDestinationIp: String
          _tmpDestinationIp = _stmt.getText(_columnIndexOfDestinationIp)
          val _tmpDestinationPort: Int
          _tmpDestinationPort = _stmt.getLong(_columnIndexOfDestinationPort).toInt()
          val _tmpHostname: String?
          if (_stmt.isNull(_columnIndexOfHostname)) {
            _tmpHostname = null
          } else {
            _tmpHostname = _stmt.getText(_columnIndexOfHostname)
          }
          val _tmpOwnerUid: Int
          _tmpOwnerUid = _stmt.getLong(_columnIndexOfOwnerUid).toInt()
          val _tmpOwnerPackage: String?
          if (_stmt.isNull(_columnIndexOfOwnerPackage)) {
            _tmpOwnerPackage = null
          } else {
            _tmpOwnerPackage = _stmt.getText(_columnIndexOfOwnerPackage)
          }
          val _tmpBytesOut: Long
          _tmpBytesOut = _stmt.getLong(_columnIndexOfBytesOut)
          val _tmpBytesIn: Long
          _tmpBytesIn = _stmt.getLong(_columnIndexOfBytesIn)
          val _tmpWasBlocked: Boolean
          val _tmp: Int
          _tmp = _stmt.getLong(_columnIndexOfWasBlocked).toInt()
          _tmpWasBlocked = _tmp != 0
          val _tmpMatchedRuleId: String?
          if (_stmt.isNull(_columnIndexOfMatchedRuleId)) {
            _tmpMatchedRuleId = null
          } else {
            _tmpMatchedRuleId = _stmt.getText(_columnIndexOfMatchedRuleId)
          }
          val _tmpDurationMs: Long
          _tmpDurationMs = _stmt.getLong(_columnIndexOfDurationMs)
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
          val _tmpWasBackground: Boolean
          val _tmp_1: Int
          _tmp_1 = _stmt.getLong(_columnIndexOfWasBackground).toInt()
          _tmpWasBackground = _tmp_1 != 0
          _item = ConnectionEntity(_tmpId,_tmpTimestamp,_tmpProtocol,_tmpSourceIp,_tmpSourcePort,_tmpDestinationIp,_tmpDestinationPort,_tmpHostname,_tmpOwnerUid,_tmpOwnerPackage,_tmpBytesOut,_tmpBytesIn,_tmpWasBlocked,_tmpMatchedRuleId,_tmpDurationMs,_tmpEncryptionStatus,_tmpTlsVersion,_tmpSniHostname,_tmpWasBackground)
          _result.add(_item)
        }
        _result
      } finally {
        _stmt.close()
      }
    }
  }

  public override suspend fun recentBlocked(limit: Int): List<ConnectionEntity> {
    val _sql: String = "SELECT * FROM connections WHERE wasBlocked = 1 ORDER BY timestamp DESC LIMIT ?"
    return performSuspending(__db, true, false) { _connection ->
      val _stmt: SQLiteStatement = _connection.prepare(_sql)
      try {
        var _argIndex: Int = 1
        _stmt.bindLong(_argIndex, limit.toLong())
        val _columnIndexOfId: Int = getColumnIndexOrThrow(_stmt, "id")
        val _columnIndexOfTimestamp: Int = getColumnIndexOrThrow(_stmt, "timestamp")
        val _columnIndexOfProtocol: Int = getColumnIndexOrThrow(_stmt, "protocol")
        val _columnIndexOfSourceIp: Int = getColumnIndexOrThrow(_stmt, "sourceIp")
        val _columnIndexOfSourcePort: Int = getColumnIndexOrThrow(_stmt, "sourcePort")
        val _columnIndexOfDestinationIp: Int = getColumnIndexOrThrow(_stmt, "destinationIp")
        val _columnIndexOfDestinationPort: Int = getColumnIndexOrThrow(_stmt, "destinationPort")
        val _columnIndexOfHostname: Int = getColumnIndexOrThrow(_stmt, "hostname")
        val _columnIndexOfOwnerUid: Int = getColumnIndexOrThrow(_stmt, "ownerUid")
        val _columnIndexOfOwnerPackage: Int = getColumnIndexOrThrow(_stmt, "ownerPackage")
        val _columnIndexOfBytesOut: Int = getColumnIndexOrThrow(_stmt, "bytesOut")
        val _columnIndexOfBytesIn: Int = getColumnIndexOrThrow(_stmt, "bytesIn")
        val _columnIndexOfWasBlocked: Int = getColumnIndexOrThrow(_stmt, "wasBlocked")
        val _columnIndexOfMatchedRuleId: Int = getColumnIndexOrThrow(_stmt, "matchedRuleId")
        val _columnIndexOfDurationMs: Int = getColumnIndexOrThrow(_stmt, "durationMs")
        val _columnIndexOfEncryptionStatus: Int = getColumnIndexOrThrow(_stmt, "encryptionStatus")
        val _columnIndexOfTlsVersion: Int = getColumnIndexOrThrow(_stmt, "tlsVersion")
        val _columnIndexOfSniHostname: Int = getColumnIndexOrThrow(_stmt, "sniHostname")
        val _columnIndexOfWasBackground: Int = getColumnIndexOrThrow(_stmt, "wasBackground")
        val _result: MutableList<ConnectionEntity> = mutableListOf()
        while (_stmt.step()) {
          val _item: ConnectionEntity
          val _tmpId: Long
          _tmpId = _stmt.getLong(_columnIndexOfId)
          val _tmpTimestamp: Long
          _tmpTimestamp = _stmt.getLong(_columnIndexOfTimestamp)
          val _tmpProtocol: Int
          _tmpProtocol = _stmt.getLong(_columnIndexOfProtocol).toInt()
          val _tmpSourceIp: String
          _tmpSourceIp = _stmt.getText(_columnIndexOfSourceIp)
          val _tmpSourcePort: Int
          _tmpSourcePort = _stmt.getLong(_columnIndexOfSourcePort).toInt()
          val _tmpDestinationIp: String
          _tmpDestinationIp = _stmt.getText(_columnIndexOfDestinationIp)
          val _tmpDestinationPort: Int
          _tmpDestinationPort = _stmt.getLong(_columnIndexOfDestinationPort).toInt()
          val _tmpHostname: String?
          if (_stmt.isNull(_columnIndexOfHostname)) {
            _tmpHostname = null
          } else {
            _tmpHostname = _stmt.getText(_columnIndexOfHostname)
          }
          val _tmpOwnerUid: Int
          _tmpOwnerUid = _stmt.getLong(_columnIndexOfOwnerUid).toInt()
          val _tmpOwnerPackage: String?
          if (_stmt.isNull(_columnIndexOfOwnerPackage)) {
            _tmpOwnerPackage = null
          } else {
            _tmpOwnerPackage = _stmt.getText(_columnIndexOfOwnerPackage)
          }
          val _tmpBytesOut: Long
          _tmpBytesOut = _stmt.getLong(_columnIndexOfBytesOut)
          val _tmpBytesIn: Long
          _tmpBytesIn = _stmt.getLong(_columnIndexOfBytesIn)
          val _tmpWasBlocked: Boolean
          val _tmp: Int
          _tmp = _stmt.getLong(_columnIndexOfWasBlocked).toInt()
          _tmpWasBlocked = _tmp != 0
          val _tmpMatchedRuleId: String?
          if (_stmt.isNull(_columnIndexOfMatchedRuleId)) {
            _tmpMatchedRuleId = null
          } else {
            _tmpMatchedRuleId = _stmt.getText(_columnIndexOfMatchedRuleId)
          }
          val _tmpDurationMs: Long
          _tmpDurationMs = _stmt.getLong(_columnIndexOfDurationMs)
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
          val _tmpWasBackground: Boolean
          val _tmp_1: Int
          _tmp_1 = _stmt.getLong(_columnIndexOfWasBackground).toInt()
          _tmpWasBackground = _tmp_1 != 0
          _item = ConnectionEntity(_tmpId,_tmpTimestamp,_tmpProtocol,_tmpSourceIp,_tmpSourcePort,_tmpDestinationIp,_tmpDestinationPort,_tmpHostname,_tmpOwnerUid,_tmpOwnerPackage,_tmpBytesOut,_tmpBytesIn,_tmpWasBlocked,_tmpMatchedRuleId,_tmpDurationMs,_tmpEncryptionStatus,_tmpTlsVersion,_tmpSniHostname,_tmpWasBackground)
          _result.add(_item)
        }
        _result
      } finally {
        _stmt.close()
      }
    }
  }

  public override suspend fun cleartextConnections(limit: Int): List<ConnectionEntity> {
    val _sql: String = "SELECT * FROM connections WHERE encryptionStatus = 'CLEARTEXT' ORDER BY timestamp DESC LIMIT ?"
    return performSuspending(__db, true, false) { _connection ->
      val _stmt: SQLiteStatement = _connection.prepare(_sql)
      try {
        var _argIndex: Int = 1
        _stmt.bindLong(_argIndex, limit.toLong())
        val _columnIndexOfId: Int = getColumnIndexOrThrow(_stmt, "id")
        val _columnIndexOfTimestamp: Int = getColumnIndexOrThrow(_stmt, "timestamp")
        val _columnIndexOfProtocol: Int = getColumnIndexOrThrow(_stmt, "protocol")
        val _columnIndexOfSourceIp: Int = getColumnIndexOrThrow(_stmt, "sourceIp")
        val _columnIndexOfSourcePort: Int = getColumnIndexOrThrow(_stmt, "sourcePort")
        val _columnIndexOfDestinationIp: Int = getColumnIndexOrThrow(_stmt, "destinationIp")
        val _columnIndexOfDestinationPort: Int = getColumnIndexOrThrow(_stmt, "destinationPort")
        val _columnIndexOfHostname: Int = getColumnIndexOrThrow(_stmt, "hostname")
        val _columnIndexOfOwnerUid: Int = getColumnIndexOrThrow(_stmt, "ownerUid")
        val _columnIndexOfOwnerPackage: Int = getColumnIndexOrThrow(_stmt, "ownerPackage")
        val _columnIndexOfBytesOut: Int = getColumnIndexOrThrow(_stmt, "bytesOut")
        val _columnIndexOfBytesIn: Int = getColumnIndexOrThrow(_stmt, "bytesIn")
        val _columnIndexOfWasBlocked: Int = getColumnIndexOrThrow(_stmt, "wasBlocked")
        val _columnIndexOfMatchedRuleId: Int = getColumnIndexOrThrow(_stmt, "matchedRuleId")
        val _columnIndexOfDurationMs: Int = getColumnIndexOrThrow(_stmt, "durationMs")
        val _columnIndexOfEncryptionStatus: Int = getColumnIndexOrThrow(_stmt, "encryptionStatus")
        val _columnIndexOfTlsVersion: Int = getColumnIndexOrThrow(_stmt, "tlsVersion")
        val _columnIndexOfSniHostname: Int = getColumnIndexOrThrow(_stmt, "sniHostname")
        val _columnIndexOfWasBackground: Int = getColumnIndexOrThrow(_stmt, "wasBackground")
        val _result: MutableList<ConnectionEntity> = mutableListOf()
        while (_stmt.step()) {
          val _item: ConnectionEntity
          val _tmpId: Long
          _tmpId = _stmt.getLong(_columnIndexOfId)
          val _tmpTimestamp: Long
          _tmpTimestamp = _stmt.getLong(_columnIndexOfTimestamp)
          val _tmpProtocol: Int
          _tmpProtocol = _stmt.getLong(_columnIndexOfProtocol).toInt()
          val _tmpSourceIp: String
          _tmpSourceIp = _stmt.getText(_columnIndexOfSourceIp)
          val _tmpSourcePort: Int
          _tmpSourcePort = _stmt.getLong(_columnIndexOfSourcePort).toInt()
          val _tmpDestinationIp: String
          _tmpDestinationIp = _stmt.getText(_columnIndexOfDestinationIp)
          val _tmpDestinationPort: Int
          _tmpDestinationPort = _stmt.getLong(_columnIndexOfDestinationPort).toInt()
          val _tmpHostname: String?
          if (_stmt.isNull(_columnIndexOfHostname)) {
            _tmpHostname = null
          } else {
            _tmpHostname = _stmt.getText(_columnIndexOfHostname)
          }
          val _tmpOwnerUid: Int
          _tmpOwnerUid = _stmt.getLong(_columnIndexOfOwnerUid).toInt()
          val _tmpOwnerPackage: String?
          if (_stmt.isNull(_columnIndexOfOwnerPackage)) {
            _tmpOwnerPackage = null
          } else {
            _tmpOwnerPackage = _stmt.getText(_columnIndexOfOwnerPackage)
          }
          val _tmpBytesOut: Long
          _tmpBytesOut = _stmt.getLong(_columnIndexOfBytesOut)
          val _tmpBytesIn: Long
          _tmpBytesIn = _stmt.getLong(_columnIndexOfBytesIn)
          val _tmpWasBlocked: Boolean
          val _tmp: Int
          _tmp = _stmt.getLong(_columnIndexOfWasBlocked).toInt()
          _tmpWasBlocked = _tmp != 0
          val _tmpMatchedRuleId: String?
          if (_stmt.isNull(_columnIndexOfMatchedRuleId)) {
            _tmpMatchedRuleId = null
          } else {
            _tmpMatchedRuleId = _stmt.getText(_columnIndexOfMatchedRuleId)
          }
          val _tmpDurationMs: Long
          _tmpDurationMs = _stmt.getLong(_columnIndexOfDurationMs)
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
          val _tmpWasBackground: Boolean
          val _tmp_1: Int
          _tmp_1 = _stmt.getLong(_columnIndexOfWasBackground).toInt()
          _tmpWasBackground = _tmp_1 != 0
          _item = ConnectionEntity(_tmpId,_tmpTimestamp,_tmpProtocol,_tmpSourceIp,_tmpSourcePort,_tmpDestinationIp,_tmpDestinationPort,_tmpHostname,_tmpOwnerUid,_tmpOwnerPackage,_tmpBytesOut,_tmpBytesIn,_tmpWasBlocked,_tmpMatchedRuleId,_tmpDurationMs,_tmpEncryptionStatus,_tmpTlsVersion,_tmpSniHostname,_tmpWasBackground)
          _result.add(_item)
        }
        _result
      } finally {
        _stmt.close()
      }
    }
  }

  public override suspend fun weakTlsConnections(limit: Int): List<ConnectionEntity> {
    val _sql: String = "SELECT * FROM connections WHERE encryptionStatus = 'WEAK_TLS' ORDER BY timestamp DESC LIMIT ?"
    return performSuspending(__db, true, false) { _connection ->
      val _stmt: SQLiteStatement = _connection.prepare(_sql)
      try {
        var _argIndex: Int = 1
        _stmt.bindLong(_argIndex, limit.toLong())
        val _columnIndexOfId: Int = getColumnIndexOrThrow(_stmt, "id")
        val _columnIndexOfTimestamp: Int = getColumnIndexOrThrow(_stmt, "timestamp")
        val _columnIndexOfProtocol: Int = getColumnIndexOrThrow(_stmt, "protocol")
        val _columnIndexOfSourceIp: Int = getColumnIndexOrThrow(_stmt, "sourceIp")
        val _columnIndexOfSourcePort: Int = getColumnIndexOrThrow(_stmt, "sourcePort")
        val _columnIndexOfDestinationIp: Int = getColumnIndexOrThrow(_stmt, "destinationIp")
        val _columnIndexOfDestinationPort: Int = getColumnIndexOrThrow(_stmt, "destinationPort")
        val _columnIndexOfHostname: Int = getColumnIndexOrThrow(_stmt, "hostname")
        val _columnIndexOfOwnerUid: Int = getColumnIndexOrThrow(_stmt, "ownerUid")
        val _columnIndexOfOwnerPackage: Int = getColumnIndexOrThrow(_stmt, "ownerPackage")
        val _columnIndexOfBytesOut: Int = getColumnIndexOrThrow(_stmt, "bytesOut")
        val _columnIndexOfBytesIn: Int = getColumnIndexOrThrow(_stmt, "bytesIn")
        val _columnIndexOfWasBlocked: Int = getColumnIndexOrThrow(_stmt, "wasBlocked")
        val _columnIndexOfMatchedRuleId: Int = getColumnIndexOrThrow(_stmt, "matchedRuleId")
        val _columnIndexOfDurationMs: Int = getColumnIndexOrThrow(_stmt, "durationMs")
        val _columnIndexOfEncryptionStatus: Int = getColumnIndexOrThrow(_stmt, "encryptionStatus")
        val _columnIndexOfTlsVersion: Int = getColumnIndexOrThrow(_stmt, "tlsVersion")
        val _columnIndexOfSniHostname: Int = getColumnIndexOrThrow(_stmt, "sniHostname")
        val _columnIndexOfWasBackground: Int = getColumnIndexOrThrow(_stmt, "wasBackground")
        val _result: MutableList<ConnectionEntity> = mutableListOf()
        while (_stmt.step()) {
          val _item: ConnectionEntity
          val _tmpId: Long
          _tmpId = _stmt.getLong(_columnIndexOfId)
          val _tmpTimestamp: Long
          _tmpTimestamp = _stmt.getLong(_columnIndexOfTimestamp)
          val _tmpProtocol: Int
          _tmpProtocol = _stmt.getLong(_columnIndexOfProtocol).toInt()
          val _tmpSourceIp: String
          _tmpSourceIp = _stmt.getText(_columnIndexOfSourceIp)
          val _tmpSourcePort: Int
          _tmpSourcePort = _stmt.getLong(_columnIndexOfSourcePort).toInt()
          val _tmpDestinationIp: String
          _tmpDestinationIp = _stmt.getText(_columnIndexOfDestinationIp)
          val _tmpDestinationPort: Int
          _tmpDestinationPort = _stmt.getLong(_columnIndexOfDestinationPort).toInt()
          val _tmpHostname: String?
          if (_stmt.isNull(_columnIndexOfHostname)) {
            _tmpHostname = null
          } else {
            _tmpHostname = _stmt.getText(_columnIndexOfHostname)
          }
          val _tmpOwnerUid: Int
          _tmpOwnerUid = _stmt.getLong(_columnIndexOfOwnerUid).toInt()
          val _tmpOwnerPackage: String?
          if (_stmt.isNull(_columnIndexOfOwnerPackage)) {
            _tmpOwnerPackage = null
          } else {
            _tmpOwnerPackage = _stmt.getText(_columnIndexOfOwnerPackage)
          }
          val _tmpBytesOut: Long
          _tmpBytesOut = _stmt.getLong(_columnIndexOfBytesOut)
          val _tmpBytesIn: Long
          _tmpBytesIn = _stmt.getLong(_columnIndexOfBytesIn)
          val _tmpWasBlocked: Boolean
          val _tmp: Int
          _tmp = _stmt.getLong(_columnIndexOfWasBlocked).toInt()
          _tmpWasBlocked = _tmp != 0
          val _tmpMatchedRuleId: String?
          if (_stmt.isNull(_columnIndexOfMatchedRuleId)) {
            _tmpMatchedRuleId = null
          } else {
            _tmpMatchedRuleId = _stmt.getText(_columnIndexOfMatchedRuleId)
          }
          val _tmpDurationMs: Long
          _tmpDurationMs = _stmt.getLong(_columnIndexOfDurationMs)
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
          val _tmpWasBackground: Boolean
          val _tmp_1: Int
          _tmp_1 = _stmt.getLong(_columnIndexOfWasBackground).toInt()
          _tmpWasBackground = _tmp_1 != 0
          _item = ConnectionEntity(_tmpId,_tmpTimestamp,_tmpProtocol,_tmpSourceIp,_tmpSourcePort,_tmpDestinationIp,_tmpDestinationPort,_tmpHostname,_tmpOwnerUid,_tmpOwnerPackage,_tmpBytesOut,_tmpBytesIn,_tmpWasBlocked,_tmpMatchedRuleId,_tmpDurationMs,_tmpEncryptionStatus,_tmpTlsVersion,_tmpSniHostname,_tmpWasBackground)
          _result.add(_item)
        }
        _result
      } finally {
        _stmt.close()
      }
    }
  }

  public override suspend fun search(q: String): List<ConnectionEntity> {
    val _sql: String = "SELECT * FROM connections WHERE hostname LIKE '%' || ? || '%' OR destinationIp LIKE '%' || ? || '%' ORDER BY timestamp DESC"
    return performSuspending(__db, true, false) { _connection ->
      val _stmt: SQLiteStatement = _connection.prepare(_sql)
      try {
        var _argIndex: Int = 1
        _stmt.bindText(_argIndex, q)
        _argIndex = 2
        _stmt.bindText(_argIndex, q)
        val _columnIndexOfId: Int = getColumnIndexOrThrow(_stmt, "id")
        val _columnIndexOfTimestamp: Int = getColumnIndexOrThrow(_stmt, "timestamp")
        val _columnIndexOfProtocol: Int = getColumnIndexOrThrow(_stmt, "protocol")
        val _columnIndexOfSourceIp: Int = getColumnIndexOrThrow(_stmt, "sourceIp")
        val _columnIndexOfSourcePort: Int = getColumnIndexOrThrow(_stmt, "sourcePort")
        val _columnIndexOfDestinationIp: Int = getColumnIndexOrThrow(_stmt, "destinationIp")
        val _columnIndexOfDestinationPort: Int = getColumnIndexOrThrow(_stmt, "destinationPort")
        val _columnIndexOfHostname: Int = getColumnIndexOrThrow(_stmt, "hostname")
        val _columnIndexOfOwnerUid: Int = getColumnIndexOrThrow(_stmt, "ownerUid")
        val _columnIndexOfOwnerPackage: Int = getColumnIndexOrThrow(_stmt, "ownerPackage")
        val _columnIndexOfBytesOut: Int = getColumnIndexOrThrow(_stmt, "bytesOut")
        val _columnIndexOfBytesIn: Int = getColumnIndexOrThrow(_stmt, "bytesIn")
        val _columnIndexOfWasBlocked: Int = getColumnIndexOrThrow(_stmt, "wasBlocked")
        val _columnIndexOfMatchedRuleId: Int = getColumnIndexOrThrow(_stmt, "matchedRuleId")
        val _columnIndexOfDurationMs: Int = getColumnIndexOrThrow(_stmt, "durationMs")
        val _columnIndexOfEncryptionStatus: Int = getColumnIndexOrThrow(_stmt, "encryptionStatus")
        val _columnIndexOfTlsVersion: Int = getColumnIndexOrThrow(_stmt, "tlsVersion")
        val _columnIndexOfSniHostname: Int = getColumnIndexOrThrow(_stmt, "sniHostname")
        val _columnIndexOfWasBackground: Int = getColumnIndexOrThrow(_stmt, "wasBackground")
        val _result: MutableList<ConnectionEntity> = mutableListOf()
        while (_stmt.step()) {
          val _item: ConnectionEntity
          val _tmpId: Long
          _tmpId = _stmt.getLong(_columnIndexOfId)
          val _tmpTimestamp: Long
          _tmpTimestamp = _stmt.getLong(_columnIndexOfTimestamp)
          val _tmpProtocol: Int
          _tmpProtocol = _stmt.getLong(_columnIndexOfProtocol).toInt()
          val _tmpSourceIp: String
          _tmpSourceIp = _stmt.getText(_columnIndexOfSourceIp)
          val _tmpSourcePort: Int
          _tmpSourcePort = _stmt.getLong(_columnIndexOfSourcePort).toInt()
          val _tmpDestinationIp: String
          _tmpDestinationIp = _stmt.getText(_columnIndexOfDestinationIp)
          val _tmpDestinationPort: Int
          _tmpDestinationPort = _stmt.getLong(_columnIndexOfDestinationPort).toInt()
          val _tmpHostname: String?
          if (_stmt.isNull(_columnIndexOfHostname)) {
            _tmpHostname = null
          } else {
            _tmpHostname = _stmt.getText(_columnIndexOfHostname)
          }
          val _tmpOwnerUid: Int
          _tmpOwnerUid = _stmt.getLong(_columnIndexOfOwnerUid).toInt()
          val _tmpOwnerPackage: String?
          if (_stmt.isNull(_columnIndexOfOwnerPackage)) {
            _tmpOwnerPackage = null
          } else {
            _tmpOwnerPackage = _stmt.getText(_columnIndexOfOwnerPackage)
          }
          val _tmpBytesOut: Long
          _tmpBytesOut = _stmt.getLong(_columnIndexOfBytesOut)
          val _tmpBytesIn: Long
          _tmpBytesIn = _stmt.getLong(_columnIndexOfBytesIn)
          val _tmpWasBlocked: Boolean
          val _tmp: Int
          _tmp = _stmt.getLong(_columnIndexOfWasBlocked).toInt()
          _tmpWasBlocked = _tmp != 0
          val _tmpMatchedRuleId: String?
          if (_stmt.isNull(_columnIndexOfMatchedRuleId)) {
            _tmpMatchedRuleId = null
          } else {
            _tmpMatchedRuleId = _stmt.getText(_columnIndexOfMatchedRuleId)
          }
          val _tmpDurationMs: Long
          _tmpDurationMs = _stmt.getLong(_columnIndexOfDurationMs)
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
          val _tmpWasBackground: Boolean
          val _tmp_1: Int
          _tmp_1 = _stmt.getLong(_columnIndexOfWasBackground).toInt()
          _tmpWasBackground = _tmp_1 != 0
          _item = ConnectionEntity(_tmpId,_tmpTimestamp,_tmpProtocol,_tmpSourceIp,_tmpSourcePort,_tmpDestinationIp,_tmpDestinationPort,_tmpHostname,_tmpOwnerUid,_tmpOwnerPackage,_tmpBytesOut,_tmpBytesIn,_tmpWasBlocked,_tmpMatchedRuleId,_tmpDurationMs,_tmpEncryptionStatus,_tmpTlsVersion,_tmpSniHostname,_tmpWasBackground)
          _result.add(_item)
        }
        _result
      } finally {
        _stmt.close()
      }
    }
  }

  public override suspend fun blockedCount(): Long {
    val _sql: String = "SELECT COUNT(*) FROM connections WHERE wasBlocked = 1"
    return performSuspending(__db, true, false) { _connection ->
      val _stmt: SQLiteStatement = _connection.prepare(_sql)
      try {
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

  public override suspend fun cleartextCount(): Long {
    val _sql: String = "SELECT COUNT(*) FROM connections WHERE encryptionStatus = 'CLEARTEXT'"
    return performSuspending(__db, true, false) { _connection ->
      val _stmt: SQLiteStatement = _connection.prepare(_sql)
      try {
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

  public override suspend fun totalBytes(): Long? {
    val _sql: String = "SELECT SUM(bytesOut) + SUM(bytesIn) FROM connections"
    return performSuspending(__db, true, false) { _connection ->
      val _stmt: SQLiteStatement = _connection.prepare(_sql)
      try {
        val _result: Long?
        if (_stmt.step()) {
          val _tmp: Long?
          if (_stmt.isNull(0)) {
            _tmp = null
          } else {
            _tmp = _stmt.getLong(0)
          }
          _result = _tmp
        } else {
          _result = null
        }
        _result
      } finally {
        _stmt.close()
      }
    }
  }

  public override suspend fun topBlockedApps(limit: Int): List<AppBlockCount> {
    val _sql: String = "SELECT ownerPackage, COUNT(*) as blockedCount FROM connections WHERE wasBlocked = 1 AND ownerPackage IS NOT NULL GROUP BY ownerPackage ORDER BY blockedCount DESC LIMIT ?"
    return performSuspending(__db, true, false) { _connection ->
      val _stmt: SQLiteStatement = _connection.prepare(_sql)
      try {
        var _argIndex: Int = 1
        _stmt.bindLong(_argIndex, limit.toLong())
        val _columnIndexOfOwnerPackage: Int = 0
        val _columnIndexOfBlockedCount: Int = 1
        val _result: MutableList<AppBlockCount> = mutableListOf()
        while (_stmt.step()) {
          val _item: AppBlockCount
          val _tmpOwnerPackage: String
          _tmpOwnerPackage = _stmt.getText(_columnIndexOfOwnerPackage)
          val _tmpBlockedCount: Long
          _tmpBlockedCount = _stmt.getLong(_columnIndexOfBlockedCount)
          _item = AppBlockCount(_tmpOwnerPackage,_tmpBlockedCount)
          _result.add(_item)
        }
        _result
      } finally {
        _stmt.close()
      }
    }
  }

  public override suspend fun deleteOlderThan(before: Long): Int {
    val _sql: String = "DELETE FROM connections WHERE timestamp < ?"
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
