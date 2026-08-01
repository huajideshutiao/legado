package io.legado.app.data

import androidx.room3.Room
import androidx.room3.RoomDatabase
import androidx.sqlite.driver.NativeSQLiteDriver
import io.legado.app.data.AppDatabase.Companion.DATABASE_NAME
import io.legado.app.help.file.AppFilesDirs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import io.legado.app.utils.File

/**
 * 鸿蒙 (OHOS) 端 [DatabaseDriverProvider] 实现: **Room KMP + NativeSQLiteDriver 真实数据库**。
 *
 * KP5: 鸿蒙端 DatabaseDriver 落地, 与 [IosDatabaseDriver] / jvmMain [BundledDatabaseDriver] 行为对齐。
 * 鸿蒙 KMP 使用 CPF 的 ohosArm64 目标，并通过 sqlite-framework 的 OHOS 变体接入系统 SQLite。
 *
 * # 实现要点 (与 jvmMain BundledDatabaseDriver / iosMain IosDatabaseDriver 行为对齐)
 * - **驱动**: [NativeSQLiteDriver] (CPF `androidx.sqlite:sqlite-framework` 的 OHOS 变体)
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
 * CPF 的 `sqlite-framework` OHOS 变体通过 CInterop 绑定鸿蒙侧 SQLite，避免把 Linux 原生
 * sqlite-bundled 库误认为可以直接加载到 OHOS 进程。最终仍需 HAP/真机运行时验证数据库打开、迁移和关闭。
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
     * lazy 构造: 首次访问时执行 `Room.databaseBuilder<AppDatabase>` + `NativeSQLiteDriver`。
     * 与 iosMain IosDatabaseDriver.appDatabase 行为完全一致, 仅数据库路径与父目录创建方式不同。
     */
    override val appDatabase: AppDatabase by lazy {
        Room.databaseBuilder<AppDatabase>(
            name = dbFile
        )
            .setDriver(NativeSQLiteDriver())
            .setQueryCoroutineContext(Dispatchers.IO)
            // 鸿蒙首启动空库即 version 86, 无 Android 端 83→86 的 autoMigration 历史。
            // 若后续 schema 升级与本地文件不匹配 (如 shared 模块升级后 @Database version 提升),
            // 兜底重建 (dropAllTables=true), 让鸿蒙端自动恢复到可用状态。
            .fallbackToDestructiveMigration(dropAllTables = true)
            // 预置分组 + 键盘助手 (对照 app 端 dbCallback)
            .addCallback(AppDatabaseDefaults)
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
         * 退化为当前工作目录下的 `legado_data/legado.db`；真实宿主应在启动时注册有效的
         * 沙盒 `filesDir`，因此该分支只用于早期骨架和编译验证。
         * 真实接入鸿蒙原生 `@ohos.file.fs` 后此分支不再触发。
         */
        fun defaultDbPath(): String {
            val filesDir = AppFilesDirs.get().filesDir
            val baseDir = if (filesDir.isEmpty()) {
                // OhosAppFilesDir stub 默认空串；OHOS CPF 不提供 JVM 的 System.getProperty。
                "./legado_data"
            } else {
                filesDir
            }
            return if (baseDir.endsWith("/")) "${baseDir}$DATABASE_NAME" else "$baseDir/$DATABASE_NAME"
        }
    }
}
