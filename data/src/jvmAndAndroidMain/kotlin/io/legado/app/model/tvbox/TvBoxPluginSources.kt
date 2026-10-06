package io.legado.app.model.tvbox

import io.legado.app.constant.AppLog
import io.legado.app.constant.BookSourceType
import io.legado.app.data.AppDbProviders
import io.legado.app.data.entities.BookSource
import io.legado.app.help.coroutine.IoDispatcher
import io.legado.app.help.source.clearExploreKindsCache
import io.legado.app.help.tvbox.TvBoxConfig
import io.legado.app.help.tvbox.TvBoxSite
import io.legado.app.utils.GSON
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString

/**
 * TVBox 站点 → 虚拟 BookSource 行注册表 (与 AnimePluginSources 同构)。
 *
 * 仅 api 为 csp_ 前缀的 JAR Spider、含 .js 的 JS Spider 与 http 开头的苹果 CMS 直连站点落行
 * (bookSourceType=video), 使其进入书源
 * 管理与搜索范围; 取数不走规则解析 (四路守卫经 VideoSourceDelegates 转交
 * TvBoxSourceDelegateImpl), 行内规则字段恒为空。
 */
object TvBoxPluginSources {

    /** 书源管理页分组名。 */
    const val GROUP_NAME = "TVBox 源"

    /**
     * 发现能力标记 (非规则)。分类由 [TvBoxSourceDelegateImpl.getExploreKinds] 运行时枚举
     * (站点 class 数组), 经 BookSource.exploreKinds() 落盘缓存; 本字段只承担
     * `BookSourceDao` 的 `hasExploreUrl = 1` 过滤 (书源管理页的发现开关据此显隐)。
     */
    private const val EXPLORE_URL = "@delegate"

    /** 由站点构造虚拟 BookSource 行 (不落库)。 */
    fun buildVirtualSource(site: TvBoxSite, globalSpider: String): BookSource = BookSource(
        bookSourceUrl = TvBoxSourceMapper.siteUrlOf(site.key),
        bookSourceName = site.name.ifBlank { site.key },
        bookSourceGroup = GROUP_NAME,
        bookSourceType = BookSourceType.video,
        // 初始启用态随站点 searchable (FongMi 语义: searchable=0 的站点不参与搜索);
        // 行建立后归书源界面的开关管, sync 不覆写用户的选择。
        enabled = site.searchable,
        enabledExplore = true,
        exploreUrl = EXPLORE_URL,
        header = headerJsonOf(site),
        bookSourceComment = listOf(
            "TVBox 站点",
            "type: ${site.type}",
            "api: ${site.api}",
            "jar: ${site.effectiveJar(globalSpider).ifBlank { "(无)" }}",
        ).joinToString("\n"),
    )

    /**
     * 与当前配置同步虚拟行: 站点在配置中 upsert (保留用户 enabled/customOrder),
     * 已移除/非 csp_ 的行删除。
     *
     * [disabledSiteKeys] 是用户在 TVBox 管理页关闭 (未添加) 的站点 key 集合:
     * 它们不落行, 且其虚拟 URL 不在 upsert 的 `urls` 集合内 → 被 stale 判定删除。
     * 这正是期望行为 (关闭 == 删行, 书源界面随之不再显示); 重新打开时由
     * [TvBoxManager.setSiteAdded] 单独重建该行。
     */
    suspend fun sync(config: TvBoxConfig, disabledSiteKeys: Set<String> = emptySet()) =
        withContext(IoDispatcher) {
            runCatching {
                val dao = AppDbProviders.get().bookSourceDao
                val sites = config.sites.filter {
                    // .py 站点无 python 运行时不可取数, 不落虚拟行 (与 hasVirtualRow/kindOf 口径一致)
                    !it.isPySpider &&
                        (it.isJarSpider || it.isCmsApi || it.isJsSpider) && it.key !in disabledSiteKeys
                }
                val urls = sites.mapTo(HashSet()) { TvBoxSourceMapper.siteUrlOf(it.key) }
                val existing = dao.getByGroup(GROUP_NAME)
                    .filter { it.bookSourceType == BookSourceType.video }
                val stale = existing.filter { it.bookSourceUrl !in urls }
                if (stale.isNotEmpty()) dao.deleteIn(stale.map { it.bookSourceUrl })
                for (site in sites) {
                    val url = TvBoxSourceMapper.siteUrlOf(site.key)
                    val fresh = buildVirtualSource(site, config.spider)
                    val old = dao.getBookSource(url)
                    if (old == null) {
                        dao.insert(fresh)
                    } else if (old.bookSourceName != fresh.bookSourceName ||
                        old.header != fresh.header
                    ) {
                        // 只同步名称/请求头这类规则面; enabled/enabledExplore/exploreUrl
                        // 均不在同步面内 (前者归书源界面管, 后者恒为 @js 占位规则)。
                        old.bookSourceName = fresh.bookSourceName
                        old.header = fresh.header
                        dao.update(old)
                    }
                    // 分类枚举结果有磁盘缓存: 配置重导入即重置, 下次进发现页重新枚举
                    runCatching { (old ?: fresh).clearExploreKindsCache() }
                }
            }.onFailure {
                AppLog.put("TVBox 虚拟书源同步失败", it)
            }
        }

    /** 清理本组全部虚拟行 (配置清除时使用)。 */
    suspend fun removeAll() = withContext(IoDispatcher) {
        runCatching {
            val dao = AppDbProviders.get().bookSourceDao
            val rows = dao.getByGroup(GROUP_NAME)
                .filter { it.bookSourceType == BookSourceType.video }
            if (rows.isNotEmpty()) dao.deleteIn(rows.map { it.bookSourceUrl })
        }.onFailure {
            AppLog.put("TVBox 虚拟书源清理失败", it)
        }
    }

    private fun headerJsonOf(site: TvBoxSite): String? {
        if (site.header.isEmpty()) return null
        return runCatching {
            GSON.encodeToString<Map<String, String>>(site.header)
        }.getOrNull()
    }
}
