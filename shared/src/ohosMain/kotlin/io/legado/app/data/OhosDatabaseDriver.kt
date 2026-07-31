package io.legado.app.data

import androidx.room3.Room
import androidx.room3.RoomDatabase
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import io.legado.app.data.AppDatabase.Companion.DATABASE_NAME
import io.legado.app.help.file.AppFilesDirs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import io.legado.app.utils.File

/**
 * 鸿蒙 (OHOS) 端 [DatabaseDriverProvider] 实现: **Room KMP + BundledSQLiteDriver 真实数据库**。
 *
 * KP5: 鸿蒙端 DatabaseDriver 落地, 与 [IosDatabaseDriver] / jvmMain [BundledDatabaseDriver] 行为对齐。
 * 鸿蒙 KMP 复用 linuxArm64 target (OpenHarmony arm64 triple `aarch64-linux-ohos` 与 linuxArm64 ABI 兼容),
 * Room KMP + BundledSQLiteDriver 在 linuxArm64 上即可构造真实 SQLite 数据库。
 *
 * # 实现要点 (与 jvmMain BundledDatabaseDriver / iosMain IosDatabaseDriver 行为对齐)
 * - **驱动**: [BundledSQLiteDriver] (androidx.sqlite:sqlite-bundled 跨平台 SQLite, 内嵌原生库)
 *   鸿蒙端 BundledSQLiteDriver 内部用 sqlite-bundled commonMain KMP 发布的 linuxArm64 原生库
 * - **数据库路径**: 默认 `{AppFilesDirs.filesDir}/legado.db` (鸿蒙应用沙盒 filesDir 下, 持久化)
 * - **查询协程上下文**: [Dispatchers.IO] (鸿蒙端 Ktor CIO + Dispatchers.IO 可用, 与 iOS 端一致)
 * - **迁移策略**: 鸿蒙首启动即此版本 (86), 无历史迁移; 若 schema 与文件不匹配,
 *   `fallbackToDestructiveMigration` 兜底重建 (鸿蒙端无 Android 端的 autoMigrations 历史数据需保)
 *
 * # 与 iosMain IosDatabaseDriver 区别
 * - **目录创建**: iOS 用 NSFileManager, 鸿蒙用 [kotlin.io.File] (Kotlin/Native linuxArm64 标准库
 *   基于 POSIX fs, 行为与 JVM java.io.File 等价, OhosPreferenceProvider 已用同模式)
 * - **路径分隔符**: 恒为 "/" (POSIX 文件系统, 与 iOS 一致)
 * - **空串退化**: 当 [AppFilesDirs.filesDir] 为空串 ([OhosAppFilesDir] stub 默认状态) 时,
 *   退化为 `{System.getProperty("user.dir")}/legado_data/legado.db` (POSIX getcwd 解析当前工作目录),
 *   让单元测试与早期骨架可运行; 真实接入鸿蒙原生 `@ohos.file.fs` 后 [registerOhosAppFilesDir]
 *   应先注入有效路径, 本退化路径不再生效
 * - **KSP 生成**: 鸿蒙复用 linuxArm64Main 源集, build.gradle 已配置
 *   `kspLinuxArm64(libs.room.compiler)` (enableOhosTarget=true 时启用, 见 shared/build.gradle 末尾)
 *
 * # Room KMP linuxArm64 支持说明
 * Room KSP 早期不支持 linuxArm64 target (Room 官方限制);
 * KP5 sharedUiMain 源集分离完成后该阻塞已解除 (见 build.gradle 注释:
 * "KP5: 鸿蒙 linuxArm64 target 启用阻塞解除 (sharedUiMain 源集分离完成), 恢复 kspLinuxArm64"),
 * 故本文件直接采用真实实现而非 stub。若后续在 DevEco 环境验证时发现 Room KSP 仍有问题,
 * 可临时回退为 stub (各方法抛 UnsupportedOperationException + TODO 标注)。
 *
 * # KP6 sqlite-bundled linuxArm64 变体验证 (任务 9 标注)
 * 任务 9 前提假设 "BundledSQLiteDriver 是 JVM 库, 鸿蒙 linuxArm64 无法直接跑" 经查证**不成立**:
 * - `androidx.sqlite:sqlite-bundled:2.7.0` 是真正的 KMP 跨平台库 (非 JVM 专属),
 *   其 Gradle metadata (`sqlite-bundled-2.7.0.module`) 显式发布 `linuxArm64ApiElements-published`
 *   变体, 子模块 `sqlite-bundled-linuxarm64` 内嵌 `linux_arm64` 原生 SQLite 库。
 * - [BundledSQLiteDriver] 类定义在 commonMain (`androidx.sqlite.driver.bundled`),
 *   鸿蒙 linuxArm64 target 可直接引用, 编译期 KMP 依赖解析通过。
 * - 鸿蒙 OpenHarmony arm64 triple `aarch64-linux-ohos` 与 `linuxArm64` ABI 兼容,
 *   sqlite-bundled 的 linuxArm64 原生库可在鸿蒙 runtime 加载。
 *
 * **结论**: 鸿蒙端无需 napi 调 `@ohos.data.relationalStore` 替代方案, [BundledSQLiteDriver] 直接可用。
 *
 * **运行时验证注意**: 编译期已确认变体可用, 真机/模拟器首次运行需观察 hilog 是否有 sqlite
 * native 库加载错误 (符号缺失 / libc 差异)。万一加载失败, 替代方案为通过 napi 桥接
 * 鸿蒙原生 `@ohos.data.relationalStore` 实现 `androidx.sqlite.SQLiteDriver` 接口替换之,
 * 详见 ohosApp/INTEROP.md 第 10.4 节。
 *
 * # 共享实例
 * [appDatabase] lazy 单例, 同一实例供 [AppDatabaseProviders] + [DatabaseDriverProviders] 共享
 * (避免重复构造, 见 [DesktopAppDatabaseProvider] 模式)。
 *
 * @param dbPath 数据库文件绝对路径, 默认 `{AppFilesDirs.filesDir}/legado.db`
 */
class OhosDatabaseDriver(
    dbPath: String = defaultDbPath()
) : NativeDatabaseDriver {

    private val dbFile: String = dbPath.apply {
        // 父目录不存在则递归创建 (与 jvmMain File(dbPath).apply { parentFile?.mkdirs() } 行为对齐)
        val parentDir = substringBeforeLast('/')
        if (parentDir.isNotEmpty()) {
            val parent = File(parentDir)
            if (!parent.exists()) {
                // mkdirs 失败静默 (与 OhosPreferenceProvider.persist 同模式: 退化路径可能无写权限,
                // 不阻断构造流程, 后续 Room.databaseBuilder.build() 时再报错更易定位)
                runCatching { parent.mkdirs() }
            }
        }
    }

    /**
     * 鸿蒙端 [AppDatabase] 单例 (真实 Room 数据库)。
     *
     * lazy 构造: 首次访问时执行 `Room.databaseBuilder<AppDatabase>` + `BundledSQLiteDriver`。
     * 与 iosMain IosDatabaseDriver.appDatabase 行为完全一致, 仅数据库路径与父目录创建方式不同。
     */
    val appDatabase: AppDatabase by lazy {
        Room.databaseBuilder<AppDatabase>(
            name = dbFile
        )
            .setDriver(BundledSQLiteDriver())
            .setQueryCoroutineContext(Dispatchers.IO)
            // 鸿蒙首启动空库即 version 86, 无 Android 端 83→86 的 autoMigration 历史。
            // 若后续 schema 升级与本地文件不匹配 (如 shared 模块升级后 @Database version 提升),
            // 兜底重建 (dropAllTables=true), 让鸿蒙端自动恢复到可用状态。
            .fallbackToDestructiveMigration(dropAllTables = true)
            .build()
    }

    override val databaseName: String
        get() = DATABASE_NAME

    override val databaseVersion: Int
        // 与 @Database(version = 86) 同步; 鸿蒙首启动空库即此版本, 无迁移历史
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
         * - 鸿蒙应用沙盒 filesDir (持久化, 应用卸载时随之清除)
         * - 与 iOS 端 `Documents/legado.db` / 桌面端 `~/.legado/legado.db` 行为等价 (持久化)
         * - 路径分隔符恒为 "/" (POSIX 文件系统)
         * - 数据库文件名恒为 [DATABASE_NAME] (跨平台同名, 便于库文件互通,
         *   符合 [DatabaseDriverProvider.databaseName] 接口契约)
         *
         * 退化策略: 当 [AppFilesDirs.filesDir] 为空串 ([OhosAppFilesDir] stub 默认状态) 时,
         * 退化为 `{System.getProperty("user.dir")}/legado_data/legado.db`
         * (POSIX getcwd 解析当前工作目录, Kotlin/Native linuxArm64 标准库支持);
         * 真实接入鸿蒙原生 `@ohos.file.fs` 后此分支不再触发。
         */
        fun defaultDbPath(): String {
            val filesDir = AppFilesDirs.get().filesDir
            val baseDir = if (filesDir.isEmpty()) {
                // OhosAppFilesDir stub 默认空串, 退化为当前工作目录下 legado_data/
                // (Kotlin/Native System.getProperty 基于 POSIX, user.dir 在 linuxArm64 上可用)
                val cwd = runCatching { System.getProperty("user.dir") }.getOrNull()
                    ?.takeIf { it.isNotEmpty() } ?: "."
                "$cwd/legado_data"
            } else {
                filesDir
            }
            return if (baseDir.endsWith("/")) "${baseDir}$DATABASE_NAME" else "$baseDir/$DATABASE_NAME"
        }
    }
}
