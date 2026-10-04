// Copyright The Keiyoushi Contributors. Apache-2.0.
package eu.kanade.tachiyomi.source

import eu.kanade.tachiyomi.source.model.SManga

interface CatalogueSource : Source {

    // ISO 639-1 双字母小写语言码
    val lang: String

    // 以下三项为 Komikku 侧扩展 API, 按契约原样保留
    val supportsRelatedMangas: Boolean get() = false

    val disableRelatedMangasBySearch: Boolean get() = false

    val disableRelatedMangas: Boolean get() = false

    suspend fun fetchRelatedMangaList(manga: SManga): List<SManga> = throw UnsupportedOperationException("Unsupported!")
}
