package io.legado.app.model

/**
 * 图片加载失败兜底图字节提供者 (Android 端 ImageProvider.errorBitmap 用)。
 *
 * 原实现读 :data 自持的 res 副本, 与 :ui composeResources 的 image_loading_error.png
 * 重复; 切分后统一由 :ui 资源单点持有, 宿主启动早期注册 (同 BookController 的
 * defaultCoverBytes 模式)。未注册时 [getOrNull] 返回 null, 调用方显式报错。
 */
object ImageErrorBytesProviders {
    @Volatile
    private var impl: (() -> ByteArray)? = null

    /** 宿主启动早期注册一次 (任何 ImageProvider.errorBitmap 访问之前)。 */
    fun register(impl: () -> ByteArray) {
        this.impl = impl
    }

    /** 获取已注册实现, 未注册返回 null。 */
    fun getOrNull(): (() -> ByteArray)? = impl
}
