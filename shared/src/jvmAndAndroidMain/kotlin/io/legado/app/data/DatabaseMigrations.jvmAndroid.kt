package io.legado.app.data

import androidx.room3.migration.Migration
import androidx.sqlite.SQLiteConnection

/**
 * Android / 桌面端三条手写迁移: 官方 Room3 的 `Migration.migrate` 是 suspend。
 * 迁移体见 [DatabaseMigrationsData]。
 */

internal actual fun migration80To81(): Migration = object : Migration(80, 81) {
    override suspend fun migrate(connection: SQLiteConnection) {
        DatabaseMigrationsData.migrate80To81(connection)
    }
}

internal actual fun migration81To82(): Migration = object : Migration(81, 82) {
    override suspend fun migrate(connection: SQLiteConnection) {
        DatabaseMigrationsData.migrate81To82(connection)
    }
}

internal actual fun migration82To83(): Migration = object : Migration(82, 83) {
    override suspend fun migrate(connection: SQLiteConnection) {
        DatabaseMigrationsData.migrate82To83(connection)
    }
}
