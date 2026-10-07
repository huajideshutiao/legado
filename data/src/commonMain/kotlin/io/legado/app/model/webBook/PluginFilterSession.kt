package io.legado.app.model.webBook

import eu.kanade.tachiyomi.animesource.model.AnimeFilterList
import eu.kanade.tachiyomi.source.model.FilterList

/**
 * 插件源筛选会话实例 (页面会话持有, 随页面销毁即丢, 不持久化)。
 *
 * 插件源的筛选状态由 Filter 对象自身携带 (上游约定: getFilterList 每次调用返回全新实例),
 * 故同一份实例必须同时供筛选 UI 与取数委派使用; 实例由页面 (VM) 创建持有, 经
 * [WebBook.getBookListAwait] 的 pluginFilters 参数透传到委派, 不经任何全局缓存。
 */
sealed interface PluginFilterSession {

    /** 源声明的筛选器是否为空 (空 = 该源不出筛选入口)。 */
    val isEmpty: Boolean
}

/** 漫画 (Mihon/Tachiyomi 契约) 筛选会话。 */
class MangaFilterSession(val filters: FilterList) : PluginFilterSession {
    override val isEmpty: Boolean get() = filters.isEmpty()
}

/** 视频 (Aniyomi 契约) 筛选会话。 */
class AnimeFilterSession(val filters: AnimeFilterList) : PluginFilterSession {
    override val isEmpty: Boolean get() = filters.isEmpty()
}
