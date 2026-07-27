package io.legado.app.help.glide

import io.legado.app.data.entities.Book
import io.legado.app.data.entities.BookSource

/**
 * 漫画页加载模型: url + book + bookSource, 供 [MangaModelFetcher] 经 BookHelp/AnalyzeUrl 取字节。
 * (原 Glide MangaModelLoader 已移除, MangaModel data class 保留供 Coil3 Fetcher 用。)
 */
data class MangaModel(
    val url: String,
    val book: Book,
    val bookSource: BookSource?
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is MangaModel) return false
        return url == other.url
    }

    override fun hashCode(): Int {
        return url.hashCode()
    }
}
