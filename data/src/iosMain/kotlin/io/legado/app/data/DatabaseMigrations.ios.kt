package io.legado.app.data

import androidx.room3.migration.Migration
import androidx.sqlite.SQLiteConnection

/**
 * iOS 端三条手写迁移: 官方 Room3 的 `Migration.migrate` 是 suspend。
 * 迁移体见 [DatabaseMigrationsData]。
 *
 * iOS 从未发布, 无存量库 (首版即 @Database 当前版本, 1..79 走破坏性重建), 这三条永不触发; 挂上只为
 * 与 Android / 桌面端同一条迁移路径, 避免日后版本号回落时静默清库。
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
