package com.privacyguard.`data`.db

import androidx.room.EntityInsertAdapter
import androidx.room.RoomDatabase
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
public class BlocklistDao_Impl(
  __db: RoomDatabase,
) : BlocklistDao {
  private val __db: RoomDatabase

  private val __insertAdapterOfBlocklistEntity: EntityInsertAdapter<BlocklistEntity>
  init {
    this.__db = __db
    this.__insertAdapterOfBlocklistEntity = object : EntityInsertAdapter<BlocklistEntity>() {
      protected override fun createQuery(): String = "INSERT OR REPLACE INTO `blocklist` (`id`,`domain`,`category`,`listSource`,`updatedAt`) VALUES (nullif(?, 0),?,?,?,?)"

      protected override fun bind(statement: SQLiteStatement, entity: BlocklistEntity) {
        statement.bindLong(1, entity.id)
        statement.bindText(2, entity.domain)
        statement.bindText(3, entity.category)
        statement.bindText(4, entity.listSource)
        statement.bindLong(5, entity.updatedAt)
      }
    }
  }

  public override suspend fun insertAll(es: List<BlocklistEntity>): Unit = performSuspending(__db, false, true) { _connection ->
    __insertAdapterOfBlocklistEntity.insert(_connection, es)
  }

  public override suspend fun allDomains(): List<String> {
    val _sql: String = "SELECT domain FROM blocklist"
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

  public override suspend fun domainsForCategory(cat: String): List<String> {
    val _sql: String = "SELECT domain FROM blocklist WHERE category = ?"
    return performSuspending(__db, true, false) { _connection ->
      val _stmt: SQLiteStatement = _connection.prepare(_sql)
      try {
        var _argIndex: Int = 1
        _stmt.bindText(_argIndex, cat)
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

  public override suspend fun count(): Long {
    val _sql: String = "SELECT COUNT(*) FROM blocklist"
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

  public override suspend fun deleteBySource(src: String): Int {
    val _sql: String = "DELETE FROM blocklist WHERE listSource = ?"
    return performSuspending(__db, false, true) { _connection ->
      val _stmt: SQLiteStatement = _connection.prepare(_sql)
      try {
        var _argIndex: Int = 1
        _stmt.bindText(_argIndex, src)
        _stmt.step()
        getTotalChangedRows(_connection)
      } finally {
        _stmt.close()
      }
    }
  }

  public override suspend fun deleteAll() {
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
