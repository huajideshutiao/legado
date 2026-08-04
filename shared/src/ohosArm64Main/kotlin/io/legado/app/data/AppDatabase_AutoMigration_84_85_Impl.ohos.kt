// 鸿蒙端 AutoMigration fork 适配: KSP 生成物为 suspend override + executeSQL 旧名,
// 与 CPF fork runtime 非 suspend Migration.migrate 不兼容, 故派生本文件 (2026-08-04)。
package io.legado.app.`data`

import androidx.room3.migration.AutoMigrationSpec
import androidx.room3.migration.Migration
import androidx.sqlite.SQLiteConnection
import androidx.sqlite.execSQL
import kotlin.Suppress

@Suppress(names = ["UNCHECKED_CAST", "DEPRECATION", "REDUNDANT_PROJECTION", "REMOVAL"])
internal class AppDatabase_AutoMigration_84_85_Impl : Migration {
    private val callback: AutoMigrationSpec = Migration84To85()

    public constructor() : super(84, 85)

    public override fun migrate(connection: SQLiteConnection) {
        connection.execSQL("DROP VIEW book_sources_part")
        connection.execSQL("ALTER TABLE `book_sources` ADD COLUMN `enabledReview` INTEGER NOT NULL DEFAULT 1")
        connection.execSQL(
            """
        |CREATE VIEW `book_sources_part` AS select bookSourceUrl, bookSourceName, bookSourceGroup, customOrder, enabled, enabledExplore,
        |    (ifnull(trim(loginUrl), '') <> '' or ifnull(trim(loginUi), '') <> '') hasLoginUrl, lastUpdateTime, respondTime, weight,
        |    (ifnull(trim(exploreUrl), '') <> '') hasExploreUrl
        |    from book_sources
        """.trimMargin()
        )
        callback.onPostMigrate(connection)
    }
}
