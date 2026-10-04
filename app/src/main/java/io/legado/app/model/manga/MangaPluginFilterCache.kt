package io.legado.app.model.manga

import eu.kanade.tachiyomi.source.Source
import eu.kanade.tachiyomi.source.model.FilterList
import io.legado.app.help.extension.MangaExtensionManager
import java.util.concurrent.ConcurrentHashMap

/**
 * 插件源筛选器状态缓存。
 *
 * 插件源的 [Source.getFilterList] 每次调用返回全新实例, 而筛选状态由 Filter 对象自身
 * 携带 (Mihon 约定), 故这里按 source.id 缓存同一实例: 搜索页筛选 UI 与取数委派
 * (MangaSourceDelegateImpl 搜索时读取) 共享, UI 回填即生效, 无需经 WebBook 传参。
 */
object MangaPluginFilterCache {

    private val cache = ConcurrentHashMap<Long, FilterList>()

    fun getOrCreate(source: Source): FilterList =
        cache.computeIfAbsent(source.id) { source.getFilterList() }

    fun getOrNull(sourceUrl: String): FilterList? =
        MangaSourceMapper.sourceIdOf(sourceUrl)
            ?.let { MangaExtensionManager.getSource(it) }
            ?.let { getOrCreate(it) }

    fun clear(sourceId: Long) {
        cache.remove(sourceId)
    }

    fun clearAll() {
        cache.clear()
    }
}
