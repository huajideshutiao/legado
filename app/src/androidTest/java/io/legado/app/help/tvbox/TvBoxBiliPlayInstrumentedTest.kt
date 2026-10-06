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
import io.legado.app.help.exoplayer.ExoPlayerHelper
import io.legado.app.model.tvbox.TvBoxManager
import io.legado.app.model.tvbox.TvBoxPluginSources
import io.legado.app.model.tvbox.TvBoxSourceDelegateImpl
import io.legado.app.model.tvbox.TvBoxSourceMapper
import io.legado.app.model.webBook.WebBook
import io.legado.app.ui.book.video.parseVideoSource
import kotlinx.coroutines.runBlocking
import okhttp3.Request
import org.json.JSONObject
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Bili(哔哔合集)根因闭环排查 (真机排查用, 非回归断言):
 *
 * 全站 sweep 已实锤: playerContent 正常, 本地代理 MPD 正常, 播放失败=
 * ①mime 推断层无 DASH 路由 (无后缀 proxy 地址按 progressive 容器嗅探, 3003);
 * ②显式 MPD 后分片 403 (manifest 与分片不同 origin, headers 按 origin 学习不继承)。
 * 本测试补三块证据: playerContent 全字段 (查 format 字段)、MPD 原文 (BaseURL 形态)、
 * 分片带头/不带头 HTTP 探测 (验证 403 根因)。
 */
@RunWith(AndroidJUnit4::class)
class TvBoxBiliPlayInstrumentedTest {

    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    @Test
    fun biliFullChainToExoPlayer() = runBlocking {
        TvBoxManager.init()
        val config = TvBoxManager.setConfigFromUrl(CONFIG_URL)
        val site = config.sites.firstOrNull { it.key == SITE_KEY }
            ?: error("fty.json 无 $SITE_KEY 站点")
        val spider = TvBoxManager.spiderFor(site, config.spider).second

        // 搜索 → 委派链取播 (与全站 sweep 同口径)
        var vodId = ""
        for (kw in SEARCH_KEYS) {
            val list = runCatching {
                JSONObject(spider.searchContent(kw, false)).optJSONArray("list")
            }.getOrNull()
            val id = list?.optJSONObject(0)?.optString("vod_id").orEmpty()
            if (id.isNotBlank()) { vodId = id; break }
        }
        check(vodId.isNotBlank()) { "搜索无种子" }

        TvBoxPluginSources.sync(config)
        val bookSource = AppDbProviders.get().bookSourceDao
            .getBookSource(TvBoxSourceMapper.siteUrlOf(site.key))
            ?: error("虚拟书源行未落库")
        val page = WebBook.getBookListAwait(bookSource, SEARCH_KEYS.first(), 1)
        val hit = page.books.firstOrNull { !it.bookUrl.contains("::") } ?: error("委派搜索无条目")
        val book = Book().apply {
            bookUrl = hit.bookUrl; name = hit.name; origin = bookSource.bookSourceUrl; type = BookType.video
        }
        val info = TvBoxSourceDelegateImpl.getBookInfoAwait(bookSource, book, canReName = true)
        val chapters = TvBoxSourceDelegateImpl.getChapterListAwait(bookSource, info).getOrThrow()
        var content: String? = null
        for (chapter in chapters.filter { !it.isVolume }.take(3)) {
            content = runCatching { TvBoxSourceDelegateImpl.getContentAwait(bookSource, info, chapter) }
                .getOrNull()
            if (!content.isNullOrBlank() && content.contains("://")) break
        }
        val contentStr = content ?: error("取播失败")

        // spider 层 playerContent 全字段 (查 format/ clarity 等未透传字段)
        val detail = JSONObject(spider.detailContent(listOf(vodId)))
        val vod = detail.optJSONArray("list")?.optJSONObject(0)
        val playFrom = vod?.optString("vod_play_from").orEmpty().trim()
        val episodeId = vod?.optString("vod_play_url").orEmpty().trim()
            .split("$$$").firstOrNull()?.split("#")?.firstOrNull { it.contains('$') }
            ?.substringAfter('$', "")?.trim().orEmpty()
        if (playFrom.isNotBlank() && episodeId.isNotBlank()) {
            val p = runCatching { JSONObject(spider.playerContent(playFrom, episodeId, emptyList())) }
                .getOrDefault(JSONObject())
            println("[BiliProbe2] playerContent keys=" + p.keys().asSequence().toList())
            for (k in p.keys()) {
                if (k != "url" && k != "header") println("[BiliProbe2]   $k = " + p.opt(k))
            }
        }

        // 真实解析 (parseVideoSource, 修复上轮整串当 URL 的取法)
        val res = parseVideoSource(contentStr)?.getResolution()
            ?: error("VideoSource 解析失败: " + contentStr.take(200))
        val url = res.url
        val headers = res.headers
        println("[BiliProbe2] playUrl=" + url)
        println("[BiliProbe2] resolutionHeaders=$headers")

        // MPD 原文探测 (带 resolution headers)
        fetchAndPrint(url, headers, "MPD带源站头")
        // MPD 内 BaseURL 提取与分片探测: 带头 vs 不带头
        val body = runCatching { httpGet(url, headers) }.getOrDefault("")
        val regex = Regex("<BaseURL>(.*?)</BaseURL>", RegexOption.DOT_MATCHES_ALL)
        val baseURLs = regex.findAll(body)
            .map { it.groupValues[1].trim().replace("&amp;", "&") }.toList()
        println("[BiliProbe2] MPD BaseURLs(反转义)=" + baseURLs.size + " 首个=" + baseURLs.firstOrNull()?.take(120))
        baseURLs.firstOrNull()?.let { seg ->
            fetchStatus(seg, headers, "分片带源站头")
            fetchStatus(seg, emptyMap(), "分片裸请求")
            fetchStatus(seg, mapOf("User-Agent" to CHROME_UA), "分片仅UA")
            fetchStatus(seg, mapOf("User-Agent" to CHROME_UA, "Referer" to "https://www.bilibili.com/"), "分片UA+Referer")
        }

        // ExoPlayer 真播: A=默认 (真实用户路径) / B=显式 MPD
        println("[BiliProbe2] 场景A(默认) => " + playWithExo(url, headers, null))
        println("[BiliProbe2] 场景B(显式MPD) => " + playWithExo(url, headers, MimeTypes.APPLICATION_MPD))
        println("[BiliProbe2] DONE")
    }

    private fun httpGet(url: String, headers: Map<String, String>): String {
        val req = Request.Builder().url(url).apply {
            headers.forEach { (k, v) -> header(k, v) }
            header("User-Agent", headers["User-Agent"] ?: CHROME_UA)
        }.build()
        com.github.catvod.net.OkHttp.client().newCall(req).execute().use { resp ->
            val text = resp.body?.string().orEmpty()
            println("[BiliProbe2] GET $url -> ${resp.code} ${resp.header("Content-Type")}")
            return text
        }
    }

    private fun fetchAndPrint(url: String, headers: Map<String, String>, tag: String) {
        runCatching {
            val body = httpGet(url, headers)
            println("[BiliProbe2] [$tag] body head=" + body.take(500).replace("\n", "\\n"))
        }.onFailure { println("[BiliProbe2] [$tag] 失败: ${it.message}") }
    }

    private fun fetchStatus(url: String, headers: Map<String, String>, tag: String) {
        runCatching {
            val req = Request.Builder().url(url)
                .header("Range", "bytes=0-1023")
                .apply { headers.forEach { (k, v) -> header(k, v) } }
                .build()
            com.github.catvod.net.OkHttp.client().newCall(req).execute().use { resp ->
                println("[BiliProbe2] [$tag] -> ${resp.code} ${resp.header("Content-Type")}")
            }
        }.onFailure { println("[BiliProbe2] [$tag] 异常: ${it.message}") }
    }

    private fun playWithExo(
        url: String,
        headers: Map<String, String>,
        explicitMime: String?,
    ): String {
        val thread = HandlerThread("bili2-exo").apply { start() }
        val handler = Handler(thread.looper)
        val latch = CountDownLatch(1)
        var outcome = "TIMEOUT(60s)"
        var player: Player? = null
        handler.post {
            try {
                val p = ExoPlayerHelper.createHttpExoPlayer(context)
                player = p
                p.addListener(object : Player.Listener {
                    override fun onPlaybackStateChanged(playbackState: Int) {
                        if (playbackState == Player.STATE_READY) {
                            outcome = "READY"
                            latch.countDown()
                        }
                    }
                    override fun onPlayerError(error: PlaybackException) {
                        val cause = generateSequence<Throwable>(error) { it.cause }.drop(1).firstOrNull()
                        outcome = "ERROR code=${error.errorCode}(${error.errorCodeName})" +
                            (cause?.let { " " + it::class.simpleName + ": " + it.message?.take(200) } ?: "")
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
        latch.await(60, TimeUnit.SECONDS)
        handler.post { player?.release() }
        Thread.sleep(300)
        thread.quitSafely()
        return outcome
    }

    companion object {
        private const val CONFIG_URL =
            "https://gh-proxy.com/https://raw.githubusercontent.com/qist/tvbox/master/fty.json"
        private const val SITE_KEY = "Bili"
        private val SEARCH_KEYS = listOf("三体", "我的三体", "罗小黑", "中国奇谭")
        private const val CHROME_UA =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/117.0.0.0 Safari/537.36"
    }
}
