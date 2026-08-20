package io.legado.app.data

import androidx.room3.RoomDatabase
import androidx.sqlite.SQLiteConnection
import kotlin.concurrent.Volatile

/**
 * iOS 端建库预置数据回调, 内容见 [AppDatabaseDefaultData]。
 *
 * 官方 Room3 的 `Callback` 方法是 suspend, 鸿蒙 CPF fork 的不是, 故按平台分文件。
 */
object AppDatabaseDefaults : RoomDatabase.Callback() {

    // 破坏性迁移回调早于建表 (room3 顺序: dropAllTables → 回调 → createAllTables),
    // 回调里插表必然 no such table, 只置标记, 由 onOpen (此时表已建好) 补插
    @Volatile
    private var needInsertDefaults = false

    override suspend fun onCreate(connection: SQLiteConnection) {
        AppDatabaseDefaultData.insert(connection)
    }

    override suspend fun onDestructiveMigration(connection: SQLiteConnection) {
        needInsertDefaults = true
    }

    override suspend fun onOpen(connection: SQLiteConnection) {
        // 必须由标记门控, 否则每次启动 insert or replace 会覆盖用户改过的键盘助手
        if (needInsertDefaults) {
            needInsertDefaults = false
            AppDatabaseDefaultData.insert(connection)
        }
    }
}
