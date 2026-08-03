package io.legado.app.data

import androidx.room3.migration.AutoMigrationSpec
import androidx.sqlite.SQLiteConnection

/**
 * 鸿蒙端 [Migration84To85]: CPF fork 的 `AutoMigrationSpec.onPostMigrate` 不是 suspend
 * (与 AppDatabaseDefaults 同一处平台差异)。清洗逻辑见 [Migration84To85Data]。
 */
actual class Migration84To85 : AutoMigrationSpec {
    override fun onPostMigrate(connection: SQLiteConnection) {
        Migration84To85Data.clean(connection)
    }
}
