package io.legado.app.help.glide.progress

/**
 * 进度回调内部接口
 *
 * 从 ProgressResponseBody.InternalProgressListener 抽取为 top-level 接口，
 * 便于 ProgressManager 下沉到 shared (jvmAndAndroidMain)。
 * ProgressResponseBody 中的 internalProgressListener 字段引用本接口。
 */
interface InternalProgressListener {
    fun onProgress(url: String, bytesRead: Long, totalBytes: Long, isComplete: Boolean)
}
