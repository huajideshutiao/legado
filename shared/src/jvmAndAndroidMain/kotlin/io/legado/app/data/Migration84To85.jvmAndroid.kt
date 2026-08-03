package io.legado.app.data

import androidx.room3.migration.AutoMigrationSpec
import androidx.sqlite.SQLiteConnection

/**
 * Android / 桌面端 [Migration84To85]: 官方 Room3 的 `AutoMigrationSpec.onPostMigrate` 是 suspend。
 * 清洗逻辑见 [Migration84To85Data]。
 */
actual class Migration84To85 : AutoMigrationSpec {
    override suspend fun onPostMigrate(connection: SQLiteConnection) {
        Migration84To85Data.clean(connection)
    }
}
