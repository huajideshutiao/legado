package io.legado.app.help.file

import kotlin.concurrent.Volatile

/**
 * 文件下载抽象 (shared commonMain)。
 *
 * # 背景
 * 安卓端 `DownloadService` 走系统 `android.app.DownloadManager`, 依赖 Android Context,
 * 不注册本契约 (下载落盘由系统下载器管理)。
 * 桌面 JVM 端用 OkHttp 同步下载到 `java.nio.file.Path`; iOS/鸿蒙共用 nativeMain 实现
 * (Ktor Client 按目标选择引擎 + [io.legado.app.utils.File] 的 okio 文件面)。
 *
 * # 设计
 * - suspend 函数: 各端在协程内完成下载
 * - 返回 Boolean: true 表示下载成功, false 表示失败
 * - [destPath] 用 String 而非 File, 避免 commonMain 引入 JDK 类型
 * - 落盘统一走共享整份写入契约 ([io.legado.app.help.writeBytesAtomically] /
 *   [io.legado.app.help.copyToFileAtomically]): 目标同目录的唯一临时件 + 平台原子改名就位,
 *   目标名上始终是完整内容; 临时件按共享契约命名, 目录清点方可按名排除与回收
 *
 * 模式参考 [io.legado.app.help.config.AppConfigProviders]。
 */
interface FileDownloader {

    /**
     * 下载 [url] 到 [destPath]/[fileName]。
     *
     * 目标名上始终是完整内容 (写入期间不会出现半截下载); 同名目标已存在时覆盖, 覆盖即为
     * 上次失败的后续自愈路径。
     *
     * @param url 下载地址
     * @param destPath 目标目录路径 (不带文件名)
     * @param fileName 目标文件名
     * @return true 表示下载成功, false 表示失败
     */
    suspend fun download(url: String, destPath: String, fileName: String): Boolean
}

/**
 * [FileDownloader] 容器 (provider 注入模式)。
 *
 * 宿主启动早期注册一次 (desktop main / iOS・鸿蒙宿主入口), shared 内通过 [get] 获取。
 * 未注册时调用 [get] 抛 [IllegalStateException]。
 *
 * 安卓端不注册 (下载走系统 `DownloadManager`, 见 app 端 `DownloadService`),
 * 桌面端调用 `registerDesktopFileDownloader()` (见 jvmMain),
 * iOS/鸿蒙调用 `registerNativeFileDownloader()` (见 nativeMain)。
 */
object FileDownloaders {

    @Volatile
    private var impl: FileDownloader? = null

    /** 宿主启动早期注册一次 (任何下载调用之前)。 */
    fun register(impl: FileDownloader) {
        this.impl = impl
    }

    /** 获取已注册实现, 未注册抛出 IllegalStateException。 */
    fun get(): FileDownloader =
        impl ?: error("FileDownloaders not registered; call registerDesktopFileDownloader() or registerNativeFileDownloader() first")
}
