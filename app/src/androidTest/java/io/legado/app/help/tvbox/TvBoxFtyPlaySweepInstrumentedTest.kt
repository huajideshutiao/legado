package io.legado.app.help.tvbox

import android.os.Handler
import android.os.HandlerThread
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.legado.app.constant.BookType
import io.legado.app.data.AppDbProviders
import io.legado.app.data.entities.Book
import io.legado.app.data.entities.BookSource
import io.legado.app.data.entities.VideoSource
import io.legado.app.help.exoplayer.ExoPlayerHelper
import io.legado.app.model.analyzeRule.AnalyzeUrl
import io.legado.app.model.tvbox.TvBoxManager
import io.legado.app.model.tvbox.TvBoxPluginSources
import io.legado.app.model.tvbox.TvBoxSourceDelegateImpl
import io.legado.app.model.tvbox.TvBoxSourceMapper
import io.legado.app.model.webBook.WebBook
import io.legado.app.ui.book.video.parseVideoSource
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.Collections
import java.util.concurrent.Callable
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

/**
 * fty.json 全站逐源 ExoPlayer 真播排查 (真机排查用, 非回归断言):
 *
 * 用户要求判定口径=ExoPlayer 正常工作 (STATE_READY), 不止"解析出 url"。每站点:
 * 委派链 (种子→详情→目录→取播) → [parseVideoSource] 真实用户解析 → ExoPlayer 真播:
 * - 场景A: createMediaItem 默认 (真实用户路径, mime 按 URL 形态推断)
 * - 场景B: A 失败后显式 APPLICATION_MPD (DASH manifest 正确路由对照)
 * - 场景C: B 失败后按 [ExoPlayerHelper.retryMimeType](A 的错误码) 换档 (对齐播放页重试链)
 * 结果边跑边落 filesDir/tvbox/sweep_play.jsonl, 可 adb run-as 取回。
 *
 * 并行 4 (低配设备 ExoPlayer 并存承受度), 单站点硬超时 90s (阻塞调用不响应协程取消,
 * 线程级 Future.get 超时, 与 TvBoxFtySweepInstrumentedTest 同款约束)。
 */
@RunWith(AndroidJUnit4::class)
class TvBoxFtyPlaySweepInstrumentedTest {

    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    private val perSiteTimeoutMs = 90_000L
    private val parallelism = 4

    @Test
    fun sweepFtyExoPlayerPlay() {
        TvBoxManager.init()
        val config = runBlocking { TvBoxManager.setConfigFromUrl(CONFIG_URL) }
        check(config.sites.isNotEmpty()) { "fty.json 无站点" }

        val outFile = File(context.filesDir, "tvbox/sweep_play.jsonl").apply {
            parentFile?.mkdirs(); writeText("")
        }
        val sites = config.sites
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
                    val line = item.toString()
                    synchronized(outFile) { outFile.appendText(line + "\n") }
                    println("[FtyPlay] " + line)
                    item
                })
            }
            for ((index, future) in futures.withIndex()) {
                try {
                    future.get(perSiteTimeoutMs, TimeUnit.MILLISECONDS)
                } catch (e: TimeoutException) {
                    val line = JSONObject().put("key", sites[index].key)
                        .put("stage", "TIMEOUT").put("ok", false)
                        .put("detail", "单站点超过 ${perSiteTimeoutMs}ms").toString()
                    synchronized(outFile) { outFile.appendText(line + "\n") }
                    println("[FtyPlay] " + line)
                }
            }
        } finally {
            pool.shutdownNow()
        }
        val lines = outFile.readLines().mapNotNull { runCatching { JSONObject(it) }.getOrNull() }
        val total = lines.size
        val readyA = lines.count { it.optString("resultA").startsWith("READY") }
        val readyB = lines.count { it.optString("resultA").startsWith("READY") || it.optString("resultB").startsWith("READY") }
        val readyAny = lines.count { it.optBoolean("ok") }
        println("[FtyPlay] SUMMARY total=$total readyA=$readyA readyAOrB=$readyB readyAny=$readyAny " +
            "elapsedMs=${System.currentTimeMillis() - started}")
    }

    private fun sweepOne(site: TvBoxSite): JSONObject {
        val item = JSONObject().put("key", site.key).put("name", site.name)
            .put("api", site.api)
        var stage = "INIT"
        try {
            val bookSource = runBlocking {
                TvBoxPluginSources.sync(TvBoxManager.config!!)
                AppDbProviders.get().bookSourceDao.getBookSource(TvBoxSourceMapper.siteUrlOf(site.key))
            } ?: return item.put("stage", "NO_ROW").put("ok", false)
            stage = "SEED"
            val seeds = runBlocking { seedBookUrls(bookSource, site) }
            if (seeds.isEmpty()) return item.put("stage", "NO_SEED").put("ok", false)
            stage = "DETAIL"
            var last = "无可用种子链路"
            for (seed in seeds) {
                val attempt = runBlocking { playOne(bookSource, seed) }
                if (attempt.first == "PLAY") {
                    return item.put("stage", "PLAY").put("ok", attempt.second.optBoolean("ok"))
                        .put("form", attempt.second.optString("form"))
                        .put("url", attempt.second.optString("url").take(160))
                        .put("resultA", attempt.second.optString("resultA"))
                        .put("resultB", attempt.second.optString("resultB"))
                        .put("resultC", attempt.second.optString("resultC"))
                        .put("seeds", seeds.size)
                }
                last = attempt.first + ": " + attempt.second
            }
            return item.put("stage", stage).put("ok", false).put("detail", last.take(300))
        } catch (e: Throwable) {
            return item.put("stage", stage + "_FAIL").put("ok", false).put("detail", describe(e).take(300))
        }
    }

    /** 单种子: 详情→目录→取播→真实解析→ExoPlayer 两/三场景真播。返回 (终态, 详情JSON)。 */
    private suspend fun playOne(
        bookSource: BookSource,
        seed: Seed,
    ): Pair<String, JSONObject> {
        val book = Book().apply {
            bookUrl = seed.bookUrl; name = seed.name
            origin = bookSource.bookSourceUrl; type = BookType.video
        }
        val info = runCatching {
            TvBoxSourceDelegateImpl.getBookInfoAwait(bookSource, book, canReName = true)
        }.getOrElse { return "DETAIL_FAIL" to JSONObject().put("detail", describe(it)) }
        if (info.name.isBlank()) return "DETAIL_EMPTY" to JSONObject()
        val chapters = runCatching {
            TvBoxSourceDelegateImpl.getChapterListAwait(bookSource, info).getOrThrow()
        }.getOrElse { return "TOC_FAIL" to JSONObject().put("detail", describe(it)) }
        if (chapters.isEmpty()) return "TOC_EMPTY" to JSONObject()
        var content: String? = null
        for (chapter in chapters.filter { !it.isVolume }.take(3)) {
            content = runCatching {
                TvBoxSourceDelegateImpl.getContentAwait(bookSource, info, chapter)
            }.getOrNull()
            if (!content.isNullOrBlank() && content.contains("://")) break
        }
        if (content.isNullOrBlank() || !content.contains("://")) {
            return "CONTENT_FAIL" to JSONObject().put("detail", content?.take(200) ?: "空内容")
        }
        // 真实用户解析: parseVideoSource (JSON/行式) → 默认档; 未命中则整串按单 URL (AnalyzeUrl)
        val parsed: Triple<String, Map<String, String>, String>? = runCatching {
            parseVideoSource(content)?.getResolution()?.let {
                Triple(it.url, it.headers, "json")
            } ?: run {
                val line = content.split("\n").first().substringAfter("::")
                val a = AnalyzeUrl(line)
                Triple(a.url, a.headerMap, "line")
            }
        }.getOrNull()
        if (parsed == null || !parsed.first.startsWith("http")) {
            return "PLAY_NO_URL" to JSONObject().put("detail", content.take(200))
        }
        val (url, headers, form) = parsed
        val out = JSONObject().put("url", url).put("form", form)
            .put("headers", headers.keys.joinToString(","))
        // 场景A: 真实用户路径
        val a = playWithExo(url, headers, null)
        out.put("resultA", a)
        if (a.startsWith("READY")) return "PLAY" to out.put("ok", true)
        // 场景B: 显式 DASH
        val b = playWithExo(url, headers, MimeTypes.APPLICATION_MPD)
        out.put("resultB", b)
        if (b.startsWith("READY")) return "PLAY" to out.put("ok", true)
        // 场景C: 对齐播放页 retryMimeType 换档 (按场景A错误码)
        val codeA = Regex("code=(-?\\d+)").find(a)?.groupValues?.getOrNull(1)?.toIntOrNull() ?: -1
        val mimeC = ExoPlayerHelper.retryMimeType(codeA)
        if (mimeC != null) {
            val c = playWithExo(url, headers, mimeC)
            out.put("resultC", c + " mime=" + mimeC)
            if (c.startsWith("READY")) return "PLAY" to out.put("ok", true)
        }
        return "PLAY" to out.put("ok", false)
    }

    /** 种子 (最多 4 条): searchable 走搜索, 否则发现/分类 (与 TvBoxFtySweepInstrumentedTest 同款)。 */
    private suspend fun seedBookUrls(
        bookSource: BookSource,
        site: TvBoxSite,
    ): List<Seed> {
        if (site.searchable) {
            val page = runCatching { WebBook.getBookListAwait(bookSource, SEARCH_KEY, 1) }.getOrNull()
            val seeds = page?.books.orEmpty()
                .filter { !it.bookUrl.contains("::") && !it.bookUrl.contains("tvbox://") }
                .map { Seed(it.name, it.bookUrl) }
            if (seeds.isNotEmpty()) return seeds.take(4)
        }
        val spider = TvBoxManager.spiderFor(site, TvBoxManager.config?.spider.orEmpty()).second
        val home = runCatching { JSONObject(spider.homeContent(true)) }.getOrElse { JSONObject() }
        val seeds = ArrayList<Seed>()
        seedsOf(home.optJSONArray("list"), site.key)?.let { seeds.addAll(it) }
        if (seeds.isEmpty()) {
            runCatching { TvBoxSourceDelegateImpl.getExploreAwait(bookSource, "popular", 1) }
                .getOrNull()?.books.orEmpty()
                .filter { !it.bookUrl.contains("::") && !it.bookUrl.contains("tvbox://") }
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
                seeds.addAll(seedsOf(list, site.key) ?: emptyList())
                if (seeds.size >= 4) break
            }
        }
        return seeds.take(4)
    }

    private fun seedsOf(list: org.json.JSONArray?, siteKey: String): List<Seed>? {
        if (list == null) return null
        val seeds = ArrayList<Seed>()
        for (i in 0 until list.length()) {
            val obj = list.optJSONObject(i) ?: continue
            val id = obj.optString("vod_id").ifBlank { obj.optString("id") }.trim()
            if (id.isEmpty()) continue
            val url = TvBoxSourceMapper.bookUrlOf(siteKey, id)
            if (url.contains("::") || url.contains("tvbox://")) continue
            seeds.add(Seed(obj.optString("vod_name").trim(), url))
        }
        return seeds
    }

    private data class Seed(val name: String, val bookUrl: String)

    /** ExoPlayer 真播到终态 (READY/ERROR/超时), 返回诊断串。 */
    private fun playWithExo(
        url: String,
        headers: Map<String, String>,
        explicitMime: String?,
    ): String {
        val thread = HandlerThread("sweep-exo").apply { start() }
        val handler = Handler(thread.looper)
        val latch = CountDownLatch(1)
        var outcome = "TIMEOUT(45s)"
        val begin = System.currentTimeMillis()
        var player: Player? = null
        handler.post {
            try {
                val p = ExoPlayerHelper.createHttpExoPlayer(context)
                player = p
                p.addListener(object : Player.Listener {
                    override fun onPlaybackStateChanged(playbackState: Int) {
                        if (playbackState == Player.STATE_READY) {
                            outcome = "READY in ${System.currentTimeMillis() - begin}ms"
                            latch.countDown()
                        }
                    }
                    override fun onPlayerError(error: PlaybackException) {
                        val cause = generateSequence<Throwable>(error) { it.cause }.drop(1).firstOrNull()
                        outcome = "ERROR code=${error.errorCode}(${error.errorCodeName})" +
                            (cause?.let { " " + it::class.simpleName + ": " + it.message?.take(160) } ?: "")
                        latch.countDown()
                    }
                })
                p.setMediaItem(ExoPlayerHelper.createMediaItem(url, headers, explicitMime))
                p.prepare()
                p.play()
            } catch (t: Throwable) {
                outcome = "SETUP_FAIL ${t::class.simpleName}: ${t.message}"
                latch.countDown()
            }
        }
        latch.await(45, TimeUnit.SECONDS)
        handler.post { player?.release() }
        Thread.sleep(250)
        thread.quitSafely()
        return outcome
    }

    /** 失败诊断: 根因类名+消息+栈首帧。 */
    private fun describe(t: Throwable): String {
        val root = generateSequence(t) { it.cause }.last()
        val frame = root.stackTrace.firstOrNull()
            ?.let { it.className.substringAfterLast('.') + "." + it.methodName + ":" + it.lineNumber }
        return t::class.simpleName + " / " + root::class.simpleName + ": " + root.message + " @ " + frame
    }

    companion object {
        private const val CONFIG_URL =
            "https://gh-proxy.com/https://raw.githubusercontent.com/qist/tvbox/master/fty.json"
        private const val SEARCH_KEY = "爱"
    }
}
