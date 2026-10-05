package io.legado.app.model.anime

import eu.kanade.tachiyomi.animesource.AnimeSource
import eu.kanade.tachiyomi.animesource.online.AnimeHttpSource
import io.legado.app.constant.BookSourceType
import io.legado.app.data.entities.BookSource
import io.legado.app.help.extension.MangaExtensionManager
import io.legado.app.model.plugin.PluginVirtualBookSourceTable

/**
 * 视频插件源 → 虚拟 BookSource 行注册表 (薄壳, 实现收敛于 [PluginVirtualBookSourceTable])。
 *
 * 每个已装载插件源落一行 bookSourceType=[BookSourceType.video] 的虚拟 [BookSource],
 * 使其自动进入书源管理/搜索范围/换源; URL 身份为 [AnimeSourceMapper.SOURCE_URL_PREFIX]
 * + source.id, 请求头 (AnimeHttpSource.headers) JSON 序列化进 header 字段供防盗链注入。
 * 取数不走规则解析 (WebBook 四路守卫转交 VideoSourceDelegateImpl), 行内规则字段恒为空
 * (仅 exploreUrl 写固定发现分类, 非规则字段)。
 *
 * 行构造/同步/清理与漫画侧共用同一份实现, 差异只在本薄壳的类型参数与常量上
 * (分组/类型/前缀/头提取/源列表), 登录标记 (loginUrl) 判定单点位于公共表。
 */
object AnimePluginSources {

    /** 书源管理页分组名 (插件源统一挂该组)。 */
    const val GROUP_NAME = "Tachiyomi 视频插件"

    /** 发现分类 url 段: 热门 (对应 [AnimeSource.getPopularAnime])。 */
    const val EXPLORE_URL_POPULAR = "popular"

    /** 发现分类 url 段: 最新 (对应 [AnimeSource.getLatestUpdates]; supportsLatest=false 的源会抛)。 */
    const val EXPLORE_URL_LATEST = "latest"

    /**
     * 虚拟行 exploreUrl: 换行分隔的 `标题::url` 列表。
     *
     * 形态与漫画侧 [io.legado.app.model.manga.MangaPluginSources.EXPLORE_URL] 一致, 由 shared 层
     * `BookSourceExtensionsShared.exploreKinds()` 的换行分支解析; url 段原样作为 key
     * 传入 `WebBook.getBookListAwait(..., isSearch = false)`, 即
     * [VideoSourceDelegateImpl.getExploreAwait] 的 url 入参。
     */
    const val EXPLORE_URL = "热门::$EXPLORE_URL_POPULAR\n最新::$EXPLORE_URL_LATEST"

    private val table = PluginVirtualBookSourceTable<AnimeSource>(
        groupName = GROUP_NAME,
        bookSourceType = BookSourceType.video,
        kindLabel = "Aniyomi 视频插件源",
        logTag = "视频插件",
        exploreUrl = EXPLORE_URL,
        headersOf = { (it as? AnimeHttpSource)?.headers?.toMap() },
    )

    /** 由插件源构造虚拟 BookSource 行 (不落库; loginUrl 判定在公共表唯一实现)。 */
    fun buildVirtualSource(source: AnimeSource, pkgName: String?): BookSource =
        table.buildVirtualSource(
            PluginVirtualBookSourceTable.Row(
                url = AnimeSourceMapper.sourceUrlOf(source.id),
                pkgName = pkgName,
                source = source,
                name = source.name,
            )
        )

    /**
     * 与插件宿主已装载视频源同步虚拟行: 新装载 upsert (保留用户 enabled/customOrder),
     * 已卸载删除。由 MangaExtensionManager.loadExtensions 在注册表更新后调用。
     */
    suspend fun sync(loadedSources: List<MangaExtensionManager.RegisteredAnimeSource>) =
        table.sync(
            loadedSources.map { reg ->
                PluginVirtualBookSourceTable.Row(
                    url = AnimeSourceMapper.sourceUrlOf(reg.source.id),
                    pkgName = reg.pkgName,
                    source = reg.source,
                    name = reg.source.name,
                )
            }
        )

    /** 清理本组全部虚拟行 (插件宿主整体停用时使用)。 */
    suspend fun removeAll() = table.removeAll()

    /** 插件视频源当前源列表 (含 pkgName), 供委派/注册表共用。 */
    fun currentRegisteredSources(): List<MangaExtensionManager.RegisteredAnimeSource> =
        MangaExtensionManager.animeSources.value
}
