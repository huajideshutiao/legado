package io.legado.app.help

import io.legado.app.data.entities.BaseBook
import io.legado.app.data.entities.BookChapter
import io.legado.app.utils.systemNanoTime
import kotlinx.atomicfu.locks.SynchronizedObject
import kotlinx.atomicfu.locks.synchronized

/**
 * IntentData 跨 Activity 临时大数据传递容器 (核心部分下沉 commonMain 供多端复用).
 *
 * 依赖范围:
 * - [BaseBook] / [BookChapter] 均在 shared commonMain, 可直接下沉.
 * - 依赖 app 端 `SearchResult` (UI 类) 的 `searchResultList` 属性留 app 端原文件,
 *   作为 [IntentData] 扩展属性保留, 调用方式不变.
 *
 * 原 app 端 `System.nanoTime()` 经 [systemNanoTime] expect 桥接 (actual 在 jvmAndAndroidMain
 * 委托 System.nanoTime()), 行为不变.
 */
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

    private val bigData: MutableMap<String, Any> = mutableMapOf()
    private val lock = SynchronizedObject()

    fun put(key: String, data: Any?): String = synchronized(lock) {
        data?.let {
            bigData[key] = data
        }
        key
    }

    fun put(data: Any?): String = synchronized(lock) {
        val key = systemNanoTime().toString()
        data?.let {
            bigData[key] = data
        }
        key
    }

    @Suppress("UNCHECKED_CAST")
    fun <T> get(key: String?): T? = synchronized(lock) {
        if (key == null) return@synchronized null
        val data = bigData[key]
        bigData.remove(key)
        data as? T
    }
}
