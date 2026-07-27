package io.legado.app.help.glide.progress

import io.legado.app.model.analyzeRule.AnalyzeUrlCore
import java.util.concurrent.ConcurrentHashMap

/**
 * 进度监听器管理类（jvmAndAndroidMain actual 实现）
 *
 * 实现 commonMain 的 [ImageProgressListener] 接口，由 [ImageProgressProviders]
 * 注入供 commonMain 跨平台调用。底层基于 Glide + OkHttp 拦截器
 * （ProgressResponseBody）实现进度回调。
 *
 * 加入图片加载进度监听，加入 Https 支持。
 */
object ProgressManager : ImageProgressListener {
    private val listenersMap = ConcurrentHashMap<String, OnProgressListener>()
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
                getProgressListener(url)?.let {
                    val percentage = when {
                        isComplete -> 100
                        totalBytes <= 0L -> 0
                        else -> (bytesRead * 1f / totalBytes * 100f).toInt().coerceIn(0, 100)
                    }
                    it.invoke(isComplete, percentage, bytesRead, totalBytes)
                    if (isComplete) {
                        removeListener(url)
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

    override fun addListener(url: String, listener: OnProgressListener) {
        if (url.isNotEmpty()) {
            val key = getUrlNoOption(url)
            listenersMap[key] = listener
            lastBytesReadMap[key] = -1L
        }
    }

    override fun removeListener(url: String) {
        if (url.isNotEmpty()) {
            val key = getUrlNoOption(url)
            listenersMap.remove(key)
            lastBytesReadMap.remove(key)
        }
    }

    override fun getProgressListener(url: String): OnProgressListener? {
        return if (url.isEmpty() || listenersMap.isEmpty()) {
            null
        } else {
            listenersMap[getUrlNoOption(url)]
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
