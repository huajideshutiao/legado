package io.legado.app.data

import android.annotation.SuppressLint
import android.os.Build
import android.util.Log
import androidx.room3.Room
import androidx.sqlite.SQLiteConnection
import androidx.sqlite.driver.AndroidSQLiteConnection
import androidx.sqlite.driver.AndroidSQLiteDriver
import io.legado.app.App
import io.legado.app.constant.AppLog
import io.legado.app.data.AndroidAppDatabaseProvider.appDb
import io.legado.app.data.entities.BookGroup
import io.legado.app.help.DefaultData
import org.intellij.lang.annotations.Language
import java.util.Locale

/**
 * Android 端 [AppDatabaseProvider] 实现。
 *
 * 委托 app 端 [appDb] lazy 单例 (依赖 appCtx + AndroidSQLiteDriver + DefaultData + Locale.CHINESE),
 * 在 [io.legado.app.model.webBook.registerAndroidWebBookProviders] 中注册到 [AppDatabaseProviders]。
 *
 * 桌面端对应实现为 `DesktopAppDatabaseProvider` (用 Room.databaseBuilder + BundledSQLiteDriver)。
 */
object AndroidAppDatabaseProvider : AppDatabaseProvider {
    override val appDb: AppDatabase
        get() = io.legado.app.data.appDb
}

/**
 * AppDatabase 主体 (含 @Database 注解) 在 shared/commonMain, 本文件保留 app 端专属内容:
 * - appDb 单例: 依赖 appCtx (Android Context) + AndroidSQLiteDriver
 * - dbCallback: 依赖 AndroidSQLiteConnection + Locale.CHINESE + DefaultData (app 端专属)
 *
 * AppDatabase::class.java / AppDatabase.DATABASE_NAME 从 shared/commonMain 引用。
 */
val appDb by lazy {
    Room.databaseBuilder(App.instance, AppDatabase::class.java, AppDatabase.DATABASE_NAME)
        // Room 3 移除了 SupportSQLiteOpenHelper, 改由 driver 打开框架 SQLite
        .setDriver(AndroidSQLiteDriver())
        // 包名已由 io.legado.app 改为 shutiao.reader（DB v80 时），新包名最低从 v80 起，
        // 之前所有版本的旧库都属于不同应用，无法原地升级，统一走破坏性重建。
        .fallbackToDestructiveMigrationFrom(
            false,
            1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15, 16, 17, 18, 19, 20,
            21, 22, 23, 24, 25, 26, 27, 28, 29, 30, 31, 32, 33, 34, 35, 36, 37, 38, 39, 40,
            41, 42, 43, 44, 45, 46, 47, 48, 49, 50, 51, 52, 53, 54, 55, 56, 57, 58, 59, 60,
            61, 62, 63, 64, 65, 66, 67, 68, 69, 70, 71, 72, 73, 74, 75, 76, 77, 78, 79
        )
        .addMigrations(*DatabaseMigrations.migrations)
        .allowMainThreadQueries()
        .addCallback(dbCallback)
        .build()
}

/**
 * Room Database callback, app 端专属。
 *
 * 依赖 AndroidSQLiteConnection (Android 平台), Locale.CHINESE (java.util), DefaultData (app 端)。
 * 原 AppDatabase.dbCallback companion 属性, 下沉后改为顶层 val (companion 已在 shared/commonMain)。
 */
// AndroidSQLiteConnection/db 是 androidx.sqlite 内部 API (@RestrictTo), 但 setLocale 无公开
// 替代路径 (BookmarkDao collate localized 依赖它), 属刻意使用, 抑制 RestrictedApi
@SuppressLint("RestrictedApi")
val dbCallback = object : androidx.room3.RoomDatabase.Callback() {

    override suspend fun onCreate(connection: SQLiteConnection) {
        // 只在 API 级别 23 (Marshmallow) 及以上版本尝试设置区域设置
        try {
            Log.d(
                "AppDatabaseCallback",
                "准备 设置 locale for API ${Build.VERSION.SDK_INT}..."
            )
            // SQLiteConnection 无 setLocale；AndroidSQLiteDriver 的连接持有框架
            // SQLiteDatabase，经它保留原语义（BookmarkDao 的 collate localized 依赖它）。
            (connection as? AndroidSQLiteConnection)?.db?.setLocale(Locale.CHINESE)
            // 在 21 上报错，但无法拦截
            Log.d(
                "AppDatabaseCallback",
                "成功 设置 locale for API ${Build.VERSION.SDK_INT}."
            )
        } catch (e: Exception) {
            Log.e(
                "AppDatabaseCallback",
                "错误 设置 locale in onCreate for API ${Build.VERSION.SDK_INT}",
                e
            )
        }

        // 预置分组：groupId, 名称, 排序, 可刷新(1/0), 显示(1/0)
        data class PresetGroup(
            val id: Long, val name: String,
            val order: Int, val enableRefresh: Int, val show: Int
        )

        val presetGroups = listOf(
            PresetGroup(BookGroup.IdAll, "全部", -10, 1, 1),
            PresetGroup(BookGroup.IdLocal, "本地", -9, 0, 1),
            PresetGroup(BookGroup.IdUngrouped, "未分组", -7, 1, 1),
            PresetGroup(BookGroup.IdError, "更新失败", -1, 1, 1),
        )
        @Language("sql")
        val insertGroupSql =
            "insert into book_groups(groupId, groupName, `order`, enableRefresh, show) " +
                "select ?, ?, ?, ?, ? " +
                "where not exists (select 1 from book_groups where groupId = ?)"
        connection.prepare(insertGroupSql).use { stmt ->
            presetGroups.forEach { group ->
                stmt.bindLong(1, group.id)
                stmt.bindText(2, group.name)
                stmt.bindInt(3, group.order)
                stmt.bindInt(4, group.enableRefresh)
                stmt.bindInt(5, group.show)
                stmt.bindLong(6, group.id)
                stmt.step()
                stmt.reset()
            }
        }

        // 预置键盘助手（insert or replace 与旧 ContentValues+CONFLICT_REPLACE 等价）
        // 资源读取/解析失败时记日志用空列表兜底, 别让首装建库崩死
        val keyboardAssists = runCatching { DefaultData.keyboardAssists }
            .onFailure { AppLog.put("读取默认键盘助手失败", it) }
            .getOrDefault(emptyList())
        @Language("sql")
        val insertAssistSql =
            "insert or replace into keyboardAssists(type, `key`, value, serialNo) " +
                "values(?, ?, ?, ?)"
        connection.prepare(insertAssistSql).use { stmt ->
            keyboardAssists.forEach { keyboardAssist ->
                stmt.bindInt(1, keyboardAssist.type)
                stmt.bindText(2, keyboardAssist.key)
                stmt.bindText(3, keyboardAssist.value)
                stmt.bindInt(4, keyboardAssist.serialNo)
                stmt.step()
                stmt.reset()
            }
        }
    }
}
