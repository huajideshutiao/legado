package io.legado.app.model.manga

import eu.kanade.tachiyomi.source.Source
import eu.kanade.tachiyomi.source.online.HttpSource
import io.legado.app.constant.BookSourceType
import io.legado.app.data.entities.BookSource
import io.legado.app.help.extension.MangaExtensionManager
import io.legado.app.data.entities.PluginExploreKindUrl
import io.legado.app.model.plugin.PluginVirtualBookSourceTable

/**
 * 漫画插件源 → 虚拟 BookSource 行注册表 (薄壳, 实现收敛于 [PluginVirtualBookSourceTable])。
 *
 * 每个已装载插件源落一行 bookSourceType=[BookSourceType.image] 的虚拟 [BookSource],
 * 使其自动出现在书源管理/搜索范围/换源; URL 身份为 [MangaSourceMapper.SOURCE_URL_PREFIX]
 * + source.id, 请求头 (HttpSource.headers) JSON 序列化进 header 字段供防盗链注入。
 * 取数不走规则解析 (WebBook 四路守卫转交 MangaSourceDelegateImpl), 行内规则字段恒为空。
 */
object MangaPluginSources {

    /** 书源管理页分组名 (插件源统一挂该组)。 */
    const val GROUP_NAME = "Tachiyomi 插件"

    /** 发现分类 url 段: 热门 (对应 [Source.getPopularManga])。 */
    const val EXPLORE_URL_POPULAR = PluginExploreKindUrl.POPULAR

    /** 发现分类 url 段: 最新 (对应 [Source.getLatestUpdates])。 */
    const val EXPLORE_URL_LATEST = PluginExploreKindUrl.LATEST

    /** 发现分类 url 段: 筛选浏览 (空关键词 + 源筛选器, 对应 Mihon FilterSheet 应用后的搜索面)。 */
    const val EXPLORE_URL_FILTER = PluginExploreKindUrl.FILTER

    /** 基础发现分类 (热门/最新, 所有源恒有)。 */
    private const val EXPLORE_URL_BASE = "热门::$EXPLORE_URL_POPULAR\n最新::$EXPLORE_URL_LATEST"

    /** 含筛选段的完整发现分类 (源声明了筛选器时用, 对齐 Mihon 仅 filters 非空才显示 Filter chip)。 */
    private const val EXPLORE_URL_WITH_FILTER =
        "$EXPLORE_URL_BASE\n筛选::$EXPLORE_URL_FILTER"

    /**
     * 虚拟行 exploreUrl: 换行分隔的 `标题::url` 列表, 按源筛选器有无定制
     * (无筛选器的源不出"筛选"入口)。采用换行形态 (非 JSON 数组), 由 shared 层
     * `BookSourceExtensionsShared.exploreKinds()` 的 `ruleStr.split("(&&|\n)+")`
     * 分支解析为 [io.legado.app.data.entities.rule.ExploreKind]; 其 url 段会原样
     * 作为 key 传入 `WebBook.getBookListAwait(..., isSearch = false)`, 即
     * [MangaSourceDelegateImpl.getExploreAwait] 的 url 入参。
     */
    fun exploreUrlOf(source: Source): String =
        if (runCatching { source.getFilterList() }.getOrNull().isNullOrEmpty()) {
            EXPLORE_URL_BASE
        } else {
            EXPLORE_URL_WITH_FILTER
        }

    private val table = PluginVirtualBookSourceTable<Source>(
        groupName = GROUP_NAME,
        bookSourceType = BookSourceType.image,
        kindLabel = "Mihon 漫画插件源",
        logTag = "漫画插件",
        exploreUrl = EXPLORE_URL_BASE,
        headersOf = { (it as? HttpSource)?.headers?.toMap() },
    )

    /** 由插件源构造虚拟 BookSource 行 (不落库; loginUrl 判定在公共表唯一实现)。 */
    fun buildVirtualSource(source: Source, pkgName: String?): BookSource =
        table.buildVirtualSource(
            PluginVirtualBookSourceTable.Row(
                url = MangaSourceMapper.sourceUrlOf(source.id),
                pkgName = pkgName,
                source = source,
                name = source.name,
                exploreUrlOverride = exploreUrlOf(source),
            )
        )

    /**
     * 与插件宿主已装载源同步虚拟行: 新装载 upsert (保留用户 enabled/customOrder),
     * 已卸载删除。由 MangaExtensionManager.loadExtensions 在注册表更新后调用。
     */
    suspend fun sync(loadedSources: List<MangaExtensionManager.RegisteredMangaSource>) =
        table.sync(
            loadedSources.map { reg ->
                PluginVirtualBookSourceTable.Row(
                    url = MangaSourceMapper.sourceUrlOf(reg.source.id),
                    pkgName = reg.pkgName,
                    source = reg.source,
                    name = reg.source.name,
                    exploreUrlOverride = exploreUrlOf(reg.source),
                )
            }
        )

    /** 清理本组全部虚拟行 (插件宿主整体停用时使用)。 */
    suspend fun removeAll() = table.removeAll()

    /** 插件源当前源列表 (含 pkgName), 供委派/注册表共用。 */
    fun currentRegisteredSources(): List<MangaExtensionManager.RegisteredMangaSource> =
        MangaExtensionManager.sources.value
}
