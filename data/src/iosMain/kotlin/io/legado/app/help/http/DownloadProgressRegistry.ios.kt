package io.legado.app.help.http

import kotlinx.atomicfu.locks.SynchronizedObject
import kotlinx.atomicfu.locks.synchronized

/**
 * 字节级下载进度注册表 (iOS, 对照 jvmAndAndroid 的 [io.legado.app.help.glide.progress.ProgressManager]):
 * Ktor [io.ktor.client.plugins.onDownload] 回调按 url 分发到监听者 (漫画页转圈环心等)。
 *
 * 与 ProgressManager 的差异: 不缓存"已读字节"状态 (每次下载由 Kotlin 请求侧直接上报;
 * 重试重发会重新从 0 上报, 与 desktop 端 okhttp 拦截器行为一致)。
 */
object DownloadProgressRegistry {

    private val lock = SynchronizedObject()
    private val listeners = mutableMapOf<String, MutableList<(Long, Long?) -> Unit>>()

    /** 注册按 url 的进度监听 (url 与 [KmpRequest.url] 一致), 返回注销函数。 */
    fun addListener(url: String, listener: (bytesRead: Long, total: Long?) -> Unit): () -> Unit {
        synchronized(lock) {
            listeners.getOrPut(url) { mutableListOf() }.add(listener)
        }
        return { removeListener(url, listener) }
    }

    fun removeListener(url: String, listener: (Long, Long?) -> Unit) {
        synchronized(lock) {
            listeners[url]?.remove(listener)
            if (listeners[url].isNullOrEmpty()) listeners.remove(url)
        }
    }

    /** Ktor onDownload 上报 (任意线程; 监听者自行切线程, 与 ProgressManager 同为 IO 线程回调)。 */
    fun notify(url: String, bytesRead: Long, total: Long?) {
        val snapshot = synchronized(lock) { listeners[url]?.toList() } ?: return
        snapshot.forEach { it(bytesRead, total) }
    }
}
