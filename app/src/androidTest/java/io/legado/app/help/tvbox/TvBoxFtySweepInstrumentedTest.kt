package io.legado.app.help.tvbox

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.legado.app.constant.BookType
import io.legado.app.data.AppDbProviders
import io.legado.app.data.entities.Book
import io.legado.app.model.tvbox.TvBoxManager
import io.legado.app.model.tvbox.TvBoxPluginSources
import io.legado.app.model.tvbox.TvBoxSourceDelegateImpl
import io.legado.app.model.tvbox.TvBoxSourceMapper
import io.legado.app.model.webBook.WebBook
import io.legado.app.help.tvbox.TvBoxSite
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.Collections
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

/**
 * fty.json 全站逐个源排查工具 (真机排查用, 非回归断言):
 *
 * 逐站点跑「装载 → home/search/category → detail → 取播」链路, 记录到达阶段与首个失败点。
 *
 * 两点实现约束 (踩过的坑, 勿改回):
 * - **必须并行**: spider 取数全链是同步阻塞 IO, 串行 48 站会拖成几十分钟; 这里用固定
 *   线程池 + 信号量限流, 单站点独占一个线程。
 * - **超时必须靠线程**: 阻塞调用不响应协程取消 (withTimeoutOrNull 收不回已进入
 *   OkHttp/DexClassLoader 的调用), 故用 `Future.get(timeout)` 真超时, 超时即放弃该线程
 *   继续跑其余站点 (排查工具允许线程泄漏, 不做资源回收)。
 *
 * 结果边跑边追加到 filesDir/tvbox/sweep.jsonl (每站点一行), 可随时 adb run-as 取回;
 * 同时 println 进 instrumentation 输出。
 */
@RunWith(AndroidJUnit4::class)
class TvBoxFtySweepInstrumentedTest {

    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    /** 单站点硬超时 (阻塞调用只能靠它止损)。 */
    private val perSiteTimeoutMs = 45_000L

    /** 并行度: 兼顾速度与设备/目标站点的承受度。 */
    private val parallelism = 8

    @Test
    fun sweepFtySites() {
        TvBoxManager.init()
        val config = runBlocking { TvBoxManager.setConfigFromUrl(CONFIG_URL) }
        assertTrue("fty.json 无站点", config.sites.isNotEmpty())

        val outFile = File(context.filesDir, "tvbox/sweep.jsonl").apply {
            parentFile?.mkdirs()
            writeText("")
        }
        val sites = config.sites
        val results = Collections.synchronizedList(ArrayList<JSONObject>())
        val pool = Executors.newCachedThreadPool()
        val gate = Semaphore(parallelism)
        val started = System.currentTimeMillis()
        try {
            val futures = sites.map { site ->
                gate.acquire()
                pool.submit(Callable {
                    val begin = System.currentTimeMillis()
                    val item = try {
                        sweepOne(site)
                    } finally {
                        gate.release()
                    }
                    item.put("ms", System.currentTimeMillis() - begin)
                    results.add(item)
                    val line = item.toString()
                    synchronized(outFile) { outFile.appendText(line + "\n") }
                    println("[TvBoxSweep] " + line)
                    item
                })
            }
            for ((index, future) in futures.withIndex()) {
                val site = sites[index]
                try {
                    future.get(perSiteTimeoutMs, TimeUnit.MILLISECONDS)
                } catch (e: TimeoutException) {
                    val item = baseItem(site)
                        .put("stage", "TIMEOUT")
                        .put("ok", false)
                        .put("detail", "单站点超过 ${perSiteTimeoutMs}ms (阻塞调用不响应取消)")
                        .put("ms", perSiteTimeoutMs)
                    results.add(item)
                    val line = item.toString()
                    synchronized(outFile) { outFile.appendText(line + "\n") }
                    println("[TvBoxSweep] " + line)
                }
            }
        } finally {
            pool.shutdownNow()
        }
        val passed = results.count { it.optBoolean("ok") }
        val byStage = results.groupBy { it.optString("stage") }
            .entries.sortedByDescending { it.value.size }
            .joinToString(" ") { "${it.key}=${it.value.size}" }
        println("[TvBoxSweep] SUMMARY total=${sites.size} passed=$passed $byStage " +
            "elapsedMs=${System.currentTimeMillis() - started}")
    }

    /**
     * 委派级真实用户路径: 搜索 → 详情 → 目录 → 取播 (含网页嗅探兜底)。
     *
     * 为何不只探 spider: 委派层对非直链本就有嗅探兜底 (`sniffContent` 在无 parses 时
     * 退化为直接嗅探播放页), 只调 spider.playerContent 会把"能播"误判成 PLAY_PAGE。
     */
    private fun sweepOne(site: TvBoxSite): JSONObject {
        val item = baseItem(site)
        var stage = "INIT"
        try {
            val bookSource = runBlocking {
                TvBoxPluginSources.sync(TvBoxManager.config!!)
                AppDbProviders.get().bookSourceDao.getBookSource(TvBoxSourceMapper.siteUrlOf(site.key))
            }
            if (bookSource == null) {
                return item.put("stage", "NO_ROW").put("ok", false).put("detail", "虚拟书源行未落库")
            }
            stage = "ROW"
            // 种子与真实用户路径一致: searchable 站点走搜索, 不可搜站点走首页/分类 (spider 层取种子);
            // 取到种子后一律走委派层 (详情/目录/取播含嗅探兜底), 不再直调 spider。
            // 多条种子逐个试: 单条片源失效不代表整站不可用 (真实用户会换片), 任一条可播即算可用。
            val seeds = runBlocking { seedBookUrls(bookSource, site) }
            if (seeds.isEmpty()) {
                return item.put("stage", "NO_SEED").put("ok", false).put("detail", "无可用种子")
            }
            stage = "SEED"
            var lastStage = "SEED"
            var lastDetail: String? = null
            for (seed in seeds) {
                val attempt = runBlocking { playOne(bookSource, seed) }
                if (attempt.ok) {
                    return item.put("stage", "PLAY").put("ok", true)
                        .put("detail", attempt.detail + " | 种子数=" + seeds.size)
                }
                lastStage = attempt.stage
                lastDetail = attempt.detail
            }
            return item.put("stage", lastStage).put("ok", false)
                .put("detail", (lastDetail ?: "") + " | 试过种子数=" + seeds.size)
        } catch (e: Throwable) {
            return item.put("stage", stage + "_FAIL").put("ok", false).put("detail", describe(e))
        }
    }

    /** 单个种子的完整委派链: 详情 → 目录 → 取播。 */
    private suspend fun playOne(
        bookSource: io.legado.app.data.entities.BookSource,
        seed: Seed,
    ): Attempt {
        val book = Book().apply {
            bookUrl = seed.bookUrl
            // 书名必须带上 (真实用户从列表点入时带着书名): 详情接口常不返回 vod_name,
            // 不带书名的探针会把"详情缺字段"误报成失败。
            name = seed.name
            origin = bookSource.bookSourceUrl
            type = BookType.video
        }
        val info = runCatching {
            TvBoxSourceDelegateImpl.getBookInfoAwait(bookSource, book, canReName = true)
        }.getOrElse { return Attempt("DETAIL_FAIL", describe(it)) }
        if (info.name.isBlank()) return Attempt("DETAIL_EMPTY", "详情标题为空")
        val chapters = TvBoxSourceDelegateImpl.getChapterListAwait(bookSource, info)
            .getOrElse { return Attempt("TOC_FAIL", describe(it)) }
        if (chapters.isEmpty()) return Attempt("TOC_EMPTY", "目录为空")
        var lastError: String? = null
        for (chapter in chapters.filter { !it.isVolume }.take(3)) {
            val content = runCatching {
                TvBoxSourceDelegateImpl.getContentAwait(bookSource, info, chapter)
            }.onFailure { lastError = describe(it) }.getOrNull()
            if (content.isNullOrBlank()) continue
            if (!content.contains("://")) return Attempt("PLAY_NO_URL", content.take(160))
            return Attempt("PLAY", content.lines().first().take(140), ok = true)
        }
        return Attempt("PLAY_FAIL", lastError ?: "全部尝试章节均无内容")
    }

    private data class Attempt(val stage: String, val detail: String?, val ok: Boolean = false)

    private fun baseItem(site: TvBoxSite): JSONObject = JSONObject()
        .put("key", site.key)
        .put("name", site.name)
        .put("api", site.api)
        .put("kind", when {
            site.isPySpider -> "py"
            site.isJsSpider -> "js"
            site.isCmsApi -> "cms"
            site.isJarSpider -> "jar"
            else -> "other"
        })
        .put("ext", site.ext.take(120))

    /** 种子: 条目名 + bookUrl (folder/action 伪 URL 已在取种子时过滤)。 */
    private data class Seed(val name: String, val bookUrl: String)

    /**
     * 取种子列表 (最多 5 条): searchable 站点走搜索 (真实用户搜片路径),
     * 不可搜站点退首页/分类/发现页 (spider 层直接出种子)。
     *
     * 过滤含 `::` 的伪 URL (folder/action 条目): 它们点击后走发现页/动作, 不是可播视频。
     */
    private suspend fun seedBookUrls(
        bookSource: io.legado.app.data.entities.BookSource,
        site: TvBoxSite,
    ): List<Seed> {
        if (site.searchable) {
            val page = runCatching { WebBook.getBookListAwait(bookSource, SEARCH_KEY, 1) }.getOrNull()
            val seeds = page?.books.orEmpty()
                .filter { !it.bookUrl.contains("::") }
                .map { Seed(it.name, it.bookUrl) }
            if (seeds.isNotEmpty()) return seeds.take(5)
        }
        val spider = TvBoxManager.spiderFor(site, TvBoxManager.config?.spider.orEmpty()).second
        val home = runCatching { JSONObject(spider.homeContent(true)) }.getOrElse { JSONObject() }
        val seeds = ArrayList<Seed>()
        seedsOf(home.optJSONArray("list"), site.key).let { seeds.addAll(it) }
        // 首页无 list 的站点 (直播/片单类, searchable=0 且 homeContent 只给 class) 真实用户路径是
        // 发现页分类取数, 故先补走委派 getExploreAwait, 再退直接扫分类页。
        if (seeds.isEmpty()) {
            runCatching { TvBoxSourceDelegateImpl.getExploreAwait(bookSource, "popular", 1) }
                .getOrNull()?.books.orEmpty()
                .filter { !it.bookUrl.contains("::") }
                .forEach { seeds.add(Seed(it.name, it.bookUrl)) }
        }
        val classes = home.optJSONArray("class")
        if (seeds.isEmpty() && classes != null) {
            for (i in 0 until minOf(classes.length(), 4)) {
                val tid = classes.optJSONObject(i)?.optString("type_id")?.trim().orEmpty()
                if (tid.isEmpty()) continue
                val list = runCatching {
                    JSONObject(spider.categoryContent(tid, "1", true, HashMap())).optJSONArray("list")
                }.getOrNull() ?: continue
                seeds.addAll(seedsOf(list, site.key))
                if (seeds.size >= 5) break
            }
        }
        return seeds.take(5)
    }

    /** 列表项 → 种子 (跳过无 id/无名的项, 并过滤 folder/action 伪 URL)。 */
    private fun seedsOf(list: org.json.JSONArray?, siteKey: String): List<Seed> {
        if (list == null) return emptyList()
        val seeds = ArrayList<Seed>()
        for (i in 0 until list.length()) {
            val obj = list.optJSONObject(i) ?: continue
            val id = obj.optString("vod_id").ifBlank { obj.optString("id") }.trim()
            if (id.isEmpty()) continue
            val name = obj.optString("vod_name").trim()
            val url = TvBoxSourceMapper.bookUrlOf(siteKey, id)
            if (url.contains("::")) continue
            seeds.add(Seed(name, url))
        }
        return seeds
    }

    /** 失败诊断: 根因类名+消息+前几个栈帧。 */
    private fun describe(t: Throwable): String {
        val root = generateSequence(t) { it.cause }.last()
        val frames = (root.stackTrace.takeIf { it.isNotEmpty() } ?: t.stackTrace)
            .take(4)
            .joinToString(" <- ") {
                it.className.substringAfterLast('.') + "." + it.methodName + ":" + it.lineNumber
            }
        return t::class.simpleName + " / " + root::class.simpleName + ": " + root.message + " @ " + frames
    }

    companion object {
        private const val CONFIG_URL =
            "https://gh-proxy.com/https://raw.githubusercontent.com/qist/tvbox/master/fty.json"
        private const val SEARCH_KEY = "爱"
    }
}
