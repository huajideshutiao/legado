package io.legado.app.data

import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.sqlite.driver.NativeSQLiteDriver
import io.legado.app.data.AppDatabase.Companion.DATABASE_NAME
import io.legado.app.help.file.AppFilesDirs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import platform.Foundation.NSFileManager

/**
 * iOS 端 [DatabaseDriverProvider] 实现: **Room KMP + NativeSQLiteDriver 真实数据库**。
 *
 * iOS 系统已提供 SQLite，使用 `sqlite-framework` 的 [NativeSQLiteDriver] 直接接入，
 * 不再把另一份 SQLite 静态库链接进 framework。
 * - **数据库路径**: 默认 `{AppFilesDirs.filesDir}/legado.db` (沙盒 Documents 目录下, 持久化)
 * - **查询协程上下文**: [Dispatchers.IO] (iOS 上 Ktor CIO + Dispatchers.IO 可用)
 * - **迁移策略**: iOS 首启动即此版本 (86), 无历史迁移; 若 schema 与文件不匹配,
 *   `fallbackToDestructiveMigration` 兜底重建 (iOS 端无 Android 端的 autoMigrations 历史数据需保)
 *
 * # 与 jvmMain BundledDatabaseDriver 区别
 * - 数据库路径: jvm 用 `~/.legado/legado.db`, iOS 用 `Documents/legado.db` (沙盒内)
 * - KSP 生成: jvmMain 用 kspJvm 生成 AppDatabase_Impl, iOS 用 kspIosArm64 + kspIosSimulatorArm64
 *   (build.gradle 已配置, 见 `kspIosArm64(libs.room.compiler)` / `kspIosSimulatorArm64(libs.room.compiler)`)
 *
 * # 共享实例
 * [appDatabase] lazy 单例, 同一实例供 [AppDatabaseProviders] + [DatabaseDriverProviders] 共享
 * (避免重复构造, 见 [DesktopAppDatabaseProvider] 模式)。
 *
 * @param dbPath 数据库文件绝对路径, 默认 `{AppFilesDirs.filesDir}/legado.db`
 */
class IosDatabaseDriver(
    dbPath: String = defaultDbPath()
) : NativeDatabaseDriver {

    private val dbFile: String = dbPath.apply {
        // 父目录不存在则递归创建 (与 jvmMain File(dbPath).apply { parentFile?.mkdirs() } 行为对齐)
        val parentDir = substringBeforeLast('/')
        val fileManager = NSFileManager.defaultManager
        if (parentDir.isNotEmpty() && !fileManager.fileExistsAtPath(parentDir)) {
            fileManager.createDirectoryAtPath(
                path = parentDir,
                withIntermediateDirectories = true,
                attributes = null,
                error = null
            )
        }
    }

    /**
     * iOS 端 [AppDatabase] 单例 (真实 Room 数据库)。
     *
     * lazy 构造: 首次访问时执行 `Room.databaseBuilder<AppDatabase>` + `NativeSQLiteDriver`。
     */
    val appDatabase: AppDatabase by lazy {
        Room.databaseBuilder<AppDatabase>(
            name = dbFile
        )
            .setDriver(NativeSQLiteDriver())
            .setQueryCoroutineContext(Dispatchers.IO)
            // iOS 首启动空库即 version 86, 无 Android 端 83→86 的 autoMigration 历史。
            // 若后续 schema 升级与本地文件不匹配 (如 shared 模块升级后 @Database version 提升),
            // 兜底重建 (dropAllTables=true), 让 iOS 端自动恢复到可用状态。
            .fallbackToDestructiveMigration(dropAllTables = true)
            .build()
    }

    override val databaseName: String
        get() = DATABASE_NAME

    override val databaseVersion: Int
        // 与 @Database(version = 86) 同步; iOS 首启动空库即此版本, 无迁移历史
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
         * 默认数据库路径: `{AppFilesDirs.filesDir}/legado.db`。
         *
         * - iOS 沙盒 Documents 目录 (持久化, iTunes 文件共享可见)
         * - 与桌面端 `~/.legado/legado.db` 行为等价 (持久化 + 用户可访问)
         * - 路径分隔符恒为 "/" (iOS POSIX 文件系统)
         */
        fun defaultDbPath(): String {
            val filesDir = AppFilesDirs.get().filesDir
            return if (filesDir.endsWith("/")) "${filesDir}$DATABASE_NAME" else "$filesDir/$DATABASE_NAME"
        }
    }
}

/**
 * iOS 宿主启动早期注册 [DatabaseDriverProvider] 的真实实现。
 *
 * 调用时机: iOS app 启动早期, 在任何 commonMain 代码访问 `DatabaseDriverProviders.get()` 之前。
 * 前置依赖: [io.legado.app.help.file.registerIosAppFilesDir] 需先注册
 * (IosDatabaseDriver 默认路径从 [AppFilesDirs.get] 派生)。
 *
 * 同时将同一 [IosDatabaseDriver] 实例的 `appDatabase` 通过 [NativeAppDatabaseProvider] 委托注册到
 * [AppDatabaseProviders], 与桌面端 `Desktop Main.kt` 模式对齐 (`dbDriver` 实例共享, 避免重复构造)。
 *
 * 模式参考 desktop `Main.kt` 中 `DatabaseDriverProviders.register(dbDriver)` +
 * `AppDatabaseProviders.register(DesktopAppDatabaseProvider(dbDriver))` 双注册模式。
 *
 * 注: accessor ([NativeAppDbAccessor]) 不在本函数注册, 而是延后到 [registerIosAppDbAccessor]
 * (在 BookStorage 之后, 与 [io.legado.app.help.config.registerIosProviders] 步骤 6 对齐)。
 */
fun registerIosDatabaseDriver() {
    val driver = IosDatabaseDriver()
    DatabaseDriverProviders.register(driver)
    AppDatabaseProviders.register(NativeAppDatabaseProvider(driver))
}

/**
 * iOS 端 [AppDatabaseProvider] 实现已下沉到 nativeMain [NativeAppDatabaseProvider]
 * (委托 [NativeDatabaseDriver.appDatabase], iOS / 鸿蒙共用),
 * 本文件不再保留平台专属 Provider 类。
 */
