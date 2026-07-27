@file:JvmName("IntentDataAndroid")

package io.legado.app.help

import io.legado.app.ui.book.searchContent.SearchResult

/**
 * IntentData Android 部分: 依赖 app 端 `SearchResult` (UI 类) 的属性.
 *
 * 核心部分 (book/source/chapterList/chapter/bigData/put/get) 已下沉至
 * modules/shared/src/commonMain/kotlin/io/legado/app/help/IntentData.kt.
 *
 * 本文件仅保留 `searchResultList` 属性, 作为 [IntentData] 扩展属性,
 * 调用方式 (`IntentData.searchResultList = ...` 等) 与原一致, 兼容现有调用方。
 */
@Suppress("UNCHECKED_CAST")
var IntentData.searchResultList: List<SearchResult>?
    get() = get("searchResultList")
    set(value) {
        put("searchResultList", value)
    }
