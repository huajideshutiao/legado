package io.legado.app.data

import androidx.room3.Room
import androidx.room3.RoomDatabase
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import io.legado.app.data.AppDatabase.Companion.DATABASE_NAME
import io.legado.app.help.file.desktopAppRootDir
import kotlinx.coroutines.Dispatchers
import java.io.File

/**
 * JVM 端 [DatabaseDriverProvider] 实现: **Room KMP + BundledSQLiteDriver 真实数据库**。
 *
 * # 背景
 * KP1.2 已完成 17 个 DAO 的 suspend 迁移 (Room KMP 2.8.4 强制非 Android 平台 DAO 必须 suspend,
 * 见 https://developer.android.com/kotlin/multiplatform/room "转换阻塞型 DAO 函数" 段)。
 * 启用 `kspJvm(libs.room.compiler)` 后, Room 在 JVM target 生成 `AppDatabase_Impl`,
 * 桌面端可通过 `Room.databaseBuilder<AppDatabase>` 真实构造数据库实例。
 *
 * # 实现要点
 * - **驱动**: [BundledSQLiteDriver] (androidx.sqlite:sqlite-bundled 跨平台 SQLite, 内嵌原生库)
 * - **数据库路径**: 默认 `{desktopAppRootDir}/legado.db`, 可通过构造参数覆盖 (测试场景)
 * - **查询协程上下文**: [Dispatchers.IO], suspend DAO 方法在 IO 线程执行
 * - **迁移策略**: 桌面端首启动即此版本 (86), 无历史迁移; 若 schema 与文件不匹配,
 *   `fallbackToDestructiveMigration` 兜底重建 (桌面端无 Android 端的 autoMigrations 历史数据需保)
 *
 * # 与 app 端 [RoomDatabaseDriver] 区别
 * - app 端用 `AndroidSQLiteDriver` + `appCtx.getDatabasePath(...)`, 依赖 Android Framework SQLite
 * - 桌面 jvm 无 Android Framework, 走 `BundledSQLiteDriver` (内嵌 SQLite 原生库) + 本地文件路径
 * - app 端 dbCallback (SupportSQLiteConnection + Locale.CHINESE + DefaultData) 不下沉,
 *   桌面端首启动走空 schema (Room 按 @Database entities 自动建表), 预置数据由后续业务逻辑补
 *
 * # 共享实例
 * [appDatabase] lazy 单例, 同一实例供 [AppDatabaseProviders] + [DatabaseDriverProviders] 共享
 * (避免重复构造, 见 [DesktopAppDatabaseProvider])。
 *
 * @param dbPath 数据库文件绝对路径, 默认 `{desktopAppRootDir}/legado.db`
 */
class BundledDatabaseDriver(
    dbPath: String = defaultDbPath()
) : DatabaseDriverProvider {

    private val dbFile: File = File(dbPath).apply { parentFile?.mkdirs() }

    /**
     * 桌面端 [AppDatabase] 单例 (真实 Room 数据库)。
     *
     * lazy 构造: 首次访问时执行 `Room.databaseBuilder<AppDatabase>` + `BundledSQLiteDriver`。
     * public 暴露供 [DesktopAppDatabaseProvider] 实现委托 (`override val appDb get() = driver.appDatabase`),
     * 避免重复构造。
     */
    val appDatabase: AppDatabase by lazy {
        Room.databaseBuilder<AppDatabase>(
            name = dbFile.absolutePath
        )
            .setDriver(BundledSQLiteDriver())
            .setQueryCoroutineContext(Dispatchers.IO)
            // 桌面端首启动空库即 version 86, 无 Android 端 83→86 的 autoMigration 历史。
            // 若后续 schema 升级与本地文件不匹配 (如 shared 模块升级后 @Database version 提升),
            // 兜底重建 (dropAllTables=true), 让桌面端自动恢复到可用状态。
            .fallbackToDestructiveMigration(dropAllTables = true)
            .build()
    }

    override val databaseName: String
        get() = DATABASE_NAME

    override val databaseVersion: Int
        // 与 @Database(version = 86) 同步; 桌面端首启动空库即此版本, 无迁移历史
        get() = 86

    override val isInitialized: Boolean
        // Room KMP 的 RoomDatabase 无 isOpen 属性 (Android 专属),
        // 用 try-catch 触发 lazy 构造判断: 构造成功即视为已初始化
        get() = try {
            appDatabase
            true
        } catch (e: Exception) {
            false
        }

    override val rawDatabase: Any
        get() = appDatabase as RoomDatabase

    override fun close() {
        // 幂等: RoomDatabase.close() 内部已处理重复调用;
        // Room KMP 无 isOpen 可判断, 直接 try-close, 失败静默
        try {
            appDatabase.close()
        } catch (e: Exception) {
            // 关闭失败不传播, 避免退出流程中断
        }
    }

    companion object {
        /**
         * 默认数据库路径: `{desktopAppRootDir}/legado.db`。
         *
         * - 便携模式: 跟随 exe 同级 `data/` 目录 (portable.txt 标记 / legado.portable.root)
         * - 安装/开发模式: 系统数据目录 (%APPDATA%/XDG_DATA_HOME/Application Support)/legado
         */
        fun defaultDbPath(): String {
            return File(desktopAppRootDir(), DATABASE_NAME).absolutePath
        }
    }
}
