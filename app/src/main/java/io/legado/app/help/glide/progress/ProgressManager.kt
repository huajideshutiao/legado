package io.legado.app.help.glide.progress

import io.legado.app.model.analyzeRule.AnalyzeUrl
import java.util.concurrent.ConcurrentHashMap

/**
 * 进度监听器管理类
 * 加入图片加载进度监听，加入Https支持
 */
object ProgressManager {
    private val listenersMap = ConcurrentHashMap<String, OnProgressListener>()

    val LISTENER = object : ProgressResponseBody.InternalProgressListener {
        override fun onProgress(
            url: String,
            bytesRead: Long,
            totalBytes: Long,
            isComplete: Boolean
        ) {
            getProgressListener(url)?.let {
                val percentage = when {
                    isComplete -> 100
                    totalBytes == -1L -> 0
                    else -> (bytesRead * 1f / totalBytes * 100f).toInt().coerceIn(0, 100)
                }
                it.invoke(isComplete, percentage, bytesRead, totalBytes)
                if (isComplete) {
                    removeListener(url)
                }
            }
        }
    }

    fun addListener(url: String, listener: OnProgressListener) {
        if (url.isNotEmpty()) {
            val url = getUrlNoOption(url)
            listenersMap[url] = listener
            listener.invoke(false, 0, 0, 0)
        }
    }

    fun removeListener(url: String) {
        if (url.isNotEmpty()) {
            val url = getUrlNoOption(url)
            listenersMap.remove(url)
        }
    }

    fun getProgressListener(url: String): OnProgressListener? {
        return if (url.isEmpty() || listenersMap.isEmpty()) {
            null
        } else {
            listenersMap[url]
        }
    }

    private fun getUrlNoOption(url: String): String {
        val urlMatcher = AnalyzeUrl.paramPattern.matcher(url)
        return if (urlMatcher.find()) {
            url.take(urlMatcher.start())
        } else {
            url
        }
    }

}