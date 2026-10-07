// TVBox 站点逐源排查工具 (桌面 JVM 侧, 非回归断言): 对任意 TVBox 配置逐站跑真实取数链,
// 记录到达阶段与首个失败点。配置/子集/并行度/超时全部可经命令行覆盖, 不绑定某个具体源:
//
//   ./gradlew :desktop:test --tests "*TvBoxSweepTest*" -Dlegado.networkTests=true \
//       -Dlegado.tvbox.sweep.configUrl=<任意配置 URL> \
//       -Dlegado.tvbox.sweep.sites=<站点key,逗号分隔,可省> \
//       -Dlegado.tvbox.sweep.parallelism=32 -Dlegado.tvbox.sweep.timeoutMs=60000
//
// 结果落 build/test-ext/tvbox-sweep[-<子集>].jsonl (每站一行) 与同名 -summary.txt。
//
// 三点实现约束 (与 app 端 TvBoxFtySweepInstrumentedTest 同源, 勿改回):
// - **必须并行**: spider 取数全链是同步阻塞 IO, 串行数百站会拖成几小时; 固定线程池 + 信号量限流,
//   单站点独占一个线程。
// - **超时必须靠线程**: 阻塞调用不响应协程取消 (withTimeoutOrNull 收不回已进入 OkHttp/类加载的调用),
//   故用 `Future.get(timeout)` 真超时, 超时即放弃该线程继续跑其余站点 (排查工具允许线程泄漏)。
// - **bootstrap 走 DesktopCore**: TVBox 取数依赖文件目录/数据库/JS 引擎/HTTP 栈/平台钩子,
//   逐项手工注册易漏 (与 headless 入口同一套调用)。
package io.legado.desktop.help.tvbox

import io.legado.app.constant.BookType
import io.legado.app.data.AppDbProviders
import io.legado.app.data.entities.Book
import io.legado.app.data.entities.BookSource
import io.legado.app.help.source.exploreKinds
import io.legado.app.help.tvbox.TvBoxSite
import io.legado.app.model.tvbox.TvBoxManager
import io.legado.app.model.tvbox.TvBoxPluginSources
import io.legado.app.model.tvbox.TvBoxSourceDelegateImpl
import io.legado.app.model.tvbox.TvBoxSourceMapper
import io.legado.app.model.webBook.WebBook
import io.legado.desktop.TestNetwork
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertTrue
import org.junit.BeforeClass
import org.junit.Test
import java.io.File
import java.util.Collections
import java.util.concurrent.Callable
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executors
import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

class TvBoxSweepTest {

    private val configUrl = prop("legado.tvbox.sweep.configUrl", DEFAULT_CONFIG_URL)

    /** 已添加站点 key 子集 (空 = 配置里的全部站点)。 */
    private val siteFilter: Set<String> = prop("legado.tvbox.sweep.sites", "")
        .split(',').map { it.trim() }.filter { it.isNotEmpty() }.toSet()

    /** 并行度: 全链是网络等待, 默认 32。 */
    private val parallelism = prop("legado.tvbox.sweep.parallelism", "32").toInt()

    /** 单站点硬超时 (阻塞调用只能靠它止损)。 */
    private val perSiteTimeoutMs = prop("legado.tvbox.sweep.timeoutMs", "60000").toLong()

    /** 搜索关键词 (首个为主, 其余为无命中时的补试; 生态免费源关键词命中差异大)。 */
    private val searchKeys = prop("legado.tvbox.sweep.searchKeys", "爱,我的")
        .split(',').map { it.trim() }.filter { it.isNotEmpty() }

    private val outDir = File("build/test-ext").apply { mkdirs() }

    private val slug = configUrl.substringAfterLast('/').substringBefore('?')
        .substringBeforeLast('.').ifBlank { "config" }
        .let { if (siteFilter.isEmpty()) it else "$it-subset" }

    /** 取播链: 装载 → 取种子 → 详情 → 目录 → 取播 (含网页嗅探兜底)。 */
    @Test
    fun sweepPlayChain() {
        runSweep(
            tag = "TvBoxSweep",
            outFileName = "tvbox-sweep-$slug.jsonl",
            summaryFileName = "tvbox-sweep-$slug-summary.txt",
            byKindOf = { it.optBoolean("ok") },
        ) { site -> sweepOne(site) }
    }

    /** 搜索/发现/分类三维探测 (不取播): 定位「发现页/分类页不可用」的失败层。 */
    @Test
    fun sweepSearchExplore() {
        runSweep(
            tag = "TvBoxSweepSE",
            outFileName = "tvbox-sweep-$slug-se.jsonl",
            summaryFileName = "tvbox-sweep-$slug-se-summary.txt",
            byKindOf = { it.optInt("searchCount", -1) > 0 || it.optInt("popularCount", -1) > 0 ||
                it.optInt("classExploreCount", -1) > 0 },
        ) { site -> sweepSearchExploreOne(site) }
    }

    /**
     * 通用扫测骨架: 逐站并行跑 [work], 边跑边追加 jsonl, 超时/异常各记一行, 末尾打印汇总。
     *
     * [byKindOf] 从站点结果判定"该站可用"(两类扫测的可用定义不同), 仅用于汇总口径。
     */
    private fun runSweep(
        tag: String,
        outFileName: String,
        summaryFileName: String,
        byKindOf: (JSONObject) -> Boolean,
        work: (TvBoxSite) -> JSONObject,
    ) {
        TvBoxManager.init()
        val config = runBlocking { TvBoxManager.setConfigFromUrl(configUrl) }
        val sites = config.sites.filter { siteFilter.isEmpty() || it.key in siteFilter }
        assertTrue("配置无站点 (或子集过滤后为空): $configUrl", sites.isNotEmpty())
        // 生效参数先落一行: 命令行参数未转发时此处会暴露默认值 (闸门/参数转发失效的直接判据)
        println(
            "[$tag] PARAMS config=$configUrl sites=${sites.size}/" +
                "${config.sites.size} filter=${siteFilter.size} parallelism=$parallelism " +
                "timeoutMs=$perSiteTimeoutMs searchKeys=$searchKeys"
        )

        val outFile = File(outDir, outFileName).apply { writeText("") }
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
                        work(site)
                    } finally {
                        gate.release()
                    }
                    item.put("ms", System.currentTimeMillis() - begin)
                    results.add(item)
                    emit(outFile, item)
                    item
                })
            }
            for ((index, future) in futures.withIndex()) {
                val site = sites[index]
                try {
                    future.get(perSiteTimeoutMs, TimeUnit.MILLISECONDS)
                } catch (e: TimeoutException) {
                    results.add(
                        emit(
                            outFile,
                            baseItem(site)
                                .put("stage", "TIMEOUT")
                                .put("ok", false)
                                .put("detail", "单站点超过 ${perSiteTimeoutMs}ms (阻塞调用不响应取消)")
                                .put("ms", perSiteTimeoutMs),
                        ),
                    )
                } catch (e: ExecutionException) {
                    // 单站点抛出的探针自身异常 (如 OOM) 不得吃掉整轮扫测
                    results.add(
                        emit(
                            outFile,
                            baseItem(site)
                                .put("stage", "TASK_FAIL")
                                .put("ok", false)
                                .put("detail", describe(e.cause ?: e)),
                        ),
                    )
                }
            }
        } finally {
            pool.shutdownNow()
        }
        val byStage = results.groupBy { it.optString("stage") }
            .entries.sortedByDescending { it.value.size }
            .joinToString(" ") { "${it.key}=${it.value.size}" }
        val byKind = results.groupBy { it.optString("kind") }
            .entries.sortedBy { it.key }
            .joinToString(" ") { (k, v) -> "$k=${v.count(byKindOf)}/${v.size}" }
        val summary = "[$tag] SUMMARY config=$configUrl total=${sites.size} " +
            "usable=${results.count(byKindOf)} byKind{$byKind} byStage{$byStage} " +
            "elapsedMs=${System.currentTimeMillis() - started}"
        println(summary)
        File(outDir, summaryFileName).writeText(summary + "\n")
    }

    /**
     * 委派级真实用户路径: 搜索/首页取种子 → 详情 → 目录 → 取播。
     *
     * 为何不只探 spider: 委派层对非直链本就有嗅探兜底 (无 parses 时退化为直接嗅探播放页),
     * 只调 spider.playerContent 会把"能播"误判成 PLAY_PAGE。
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
            // 种子与真实用户路径一致: searchable 站点走搜索, 不可搜站点走首页/分类;
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

    /** 单站点结果落盘 (每站一行, 可随时读回; 异常不吞成静默失败)。 */
    private fun emit(outFile: File, item: JSONObject): JSONObject {
        val line = item.toString()
        synchronized(outFile) { outFile.appendText(line + "\n") }
        println("[TvBoxSweep] " + line)
        return item
    }

    /** 单个种子的完整委派链: 详情 → 目录 → 取播。 */
    private suspend fun playOne(bookSource: BookSource, seed: Seed): Attempt {
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

    /** 单站点搜索/发现/分类三维探测。 */
    private fun sweepSearchExploreOne(site: TvBoxSite): JSONObject {
        val item = baseItem(site).put("searchable", site.searchable)
        val bookSource = runBlocking {
            TvBoxPluginSources.sync(TvBoxManager.config!!)
            AppDbProviders.get().bookSourceDao.getBookSource(TvBoxSourceMapper.siteUrlOf(site.key))
        } ?: return item.put("stage", "NO_ROW")
        // 搜索面: searchable 站点走真实用户路径 (WebBook → 委派 → spider.searchContent);
        // 空结果换关键词补试, 区分「站点搜索坏」与「关键词在该站无命中」
        if (site.searchable && searchKeys.isNotEmpty()) {
            for ((i, key) in searchKeys.withIndex()) {
                val field = if (i == 0) "searchCount" else "searchCount${i + 1}"
                runCatching { runBlocking { WebBook.getBookListAwait(bookSource, key, 1) } }
                    .onSuccess { item.put(field, it.books.size) }
                    .onFailure { item.put(if (i == 0) "searchErr" else "searchErr${i + 1}", describe(it).take(200)) }
                if (item.optInt(field, -1) > 0) break
            }
        }
        // 发现面: 真实用户发现页入口 "推荐"(popular) → homeVideoContent/homeContent
        runCatching { runBlocking { TvBoxSourceDelegateImpl.getExploreAwait(bookSource, "popular", 1) } }
            .onSuccess { item.put("popularCount", it.books.size) }
            .onFailure { item.put("popularErr", describe(it).take(200)) }
        // 分类面·app 层: exploreKinds() 走取数委派惰性枚举 + 既有磁盘缓存 (与发现页同源)
        runCatching { runBlocking { bookSource.exploreKinds() } }
            .onSuccess { kinds ->
                item.put("kindCount", kinds.size)
                val classKind = kinds.firstOrNull { !it.url.isNullOrBlank() && it.url != "popular" }
                if (classKind != null) {
                    runCatching {
                        runBlocking { TvBoxSourceDelegateImpl.getExploreAwait(bookSource, classKind.url!!, 1) }
                    }
                        .onSuccess { item.put("classExploreCount", it.books.size) }
                        .onFailure { item.put("classExploreErr", describe(it).take(200)) }
                }
            }
            .onFailure { item.put("kindErr", describe(it).take(200)) }
        // 分类面: spider 层分类数与首个分类页条目数 (定位「有分类无推荐」的站点)
        runCatching {
            val spider = runBlocking {
                TvBoxManager.spiderFor(site, TvBoxManager.config?.spider.orEmpty()).second
            }
            val home = JSONObject(spider.homeContent(true))
            val classes = home.optJSONArray("class") ?: JSONArray()
            item.put("classCount", classes.length())
            val tid = classes.optJSONObject(0)?.optString("type_id")?.trim().orEmpty()
            if (tid.isNotEmpty()) {
                val list = runCatching {
                    JSONObject(spider.categoryContent(tid, "1", false, HashMap())).optJSONArray("list")
                }.getOrNull()
                item.put("firstClassCount", list?.length() ?: 0)
            }
        }.onFailure { item.put("classErr", describe(it).take(200)) }
        return item
    }

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
    private suspend fun seedBookUrls(bookSource: BookSource, site: TvBoxSite): List<Seed> {
        if (site.searchable && searchKeys.isNotEmpty()) {
            for (key in searchKeys) {
                val page = runCatching { WebBook.getBookListAwait(bookSource, key, 1) }.getOrNull()
                val seeds = page?.books.orEmpty()
                    .filter { !it.bookUrl.contains("::") }
                    .map { Seed(it.name, it.bookUrl) }
                if (seeds.isNotEmpty()) return seeds.take(5)
            }
        }
        val spider = TvBoxManager.spiderFor(site, TvBoxManager.config?.spider.orEmpty()).second
        val home = runCatching { JSONObject(spider.homeContent(true)) }.getOrElse { JSONObject() }
        val seeds = ArrayList<Seed>()
        seeds.addAll(seedsOf(home.optJSONArray("list"), site.key))
        // 首页无 list 的站点真实用户路径是发现页分类取数, 故先补走委派 getExploreAwait, 再退直接扫分类页。
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
    private fun seedsOf(list: JSONArray?, siteKey: String): List<Seed> {
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

        /** 默认扫 gao/js.json (298 站, JS 与 jar 混合的生态大盘)。 */
        private const val DEFAULT_CONFIG_URL =
            "https://raw.githubusercontent.com/gaotianliuyun/gao/master/js.json"

        private fun prop(key: String, default: String): String =
            System.getProperty(key)?.takeIf { it.isNotBlank() } ?: default

        @JvmStatic
        @BeforeClass
        fun bootDesktopRuntime() {
            TestNetwork.requireSamples("TVBox 扫测需联网拉取配置与 spider 产物")
            TvBoxTestRuntime.bootstrap()
        }
    }
}
