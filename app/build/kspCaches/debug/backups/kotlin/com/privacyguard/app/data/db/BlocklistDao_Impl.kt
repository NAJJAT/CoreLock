package com.privacyguard.app.`data`.db

import androidx.room.EntityInsertAdapter
import androidx.room.RoomDatabase
import androidx.room.coroutines.createFlow
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
import kotlinx.coroutines.flow.Flow

@Generated(value = ["androidx.room.RoomProcessor"])
@Suppress(names = ["UNCHECKED_CAST", "DEPRECATION", "REDUNDANT_PROJECTION", "REMOVAL"])
public class BlocklistDao_Impl(
  __db: RoomDatabase,
) : BlocklistDao {
  private val __db: RoomDatabase

  private val __insertAdapterOfBlocklistEntity: EntityInsertAdapter<BlocklistEntity>
  init {
    this.__db = __db
    this.__insertAdapterOfBlocklistEntity = object : EntityInsertAdapter<BlocklistEntity>() {
      protected override fun createQuery(): String = "INSERT OR REPLACE INTO `blocklist` (`id`,`domain`,`source`,`category`,`lastUpdated`,`isEnabled`) VALUES (nullif(?, 0),?,?,?,?,?)"

      protected override fun bind(statement: SQLiteStatement, entity: BlocklistEntity) {
        statement.bindLong(1, entity.id)
        statement.bindText(2, entity.domain)
        statement.bindText(3, entity.source)
        statement.bindText(4, entity.category)
        statement.bindLong(5, entity.lastUpdated)
        val _tmp: Int = if (entity.isEnabled) 1 else 0
        statement.bindLong(6, _tmp.toLong())
      }
    }
  }

  public override suspend fun insert(entry: BlocklistEntity): Unit = performSuspending(__db, false, true) { _connection ->
    __insertAdapterOfBlocklistEntity.insert(_connection, entry)
  }

  public override suspend fun insertAll(entries: List<BlocklistEntity>): Unit = performSuspending(__db, false, true) { _connection ->
    __insertAdapterOfBlocklistEntity.insert(_connection, entries)
  }

  public override suspend fun getAllEntries(): List<BlocklistEntity> {
    val _sql: String = "SELECT * FROM blocklist WHERE isEnabled = 1 ORDER BY domain"
    return performSuspending(__db, true, false) { _connection ->
      val _stmt: SQLiteStatement = _connection.prepare(_sql)
      try {
        val _columnIndexOfId: Int = getColumnIndexOrThrow(_stmt, "id")
        val _columnIndexOfDomain: Int = getColumnIndexOrThrow(_stmt, "domain")
        val _columnIndexOfSource: Int = getColumnIndexOrThrow(_stmt, "source")
        val _columnIndexOfCategory: Int = getColumnIndexOrThrow(_stmt, "category")
        val _columnIndexOfLastUpdated: Int = getColumnIndexOrThrow(_stmt, "lastUpdated")
        val _columnIndexOfIsEnabled: Int = getColumnIndexOrThrow(_stmt, "isEnabled")
        val _result: MutableList<BlocklistEntity> = mutableListOf()
        while (_stmt.step()) {
          val _item: BlocklistEntity
          val _tmpId: Long
          _tmpId = _stmt.getLong(_columnIndexOfId)
          val _tmpDomain: String
          _tmpDomain = _stmt.getText(_columnIndexOfDomain)
          val _tmpSource: String
          _tmpSource = _stmt.getText(_columnIndexOfSource)
          val _tmpCategory: String
          _tmpCategory = _stmt.getText(_columnIndexOfCategory)
          val _tmpLastUpdated: Long
          _tmpLastUpdated = _stmt.getLong(_columnIndexOfLastUpdated)
          val _tmpIsEnabled: Boolean
          val _tmp: Int
          _tmp = _stmt.getLong(_columnIndexOfIsEnabled).toInt()
          _tmpIsEnabled = _tmp != 0
          _item = BlocklistEntity(_tmpId,_tmpDomain,_tmpSource,_tmpCategory,_tmpLastUpdated,_tmpIsEnabled)
          _result.add(_item)
        }
        _result
      } finally {
        _stmt.close()
      }
    }
  }

  public override fun getAllEntriesFlow(): Flow<List<BlocklistEntity>> {
    val _sql: String = "SELECT * FROM blocklist WHERE isEnabled = 1 ORDER BY domain"
    return createFlow(__db, false, arrayOf("blocklist")) { _connection ->
      val _stmt: SQLiteStatement = _connection.prepare(_sql)
      try {
        val _columnIndexOfId: Int = getColumnIndexOrThrow(_stmt, "id")
        val _columnIndexOfDomain: Int = getColumnIndexOrThrow(_stmt, "domain")
        val _columnIndexOfSource: Int = getColumnIndexOrThrow(_stmt, "source")
        val _columnIndexOfCategory: Int = getColumnIndexOrThrow(_stmt, "category")
        val _columnIndexOfLastUpdated: Int = getColumnIndexOrThrow(_stmt, "lastUpdated")
        val _columnIndexOfIsEnabled: Int = getColumnIndexOrThrow(_stmt, "isEnabled")
        val _result: MutableList<BlocklistEntity> = mutableListOf()
        while (_stmt.step()) {
          val _item: BlocklistEntity
          val _tmpId: Long
          _tmpId = _stmt.getLong(_columnIndexOfId)
          val _tmpDomain: String
          _tmpDomain = _stmt.getText(_columnIndexOfDomain)
          val _tmpSource: String
          _tmpSource = _stmt.getText(_columnIndexOfSource)
          val _tmpCategory: String
          _tmpCategory = _stmt.getText(_columnIndexOfCategory)
          val _tmpLastUpdated: Long
          _tmpLastUpdated = _stmt.getLong(_columnIndexOfLastUpdated)
          val _tmpIsEnabled: Boolean
          val _tmp: Int
          _tmp = _stmt.getLong(_columnIndexOfIsEnabled).toInt()
          _tmpIsEnabled = _tmp != 0
          _item = BlocklistEntity(_tmpId,_tmpDomain,_tmpSource,_tmpCategory,_tmpLastUpdated,_tmpIsEnabled)
          _result.add(_item)
        }
        _result
      } finally {
        _stmt.close()
      }
    }
  }

  public override suspend fun getEntriesBySource(source: String): List<BlocklistEntity> {
    val _sql: String = "SELECT * FROM blocklist WHERE source = ? AND isEnabled = 1 ORDER BY domain"
    return performSuspending(__db, true, false) { _connection ->
      val _stmt: SQLiteStatement = _connection.prepare(_sql)
      try {
        var _argIndex: Int = 1
        _stmt.bindText(_argIndex, source)
        val _columnIndexOfId: Int = getColumnIndexOrThrow(_stmt, "id")
        val _columnIndexOfDomain: Int = getColumnIndexOrThrow(_stmt, "domain")
        val _columnIndexOfSource: Int = getColumnIndexOrThrow(_stmt, "source")
        val _columnIndexOfCategory: Int = getColumnIndexOrThrow(_stmt, "category")
        val _columnIndexOfLastUpdated: Int = getColumnIndexOrThrow(_stmt, "lastUpdated")
        val _columnIndexOfIsEnabled: Int = getColumnIndexOrThrow(_stmt, "isEnabled")
        val _result: MutableList<BlocklistEntity> = mutableListOf()
        while (_stmt.step()) {
          val _item: BlocklistEntity
          val _tmpId: Long
          _tmpId = _stmt.getLong(_columnIndexOfId)
          val _tmpDomain: String
          _tmpDomain = _stmt.getText(_columnIndexOfDomain)
          val _tmpSource: String
          _tmpSource = _stmt.getText(_columnIndexOfSource)
          val _tmpCategory: String
          _tmpCategory = _stmt.getText(_columnIndexOfCategory)
          val _tmpLastUpdated: Long
          _tmpLastUpdated = _stmt.getLong(_columnIndexOfLastUpdated)
          val _tmpIsEnabled: Boolean
          val _tmp: Int
          _tmp = _stmt.getLong(_columnIndexOfIsEnabled).toInt()
          _tmpIsEnabled = _tmp != 0
          _item = BlocklistEntity(_tmpId,_tmpDomain,_tmpSource,_tmpCategory,_tmpLastUpdated,_tmpIsEnabled)
          _result.add(_item)
        }
        _result
      } finally {
        _stmt.close()
      }
    }
  }

  public override suspend fun getEntriesByCategory(category: String): List<BlocklistEntity> {
    val _sql: String = "SELECT * FROM blocklist WHERE category = ? AND isEnabled = 1 ORDER BY domain"
    return performSuspending(__db, true, false) { _connection ->
      val _stmt: SQLiteStatement = _connection.prepare(_sql)
      try {
        var _argIndex: Int = 1
        _stmt.bindText(_argIndex, category)
        val _columnIndexOfId: Int = getColumnIndexOrThrow(_stmt, "id")
        val _columnIndexOfDomain: Int = getColumnIndexOrThrow(_stmt, "domain")
        val _columnIndexOfSource: Int = getColumnIndexOrThrow(_stmt, "source")
        val _columnIndexOfCategory: Int = getColumnIndexOrThrow(_stmt, "category")
        val _columnIndexOfLastUpdated: Int = getColumnIndexOrThrow(_stmt, "lastUpdated")
        val _columnIndexOfIsEnabled: Int = getColumnIndexOrThrow(_stmt, "isEnabled")
        val _result: MutableList<BlocklistEntity> = mutableListOf()
        while (_stmt.step()) {
          val _item: BlocklistEntity
          val _tmpId: Long
          _tmpId = _stmt.getLong(_columnIndexOfId)
          val _tmpDomain: String
          _tmpDomain = _stmt.getText(_columnIndexOfDomain)
          val _tmpSource: String
          _tmpSource = _stmt.getText(_columnIndexOfSource)
          val _tmpCategory: String
          _tmpCategory = _stmt.getText(_columnIndexOfCategory)
          val _tmpLastUpdated: Long
          _tmpLastUpdated = _stmt.getLong(_columnIndexOfLastUpdated)
          val _tmpIsEnabled: Boolean
          val _tmp: Int
          _tmp = _stmt.getLong(_columnIndexOfIsEnabled).toInt()
          _tmpIsEnabled = _tmp != 0
          _item = BlocklistEntity(_tmpId,_tmpDomain,_tmpSource,_tmpCategory,_tmpLastUpdated,_tmpIsEnabled)
          _result.add(_item)
        }
        _result
      } finally {
        _stmt.close()
      }
    }
  }

  public override suspend fun isBlocked(domain: String): Boolean {
    val _sql: String = "SELECT EXISTS(SELECT 1 FROM blocklist WHERE domain = ? AND isEnabled = 1)"
    return performSuspending(__db, true, false) { _connection ->
      val _stmt: SQLiteStatement = _connection.prepare(_sql)
      try {
        var _argIndex: Int = 1
        _stmt.bindText(_argIndex, domain)
        val _result: Boolean
        if (_stmt.step()) {
          val _tmp: Int
          _tmp = _stmt.getLong(0).toInt()
          _result = _tmp != 0
        } else {
          _result = false
        }
        _result
      } finally {
        _stmt.close()
      }
    }
  }

  public override suspend fun getEntry(domain: String): BlocklistEntity? {
    val _sql: String = "SELECT * FROM blocklist WHERE domain = ?"
    return performSuspending(__db, true, false) { _connection ->
      val _stmt: SQLiteStatement = _connection.prepare(_sql)
      try {
        var _argIndex: Int = 1
        _stmt.bindText(_argIndex, domain)
        val _columnIndexOfId: Int = getColumnIndexOrThrow(_stmt, "id")
        val _columnIndexOfDomain: Int = getColumnIndexOrThrow(_stmt, "domain")
        val _columnIndexOfSource: Int = getColumnIndexOrThrow(_stmt, "source")
        val _columnIndexOfCategory: Int = getColumnIndexOrThrow(_stmt, "category")
        val _columnIndexOfLastUpdated: Int = getColumnIndexOrThrow(_stmt, "lastUpdated")
        val _columnIndexOfIsEnabled: Int = getColumnIndexOrThrow(_stmt, "isEnabled")
        val _result: BlocklistEntity?
        if (_stmt.step()) {
          val _tmpId: Long
          _tmpId = _stmt.getLong(_columnIndexOfId)
          val _tmpDomain: String
          _tmpDomain = _stmt.getText(_columnIndexOfDomain)
          val _tmpSource: String
          _tmpSource = _stmt.getText(_columnIndexOfSource)
          val _tmpCategory: String
          _tmpCategory = _stmt.getText(_columnIndexOfCategory)
          val _tmpLastUpdated: Long
          _tmpLastUpdated = _stmt.getLong(_columnIndexOfLastUpdated)
          val _tmpIsEnabled: Boolean
          val _tmp: Int
          _tmp = _stmt.getLong(_columnIndexOfIsEnabled).toInt()
          _tmpIsEnabled = _tmp != 0
          _result = BlocklistEntity(_tmpId,_tmpDomain,_tmpSource,_tmpCategory,_tmpLastUpdated,_tmpIsEnabled)
        } else {
          _result = null
        }
        _result
      } finally {
        _stmt.close()
      }
    }
  }

  public override suspend fun getSize(): Int {
    val _sql: String = "SELECT COUNT(*) FROM blocklist WHERE isEnabled = 1"
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

  public override suspend fun getStatsBySource(): List<SourceStats> {
    val _sql: String = """
        |
        |        SELECT source, COUNT(*) as count 
        |        FROM blocklist 
        |        WHERE isEnabled = 1
        |        GROUP BY source 
        |        ORDER BY count DESC
        |    
        """.trimMargin()
    return performSuspending(__db, true, false) { _connection ->
      val _stmt: SQLiteStatement = _connection.prepare(_sql)
      try {
        val _columnIndexOfSource: Int = 0
        val _columnIndexOfCount: Int = 1
        val _result: MutableList<SourceStats> = mutableListOf()
        while (_stmt.step()) {
          val _item: SourceStats
          val _tmpSource: String
          _tmpSource = _stmt.getText(_columnIndexOfSource)
          val _tmpCount: Int
          _tmpCount = _stmt.getLong(_columnIndexOfCount).toInt()
          _item = SourceStats(_tmpSource,_tmpCount)
          _result.add(_item)
        }
        _result
      } finally {
        _stmt.close()
      }
    }
  }

  public override suspend fun getStatsByCategory(): List<CategoryStats> {
    val _sql: String = """
        |
        |        SELECT category, COUNT(*) as count
        |        FROM blocklist
        |        WHERE isEnabled = 1
        |        GROUP BY category
        |        ORDER BY count DESC
        |    
        """.trimMargin()
    return performSuspending(__db, true, false) { _connection ->
      val _stmt: SQLiteStatement = _connection.prepare(_sql)
      try {
        val _columnIndexOfCategory: Int = 0
        val _columnIndexOfCount: Int = 1
        val _result: MutableList<CategoryStats> = mutableListOf()
        while (_stmt.step()) {
          val _item: CategoryStats
          val _tmpCategory: String
          _tmpCategory = _stmt.getText(_columnIndexOfCategory)
          val _tmpCount: Int
          _tmpCount = _stmt.getLong(_columnIndexOfCount).toInt()
          _item = CategoryStats(_tmpCategory,_tmpCount)
          _result.add(_item)
        }
        _result
      } finally {
        _stmt.close()
      }
    }
  }

  public override suspend fun setEnabled(domain: String, enabled: Boolean) {
    val _sql: String = "UPDATE blocklist SET isEnabled = ? WHERE domain = ?"
    return performSuspending(__db, false, true) { _connection ->
      val _stmt: SQLiteStatement = _connection.prepare(_sql)
      try {
        var _argIndex: Int = 1
        val _tmp: Int = if (enabled) 1 else 0
        _stmt.bindLong(_argIndex, _tmp.toLong())
        _argIndex = 2
        _stmt.bindText(_argIndex, domain)
        _stmt.step()
      } finally {
        _stmt.close()
      }
    }
  }

  public override suspend fun deleteEntry(domain: String) {
    val _sql: String = "DELETE FROM blocklist WHERE domain = ?"
    return performSuspending(__db, false, true) { _connection ->
      val _stmt: SQLiteStatement = _connection.prepare(_sql)
      try {
        var _argIndex: Int = 1
        _stmt.bindText(_argIndex, domain)
        _stmt.step()
      } finally {
        _stmt.close()
      }
    }
  }

  public override suspend fun deleteBySource(source: String) {
    val _sql: String = "DELETE FROM blocklist WHERE source = ?"
    return performSuspending(__db, false, true) { _connection ->
      val _stmt: SQLiteStatement = _connection.prepare(_sql)
      try {
        var _argIndex: Int = 1
        _stmt.bindText(_argIndex, source)
        _stmt.step()
      } finally {
        _stmt.close()
      }
    }
  }

  public override suspend fun deleteOldEntries(cutoff: Long) {
    val _sql: String = "DELETE FROM blocklist WHERE lastUpdated < ?"
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

  public override suspend fun clearAll() {
    val _sql: String = "DELETE FROM blocklist"
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
