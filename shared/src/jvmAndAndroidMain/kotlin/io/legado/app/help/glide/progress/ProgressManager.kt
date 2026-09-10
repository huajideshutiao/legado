package io.legado.app.help.glide.progress

import io.legado.app.help.glide.progress.ProgressManager.LISTENER
import io.legado.app.model.analyzeRule.AnalyzeUrlCore
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList

/**
 * 进度监听器管理类（jvmAndAndroidMain 实现，非 expect/actual——[ImageProgressListener]
 * 是 commonMain 的普通接口）。
 *
 * 实现 commonMain 的 [ImageProgressListener] 接口。底层基于 OkHttp 拦截器
 * （ProgressResponseBody）实现进度回调（原 Glide 体系迁移后 Glide 已移除）。
 *
 * 加入图片加载进度监听，加入 Https 支持。
 */
object ProgressManager : ImageProgressListener {
    private val listenersMap = ConcurrentHashMap<String, CopyOnWriteArrayList<OnProgressListener>>()
    private val lastBytesReadMap = ConcurrentHashMap<String, Long>()

    val LISTENER = object : InternalProgressListener {
        override fun onProgress(
            url: String,
            bytesRead: Long,
            totalBytes: Long,
            isComplete: Boolean
        ) {
            val key = getUrlNoOption(url)
            val lastBytesRead = lastBytesReadMap[key] ?: -1L
            if (isComplete || bytesRead > lastBytesRead) {
                if (isComplete) {
                    lastBytesReadMap.remove(key)
                } else {
                    lastBytesReadMap[key] = bytesRead
                }
                val listeners = listenersMap[key]
                if (!listeners.isNullOrEmpty()) {
                    val percentage = when {
                        isComplete -> 100
                        totalBytes <= 0L -> 0
                        else -> (bytesRead * 1f / totalBytes * 100f).toInt().coerceIn(0, 100)
                    }
                    for (listener in listeners) {
                        listener.invoke(isComplete, percentage, bytesRead, totalBytes)
                    }
                }
            }
        }
    }

    /**
     * 实现 [ImageProgressListener.internalListener]：复用 [LISTENER] 实例。
     * 保留 [LISTENER] 供 HttpHelper.kt 通过 `import ... ProgressManager.LISTENER` 引用。
     */
    override val internalListener: InternalProgressListener
        get() = LISTENER

    override fun addListener(url: String, listener: OnProgressListener): () -> Unit {
        if (url.isEmpty()) return {}
        val key = getUrlNoOption(url)
        listenersMap.compute(key) { _, existing ->
            val list = existing ?: CopyOnWriteArrayList()
            list.addIfAbsent(listener)
            list
        }
        lastBytesReadMap[key] = -1L
        return { removeListener(url, listener) }
    }

    override fun removeListener(url: String) {
        if (url.isNotEmpty()) {
            val key = getUrlNoOption(url)
            listenersMap.remove(key)
            lastBytesReadMap.remove(key)
        }
    }

    override fun removeListener(url: String, listener: OnProgressListener) {
        if (url.isNotEmpty()) {
            val key = getUrlNoOption(url)
            listenersMap.computeIfPresent(key) { _, list ->
                list.remove(listener)
                if (list.isEmpty()) null else list
            }
            if (!listenersMap.containsKey(key)) {
                lastBytesReadMap.remove(key)
            }
        }
    }

    override fun getProgressListener(url: String): OnProgressListener? {
        return if (url.isEmpty() || listenersMap.isEmpty()) {
            null
        } else {
            listenersMap[getUrlNoOption(url)]?.firstOrNull()
        }
    }

    override fun getUrlNoOption(url: String): String {
        // Pattern.matcher → Regex.find: match.range.first 对应 matcher.start()
        val urlMatch = AnalyzeUrlCore.paramPattern.find(url)
        return if (urlMatch != null) {
            url.take(urlMatch.range.first)
        } else {
            url
        }
    }

}
