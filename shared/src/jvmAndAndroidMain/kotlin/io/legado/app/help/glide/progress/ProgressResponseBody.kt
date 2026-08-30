package io.legado.app.help.glide.progress

import io.legado.app.help.coroutine.mainDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import okhttp3.MediaType
import okhttp3.ResponseBody
import okio.Buffer
import okio.BufferedSource
import okio.ForwardingSource
import okio.Source
import okio.buffer
import java.io.IOException

/**
 * 下载进度包装体（从 app 模块下沉至 shared jvmAndAndroidMain）。
 *
 * 原版依赖 `android.os.Handler/Looper` 投主线程; 下沉后改用 shared 的 [mainDispatcher]
 * (Android actual = Dispatchers.Main, JVM actual = Swing/Default), 消费方零改动:
 * app 端 `HttpHelper` 拦截器按 `ProgressManager.getProgressListener(url)` 判空包装,
 * 回调仍经 [ProgressManager.LISTENER] 分发。
 *
 * 构造函数公开 (原版 `internal` 在 app 模块内可见; 跨模块消费方是 app 的 HttpHelper)。
 */
class ProgressResponseBody(
    private val url: String,
    private val internalProgressListener: InternalProgressListener,
    private val responseBody: ResponseBody
) : ResponseBody() {
    private var bufferedSource: BufferedSource? = null
    override fun contentType(): MediaType? {
        return responseBody.contentType()
    }

    override fun contentLength(): Long {
        return responseBody.contentLength()
    }

    override fun source(): BufferedSource {
        if (bufferedSource == null) {
            bufferedSource = source(responseBody.source()).buffer()
        }
        return bufferedSource!!
    }

    private fun source(source: Source): Source {
        return object : ForwardingSource(source) {
            var totalBytesRead: Long = 0
            var lastTotalBytesRead: Long = 0
            var lastPostTime: Long = 0

            @Throws(IOException::class)
            override fun read(sink: Buffer, byteCount: Long): Long {
                val bytesRead = super.read(sink, byteCount)
                totalBytesRead += if (bytesRead == -1L) 0 else bytesRead
                // 时间节流 (原版 f8a97ffd15 是 100ms, 这里放宽到 [PROGRESS_INTERVAL_MS]):
                // 一次 read 一次主线程投递的话一张 2MB 图约 250 次, 下载期把 UI 线程压满
                // (漫画多页并发时更明显); 读完那次 (bytesRead == -1) 不节流, 保证 100% 一定送到
                val currentTime = System.currentTimeMillis()
                if (bytesRead == -1L ||
                    (currentTime - lastPostTime > PROGRESS_INTERVAL_MS &&
                        lastTotalBytesRead != totalBytesRead)
                ) {
                    lastTotalBytesRead = totalBytesRead
                    lastPostTime = currentTime
                    scope.launch {
                        internalProgressListener.onProgress(
                            url, totalBytesRead, contentLength(), bytesRead == -1L
                        )
                    }
                }
                return bytesRead
            }
        }
    }

    companion object {
        /** 进度上报最小间隔 (ms)。 */
        private const val PROGRESS_INTERVAL_MS = 300

        private val scope = CoroutineScope(SupervisorJob() + mainDispatcher)
    }

}
