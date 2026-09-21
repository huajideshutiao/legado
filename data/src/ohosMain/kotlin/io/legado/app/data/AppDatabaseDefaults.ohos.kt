package io.legado.app.data

import androidx.room3.RoomDatabase
import androidx.sqlite.SQLiteConnection
import kotlin.concurrent.Volatile

/**
 * 鸿蒙端建库预置数据回调, 内容见 [AppDatabaseDefaultData]。
 *
 * CPF fork 的 `RoomDatabase.Callback` 方法**不是** suspend (官方 Room3 是), 故按平台分文件,
 * 与 [Migration84To85] 的 `onPostMigrate` 是同一处差异。
 */
object AppDatabaseDefaults : RoomDatabase.Callback() {

    // 破坏性迁移回调早于建表 (room3 顺序: dropAllTables → 回调 → createAllTables),
    // 回调里插表必然 no such table, 只置标记, 由 onOpen (此时表已建好) 补插
    @Volatile
    private var needInsertDefaults = false

    override fun onCreate(connection: SQLiteConnection) {
        AppDatabaseDefaultData.insert(connection)
    }

    override fun onDestructiveMigration(connection: SQLiteConnection) {
        needInsertDefaults = true
    }

    override fun onOpen(connection: SQLiteConnection) {
        // 必须由标记门控, 否则每次启动 insert or replace 会覆盖用户改过的键盘助手
        if (needInsertDefaults) {
            needInsertDefaults = false
            AppDatabaseDefaultData.insert(connection)
        }
    }
}
