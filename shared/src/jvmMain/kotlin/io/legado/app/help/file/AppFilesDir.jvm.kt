package io.legado.app.help.file

import java.nio.file.Files
import java.nio.file.Paths

/**
 * [AppFilesDir] 的桌面 JVM actual 实现。
 *
 * 用 `~/.legado/files` / `~/.legado/cache` 作为应用文件目录, 对齐桌面应用惯例。
 *
 * # 设计要点
 * - [filesDir] / [cacheDir] 在构造时创建 (避免后续读写时目录不存在)
 * - [externalFilesDir] / [externalCacheDir] 返回 null: 桌面端无"外部存储"概念
 * - 用户目录走 `System.getProperty("user.home")` (JVM 跨平台标准方式)
 *
 * 模式参考 `registerAndroidMediaNotificationProvider`。
 */
class DesktopAppFilesDir : AppFilesDir {

    /** 应用根目录: 便携模式跟随 exe (legado.portable.root), 开发模式 ~/.legado。 */
    private val rootDir: String = desktopAppRootDir()

    override val filesDir: String = run {
        val path = Paths.get(rootDir, "files")
        // 确保目录存在 (与 Android filesDir 已存在的语义对齐)
        Files.createDirectories(path)
        path.toString()
    }

    override val cacheDir: String = run {
        val path = Paths.get(rootDir, "cache")
        Files.createDirectories(path)
        path.toString()
    }

    /** 桌面端无外部存储概念, 返回 null。 */
    override val externalFilesDir: String? = null

    /** 桌面端无外部缓存概念, 返回 null (调用方回退到 [cacheDir])。 */
    override val externalCacheDir: String? = null
}

/**
 * 桌面宿主启动早期注册 [AppFilesDir] 的 actual 实现。
 *
 * 调用时机: desktop main(), 在任何 commonMain 代码调用 `AppFilesDirs.get()` 之前。
 *
 * 模式参考 `registerAndroidMediaNotificationProvider`。
 */
fun registerDesktopAppFilesDir() {
    AppFilesDirs.register(DesktopAppFilesDir())
}
