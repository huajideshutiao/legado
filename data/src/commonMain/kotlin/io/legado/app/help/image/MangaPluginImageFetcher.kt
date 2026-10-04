package io.legado.app.help.image

/**
 * 插件源图片字节获取跨平台抽象（[io.legado.app.help.image.MangaImageBytesLoader]
 * 的插件源分支使用）。
 *
 * 实现经 [MangaPluginImageFetcherProviders.register] 注入: Android 端委托
 * 扩展自身 client 的 HttpSource.getImage（扩展 OkHttp 拦截器 — 禁漫天堂图片
 * 分割重排等 — 在官方通道内生效）; 其余端插件子系统未注册（无实现）→
 * getOrNull 返回 null, loader 报"插件源图片仅 Android 端支持"。
 *
 * 模式参考 [io.legado.app.help.book.BookImageStorageProviders]。
 */
interface MangaPluginImageFetcher {

    /**
     * 经扩展自身 client 下载插件源图片, 返回还原后的原始字节; 失败返回 null。
     *
     * @param sourceId 插件源唯一 Long（[PluginImageUrl.parse] 解析出的 sourceId 段）
     * @param encodedImageUrl [PluginImageUrl] 的编码 URL 段（实现内 URLDecoder 解码）
     */
    suspend fun fetchImage(sourceId: Long, encodedImageUrl: String): ByteArray?
}

object MangaPluginImageFetcherProviders {
    private var impl: MangaPluginImageFetcher? = null

    fun register(fetcher: MangaPluginImageFetcher) {
        impl = fetcher
    }

    fun getOrNull(): MangaPluginImageFetcher? = impl
}
