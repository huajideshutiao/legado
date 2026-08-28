package io.legado.app.model.script.quickjs

import io.legado.app.App
import io.legado.app.exception.NoStackTraceException
import io.legado.app.help.http.newCallStrResponse
import io.legado.app.help.http.okHttpClient
import io.legado.app.utils.ACache
import kotlinx.coroutines.runBlocking
import java.io.File

/**
 * quickjs 版 SharedJsScope provider (app 端薄适配)。
 *
 * 三层缓存 (bytecodeCache + ThreadLocal LRU + 版本号失效) 与 jsLib 编译/下载/eval
 * 流程下沉在 shared 的 [QuickJsSharedJsScopeBase] (Android 与桌面 JVM 共用),
 * 本类只提供 app 侧差异:
 * - jsLib URL 下载内容用 [ACache] 文件缓存 (cacheDir/shareJs, 进程重启仍有效)
 * - 下载走 app 端 [okHttpClient] 单例
 */
object QuickJsSharedJsScopeProvider : QuickJsSharedJsScopeBase() {

    private val cacheFolder = File(App.instance.cacheDir, "shareJs")
    private val aCache = ACache.get(cacheFolder)

    // 省略可见性修饰的 override 自动继承基类的 protected 可见性
    override fun cachedJsLibContent(name: String): String? = aCache.getAsString(name)

    override fun storeJsLibContent(name: String, content: String) {
        aCache.put(name, content)
    }

    override fun downloadJsLibContent(url: String): String? = runBlocking {
        okHttpClient.newCallStrResponse { url(url) }.body
    }

    override fun jsLibDownloadFailedException(url: String): Exception =
        NoStackTraceException("下载jsLib-${url}失败")
}
