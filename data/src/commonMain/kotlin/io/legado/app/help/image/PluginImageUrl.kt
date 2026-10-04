package io.legado.app.help.image

/**
 * 插件源图片内部协议形态（正文 `<img src>` 与图片缓存 key 共用）。
 *
 * 形态: `tachiyomi-img://<sourceId>/<urlencode(原始图片URL)>`
 * - sourceId: 插件源唯一 Long（与虚拟书源 URL `tachiyomi://<sourceId>` 同源）;
 * - 原始 URL 经 URLEncoder 编码后置于 path 段, 与 Book.bookUrl 的
 *   `tachiyomi://<sourceId>/<urlencode(manga.url)>` 形态对齐;
 * - 编码仅在 JVM 侧（MangaSourceMapper）生成, commonMain 只做纯字符串解析,
 *   解码由 Android 端 [MangaPluginImageFetcher] 实现完成。
 *
 * 语义: 插件源图片不再由 legado 下载器直拉原始字节（那样会绕开扩展自带
 * OkHttp 拦截器 — 禁漫图片分割重排等加工全部失效), 而是经
 * [MangaPluginImageFetcher] 委托扩展自身 client 的 HttpSource.getImage 下载,
 * 拦截器在官方通道内生效。
 */
object PluginImageUrl {

    /** 内部协议前缀（单一事实来源, 引用方禁止裸字面量）。 */
    const val PREFIX = "tachiyomi-img://"

    /** commonMain 侧解析: 命中返回 (sourceId, 编码URL段), 未命中返回 null。 */
    fun parse(raw: String): Pair<Long, String>? {
        if (!raw.startsWith(PREFIX)) return null
        val rest = raw.removePrefix(PREFIX)
        val slash = rest.indexOf('/')
        if (slash <= 0) return null
        val sourceId = rest.substring(0, slash).toLongOrNull() ?: return null
        val encoded = rest.substring(slash + 1)
        if (encoded.isEmpty()) return null
        return sourceId to encoded
    }
}
