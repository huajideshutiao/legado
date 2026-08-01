package io.legado.app.data

import androidx.sqlite.SQLiteConnection
import androidx.sqlite.SQLiteStatement
import io.legado.app.constant.AppLog
import io.legado.app.data.AppDatabaseDefaultData.insert
import io.legado.app.data.entities.BookGroup
import io.legado.app.help.DefaultDataShared

/**
 * 建库预置数据 (下沉自 app 端 `io.legado.app.dbCallback.onCreate`)。
 *
 * app 端的 dbCallback 还做了 `setLocale(Locale.CHINESE)` (BookmarkDao 的 collate localized 依赖它),
 * 那段依赖框架 SQLite 的 AndroidSQLiteConnection, 非 Android 端没有对应物, 故不在本类内。
 *
 * 挂载点 (`RoomDatabase.Callback`) 由各平台 source set 提供: 官方 Room3 的 Callback 方法是
 * suspend, 鸿蒙 CPF fork 的是非 suspend, 与 [Migration84To85] 同一处平台差异。
 *
 * 建库与破坏性重建都调 [insert]: 后者会 dropAllTables, 不补的话四个预置分组就永久没了。
 * 两段 SQL 都幂等 (分组 `where not exists`, 键盘助手 `insert or replace`)。
 */
object AppDatabaseDefaultData {

    fun insert(connection: SQLiteConnection) {
        insertPresetGroups(connection)
        insertKeyboardAssists(connection)
    }

    /** 预置分组: groupId, 名称, 排序, 可刷新(1/0), 显示(1/0) */
    private data class PresetGroup(
        val id: Long,
        val name: String,
        val order: Int,
        val enableRefresh: Int,
        val show: Int,
    )

    private val presetGroups = listOf(
        PresetGroup(BookGroup.IdAll, "全部", -10, 1, 1),
        PresetGroup(BookGroup.IdLocal, "本地", -9, 0, 1),
        PresetGroup(BookGroup.IdUngrouped, "未分组", -7, 1, 1),
        PresetGroup(BookGroup.IdError, "更新失败", -1, 1, 1),
    )

    private fun insertPresetGroups(connection: SQLiteConnection) {
        val sql = "insert into book_groups(groupId, groupName, `order`, enableRefresh, show) " +
            "select ?, ?, ?, ?, ? " +
            "where not exists (select 1 from book_groups where groupId = ?)"
        runCatching {
            connection.prepare(sql).use { stmt: SQLiteStatement ->
                presetGroups.forEach { group ->
                    stmt.bindLong(1, group.id)
                    stmt.bindText(2, group.name)
                    stmt.bindInt(3, group.order)
                    stmt.bindInt(4, group.enableRefresh)
                    stmt.bindInt(5, group.show)
                    stmt.bindLong(6, group.id)
                    stmt.step()
                    stmt.reset()
                }
            }
        }.onFailure { AppLog.put("写入默认书籍分组失败", it) }
    }

    /**
     * 预置键盘助手。资源读取/解析失败时记日志用空列表兜底, 别让首装建库崩死
     * (与 app 端 dbCallback 同款容错)。
     */
    private fun insertKeyboardAssists(connection: SQLiteConnection) {
        val keyboardAssists = runCatching { DefaultDataShared.keyboardAssists }
            .onFailure { AppLog.put("读取默认键盘助手失败", it) }
            .getOrDefault(emptyList())
        if (keyboardAssists.isEmpty()) return
        val sql = "insert or replace into keyboardAssists(type, `key`, value, serialNo) " +
            "values(?, ?, ?, ?)"
        runCatching {
            connection.prepare(sql).use { stmt: SQLiteStatement ->
                keyboardAssists.forEach { keyboardAssist ->
                    stmt.bindInt(1, keyboardAssist.type)
                    stmt.bindText(2, keyboardAssist.key)
                    stmt.bindText(3, keyboardAssist.value)
                    stmt.bindInt(4, keyboardAssist.serialNo)
                    stmt.step()
                    stmt.reset()
                }
            }
        }.onFailure { AppLog.put("写入默认键盘助手失败", it) }
    }
}
