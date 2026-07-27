package io.legado.app.help.file

import java.nio.file.Files
import java.nio.file.Paths

/**
 * [AppFilesDir] 的桌面 JVM actual 实现。
 *
 * [filesDir] = `{desktopAppRootDir}/files` (便携=exe 同级 data/, 安装=系统数据目录);
 * [cacheDir] = [desktopAppCacheDir] (系统临时目录 {java.io.tmpdir}/legado/cache)。
 *
 * # 设计要点
 * - [filesDir] / [cacheDir] 在构造时创建 (避免后续读写时目录不存在)
 * - [externalFilesDir] / [externalCacheDir] 返回 null: 桌面端无"外部存储"概念
 *
 * 模式参考 `registerAndroidMediaNotificationProvider`。
 */
class DesktopAppFilesDir : AppFilesDir {

    /** 应用数据根目录 (便携/系统目录解析见 desktopAppRootDir)。 */
    private val rootDir: String = desktopAppRootDir()

    override val filesDir: String = run {
        val path = Paths.get(rootDir, "files")
        // 确保目录存在 (与 Android filesDir 已存在的语义对齐)
        Files.createDirectories(path)
        path.toString()
    }

    /** 缓存走系统临时目录 (desktopAppCacheDir 构造时已 mkdirs)。 */
    override val cacheDir: String = desktopAppCacheDir()

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
