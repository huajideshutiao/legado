package io.legado.app.model

import io.legado.app.help.service.ServiceLaunchers

/**
 * 下载入口 (KMP shared/commonMain 版本)。
 *
 * 原 app 端 `io.legado.app.model.Download` object 仅有一个 `start(context, url, fileName)`
 * 方法, 内部走 `context.startService<DownloadService>`。下沉后:
 * - commonMain 入口去掉 `Context` 参数 (多端无 Context 概念), 委托 [ServiceLaunchers]
 * - Android 端 [ServiceLaunchers] actual 实现仍走系统 `DownloadManager` + `DownloadService`
 *   (见 `shared/src/androidMain/.../ServiceLauncher.android.kt`)
 * - 桌面端 [ServiceLaunchers] actual 实现用 [io.legado.app.help.file.FileDownloader] 写文件
 *
 * app 端原 Download object 已删除, 2 个调用点 (WebViewUtil / UpdateDialog) 改为
 * 调用 `Download.start(url, fileName)` (去掉 context 参数), 由 commonMain 本 object 提供。
 */
object Download {

    /**
     * 提交下载任务。
     *
     * @param url 下载地址
     * @param fileName 保存文件名 (含扩展名, 用于系统下载通知标题 + MIME 推断 + 目标路径)
     */
    fun start(url: String, fileName: String) {
        ServiceLaunchers.get().startDownloadService(url, fileName)
    }
}
