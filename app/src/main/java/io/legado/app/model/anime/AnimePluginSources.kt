package io.legado.app.model.anime

import eu.kanade.tachiyomi.animesource.AnimeSource
import eu.kanade.tachiyomi.animesource.online.AnimeHttpSource
import io.legado.app.constant.AppLog
import io.legado.app.constant.BookSourceType
import io.legado.app.data.AppDbProviders
import io.legado.app.data.entities.BookSource
import io.legado.app.help.coroutine.IoDispatcher
import io.legado.app.help.extension.MangaExtensionManager
import io.legado.app.help.extension.model.MangaExtension
import io.legado.app.utils.GSON
import kotlinx.serialization.encodeToString
import kotlinx.coroutines.withContext
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer

/**
 * 视频插件源 → 虚拟 BookSource 行注册表 (与漫画侧 MangaPluginSources 同构)。
 *
 * 每个已装载插件源落一行 bookSourceType=[BookSourceType.video] 的虚拟 [BookSource],
 * 使其自动进入书源管理/搜索范围/换源; URL 身份为 [AnimeSourceMapper.SOURCE_URL_PREFIX]
 * + source.id, 请求头 (AnimeHttpSource.headers) JSON 序列化进 header 字段供防盗链注入。
 * 取数不走规则解析 (WebBook 四路守卫转交 VideoSourceDelegateImpl), 行内规则字段恒为空
 * (仅 exploreUrl 写固定发现分类, 非规则字段)。
 *
 * 与漫画侧的差异: 虚拟行同步由 [MangaExtensionManager.loadExtensions] 直接触发
 * (漫画侧由 MainActivity 注册的 AndroidMangaExtensionPlatform 监听, 视频 UI 入口未建)。
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
     * 形态与漫画侧 [MangaPluginSources.EXPLORE_URL] 一致, 由 shared 层
     * `BookSourceExtensionsShared.exploreKinds()` 的换行分支解析; url 段原样作为 key
     * 传入 `WebBook.getBookListAwait(..., isSearch = false)`, 即
     * [VideoSourceDelegateImpl.getExploreAwait] 的 url 入参。
     */
    const val EXPLORE_URL = "热门::$EXPLORE_URL_POPULAR\n最新::$EXPLORE_URL_LATEST"

    /** 由插件源构造虚拟 BookSource 行 (不落库)。 */
    fun buildVirtualSource(source: AnimeSource, pkgName: String?): BookSource = BookSource(
        bookSourceUrl = AnimeSourceMapper.sourceUrlOf(source.id),
        bookSourceName = source.name,
        bookSourceGroup = GROUP_NAME,
        bookSourceType = BookSourceType.video,
        enabled = true,
        enabledExplore = true,
        exploreUrl = EXPLORE_URL,
        header = headerJsonOf(source),
        bookSourceComment = listOfNotNull(
            "Aniyomi 视频插件源",
            pkgName?.let { "pkg: $it" },
        ).joinToString("\n"),
    )

    private val stringMapSerializer = MapSerializer(String.serializer(), String.serializer())

    private fun headerJsonOf(source: AnimeSource): String? {
        val headers = (source as? AnimeHttpSource)?.headers ?: return null
        return runCatching {
            GSON.encodeToString<Map<String, String>>(headers.toMap())
        }.getOrNull()
    }

    /**
     * 与插件宿主已装载视频源同步虚拟行: 新装载 upsert (保留用户 enabled/customOrder),
     * 已卸载删除。由 MangaExtensionManager.loadExtensions 在注册表更新后调用。
     */
    suspend fun sync(loadedSources: List<MangaExtensionManager.RegisteredAnimeSource>) =
        withContext(IoDispatcher) {
            runCatching {
                val dao = AppDbProviders.get().bookSourceDao
                val urls = loadedSources.mapTo(HashSet()) {
                    AnimeSourceMapper.sourceUrlOf(it.source.id)
                }
                // 删除已卸载/失效的虚拟行
                val existing = dao.getByGroup(GROUP_NAME)
                    .filter { it.bookSourceType == BookSourceType.video }
                val stale = existing.filter { it.bookSourceUrl !in urls }
                if (stale.isNotEmpty()) dao.deleteIn(stale.map { it.bookSourceUrl })
                // upsert 新装载行 (存在则只补齐名称/请求头, 不动用户设置)
                for (registered in loadedSources) {
                    val url = AnimeSourceMapper.sourceUrlOf(registered.source.id)
                    val fresh = buildVirtualSource(registered.source, registered.pkgName)
                    val old = dao.getBookSource(url)
                    if (old == null) {
                        dao.insert(fresh)
                    } else if (old.bookSourceName != fresh.bookSourceName ||
                        !headerSuperset(old.header, fresh.header)
                    ) {
                        old.bookSourceName = fresh.bookSourceName
                        old.header = fresh.header
                        dao.update(old)
                    }
                }
            }.onFailure {
                AppLog.put("视频插件虚拟书源同步失败", it)
            }
        }

    /** old.header 已含 fresh.header 全部键值时视为超集 (防盗链合并头), 不被静态头回退覆盖。 */
    private fun headerSuperset(oldJson: String?, freshJson: String?): Boolean {
        if (oldJson == freshJson) return true
        val old = oldJson?.let { runCatching { GSON.decodeFromString(stringMapSerializer, it) }.getOrNull() }
            ?: return false
        val fresh = freshJson?.let { runCatching { GSON.decodeFromString(stringMapSerializer, it) }.getOrNull() }
            ?: return true
        return old.entries.containsAll(fresh.entries)
    }

    /** 清理本组全部虚拟行 (插件宿主整体停用时使用)。 */
    suspend fun removeAll() = withContext(IoDispatcher) {
        runCatching {
            val dao = AppDbProviders.get().bookSourceDao
            val rows = dao.getByGroup(GROUP_NAME)
                .filter { it.bookSourceType == BookSourceType.video }
            if (rows.isNotEmpty()) dao.deleteIn(rows.map { it.bookSourceUrl })
        }.onFailure {
            AppLog.put("视频插件虚拟书源清理失败", it)
        }
    }

    /** 插件视频源当前源列表 (含 pkgName), 供委派/注册表共用。 */
    fun currentRegisteredSources(): List<MangaExtensionManager.RegisteredAnimeSource> =
        MangaExtensionManager.animeSources.value

    /** Available 条目是否有可安装动作 (已装同包名更高版本由 Loaded.hasUpdate 判定)。 */
    fun installedOf(pkgName: String): MangaExtension.Installed? =
        MangaExtensionManager.loadedExtensions.value[pkgName]
            ?: MangaExtensionManager.notLoadedExtensions.value[pkgName]
}
