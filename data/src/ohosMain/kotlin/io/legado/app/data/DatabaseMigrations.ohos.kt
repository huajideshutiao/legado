package io.legado.app.data

import androidx.room3.migration.Migration
import androidx.sqlite.SQLiteConnection

/**
 * 鸿蒙端三条手写迁移: CPF fork 的 `Migration.migrate` **不是** suspend (官方 Room3 是),
 * 与 [Migration84To85] 的 `onPostMigrate` 是同一处差异。迁移体见 [DatabaseMigrationsData]。
 *
 * 鸿蒙同样无存量库 (首版即 @Database 当前版本), 这三条永不触发, 挂上只为与其他端同一条迁移路径。
 */

internal actual fun migration80To81(): Migration = object : Migration(80, 81) {
    override fun migrate(connection: SQLiteConnection) {
        DatabaseMigrationsData.migrate80To81(connection)
    }
}

internal actual fun migration81To82(): Migration = object : Migration(81, 82) {
    override fun migrate(connection: SQLiteConnection) {
        DatabaseMigrationsData.migrate81To82(connection)
    }
}

internal actual fun migration82To83(): Migration = object : Migration(82, 83) {
    override fun migrate(connection: SQLiteConnection) {
        DatabaseMigrationsData.migrate82To83(connection)
    }
}
