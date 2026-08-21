package io.legado.app.data

import androidx.room3.Room
import androidx.room3.RoomDatabase
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
 * - **迁移策略**: 与 app 端 AppDatabase 一致 —— 仅 v1..79 旧库破坏性重建, 83..86 走
 *   @Database autoMigrations, 无迁移路径时让 Room 显式抛错而非静默清库
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

    @OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)
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
    override val appDatabase: AppDatabase by lazy {
        Room.databaseBuilder<AppDatabase>(
            name = dbFile
        )
            .setDriver(NativeSQLiteDriver())
            .setQueryCoroutineContext(Dispatchers.IO)
            // 与 app 端一致: 仅旧包名 (io.legado.app) 时代的 v1..79 旧库属于不同应用、无法原地升级,
            // 走破坏性重建; 其余版本宁可让 Room 抛错也不静默清库 (dropAllTables 会丢光书架/分组/进度)。
            // 80..82 的手写 Migration 在 jvmAndAndroidMain, native 端暂不可见 (iOS 首版即 86, 不会命中)。
            .fallbackToDestructiveMigrationFrom(
                false,
                1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15, 16, 17, 18, 19, 20,
                21, 22, 23, 24, 25, 26, 27, 28, 29, 30, 31, 32, 33, 34, 35, 36, 37, 38, 39, 40,
                41, 42, 43, 44, 45, 46, 47, 48, 49, 50, 51, 52, 53, 54, 55, 56, 57, 58, 59, 60,
                61, 62, 63, 64, 65, 66, 67, 68, 69, 70, 71, 72, 73, 74, 75, 76, 77, 78, 79
            )
            // 预置分组 + 键盘助手 (对照 app 端 dbCallback)
            .addCallback(AppDatabaseDefaults)
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
