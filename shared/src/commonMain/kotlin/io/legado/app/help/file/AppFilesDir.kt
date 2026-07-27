package io.legado.app.help.file

import kotlin.concurrent.Volatile

/**
 * 应用文件目录抽象 (shared commonMain)。
 *
 * # 背景
 * 安卓端 `Context.filesDir` / `cacheDir` / `getExternalFilesDir(null)` 依赖 Android
 * Context。桌面 JVM 端用 `~/.legado/files` / `~/.legado/cache`; iOS 用沙盒 Documents/Caches,
 * 鸿蒙经 napi 桥接取 `context.filesDir`/`cacheDir`。
 *
 * # 设计
 * - 所有路径用 String 而非 File, 避免 commonMain 引入 JDK 类型
 * - [externalFilesDir] / [externalCacheDir] 可空: 桌面端无外部存储概念, 返回 null
 * - 路径在注册时确定, 后续不变 (与 app 端 appCtx 行为一致)
 *
 * 模式参考 [io.legado.app.help.config.AppConfigProviders]。
 */
interface AppFilesDir {

    /** 应用内部文件目录 (对应 Android `Context.filesDir`)。 */
    val filesDir: String

    /** 应用内部缓存目录 (对应 Android `Context.cacheDir`)。 */
    val cacheDir: String

    /** 应用外部文件目录 (对应 Android `Context.getExternalFilesDir(null)`), 无则 null。 */
    val externalFilesDir: String?

    /**
     * 应用外部缓存目录 (对应 Android `Context.externalCacheDir`), 无则 null。
     *
     * 桌面端无外部缓存概念, 返回 null; 调用方需回退到 [cacheDir]
     * (参考 [io.legado.app.help.storage.BackupShared] 的 externalFilesDir ?: filesDir 模式)。
     */
    val externalCacheDir: String?
}

/**
 * [AppFilesDir] 容器 (provider 注入模式)。
 *
 * 宿主启动早期注册一次 (App.onCreate / desktop main), shared 内通过 [get] 获取。
 * 未注册时调用 [get] 抛 [IllegalStateException]。
 *
 * 安卓端调用 `registerAndroidAppFilesDir(context)` (见 androidMain),
 * 桌面端调用 `registerDesktopAppFilesDir()` (见 jvmMain)。
 */
object AppFilesDirs {

    @Volatile
    private var impl: AppFilesDir? = null

    /** 宿主启动早期注册一次 (任何目录访问之前)。 */
    fun register(impl: AppFilesDir) {
        this.impl = impl
    }

    /** 获取已注册实现, 未注册抛出 IllegalStateException。 */
    fun get(): AppFilesDir =
        impl ?: error("AppFilesDirs not registered; call registerAndroidAppFilesDir() or registerDesktopAppFilesDir() first")

    /** 仅测试场景: 清空注册 (生产代码勿调用)。 */
    fun reset() {
        impl = null
    }
}
