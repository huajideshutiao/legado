// Copyright The Keiyoushi Contributors. Apache-2.0.
package eu.kanade.tachiyomi.source.model

import kotlinx.serialization.json.JsonObject

@Suppress("UNUSED", "PropertyName")
interface SChapter {

    var url: String

    var name: String

    var chapter_number: Float

    var scanlator: String?

    var date_upload: Long

    // 1.6 新增: 源自定义元数据, 宿主与扩展按需读写
    var memo: JsonObject

    companion object {
        fun create(): SChapter = SChapterImpl()
    }
}
