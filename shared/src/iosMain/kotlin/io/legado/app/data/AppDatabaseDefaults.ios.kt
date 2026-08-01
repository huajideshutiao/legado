package io.legado.app.data

import androidx.room3.RoomDatabase
import androidx.sqlite.SQLiteConnection

/**
 * iOS 端建库预置数据回调, 内容见 [AppDatabaseDefaultData]。
 *
 * 官方 Room3 的 `Callback` 方法是 suspend, 鸿蒙 CPF fork 的不是, 故按平台分文件。
 */
object AppDatabaseDefaults : RoomDatabase.Callback() {

    override suspend fun onCreate(connection: SQLiteConnection) {
        AppDatabaseDefaultData.insert(connection)
    }

    override suspend fun onDestructiveMigration(connection: SQLiteConnection) {
        AppDatabaseDefaultData.insert(connection)
    }
}
