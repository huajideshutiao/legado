// 鸿蒙端 AutoMigration fork 适配: KSP 生成物为 suspend override + executeSQL 旧名,
// 与 CPF fork runtime 非 suspend Migration.migrate 不兼容, 故派生本文件 (2026-08-04)。
package io.legado.app.`data`

import androidx.room3.migration.Migration
import androidx.sqlite.SQLiteConnection
import androidx.sqlite.execSQL
import kotlin.Suppress

@Suppress(names = ["UNCHECKED_CAST", "DEPRECATION", "REDUNDANT_PROJECTION", "REMOVAL"])
internal class AppDatabase_AutoMigration_85_86_Impl : Migration {
    public constructor() : super(85, 86)

    public override fun migrate(connection: SQLiteConnection) {
        connection.execSQL("DROP VIEW book_sources_part")
        connection.execSQL("CREATE TABLE IF NOT EXISTS `source_filter_rules` (`id` TEXT NOT NULL, `name` TEXT NOT NULL DEFAULT '', `enabled` INTEGER NOT NULL DEFAULT 1, `pattern` TEXT NOT NULL DEFAULT '', `fields` TEXT NOT NULL DEFAULT '', `scope` TEXT NOT NULL DEFAULT '', `sortOrder` INTEGER NOT NULL DEFAULT 0, `createTime` INTEGER NOT NULL DEFAULT 0, PRIMARY KEY(`id`))")
        connection.execSQL(
            """
        |CREATE VIEW `book_sources_part` AS select bookSourceUrl, bookSourceName, bookSourceGroup, customOrder, enabled, enabledExplore,
        |    (ifnull(trim(loginUrl), '') <> '' or ifnull(trim(loginUi), '') <> '') hasLoginUrl, lastUpdateTime, respondTime, weight,
        |    (ifnull(trim(exploreUrl), '') <> '') hasExploreUrl
        |    from book_sources
        """.trimMargin()
        )
    }
}
