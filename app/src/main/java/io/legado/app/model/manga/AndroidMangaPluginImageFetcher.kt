package io.legado.app.model.manga

import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.online.HttpSource
import io.legado.app.help.extension.MangaExtensionManager
import io.legado.app.help.image.MangaPluginImageFetcher
import java.net.URLDecoder

/**
 * Android 端插件源图片获取: 委托扩展自身 client 的 HttpSource.getImage 下载。
 *
 * 扩展在 client 上挂的 OkHttp 拦截器（禁漫天堂图片分割重排等）在此通道内生效,
 * 返回字节即还原后图片。解码 [io.legado.app.help.image.PluginImageUrl] 编码段后
 * 构造 Page 喂给扩展的 imageRequest（扩展常在 imageRequest 覆写中追加防盗链头,
 * 一并生效）。
 */
class AndroidMangaPluginImageFetcher : MangaPluginImageFetcher {

    override suspend fun fetchImage(sourceId: Long, encodedImageUrl: String): ByteArray? {
        val source = MangaExtensionManager.getSource(sourceId) as? HttpSource ?: return null
        val imageUrl = runCatching { URLDecoder.decode(encodedImageUrl, "UTF-8") }
            .getOrNull() ?: return null
        val page = Page(0, url = imageUrl, imageUrl = imageUrl)
        val response = runCatching { source.getImage(page) }.getOrNull() ?: return null
        return try {
            response.body?.byteStream()?.readBytes()
        } finally {
            response.close()
        }
    }
}
