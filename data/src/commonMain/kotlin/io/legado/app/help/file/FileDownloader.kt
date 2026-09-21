package io.legado.app.help.file

import kotlin.concurrent.Volatile

/**
 * 文件下载抽象 (shared commonMain)。
 *
 * # 背景
 * 安卓端 `DownloadService` 走系统 `android.app.DownloadManager`, 依赖 Android Context。
 * 桌面 JVM 端用 OkHttp 同步下载到 `java.nio.file.Path`; iOS/鸿蒙共用 nativeMain 实现
 * (Ktor CIO + `kotlin.io.File`)。
 *
 * # 设计
 * - suspend 函数: 桌面端协程内同步下载, 安卓端可走 DownloadManager 异步队列
 *   (实现可阻塞等待完成或立即返回 true 表示已入队)
 * - 返回 Boolean: true 表示下载成功 (或已成功入队), false 表示失败
 * - [destPath] 用 String 而非 File, 避免 commonMain 引入 JDK 类型
 *
 * 模式参考 [io.legado.app.help.config.AppConfigProviders]。
 */
interface FileDownloader {

    /**
     * 下载 [url] 到 [destPath]/[fileName]。
     *
     * @param url 下载地址
     * @param destPath 目标目录路径 (不带文件名)
     * @param fileName 目标文件名
     * @return true 表示下载成功 (或已成功入队), false 表示失败
     */
    suspend fun download(url: String, destPath: String, fileName: String): Boolean
}

/**
 * [FileDownloader] 容器 (provider 注入模式)。
 *
 * 宿主启动早期注册一次 (App.onCreate / desktop main), shared 内通过 [get] 获取。
 * 未注册时调用 [get] 抛 [IllegalStateException]。
 *
 * 安卓端调用 `registerAndroidFileDownloader(context)` (见 androidMain),
 * 桌面端调用 `registerDesktopFileDownloader()` (见 jvmMain)。
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
        impl ?: error("FileDownloaders not registered; call registerAndroidFileDownloader() or registerDesktopFileDownloader() first")

    /** 仅测试场景: 清空注册 (生产代码勿调用)。 */
    fun reset() {
        impl = null
    }
}
