package io.legado.app.help.image

import coil3.ImageLoader
import coil3.decode.DataSource
import coil3.decode.ImageSource
import coil3.fetch.FetchResult
import coil3.fetch.Fetcher
import coil3.fetch.SourceFetchResult
import coil3.request.Options
import io.legado.app.help.coroutine.IoDispatcher
import io.legado.app.model.manga.MangaModel
import okio.Buffer
import okio.FileSystem

/**
 * Coil3 Fetcher: 把 [MangaModel] 经 [MangaImageBytesLoader] 取字节, 包成 [SourceFetchResult]。
 *
 * 放 nonOhosUiMain: android / desktop / iOS 三端共用 (鸿蒙无 coil3 变体, 走自绘链路直接调
 * [MangaImageBytesLoader])。注册在 `buildBookImageLoader` / `jvmBookImageLoader` /
 * `buildIosBookImageLoader` 的 ComponentRegistry 中。
 */
class MangaModelFetcher(
    private val model: MangaModel,
    // okio 的 FileSystem.SYSTEM 只在各平台源集有声明, common 里取 Coil 的 Options.fileSystem
    private val fileSystem: FileSystem,
) : Fetcher {

    override suspend fun fetch(): FetchResult? {
        val bytes = MangaImageBytesLoader.load(
            model.url,
            model.book,
            model.bookSource,
            IoDispatcher,
        ) ?: return null
        return SourceFetchResult(
            source = ImageSource(Buffer().write(bytes), fileSystem),
            mimeType = null,
            dataSource = DataSource.NETWORK,
        )
    }

    class Factory : Fetcher.Factory<MangaModel> {
        override fun create(
            data: MangaModel,
            options: Options,
            imageLoader: ImageLoader,
        ): Fetcher = MangaModelFetcher(data, options.fileSystem)
    }
}
