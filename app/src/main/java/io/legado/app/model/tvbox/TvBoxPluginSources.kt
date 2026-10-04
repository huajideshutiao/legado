package io.legado.app.model.tvbox

import io.legado.app.constant.AppLog
import io.legado.app.constant.BookSourceType
import io.legado.app.data.AppDbProviders
import io.legado.app.data.entities.BookSource
import io.legado.app.help.coroutine.IoDispatcher
import io.legado.app.help.tvbox.TvBoxConfig
import io.legado.app.help.tvbox.TvBoxSite
import io.legado.app.utils.GSON
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString

/**
 * TVBox 站点 → 虚拟 BookSource 行注册表 (与 AnimePluginSources 同构)。
 *
 * 仅 api 为 csp_ 前缀的 JAR Spider 与 http 开头的苹果 CMS 直连站点落行
 * (bookSourceType=video), 使其进入书源
 * 管理与搜索范围; 取数不走规则解析 (四路守卫经 VideoSourceDelegates 转交
 * TvBoxSourceDelegateImpl), 行内规则字段恒为空。
 */
object TvBoxPluginSources {

    /** 书源管理页分组名。 */
    const val GROUP_NAME = "TVBox源"

    /** 由站点构造虚拟 BookSource 行 (不落库)。 */
    fun buildVirtualSource(site: TvBoxSite, globalSpider: String): BookSource = BookSource(
        bookSourceUrl = TvBoxSourceMapper.siteUrlOf(site.key),
        bookSourceName = site.name.ifBlank { site.key },
        bookSourceGroup = GROUP_NAME,
        bookSourceType = BookSourceType.video,
        enabled = true,
        enabledExplore = false,
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
     */
    suspend fun sync(config: TvBoxConfig) = withContext(IoDispatcher) {
        runCatching {
            val dao = AppDbProviders.get().bookSourceDao
            val sites = config.sites.filter { it.isJarSpider || it.isCmsApi }
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
                    old.bookSourceName = fresh.bookSourceName
                    old.header = fresh.header
                    dao.update(old)
                }
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
