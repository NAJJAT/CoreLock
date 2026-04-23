package com.privacyguard.`data`.db

import androidx.room.EntityDeleteOrUpdateAdapter
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
public class RulesDao_Impl(
  __db: RoomDatabase,
) : RulesDao {
  private val __db: RoomDatabase

  private val __insertAdapterOfRuleEntity: EntityInsertAdapter<RuleEntity>

  private val __updateAdapterOfRuleEntity: EntityDeleteOrUpdateAdapter<RuleEntity>
  init {
    this.__db = __db
    this.__insertAdapterOfRuleEntity = object : EntityInsertAdapter<RuleEntity>() {
      protected override fun createQuery(): String = "INSERT OR REPLACE INTO `rules` (`id`,`label`,`action`,`source`,`priority`,`isEnabled`,`matchUid`,`matchPackage`,`matchDomain`,`matchIp`,`matchPort`,`matchProtocol`,`matchEncryption`,`createdAt`) VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?)"

      protected override fun bind(statement: SQLiteStatement, entity: RuleEntity) {
        statement.bindText(1, entity.id)
        statement.bindText(2, entity.label)
        statement.bindText(3, entity.action)
        statement.bindText(4, entity.source)
        statement.bindLong(5, entity.priority.toLong())
        val _tmp: Int = if (entity.isEnabled) 1 else 0
        statement.bindLong(6, _tmp.toLong())
        val _tmpMatchUid: Int? = entity.matchUid
        if (_tmpMatchUid == null) {
          statement.bindNull(7)
        } else {
          statement.bindLong(7, _tmpMatchUid.toLong())
        }
        val _tmpMatchPackage: String? = entity.matchPackage
        if (_tmpMatchPackage == null) {
          statement.bindNull(8)
        } else {
          statement.bindText(8, _tmpMatchPackage)
        }
        val _tmpMatchDomain: String? = entity.matchDomain
        if (_tmpMatchDomain == null) {
          statement.bindNull(9)
        } else {
          statement.bindText(9, _tmpMatchDomain)
        }
        val _tmpMatchIp: String? = entity.matchIp
        if (_tmpMatchIp == null) {
          statement.bindNull(10)
        } else {
          statement.bindText(10, _tmpMatchIp)
        }
        val _tmpMatchPort: Int? = entity.matchPort
        if (_tmpMatchPort == null) {
          statement.bindNull(11)
        } else {
          statement.bindLong(11, _tmpMatchPort.toLong())
        }
        val _tmpMatchProtocol: String? = entity.matchProtocol
        if (_tmpMatchProtocol == null) {
          statement.bindNull(12)
        } else {
          statement.bindText(12, _tmpMatchProtocol)
        }
        val _tmpMatchEncryption: String? = entity.matchEncryption
        if (_tmpMatchEncryption == null) {
          statement.bindNull(13)
        } else {
          statement.bindText(13, _tmpMatchEncryption)
        }
        statement.bindLong(14, entity.createdAt)
      }
    }
    this.__updateAdapterOfRuleEntity = object : EntityDeleteOrUpdateAdapter<RuleEntity>() {
      protected override fun createQuery(): String = "UPDATE OR ABORT `rules` SET `id` = ?,`label` = ?,`action` = ?,`source` = ?,`priority` = ?,`isEnabled` = ?,`matchUid` = ?,`matchPackage` = ?,`matchDomain` = ?,`matchIp` = ?,`matchPort` = ?,`matchProtocol` = ?,`matchEncryption` = ?,`createdAt` = ? WHERE `id` = ?"

      protected override fun bind(statement: SQLiteStatement, entity: RuleEntity) {
        statement.bindText(1, entity.id)
        statement.bindText(2, entity.label)
        statement.bindText(3, entity.action)
        statement.bindText(4, entity.source)
        statement.bindLong(5, entity.priority.toLong())
        val _tmp: Int = if (entity.isEnabled) 1 else 0
        statement.bindLong(6, _tmp.toLong())
        val _tmpMatchUid: Int? = entity.matchUid
        if (_tmpMatchUid == null) {
          statement.bindNull(7)
        } else {
          statement.bindLong(7, _tmpMatchUid.toLong())
        }
        val _tmpMatchPackage: String? = entity.matchPackage
        if (_tmpMatchPackage == null) {
          statement.bindNull(8)
        } else {
          statement.bindText(8, _tmpMatchPackage)
        }
        val _tmpMatchDomain: String? = entity.matchDomain
        if (_tmpMatchDomain == null) {
          statement.bindNull(9)
        } else {
          statement.bindText(9, _tmpMatchDomain)
        }
        val _tmpMatchIp: String? = entity.matchIp
        if (_tmpMatchIp == null) {
          statement.bindNull(10)
        } else {
          statement.bindText(10, _tmpMatchIp)
        }
        val _tmpMatchPort: Int? = entity.matchPort
        if (_tmpMatchPort == null) {
          statement.bindNull(11)
        } else {
          statement.bindLong(11, _tmpMatchPort.toLong())
        }
        val _tmpMatchProtocol: String? = entity.matchProtocol
        if (_tmpMatchProtocol == null) {
          statement.bindNull(12)
        } else {
          statement.bindText(12, _tmpMatchProtocol)
        }
        val _tmpMatchEncryption: String? = entity.matchEncryption
        if (_tmpMatchEncryption == null) {
          statement.bindNull(13)
        } else {
          statement.bindText(13, _tmpMatchEncryption)
        }
        statement.bindLong(14, entity.createdAt)
        statement.bindText(15, entity.id)
      }
    }
  }

  public override suspend fun insert(r: RuleEntity): Unit = performSuspending(__db, false, true) { _connection ->
    __insertAdapterOfRuleEntity.insert(_connection, r)
  }

  public override suspend fun insertAll(rs: List<RuleEntity>): Unit = performSuspending(__db, false, true) { _connection ->
    __insertAdapterOfRuleEntity.insert(_connection, rs)
  }

  public override suspend fun update(r: RuleEntity): Unit = performSuspending(__db, false, true) { _connection ->
    __updateAdapterOfRuleEntity.handle(_connection, r)
  }

  public override suspend fun allRules(): List<RuleEntity> {
    val _sql: String = "SELECT * FROM rules ORDER BY priority ASC"
    return performSuspending(__db, true, false) { _connection ->
      val _stmt: SQLiteStatement = _connection.prepare(_sql)
      try {
        val _columnIndexOfId: Int = getColumnIndexOrThrow(_stmt, "id")
        val _columnIndexOfLabel: Int = getColumnIndexOrThrow(_stmt, "label")
        val _columnIndexOfAction: Int = getColumnIndexOrThrow(_stmt, "action")
        val _columnIndexOfSource: Int = getColumnIndexOrThrow(_stmt, "source")
        val _columnIndexOfPriority: Int = getColumnIndexOrThrow(_stmt, "priority")
        val _columnIndexOfIsEnabled: Int = getColumnIndexOrThrow(_stmt, "isEnabled")
        val _columnIndexOfMatchUid: Int = getColumnIndexOrThrow(_stmt, "matchUid")
        val _columnIndexOfMatchPackage: Int = getColumnIndexOrThrow(_stmt, "matchPackage")
        val _columnIndexOfMatchDomain: Int = getColumnIndexOrThrow(_stmt, "matchDomain")
        val _columnIndexOfMatchIp: Int = getColumnIndexOrThrow(_stmt, "matchIp")
        val _columnIndexOfMatchPort: Int = getColumnIndexOrThrow(_stmt, "matchPort")
        val _columnIndexOfMatchProtocol: Int = getColumnIndexOrThrow(_stmt, "matchProtocol")
        val _columnIndexOfMatchEncryption: Int = getColumnIndexOrThrow(_stmt, "matchEncryption")
        val _columnIndexOfCreatedAt: Int = getColumnIndexOrThrow(_stmt, "createdAt")
        val _result: MutableList<RuleEntity> = mutableListOf()
        while (_stmt.step()) {
          val _item: RuleEntity
          val _tmpId: String
          _tmpId = _stmt.getText(_columnIndexOfId)
          val _tmpLabel: String
          _tmpLabel = _stmt.getText(_columnIndexOfLabel)
          val _tmpAction: String
          _tmpAction = _stmt.getText(_columnIndexOfAction)
          val _tmpSource: String
          _tmpSource = _stmt.getText(_columnIndexOfSource)
          val _tmpPriority: Int
          _tmpPriority = _stmt.getLong(_columnIndexOfPriority).toInt()
          val _tmpIsEnabled: Boolean
          val _tmp: Int
          _tmp = _stmt.getLong(_columnIndexOfIsEnabled).toInt()
          _tmpIsEnabled = _tmp != 0
          val _tmpMatchUid: Int?
          if (_stmt.isNull(_columnIndexOfMatchUid)) {
            _tmpMatchUid = null
          } else {
            _tmpMatchUid = _stmt.getLong(_columnIndexOfMatchUid).toInt()
          }
          val _tmpMatchPackage: String?
          if (_stmt.isNull(_columnIndexOfMatchPackage)) {
            _tmpMatchPackage = null
          } else {
            _tmpMatchPackage = _stmt.getText(_columnIndexOfMatchPackage)
          }
          val _tmpMatchDomain: String?
          if (_stmt.isNull(_columnIndexOfMatchDomain)) {
            _tmpMatchDomain = null
          } else {
            _tmpMatchDomain = _stmt.getText(_columnIndexOfMatchDomain)
          }
          val _tmpMatchIp: String?
          if (_stmt.isNull(_columnIndexOfMatchIp)) {
            _tmpMatchIp = null
          } else {
            _tmpMatchIp = _stmt.getText(_columnIndexOfMatchIp)
          }
          val _tmpMatchPort: Int?
          if (_stmt.isNull(_columnIndexOfMatchPort)) {
            _tmpMatchPort = null
          } else {
            _tmpMatchPort = _stmt.getLong(_columnIndexOfMatchPort).toInt()
          }
          val _tmpMatchProtocol: String?
          if (_stmt.isNull(_columnIndexOfMatchProtocol)) {
            _tmpMatchProtocol = null
          } else {
            _tmpMatchProtocol = _stmt.getText(_columnIndexOfMatchProtocol)
          }
          val _tmpMatchEncryption: String?
          if (_stmt.isNull(_columnIndexOfMatchEncryption)) {
            _tmpMatchEncryption = null
          } else {
            _tmpMatchEncryption = _stmt.getText(_columnIndexOfMatchEncryption)
          }
          val _tmpCreatedAt: Long
          _tmpCreatedAt = _stmt.getLong(_columnIndexOfCreatedAt)
          _item = RuleEntity(_tmpId,_tmpLabel,_tmpAction,_tmpSource,_tmpPriority,_tmpIsEnabled,_tmpMatchUid,_tmpMatchPackage,_tmpMatchDomain,_tmpMatchIp,_tmpMatchPort,_tmpMatchProtocol,_tmpMatchEncryption,_tmpCreatedAt)
          _result.add(_item)
        }
        _result
      } finally {
        _stmt.close()
      }
    }
  }

  public override suspend fun enabledRules(): List<RuleEntity> {
    val _sql: String = "SELECT * FROM rules WHERE isEnabled = 1 ORDER BY priority ASC"
    return performSuspending(__db, true, false) { _connection ->
      val _stmt: SQLiteStatement = _connection.prepare(_sql)
      try {
        val _columnIndexOfId: Int = getColumnIndexOrThrow(_stmt, "id")
        val _columnIndexOfLabel: Int = getColumnIndexOrThrow(_stmt, "label")
        val _columnIndexOfAction: Int = getColumnIndexOrThrow(_stmt, "action")
        val _columnIndexOfSource: Int = getColumnIndexOrThrow(_stmt, "source")
        val _columnIndexOfPriority: Int = getColumnIndexOrThrow(_stmt, "priority")
        val _columnIndexOfIsEnabled: Int = getColumnIndexOrThrow(_stmt, "isEnabled")
        val _columnIndexOfMatchUid: Int = getColumnIndexOrThrow(_stmt, "matchUid")
        val _columnIndexOfMatchPackage: Int = getColumnIndexOrThrow(_stmt, "matchPackage")
        val _columnIndexOfMatchDomain: Int = getColumnIndexOrThrow(_stmt, "matchDomain")
        val _columnIndexOfMatchIp: Int = getColumnIndexOrThrow(_stmt, "matchIp")
        val _columnIndexOfMatchPort: Int = getColumnIndexOrThrow(_stmt, "matchPort")
        val _columnIndexOfMatchProtocol: Int = getColumnIndexOrThrow(_stmt, "matchProtocol")
        val _columnIndexOfMatchEncryption: Int = getColumnIndexOrThrow(_stmt, "matchEncryption")
        val _columnIndexOfCreatedAt: Int = getColumnIndexOrThrow(_stmt, "createdAt")
        val _result: MutableList<RuleEntity> = mutableListOf()
        while (_stmt.step()) {
          val _item: RuleEntity
          val _tmpId: String
          _tmpId = _stmt.getText(_columnIndexOfId)
          val _tmpLabel: String
          _tmpLabel = _stmt.getText(_columnIndexOfLabel)
          val _tmpAction: String
          _tmpAction = _stmt.getText(_columnIndexOfAction)
          val _tmpSource: String
          _tmpSource = _stmt.getText(_columnIndexOfSource)
          val _tmpPriority: Int
          _tmpPriority = _stmt.getLong(_columnIndexOfPriority).toInt()
          val _tmpIsEnabled: Boolean
          val _tmp: Int
          _tmp = _stmt.getLong(_columnIndexOfIsEnabled).toInt()
          _tmpIsEnabled = _tmp != 0
          val _tmpMatchUid: Int?
          if (_stmt.isNull(_columnIndexOfMatchUid)) {
            _tmpMatchUid = null
          } else {
            _tmpMatchUid = _stmt.getLong(_columnIndexOfMatchUid).toInt()
          }
          val _tmpMatchPackage: String?
          if (_stmt.isNull(_columnIndexOfMatchPackage)) {
            _tmpMatchPackage = null
          } else {
            _tmpMatchPackage = _stmt.getText(_columnIndexOfMatchPackage)
          }
          val _tmpMatchDomain: String?
          if (_stmt.isNull(_columnIndexOfMatchDomain)) {
            _tmpMatchDomain = null
          } else {
            _tmpMatchDomain = _stmt.getText(_columnIndexOfMatchDomain)
          }
          val _tmpMatchIp: String?
          if (_stmt.isNull(_columnIndexOfMatchIp)) {
            _tmpMatchIp = null
          } else {
            _tmpMatchIp = _stmt.getText(_columnIndexOfMatchIp)
          }
          val _tmpMatchPort: Int?
          if (_stmt.isNull(_columnIndexOfMatchPort)) {
            _tmpMatchPort = null
          } else {
            _tmpMatchPort = _stmt.getLong(_columnIndexOfMatchPort).toInt()
          }
          val _tmpMatchProtocol: String?
          if (_stmt.isNull(_columnIndexOfMatchProtocol)) {
            _tmpMatchProtocol = null
          } else {
            _tmpMatchProtocol = _stmt.getText(_columnIndexOfMatchProtocol)
          }
          val _tmpMatchEncryption: String?
          if (_stmt.isNull(_columnIndexOfMatchEncryption)) {
            _tmpMatchEncryption = null
          } else {
            _tmpMatchEncryption = _stmt.getText(_columnIndexOfMatchEncryption)
          }
          val _tmpCreatedAt: Long
          _tmpCreatedAt = _stmt.getLong(_columnIndexOfCreatedAt)
          _item = RuleEntity(_tmpId,_tmpLabel,_tmpAction,_tmpSource,_tmpPriority,_tmpIsEnabled,_tmpMatchUid,_tmpMatchPackage,_tmpMatchDomain,_tmpMatchIp,_tmpMatchPort,_tmpMatchProtocol,_tmpMatchEncryption,_tmpCreatedAt)
          _result.add(_item)
        }
        _result
      } finally {
        _stmt.close()
      }
    }
  }

  public override suspend fun bySource(src: String): List<RuleEntity> {
    val _sql: String = "SELECT * FROM rules WHERE source = ? ORDER BY priority ASC"
    return performSuspending(__db, true, false) { _connection ->
      val _stmt: SQLiteStatement = _connection.prepare(_sql)
      try {
        var _argIndex: Int = 1
        _stmt.bindText(_argIndex, src)
        val _columnIndexOfId: Int = getColumnIndexOrThrow(_stmt, "id")
        val _columnIndexOfLabel: Int = getColumnIndexOrThrow(_stmt, "label")
        val _columnIndexOfAction: Int = getColumnIndexOrThrow(_stmt, "action")
        val _columnIndexOfSource: Int = getColumnIndexOrThrow(_stmt, "source")
        val _columnIndexOfPriority: Int = getColumnIndexOrThrow(_stmt, "priority")
        val _columnIndexOfIsEnabled: Int = getColumnIndexOrThrow(_stmt, "isEnabled")
        val _columnIndexOfMatchUid: Int = getColumnIndexOrThrow(_stmt, "matchUid")
        val _columnIndexOfMatchPackage: Int = getColumnIndexOrThrow(_stmt, "matchPackage")
        val _columnIndexOfMatchDomain: Int = getColumnIndexOrThrow(_stmt, "matchDomain")
        val _columnIndexOfMatchIp: Int = getColumnIndexOrThrow(_stmt, "matchIp")
        val _columnIndexOfMatchPort: Int = getColumnIndexOrThrow(_stmt, "matchPort")
        val _columnIndexOfMatchProtocol: Int = getColumnIndexOrThrow(_stmt, "matchProtocol")
        val _columnIndexOfMatchEncryption: Int = getColumnIndexOrThrow(_stmt, "matchEncryption")
        val _columnIndexOfCreatedAt: Int = getColumnIndexOrThrow(_stmt, "createdAt")
        val _result: MutableList<RuleEntity> = mutableListOf()
        while (_stmt.step()) {
          val _item: RuleEntity
          val _tmpId: String
          _tmpId = _stmt.getText(_columnIndexOfId)
          val _tmpLabel: String
          _tmpLabel = _stmt.getText(_columnIndexOfLabel)
          val _tmpAction: String
          _tmpAction = _stmt.getText(_columnIndexOfAction)
          val _tmpSource: String
          _tmpSource = _stmt.getText(_columnIndexOfSource)
          val _tmpPriority: Int
          _tmpPriority = _stmt.getLong(_columnIndexOfPriority).toInt()
          val _tmpIsEnabled: Boolean
          val _tmp: Int
          _tmp = _stmt.getLong(_columnIndexOfIsEnabled).toInt()
          _tmpIsEnabled = _tmp != 0
          val _tmpMatchUid: Int?
          if (_stmt.isNull(_columnIndexOfMatchUid)) {
            _tmpMatchUid = null
          } else {
            _tmpMatchUid = _stmt.getLong(_columnIndexOfMatchUid).toInt()
          }
          val _tmpMatchPackage: String?
          if (_stmt.isNull(_columnIndexOfMatchPackage)) {
            _tmpMatchPackage = null
          } else {
            _tmpMatchPackage = _stmt.getText(_columnIndexOfMatchPackage)
          }
          val _tmpMatchDomain: String?
          if (_stmt.isNull(_columnIndexOfMatchDomain)) {
            _tmpMatchDomain = null
          } else {
            _tmpMatchDomain = _stmt.getText(_columnIndexOfMatchDomain)
          }
          val _tmpMatchIp: String?
          if (_stmt.isNull(_columnIndexOfMatchIp)) {
            _tmpMatchIp = null
          } else {
            _tmpMatchIp = _stmt.getText(_columnIndexOfMatchIp)
          }
          val _tmpMatchPort: Int?
          if (_stmt.isNull(_columnIndexOfMatchPort)) {
            _tmpMatchPort = null
          } else {
            _tmpMatchPort = _stmt.getLong(_columnIndexOfMatchPort).toInt()
          }
          val _tmpMatchProtocol: String?
          if (_stmt.isNull(_columnIndexOfMatchProtocol)) {
            _tmpMatchProtocol = null
          } else {
            _tmpMatchProtocol = _stmt.getText(_columnIndexOfMatchProtocol)
          }
          val _tmpMatchEncryption: String?
          if (_stmt.isNull(_columnIndexOfMatchEncryption)) {
            _tmpMatchEncryption = null
          } else {
            _tmpMatchEncryption = _stmt.getText(_columnIndexOfMatchEncryption)
          }
          val _tmpCreatedAt: Long
          _tmpCreatedAt = _stmt.getLong(_columnIndexOfCreatedAt)
          _item = RuleEntity(_tmpId,_tmpLabel,_tmpAction,_tmpSource,_tmpPriority,_tmpIsEnabled,_tmpMatchUid,_tmpMatchPackage,_tmpMatchDomain,_tmpMatchIp,_tmpMatchPort,_tmpMatchProtocol,_tmpMatchEncryption,_tmpCreatedAt)
          _result.add(_item)
        }
        _result
      } finally {
        _stmt.close()
      }
    }
  }

  public override suspend fun activeDenyCount(): Int {
    val _sql: String = "SELECT COUNT(*) FROM rules WHERE isEnabled = 1 AND action = 'DENY'"
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

  public override suspend fun deleteById(id: String): Int {
    val _sql: String = "DELETE FROM rules WHERE id = ?"
    return performSuspending(__db, false, true) { _connection ->
      val _stmt: SQLiteStatement = _connection.prepare(_sql)
      try {
        var _argIndex: Int = 1
        _stmt.bindText(_argIndex, id)
        _stmt.step()
        getTotalChangedRows(_connection)
      } finally {
        _stmt.close()
      }
    }
  }

  public override suspend fun setEnabled(id: String, on: Boolean) {
    val _sql: String = "UPDATE rules SET isEnabled = ? WHERE id = ?"
    return performSuspending(__db, false, true) { _connection ->
      val _stmt: SQLiteStatement = _connection.prepare(_sql)
      try {
        var _argIndex: Int = 1
        val _tmp: Int = if (on) 1 else 0
        _stmt.bindLong(_argIndex, _tmp.toLong())
        _argIndex = 2
        _stmt.bindText(_argIndex, id)
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
