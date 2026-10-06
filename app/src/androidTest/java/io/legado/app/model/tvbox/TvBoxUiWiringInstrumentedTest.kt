package io.legado.app.model.tvbox

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.legado.app.constant.BookSourceType
import io.legado.app.data.AppDbProviders
import io.legado.app.data.entities.BookListPage
import io.legado.app.data.entities.BookSource
import io.legado.app.model.webBook.PluginSourceDelegates
import io.legado.app.model.webBook.WebBook
import io.legado.app.ui.book.tvbox.JvmTvBoxPlatform
import io.legado.app.ui.book.tvbox.TvBoxServiceProviders
import io.legado.app.ui.book.tvbox.TvBoxSiteItem
import io.legado.app.ui.book.tvbox.TvBoxSiteKind
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * 影视源 (TVBox) 管理链路的真机闭环验证:
 * 生产入口注册 → 配置导入 → 站点虚拟书源行落库 → 站点添加/移除开关 → WebBook 搜索 → 删源回收。
 *
 * 开关语义: TVBox 管理页开关 = 站点是否已添加 (是否落虚拟行, 即书源界面是否显示);
 * 是否参与搜索由书源界面的 enabled 开关管, 本测试不涉足它。
 *
 * 配置与 spider jar 均运行时远程获取 (社区活跃配置 gaotianliuyun/gao), 不把样本打进仓库;
 * 候选站点按形态排序逐个扫描 (JAR → CMS → JS), 首个取数成功者即判成功 —— 社区站点漂移快,
 * 单个站点失败不算回归。
 *
 * 锚点选择: 断言管理页的状态真源 [JvmTvBoxPlatform.state] 与落库的书源行, 而不是像素 ——
 * 同一份状态也是 [io.legado.app.ui.book.tvbox.TvBoxScreenModel] 的唯一数据源。
 */
@RunWith(AndroidJUnit4::class)
class TvBoxUiWiringInstrumentedTest {

    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val platform = JvmTvBoxPlatform()

    @Test
    fun tvBoxManage_fullLoopImportToggleSearchCleanup() = runBlocking {
        platform.init()
        // 1. 生产入口: App.onCreate 的 TvBoxManager.init 已把 TVBox 委派注册进 PluginSourceDelegates
        //    (与漫画/视频插件委派并列; 各实现身份互斥, 注册顺序不影响命中)。
        assertTrue(
            "TVBox 取数委派未注册",
            PluginSourceDelegates.registered().any { it === TvBoxSourceDelegateImpl },
        )
        // 「我的」页入口的门槛: 服务已注册 (仅 Android 端), 未注册端 getOrNull()=null → 入口隐藏
        TvBoxServiceProviders.register(platform)
        assertEquals(platform, TvBoxServiceProviders.getOrNull())

        // 2~3. 配置导入 (运行时拉真实远程配置) + 站点虚拟书源行落库 + WebBook 搜索
        val session = buildSession()
        println(
            "[TvBoxUiTest] ok url=${session.url} source=${session.row.bookSourceUrl} " +
                "api=${session.site.api} kind=${session.site.kind} books=${session.page.books.size}"
        )
        assertTrue(
            "站点形态识别落到 OTHER: ${session.site.api}",
            session.site.kind != TvBoxSiteKind.OTHER,
        )
        assertEquals(BookSourceType.video, session.row.bookSourceType)
        assertEquals(TvBoxPluginSources.GROUP_NAME, session.row.bookSourceGroup)
        assertTrue(
            "搜索结果 origin 与虚拟行不一致",
            session.page.books.first().origin == session.row.bookSourceUrl,
        )
        assertTrue(
            "搜索结果 bookUrl 未走 tvbox:// 语义",
            session.page.books.first().bookUrl.startsWith(session.row.bookSourceUrl),
        )

        // 4. 站点开关 = 是否已添加 (是否落虚拟行), 并经管理页状态回显
        val siteKey = session.site.key
        // added 由 DAO flow 异步回填 (行存在性), 故轮询而非读快照
        assertTrue("站点默认未添加", awaitStateAdded(siteKey, true))
        platform.setSiteEnabled(siteKey, false)
        assertTrue("关闭未删虚拟行", awaitRowGone(siteKey, 10_000))
        assertTrue("关闭未回显到管理页状态", awaitStateAdded(siteKey, false))
        platform.setSiteEnabled(siteKey, true)
        assertNotNull("重新添加未落虚拟行", awaitRowExists(siteKey, 10_000))
        assertTrue("重新添加未回显到管理页状态", awaitStateAdded(siteKey, true))
        // 复用同一行再搜一次, 确认重新添加后取数链路仍然可用
        val reSearch = WebBook.getBookListAwait(session.row, SEARCH_KEY, 1)
        assertTrue("重新添加后搜索无结果", reSearch.books.isNotEmpty())

        // 5. 错误路径: 拉不到的配置源必须失败, 且不弄脏当前配置
        assertTrue("不可达配置应导入失败", platform.importConfig(BAD_CONFIG_URL).isFailure)
        val errorState = withTimeoutOrNull(5_000) { platform.state.first { it.error != null } }
        println("[TvBoxUiTest] bad url error=${errorState?.error}")
        assertNotNull("失败原因未写进管理页状态", errorState?.error)
        assertTrue("失败后当前配置被弄脏", errorState!!.sites.isNotEmpty())
        platform.clearError()

        // 6. 删源回收: 删除当前源后其虚拟行清空
        platform.removeSource(session.url)
        assertTrue("删源后虚拟行未清理", awaitRowGone(siteKey, 10_000))
        val cleaned = withTimeoutOrNull(5_000) { platform.state.first { it.activeSource == null } }
        assertTrue("删源后来源未移除", session.url !in (cleaned?.sources ?: listOf(session.url)))
        println("[TvBoxUiTest] cleanup ok")
    }

    // ===== helpers =====

    /**
     * 导入首个能跑通“虚拟行 + 搜索”的远程配置并锁定站点:
     * 配置逐个试, 每个配置内按 JAR → CMS → JS 顺序最多试 [MAX_TRIES] 个站点。
     */
    private suspend fun buildSession(): Session {
        val failures = ArrayList<String>()
        for (url in CONFIG_URLS) {
            val imported = platform.importConfig(url)
            if (imported.isFailure) {
                failures.add("$url: ${imported.exceptionOrNull()?.message}")
                continue
            }
            val state = withTimeoutOrNull(15_000) { platform.state.first { it.sites.isNotEmpty() } }
                ?: run {
                    failures.add("$url: 管理页状态未回填站点")
                    continue
                }
            println("[TvBoxUiTest] imported=$url sites=${state.sites.size} jars=${state.jars.size}")
            val usable = state.sites.filter { it.supported && it.searchable }
                .ifEmpty { state.sites.filter { it.supported } }
            for (candidate in preferCandidates(usable).take(MAX_TRIES)) {
                val row = awaitRowExists(candidate.key, 3_000) ?: continue
                val outcome = searchOrMessage(row)
                if (outcome.page != null && outcome.page.books.isNotEmpty()) {
                    return Session(url, candidate, row, outcome.page)
                }
                failures.add("${candidate.key}@${candidate.api}[${candidate.kind}]: ${outcome.message}")
            }
        }
        throw AssertionError(
            "候选配置/站点全链路均失败:\n" + failures.take(15).joinToString("\n")
        )
    }

    /**
     * 候选排序: 已知 CMS 直连站优先, 其次按形态 JAR → CMS → JS
     * (JS spider 链路是并行进行中的工作, 排最后免得把稳定链路的回归都挡了)。
     */
    private fun preferCandidates(list: List<TvBoxSiteItem>): List<TvBoxSiteItem> {
        val byKind = list.sortedBy { kindPriority(it.kind) }
        val head = CANDIDATE_APIS.firstNotNullOfOrNull { api -> byKind.firstOrNull { it.api == api } }
            ?: return byKind
        return listOf(head) + byKind.filter { it !== head }
    }

    private fun kindPriority(kind: TvBoxSiteKind): Int = when (kind) {
        TvBoxSiteKind.JAR -> 0
        TvBoxSiteKind.CMS -> 1
        TvBoxSiteKind.JS -> 2
        TvBoxSiteKind.OTHER -> 3
    }

    private suspend fun searchOrMessage(row: BookSource): SearchOutcome =
        runCatching { WebBook.getBookListAwait(row, SEARCH_KEY, 1) }
            .fold(
                onSuccess = { SearchOutcome(it, null) },
                onFailure = { SearchOutcome(null, it.message ?: it.toString()) },
            )

    /** 轮询直到书源行出现 (返回 null = 超时未落库)。 */
    private suspend fun awaitRowExists(siteKey: String, timeoutMs: Long): BookSource? =
        withTimeoutOrNull(timeoutMs) {
            var found = AppDbProviders.get().bookSourceDao.row(siteKey)
            while (found == null) {
                delay(200)
                found = AppDbProviders.get().bookSourceDao.row(siteKey)
            }
            found
        }

    /** 轮询直到书源行消失 (返回 true = 已删; false = 超时仍在)。 */
    private suspend fun awaitRowGone(siteKey: String, timeoutMs: Long): Boolean =
        withTimeoutOrNull(timeoutMs) {
            while (AppDbProviders.get().bookSourceDao.row(siteKey) != null) {
                delay(200)
            }
            true
        } ?: false

    private suspend fun awaitStateAdded(siteKey: String, added: Boolean): Boolean =
        withTimeoutOrNull(10_000) {
            var matched = false
            while (!matched) {
                matched = platform.state.value.sites.firstOrNull { it.key == siteKey }?.added == added
                if (!matched) delay(200)
            }
            true
        } ?: false

    private suspend fun io.legado.app.data.dao.BookSourceDao.row(siteKey: String): BookSource? =
        getBookSource(TvBoxSourceMapper.siteUrlOf(siteKey))

    private data class Session(
        val url: String,
        val site: TvBoxSiteItem,
        val row: BookSource,
        val page: BookListPage,
    )

    private data class SearchOutcome(val page: BookListPage?, val message: String?)

    companion object {
        private const val SEARCH_KEY = "爱"
        /** 必然拉不到的配置地址 (本机未占用端口), 走失败路径不消耗外部网络。 */
        private const val BAD_CONFIG_URL = "https://127.0.0.1:1/tvbox-none.json"
        /** 单个配置内最多试这么多个站点 (配置常含数百站点, 不设上界会拖成马拉松)。 */
        private const val MAX_TRIES = 8
        /** 配置候选 (GitHub 直连优先; jsDelivr 镜像作设备网络受限时兜底)。 */
        private val CONFIG_URLS = listOf(
            "https://raw.githubusercontent.com/gaotianliuyun/gao/master/js.json",
            "https://raw.githubusercontent.com/gaotianliuyun/gao/master/0827.json",
            "https://fastly.jsdelivr.net/gh/gaotianliuyun/gao@master/0827.json",
        )
        /** 已知 CMS 直连站 api (免 jar 下载, 通过最快)。 */
        private val CANDIDATE_APIS = listOf(
            "https://cj.ffzyapi.com/api.php/provide/vod",
            "https://api.kuaifan.tv/api.php/provide/vod",
            "https://cj.lziapi.com/api.php/provide/vod",
            "https://bfzyapi.com/api.php/provide/vod",
            "https://suoniapi.com/api.php/provide/vod",
        )
    }
}
