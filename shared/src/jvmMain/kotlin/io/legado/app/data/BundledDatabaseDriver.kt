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
 * 17 个 DAO 已完成 suspend 迁移 (Room KMP 2.8.4 强制非 Android 平台 DAO 必须 suspend,
 * 见 https://developer.android.com/kotlin/multiplatform/room "转换阻塞型 DAO 函数" 段)。
 * 启用 `kspJvm(libs.room.compiler)` 后, Room 在 JVM target 生成 `AppDatabase_Impl`,
 * 桌面端可通过 `Room.databaseBuilder<AppDatabase>` 真实构造数据库实例。
 *
 * # 实现要点
 * - **驱动**: [BundledSQLiteDriver] (androidx.sqlite:sqlite-bundled 跨平台 SQLite, 内嵌原生库)
 * - **数据库路径**: 默认 `{desktopAppRootDir}/legado.db`, 可通过构造参数覆盖 (测试场景)
 * - **查询协程上下文**: [Dispatchers.IO], suspend DAO 方法在 IO 线程执行
 * - **迁移策略**: 桌面端首启动即此版本 (86); schema 升级依赖 shared AppDatabase 的
 *   autoMigrations/显式 Migration (与 Android 端同源), 迁移失败显式抛出,
 *   不做静默破坏性重建 (防止丢用户数据)
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
            // 桌面端首启动空库即 version 86; autoMigrations 由 @Database 声明自动生效,
            // 但手写 Migration (80..83) 必须显式注册, 与 app 端 AppDatabase.kt:46 同一份
            .addMigrations(*DatabaseMigrations.migrations)
            // 迁移失败时 Room 显式抛 IllegalStateException, 不做静默破坏性重建:
            // 不能像原来那样 fallbackToDestructiveMigration —— 那是无条件 drop 全部表,
            // 下次 schema 变更即丢光书架/分组/阅读进度, 且静默无提示。
            // 预置分组 + 键盘助手 (对照 app 端 dbCallback)
            .addCallback(AppDatabaseDefaults)
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
