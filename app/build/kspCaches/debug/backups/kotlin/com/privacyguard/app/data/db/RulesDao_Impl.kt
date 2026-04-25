package com.privacyguard.app.`data`.db

import androidx.room.EntityDeleteOrUpdateAdapter
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
public class RulesDao_Impl(
  __db: RoomDatabase,
) : RulesDao {
  private val __db: RoomDatabase

  private val __insertAdapterOfRuleEntity: EntityInsertAdapter<RuleEntity>

  private val __deleteAdapterOfRuleEntity: EntityDeleteOrUpdateAdapter<RuleEntity>

  private val __updateAdapterOfRuleEntity: EntityDeleteOrUpdateAdapter<RuleEntity>
  init {
    this.__db = __db
    this.__insertAdapterOfRuleEntity = object : EntityInsertAdapter<RuleEntity>() {
      protected override fun createQuery(): String = "INSERT OR REPLACE INTO `rules` (`id`,`type`,`value`,`matchUid`,`matchPackage`,`matchDomain`,`matchIp`,`matchPort`,`matchProtocol`,`matchEncryption`,`action`,`enabled`,`priority`,`description`,`createdAt`,`lastModified`,`hitCount`,`lastHit`) VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)"

      protected override fun bind(statement: SQLiteStatement, entity: RuleEntity) {
        statement.bindText(1, entity.id)
        statement.bindText(2, entity.type)
        statement.bindText(3, entity.value)
        val _tmpMatchUid: Int? = entity.matchUid
        if (_tmpMatchUid == null) {
          statement.bindNull(4)
        } else {
          statement.bindLong(4, _tmpMatchUid.toLong())
        }
        val _tmpMatchPackage: String? = entity.matchPackage
        if (_tmpMatchPackage == null) {
          statement.bindNull(5)
        } else {
          statement.bindText(5, _tmpMatchPackage)
        }
        val _tmpMatchDomain: String? = entity.matchDomain
        if (_tmpMatchDomain == null) {
          statement.bindNull(6)
        } else {
          statement.bindText(6, _tmpMatchDomain)
        }
        val _tmpMatchIp: String? = entity.matchIp
        if (_tmpMatchIp == null) {
          statement.bindNull(7)
        } else {
          statement.bindText(7, _tmpMatchIp)
        }
        val _tmpMatchPort: Int? = entity.matchPort
        if (_tmpMatchPort == null) {
          statement.bindNull(8)
        } else {
          statement.bindLong(8, _tmpMatchPort.toLong())
        }
        val _tmpMatchProtocol: String? = entity.matchProtocol
        if (_tmpMatchProtocol == null) {
          statement.bindNull(9)
        } else {
          statement.bindText(9, _tmpMatchProtocol)
        }
        val _tmpMatchEncryption: String? = entity.matchEncryption
        if (_tmpMatchEncryption == null) {
          statement.bindNull(10)
        } else {
          statement.bindText(10, _tmpMatchEncryption)
        }
        statement.bindText(11, entity.action)
        val _tmp: Int = if (entity.enabled) 1 else 0
        statement.bindLong(12, _tmp.toLong())
        statement.bindLong(13, entity.priority.toLong())
        statement.bindText(14, entity.description)
        statement.bindLong(15, entity.createdAt)
        statement.bindLong(16, entity.lastModified)
        statement.bindLong(17, entity.hitCount.toLong())
        val _tmpLastHit: Long? = entity.lastHit
        if (_tmpLastHit == null) {
          statement.bindNull(18)
        } else {
          statement.bindLong(18, _tmpLastHit)
        }
      }
    }
    this.__deleteAdapterOfRuleEntity = object : EntityDeleteOrUpdateAdapter<RuleEntity>() {
      protected override fun createQuery(): String = "DELETE FROM `rules` WHERE `id` = ?"

      protected override fun bind(statement: SQLiteStatement, entity: RuleEntity) {
        statement.bindText(1, entity.id)
      }
    }
    this.__updateAdapterOfRuleEntity = object : EntityDeleteOrUpdateAdapter<RuleEntity>() {
      protected override fun createQuery(): String = "UPDATE OR ABORT `rules` SET `id` = ?,`type` = ?,`value` = ?,`matchUid` = ?,`matchPackage` = ?,`matchDomain` = ?,`matchIp` = ?,`matchPort` = ?,`matchProtocol` = ?,`matchEncryption` = ?,`action` = ?,`enabled` = ?,`priority` = ?,`description` = ?,`createdAt` = ?,`lastModified` = ?,`hitCount` = ?,`lastHit` = ? WHERE `id` = ?"

      protected override fun bind(statement: SQLiteStatement, entity: RuleEntity) {
        statement.bindText(1, entity.id)
        statement.bindText(2, entity.type)
        statement.bindText(3, entity.value)
        val _tmpMatchUid: Int? = entity.matchUid
        if (_tmpMatchUid == null) {
          statement.bindNull(4)
        } else {
          statement.bindLong(4, _tmpMatchUid.toLong())
        }
        val _tmpMatchPackage: String? = entity.matchPackage
        if (_tmpMatchPackage == null) {
          statement.bindNull(5)
        } else {
          statement.bindText(5, _tmpMatchPackage)
        }
        val _tmpMatchDomain: String? = entity.matchDomain
        if (_tmpMatchDomain == null) {
          statement.bindNull(6)
        } else {
          statement.bindText(6, _tmpMatchDomain)
        }
        val _tmpMatchIp: String? = entity.matchIp
        if (_tmpMatchIp == null) {
          statement.bindNull(7)
        } else {
          statement.bindText(7, _tmpMatchIp)
        }
        val _tmpMatchPort: Int? = entity.matchPort
        if (_tmpMatchPort == null) {
          statement.bindNull(8)
        } else {
          statement.bindLong(8, _tmpMatchPort.toLong())
        }
        val _tmpMatchProtocol: String? = entity.matchProtocol
        if (_tmpMatchProtocol == null) {
          statement.bindNull(9)
        } else {
          statement.bindText(9, _tmpMatchProtocol)
        }
        val _tmpMatchEncryption: String? = entity.matchEncryption
        if (_tmpMatchEncryption == null) {
          statement.bindNull(10)
        } else {
          statement.bindText(10, _tmpMatchEncryption)
        }
        statement.bindText(11, entity.action)
        val _tmp: Int = if (entity.enabled) 1 else 0
        statement.bindLong(12, _tmp.toLong())
        statement.bindLong(13, entity.priority.toLong())
        statement.bindText(14, entity.description)
        statement.bindLong(15, entity.createdAt)
        statement.bindLong(16, entity.lastModified)
        statement.bindLong(17, entity.hitCount.toLong())
        val _tmpLastHit: Long? = entity.lastHit
        if (_tmpLastHit == null) {
          statement.bindNull(18)
        } else {
          statement.bindLong(18, _tmpLastHit)
        }
        statement.bindText(19, entity.id)
      }
    }
  }

  public override suspend fun insert(rule: RuleEntity): Unit = performSuspending(__db, false, true) { _connection ->
    __insertAdapterOfRuleEntity.insert(_connection, rule)
  }

  public override suspend fun insertAll(rules: List<RuleEntity>): Unit = performSuspending(__db, false, true) { _connection ->
    __insertAdapterOfRuleEntity.insert(_connection, rules)
  }

  public override suspend fun delete(rule: RuleEntity): Unit = performSuspending(__db, false, true) { _connection ->
    __deleteAdapterOfRuleEntity.handle(_connection, rule)
  }

  public override suspend fun update(rule: RuleEntity): Unit = performSuspending(__db, false, true) { _connection ->
    __updateAdapterOfRuleEntity.handle(_connection, rule)
  }

  public override suspend fun getAllRules(): List<RuleEntity> {
    val _sql: String = "SELECT * FROM rules ORDER BY priority DESC, createdAt DESC"
    return performSuspending(__db, true, false) { _connection ->
      val _stmt: SQLiteStatement = _connection.prepare(_sql)
      try {
        val _columnIndexOfId: Int = getColumnIndexOrThrow(_stmt, "id")
        val _columnIndexOfType: Int = getColumnIndexOrThrow(_stmt, "type")
        val _columnIndexOfValue: Int = getColumnIndexOrThrow(_stmt, "value")
        val _columnIndexOfMatchUid: Int = getColumnIndexOrThrow(_stmt, "matchUid")
        val _columnIndexOfMatchPackage: Int = getColumnIndexOrThrow(_stmt, "matchPackage")
        val _columnIndexOfMatchDomain: Int = getColumnIndexOrThrow(_stmt, "matchDomain")
        val _columnIndexOfMatchIp: Int = getColumnIndexOrThrow(_stmt, "matchIp")
        val _columnIndexOfMatchPort: Int = getColumnIndexOrThrow(_stmt, "matchPort")
        val _columnIndexOfMatchProtocol: Int = getColumnIndexOrThrow(_stmt, "matchProtocol")
        val _columnIndexOfMatchEncryption: Int = getColumnIndexOrThrow(_stmt, "matchEncryption")
        val _columnIndexOfAction: Int = getColumnIndexOrThrow(_stmt, "action")
        val _columnIndexOfEnabled: Int = getColumnIndexOrThrow(_stmt, "enabled")
        val _columnIndexOfPriority: Int = getColumnIndexOrThrow(_stmt, "priority")
        val _columnIndexOfDescription: Int = getColumnIndexOrThrow(_stmt, "description")
        val _columnIndexOfCreatedAt: Int = getColumnIndexOrThrow(_stmt, "createdAt")
        val _columnIndexOfLastModified: Int = getColumnIndexOrThrow(_stmt, "lastModified")
        val _columnIndexOfHitCount: Int = getColumnIndexOrThrow(_stmt, "hitCount")
        val _columnIndexOfLastHit: Int = getColumnIndexOrThrow(_stmt, "lastHit")
        val _result: MutableList<RuleEntity> = mutableListOf()
        while (_stmt.step()) {
          val _item: RuleEntity
          val _tmpId: String
          _tmpId = _stmt.getText(_columnIndexOfId)
          val _tmpType: String
          _tmpType = _stmt.getText(_columnIndexOfType)
          val _tmpValue: String
          _tmpValue = _stmt.getText(_columnIndexOfValue)
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
          val _tmpAction: String
          _tmpAction = _stmt.getText(_columnIndexOfAction)
          val _tmpEnabled: Boolean
          val _tmp: Int
          _tmp = _stmt.getLong(_columnIndexOfEnabled).toInt()
          _tmpEnabled = _tmp != 0
          val _tmpPriority: Int
          _tmpPriority = _stmt.getLong(_columnIndexOfPriority).toInt()
          val _tmpDescription: String
          _tmpDescription = _stmt.getText(_columnIndexOfDescription)
          val _tmpCreatedAt: Long
          _tmpCreatedAt = _stmt.getLong(_columnIndexOfCreatedAt)
          val _tmpLastModified: Long
          _tmpLastModified = _stmt.getLong(_columnIndexOfLastModified)
          val _tmpHitCount: Int
          _tmpHitCount = _stmt.getLong(_columnIndexOfHitCount).toInt()
          val _tmpLastHit: Long?
          if (_stmt.isNull(_columnIndexOfLastHit)) {
            _tmpLastHit = null
          } else {
            _tmpLastHit = _stmt.getLong(_columnIndexOfLastHit)
          }
          _item = RuleEntity(_tmpId,_tmpType,_tmpValue,_tmpMatchUid,_tmpMatchPackage,_tmpMatchDomain,_tmpMatchIp,_tmpMatchPort,_tmpMatchProtocol,_tmpMatchEncryption,_tmpAction,_tmpEnabled,_tmpPriority,_tmpDescription,_tmpCreatedAt,_tmpLastModified,_tmpHitCount,_tmpLastHit)
          _result.add(_item)
        }
        _result
      } finally {
        _stmt.close()
      }
    }
  }

  public override suspend fun getEnabledRules(): List<RuleEntity> {
    val _sql: String = "SELECT * FROM rules WHERE enabled = 1 ORDER BY priority DESC"
    return performSuspending(__db, true, false) { _connection ->
      val _stmt: SQLiteStatement = _connection.prepare(_sql)
      try {
        val _columnIndexOfId: Int = getColumnIndexOrThrow(_stmt, "id")
        val _columnIndexOfType: Int = getColumnIndexOrThrow(_stmt, "type")
        val _columnIndexOfValue: Int = getColumnIndexOrThrow(_stmt, "value")
        val _columnIndexOfMatchUid: Int = getColumnIndexOrThrow(_stmt, "matchUid")
        val _columnIndexOfMatchPackage: Int = getColumnIndexOrThrow(_stmt, "matchPackage")
        val _columnIndexOfMatchDomain: Int = getColumnIndexOrThrow(_stmt, "matchDomain")
        val _columnIndexOfMatchIp: Int = getColumnIndexOrThrow(_stmt, "matchIp")
        val _columnIndexOfMatchPort: Int = getColumnIndexOrThrow(_stmt, "matchPort")
        val _columnIndexOfMatchProtocol: Int = getColumnIndexOrThrow(_stmt, "matchProtocol")
        val _columnIndexOfMatchEncryption: Int = getColumnIndexOrThrow(_stmt, "matchEncryption")
        val _columnIndexOfAction: Int = getColumnIndexOrThrow(_stmt, "action")
        val _columnIndexOfEnabled: Int = getColumnIndexOrThrow(_stmt, "enabled")
        val _columnIndexOfPriority: Int = getColumnIndexOrThrow(_stmt, "priority")
        val _columnIndexOfDescription: Int = getColumnIndexOrThrow(_stmt, "description")
        val _columnIndexOfCreatedAt: Int = getColumnIndexOrThrow(_stmt, "createdAt")
        val _columnIndexOfLastModified: Int = getColumnIndexOrThrow(_stmt, "lastModified")
        val _columnIndexOfHitCount: Int = getColumnIndexOrThrow(_stmt, "hitCount")
        val _columnIndexOfLastHit: Int = getColumnIndexOrThrow(_stmt, "lastHit")
        val _result: MutableList<RuleEntity> = mutableListOf()
        while (_stmt.step()) {
          val _item: RuleEntity
          val _tmpId: String
          _tmpId = _stmt.getText(_columnIndexOfId)
          val _tmpType: String
          _tmpType = _stmt.getText(_columnIndexOfType)
          val _tmpValue: String
          _tmpValue = _stmt.getText(_columnIndexOfValue)
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
          val _tmpAction: String
          _tmpAction = _stmt.getText(_columnIndexOfAction)
          val _tmpEnabled: Boolean
          val _tmp: Int
          _tmp = _stmt.getLong(_columnIndexOfEnabled).toInt()
          _tmpEnabled = _tmp != 0
          val _tmpPriority: Int
          _tmpPriority = _stmt.getLong(_columnIndexOfPriority).toInt()
          val _tmpDescription: String
          _tmpDescription = _stmt.getText(_columnIndexOfDescription)
          val _tmpCreatedAt: Long
          _tmpCreatedAt = _stmt.getLong(_columnIndexOfCreatedAt)
          val _tmpLastModified: Long
          _tmpLastModified = _stmt.getLong(_columnIndexOfLastModified)
          val _tmpHitCount: Int
          _tmpHitCount = _stmt.getLong(_columnIndexOfHitCount).toInt()
          val _tmpLastHit: Long?
          if (_stmt.isNull(_columnIndexOfLastHit)) {
            _tmpLastHit = null
          } else {
            _tmpLastHit = _stmt.getLong(_columnIndexOfLastHit)
          }
          _item = RuleEntity(_tmpId,_tmpType,_tmpValue,_tmpMatchUid,_tmpMatchPackage,_tmpMatchDomain,_tmpMatchIp,_tmpMatchPort,_tmpMatchProtocol,_tmpMatchEncryption,_tmpAction,_tmpEnabled,_tmpPriority,_tmpDescription,_tmpCreatedAt,_tmpLastModified,_tmpHitCount,_tmpLastHit)
          _result.add(_item)
        }
        _result
      } finally {
        _stmt.close()
      }
    }
  }

  public override suspend fun getRulesByType(type: String): List<RuleEntity> {
    val _sql: String = "SELECT * FROM rules WHERE type = ? ORDER BY priority DESC"
    return performSuspending(__db, true, false) { _connection ->
      val _stmt: SQLiteStatement = _connection.prepare(_sql)
      try {
        var _argIndex: Int = 1
        _stmt.bindText(_argIndex, type)
        val _columnIndexOfId: Int = getColumnIndexOrThrow(_stmt, "id")
        val _columnIndexOfType: Int = getColumnIndexOrThrow(_stmt, "type")
        val _columnIndexOfValue: Int = getColumnIndexOrThrow(_stmt, "value")
        val _columnIndexOfMatchUid: Int = getColumnIndexOrThrow(_stmt, "matchUid")
        val _columnIndexOfMatchPackage: Int = getColumnIndexOrThrow(_stmt, "matchPackage")
        val _columnIndexOfMatchDomain: Int = getColumnIndexOrThrow(_stmt, "matchDomain")
        val _columnIndexOfMatchIp: Int = getColumnIndexOrThrow(_stmt, "matchIp")
        val _columnIndexOfMatchPort: Int = getColumnIndexOrThrow(_stmt, "matchPort")
        val _columnIndexOfMatchProtocol: Int = getColumnIndexOrThrow(_stmt, "matchProtocol")
        val _columnIndexOfMatchEncryption: Int = getColumnIndexOrThrow(_stmt, "matchEncryption")
        val _columnIndexOfAction: Int = getColumnIndexOrThrow(_stmt, "action")
        val _columnIndexOfEnabled: Int = getColumnIndexOrThrow(_stmt, "enabled")
        val _columnIndexOfPriority: Int = getColumnIndexOrThrow(_stmt, "priority")
        val _columnIndexOfDescription: Int = getColumnIndexOrThrow(_stmt, "description")
        val _columnIndexOfCreatedAt: Int = getColumnIndexOrThrow(_stmt, "createdAt")
        val _columnIndexOfLastModified: Int = getColumnIndexOrThrow(_stmt, "lastModified")
        val _columnIndexOfHitCount: Int = getColumnIndexOrThrow(_stmt, "hitCount")
        val _columnIndexOfLastHit: Int = getColumnIndexOrThrow(_stmt, "lastHit")
        val _result: MutableList<RuleEntity> = mutableListOf()
        while (_stmt.step()) {
          val _item: RuleEntity
          val _tmpId: String
          _tmpId = _stmt.getText(_columnIndexOfId)
          val _tmpType: String
          _tmpType = _stmt.getText(_columnIndexOfType)
          val _tmpValue: String
          _tmpValue = _stmt.getText(_columnIndexOfValue)
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
          val _tmpAction: String
          _tmpAction = _stmt.getText(_columnIndexOfAction)
          val _tmpEnabled: Boolean
          val _tmp: Int
          _tmp = _stmt.getLong(_columnIndexOfEnabled).toInt()
          _tmpEnabled = _tmp != 0
          val _tmpPriority: Int
          _tmpPriority = _stmt.getLong(_columnIndexOfPriority).toInt()
          val _tmpDescription: String
          _tmpDescription = _stmt.getText(_columnIndexOfDescription)
          val _tmpCreatedAt: Long
          _tmpCreatedAt = _stmt.getLong(_columnIndexOfCreatedAt)
          val _tmpLastModified: Long
          _tmpLastModified = _stmt.getLong(_columnIndexOfLastModified)
          val _tmpHitCount: Int
          _tmpHitCount = _stmt.getLong(_columnIndexOfHitCount).toInt()
          val _tmpLastHit: Long?
          if (_stmt.isNull(_columnIndexOfLastHit)) {
            _tmpLastHit = null
          } else {
            _tmpLastHit = _stmt.getLong(_columnIndexOfLastHit)
          }
          _item = RuleEntity(_tmpId,_tmpType,_tmpValue,_tmpMatchUid,_tmpMatchPackage,_tmpMatchDomain,_tmpMatchIp,_tmpMatchPort,_tmpMatchProtocol,_tmpMatchEncryption,_tmpAction,_tmpEnabled,_tmpPriority,_tmpDescription,_tmpCreatedAt,_tmpLastModified,_tmpHitCount,_tmpLastHit)
          _result.add(_item)
        }
        _result
      } finally {
        _stmt.close()
      }
    }
  }

  public override suspend fun getRuleById(id: String): RuleEntity? {
    val _sql: String = "SELECT * FROM rules WHERE id = ?"
    return performSuspending(__db, true, false) { _connection ->
      val _stmt: SQLiteStatement = _connection.prepare(_sql)
      try {
        var _argIndex: Int = 1
        _stmt.bindText(_argIndex, id)
        val _columnIndexOfId: Int = getColumnIndexOrThrow(_stmt, "id")
        val _columnIndexOfType: Int = getColumnIndexOrThrow(_stmt, "type")
        val _columnIndexOfValue: Int = getColumnIndexOrThrow(_stmt, "value")
        val _columnIndexOfMatchUid: Int = getColumnIndexOrThrow(_stmt, "matchUid")
        val _columnIndexOfMatchPackage: Int = getColumnIndexOrThrow(_stmt, "matchPackage")
        val _columnIndexOfMatchDomain: Int = getColumnIndexOrThrow(_stmt, "matchDomain")
        val _columnIndexOfMatchIp: Int = getColumnIndexOrThrow(_stmt, "matchIp")
        val _columnIndexOfMatchPort: Int = getColumnIndexOrThrow(_stmt, "matchPort")
        val _columnIndexOfMatchProtocol: Int = getColumnIndexOrThrow(_stmt, "matchProtocol")
        val _columnIndexOfMatchEncryption: Int = getColumnIndexOrThrow(_stmt, "matchEncryption")
        val _columnIndexOfAction: Int = getColumnIndexOrThrow(_stmt, "action")
        val _columnIndexOfEnabled: Int = getColumnIndexOrThrow(_stmt, "enabled")
        val _columnIndexOfPriority: Int = getColumnIndexOrThrow(_stmt, "priority")
        val _columnIndexOfDescription: Int = getColumnIndexOrThrow(_stmt, "description")
        val _columnIndexOfCreatedAt: Int = getColumnIndexOrThrow(_stmt, "createdAt")
        val _columnIndexOfLastModified: Int = getColumnIndexOrThrow(_stmt, "lastModified")
        val _columnIndexOfHitCount: Int = getColumnIndexOrThrow(_stmt, "hitCount")
        val _columnIndexOfLastHit: Int = getColumnIndexOrThrow(_stmt, "lastHit")
        val _result: RuleEntity?
        if (_stmt.step()) {
          val _tmpId: String
          _tmpId = _stmt.getText(_columnIndexOfId)
          val _tmpType: String
          _tmpType = _stmt.getText(_columnIndexOfType)
          val _tmpValue: String
          _tmpValue = _stmt.getText(_columnIndexOfValue)
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
          val _tmpAction: String
          _tmpAction = _stmt.getText(_columnIndexOfAction)
          val _tmpEnabled: Boolean
          val _tmp: Int
          _tmp = _stmt.getLong(_columnIndexOfEnabled).toInt()
          _tmpEnabled = _tmp != 0
          val _tmpPriority: Int
          _tmpPriority = _stmt.getLong(_columnIndexOfPriority).toInt()
          val _tmpDescription: String
          _tmpDescription = _stmt.getText(_columnIndexOfDescription)
          val _tmpCreatedAt: Long
          _tmpCreatedAt = _stmt.getLong(_columnIndexOfCreatedAt)
          val _tmpLastModified: Long
          _tmpLastModified = _stmt.getLong(_columnIndexOfLastModified)
          val _tmpHitCount: Int
          _tmpHitCount = _stmt.getLong(_columnIndexOfHitCount).toInt()
          val _tmpLastHit: Long?
          if (_stmt.isNull(_columnIndexOfLastHit)) {
            _tmpLastHit = null
          } else {
            _tmpLastHit = _stmt.getLong(_columnIndexOfLastHit)
          }
          _result = RuleEntity(_tmpId,_tmpType,_tmpValue,_tmpMatchUid,_tmpMatchPackage,_tmpMatchDomain,_tmpMatchIp,_tmpMatchPort,_tmpMatchProtocol,_tmpMatchEncryption,_tmpAction,_tmpEnabled,_tmpPriority,_tmpDescription,_tmpCreatedAt,_tmpLastModified,_tmpHitCount,_tmpLastHit)
        } else {
          _result = null
        }
        _result
      } finally {
        _stmt.close()
      }
    }
  }

  public override suspend fun getRuleCount(): Int {
    val _sql: String = "SELECT COUNT(*) FROM rules"
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

  public override suspend fun getEnabledRuleCount(): Int {
    val _sql: String = "SELECT COUNT(*) FROM rules WHERE enabled = 1"
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

  public override suspend fun deleteById(id: String) {
    val _sql: String = "DELETE FROM rules WHERE id = ?"
    return performSuspending(__db, false, true) { _connection ->
      val _stmt: SQLiteStatement = _connection.prepare(_sql)
      try {
        var _argIndex: Int = 1
        _stmt.bindText(_argIndex, id)
        _stmt.step()
      } finally {
        _stmt.close()
      }
    }
  }

  public override suspend fun setRuleEnabled(
    id: String,
    enabled: Boolean,
    now: Long,
  ) {
    val _sql: String = "UPDATE rules SET enabled = ?, lastModified = ? WHERE id = ?"
    return performSuspending(__db, false, true) { _connection ->
      val _stmt: SQLiteStatement = _connection.prepare(_sql)
      try {
        var _argIndex: Int = 1
        val _tmp: Int = if (enabled) 1 else 0
        _stmt.bindLong(_argIndex, _tmp.toLong())
        _argIndex = 2
        _stmt.bindLong(_argIndex, now)
        _argIndex = 3
        _stmt.bindText(_argIndex, id)
        _stmt.step()
      } finally {
        _stmt.close()
      }
    }
  }

  public override suspend fun incrementHitCount(id: String, now: Long) {
    val _sql: String = "UPDATE rules SET hitCount = hitCount + 1, lastHit = ? WHERE id = ?"
    return performSuspending(__db, false, true) { _connection ->
      val _stmt: SQLiteStatement = _connection.prepare(_sql)
      try {
        var _argIndex: Int = 1
        _stmt.bindLong(_argIndex, now)
        _argIndex = 2
        _stmt.bindText(_argIndex, id)
        _stmt.step()
      } finally {
        _stmt.close()
      }
    }
  }

  public override suspend fun deleteAll() {
    val _sql: String = "DELETE FROM rules"
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
