package io.legado.app.help

import io.legado.app.data.entities.BaseBook
import io.legado.app.data.entities.BookChapter
import io.legado.app.ui.book.searchContent.SearchResult

object IntentData {
    var book: BaseBook?
        get() = get("nowBook")
        set(value) {
            put("nowBook", value)
        }

    var source: Any?
        get() = get("nowSource")
        set(value) {
            put("nowSource", value)
        }

    @Suppress("UNCHECKED_CAST")
    var chapterList: List<BookChapter>?
        get() = get("nowChapterList")
        set(value) {
            put("nowChapterList", value)
        }

    var chapter: BookChapter?
        get() = get("nowChapter")
        set(value) {
            put("nowChapter", value)
        }

    @Suppress("UNCHECKED_CAST")
    var searchResultList: List<SearchResult>?
        get() = get("searchResultList")
        set(value) {
            put("searchResultList", value)
        }

    private val bigData: MutableMap<String, Any> = mutableMapOf()

    @Synchronized
    fun put(key: String, data: Any?): String {
        data?.let {
            bigData[key] = data
        }
        return key
    }

    @Synchronized
    fun put(data: Any?): String {
        val key = System.nanoTime().toString()
        data?.let {
            bigData[key] = data
        }
        return key
    }

    @Suppress("UNCHECKED_CAST")
    @Synchronized
    fun <T> get(key: String?): T? {
        if (key == null) return null
        val data = bigData[key]
        bigData.remove(key)
        return data as? T
    }
}