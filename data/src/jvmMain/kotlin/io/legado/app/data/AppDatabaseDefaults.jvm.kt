package io.legado.app.data

import androidx.room3.RoomDatabase
import androidx.sqlite.SQLiteConnection

/**
 * 桌面端建库预置数据回调, 内容见 [AppDatabaseDefaultData]。
 *
 * 官方 Room3 的 `Callback` 方法是 suspend, 鸿蒙 CPF fork 的不是, 故按平台分文件。
 */
object AppDatabaseDefaults : RoomDatabase.Callback() {

    // room3 时序是 dropAllTables → onDestructiveMigration → createAllTables, 回调里插入必然
    // "no such table", 故只置标记、由 onOpen (建表之后) 实插 (与 app/iOS/鸿蒙同一写法)
    @Volatile
    private var needInsertDefaults = false

    override suspend fun onCreate(connection: SQLiteConnection) {
        AppDatabaseDefaultData.insert(connection)
    }

    override suspend fun onDestructiveMigration(connection: SQLiteConnection) {
        needInsertDefaults = true
    }

    override suspend fun onOpen(connection: SQLiteConnection) {
        if (needInsertDefaults) {
            needInsertDefaults = false
            AppDatabaseDefaultData.insert(connection)
        }
    }
}
