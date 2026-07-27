package io.legado.app.help.glide.progress

/**
 * ImageProgressProviders 的 iOS/鸿蒙 actual 实现。
 *
 * 详见 commonMain/help/glide/progress/ImageProgressProviders.kt expect 注释。
 *
 * iOS/鸿蒙端不用 Glide (Android-only), progress 上报改用 NoOpImageProgressListener (no-op):
 * - addListener/removeListener/getProgressListener 均 no-op, 不维护监听器表
 * - internalListener 是 no-op, 永不触发 onProgress 回调
 *
 * 功能受限但不崩: iOS/鸿蒙端图片加载进度上报功能缺失, 但图片加载本身不依赖此 progress provider。
 */
actual object ImageProgressProviders {

    actual val progressListener: ImageProgressListener = NoOpImageProgressListener
}

private object NoOpImageProgressListener : ImageProgressListener {

    override val internalListener: InternalProgressListener
        get() = NoOpInternalListener

    override fun addListener(url: String, listener: OnProgressListener) {
        // no-op: iOS/鸿蒙不支持图片加载进度
    }

    override fun removeListener(url: String) {
        // no-op: iOS/鸿蒙不支持图片加载进度
    }

    override fun getProgressListener(url: String): OnProgressListener? = null

    override fun getUrlNoOption(url: String): String = url
}

private object NoOpInternalListener : InternalProgressListener {
    override fun onProgress(url: String, bytesRead: Long, totalBytes: Long, isComplete: Boolean) {
        // no-op: iOS/鸿蒙不支持图片加载进度
    }
}
