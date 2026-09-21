package io.legado.app.help.glide.progress

/**
 * 跨平台图片加载进度监听（管理）接口。
 *
 * 抽象自 jvmAndAndroidMain 的 `ProgressManager`，使 commonMain 代码可在
 * 不直接依赖 Glide / OkHttp 等 Android 平台库的前提下调用图片加载进度能力。
 *
 * - jvmAndAndroidMain 端实现：[ProgressManager]（OkHttp 拦截器 ProgressResponseBody）
 * - iOS / HarmonyOS 端用各自的下载进度注册表 (如 DownloadProgressRegistry)，不经本接口
 *
 * 设计要点：
 * - [internalListener] 由底层 HTTP 拦截器（如 ProgressResponseBody）回调，
 *   actual 实现负责将进度分发到已注册的 [OnProgressListener]。
 * - [addListener] / [removeListener] / [getProgressListener] 维护以
 *   [getUrlNoOption] 处理后的 url 为 key 的监听器表。
 */
interface ImageProgressListener {

    /**
     * 内部进度回调入口。HTTP 层（OkHttp 拦截器 / 各平台网络栈）每读到一段
     * 响应体就调用此接口，由 actual 实现转发到对应 url 注册的 [OnProgressListener]。
     */
    val internalListener: InternalProgressListener

    /** 注册进度监听器；url 为空则忽略。返回注销函数句柄。 */
    fun addListener(url: String, listener: OnProgressListener): () -> Unit

    /** 注销指定 url 的所有进度监听器；url 为空则忽略。未注册时调用为 no-op。 */
    fun removeListener(url: String)

    /** 注销指定 url 的特定进度监听器；url 为空或 listener 未注册时为 no-op。 */
    fun removeListener(url: String, listener: OnProgressListener) {
        removeListener(url)
    }

    /** 获取指定 url 的监听器（多监听器时返回首个）；未注册或 url 为空返回 null。 */
    fun getProgressListener(url: String): OnProgressListener?

    /**
     * 去掉 url 中的书源参数部分（如 `{,{"method":"GET"}}`），
     * 作为监听器表的 key，避免带参 url 注册与查询时无法对齐。
     */
    fun getUrlNoOption(url: String): String
}
