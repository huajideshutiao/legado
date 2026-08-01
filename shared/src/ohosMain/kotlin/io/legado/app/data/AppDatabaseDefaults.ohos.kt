package io.legado.app.data

import androidx.room3.RoomDatabase
import androidx.sqlite.SQLiteConnection

/**
 * 鸿蒙端建库预置数据回调, 内容见 [AppDatabaseDefaultData]。
 *
 * CPF fork 的 `RoomDatabase.Callback` 方法**不是** suspend (官方 Room3 是), 故按平台分文件,
 * 与 [Migration84To85] 的 `onPostMigrate` 是同一处差异。
 */
object AppDatabaseDefaults : RoomDatabase.Callback() {

    override fun onCreate(connection: SQLiteConnection) {
        AppDatabaseDefaultData.insert(connection)
    }

    override fun onDestructiveMigration(connection: SQLiteConnection) {
        AppDatabaseDefaultData.insert(connection)
    }
}
