package io.legado.app.help.glide

import coil3.ImageLoader
import coil3.decode.DataSource
import coil3.decode.ImageSource
import coil3.fetch.FetchResult
import coil3.fetch.Fetcher
import coil3.fetch.SourceFetchResult
import coil3.request.Options
import okio.Buffer
import okio.FileSystem

/**
 * Coil3 Fetcher: 把 [MangaModel] 经 app 端 ImageLoader.loadManga 取字节, 包成 [SourceFetchResult]。
 * 替代 Glide 的 MangaModelLoader。注册到 app 端 ImageLoader.components(见 App.kt SingletonImageLoader)。
 */
class MangaModelFetcher(
    private val model: MangaModel,
) : Fetcher {

    override suspend fun fetch(): FetchResult? {
        val bytes = io.legado.app.help.glide.ImageLoader.loadManga(
            model.url,
            model.book,
            model.bookSource,
            kotlinx.coroutines.Dispatchers.IO,
        ) ?: return null
        val source = ImageSource(Buffer().write(bytes), FileSystem.SYSTEM)
        return SourceFetchResult(
            source = source,
            mimeType = null,
            dataSource = DataSource.NETWORK,
        )
    }

    class Factory : Fetcher.Factory<MangaModel> {
        override fun create(
            data: MangaModel,
            options: Options,
            imageLoader: ImageLoader,
        ): Fetcher = MangaModelFetcher(data)
    }
}
