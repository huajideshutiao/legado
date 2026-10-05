package io.legado.app.help.tvbox

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.legado.app.constant.BookSourceType
import io.legado.app.constant.BookType
import io.legado.app.data.AppDbProviders
import io.legado.app.data.entities.Book
import io.legado.app.data.entities.BookSource
import io.legado.app.model.analyzeRule.AnalyzeUrl
import io.legado.app.model.tvbox.TvBoxManager
import io.legado.app.model.tvbox.TvBoxPluginSources
import io.legado.app.model.tvbox.TvBoxSourceDelegateImpl
import io.legado.app.model.tvbox.TvBoxSourceMapper
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import okhttp3.Request
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * TVBox parse=1 (网页嗅探) 的真机闭环验证。
 *
 * 两条入口, 分别验 sniffer 本体与取播链路的端到端接线:
 * - [sniffParsePage_realSniffedVideo]: 解析站 URL (`jxs + 播放页`) → WebView 加载 + 请求拦截
 *   → 嗅出真实 m3u8/mp4 → 断言可被 okhttp 拉到头字节 (m3u8 必须回 #EXTM3U 播放列表)。
 * - [delegateChain_parseSiteToRealVideo]: 走委派取播全链路 (虚拟书源行 → 搜索/详情/目录 →
 *   getContentAwait), 内容串经 AnalyzeUrl 解释后同样是那个可播地址。
 *
 * 社区站点/解析站时效性强: 候选按序扫描, 首个全通过者即判成功; 全失败才断言失败并输出明细。
 * 不及格 Criterion 一律 hoy NO: 第三方站点漂移导致的失败如实报出, 不伪造通过。
 */
@RunWith(AndroidJUnit4::class)
class TvBoxSnifferInstrumentedTest {

    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext

    /** 解析站候选 (jxs): 后面拼上播放页就是一个待嗅探的解析页。可用性漂移很快, 逐个尝试。 */
    private val candidateJxs = listOf(
        "https://jx.xmflv.com/?url=",
        "https://www.playm3u8.cn/jiexi.php?url=",
        "https://jx.m3u8.tv/jiexi/?url=",
        "https://jx.m3u8.pw/?url=",
        "https://jx.xyflv.cc/?url=",
        "https://jx.yparse.com/index.php?url=",
        "https://jx.aidouer.net/?url=",
        "https://www.8090.la/8090/?url=",
    )

    /** 喂给解析站的公开会员视频播放页样本 (VIP 线才需要 parse, 免费片源没有解析需求)。 */
    private val candidatePlayPages = listOf(
        "https://v.qq.com/x/cover/mzc00200v4pnq4n/x0044wzcpcj.html",
        "https://www.iqiyi.com/v_19rr7nb0x0.html",
        "https://v.youku.com/v_show/id_XNTkzNjc5MTQ4.html",
    )

    /** 可选站点: 会员/VIP 线路为主, 它们的 playerContent 更可能回 parse=1。 */
    private val preferredSiteKeys = listOf(
        "csp_Xinshijue",
        "csp_Gaoqing",
        "csp_Bdys01",
        "csp_Moli",
        "csp_PikaSo",
        "csp_Star",
        "csp_TTian",
        "csp_Jianpian",
        "csp_Xpanpan",
        "csp_Ppxzy",
        "csp_Dm84",
    )

    /** 拉一个可达的远程配置; 失败明细随消息输出。 */
    private suspend fun loadConfig(): TvBoxConfig {
        TvBoxManager.init()
        val failures = ArrayList<String>()
        for (url in CONFIG_URLS) {
            val config = try {
                TvBoxManager.setConfigFromUrl(url)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                failures += "$url: ${e.message}"
                continue
            }
            if (config.sites.isEmpty()) {
                failures += "$url: sites 为空"
                continue
            }
            println(
                "[TvBoxSniffTest] config ok url=$url sites=${config.sites.size} " +
                    "parses=${config.parses.size} webParse=${config.parses.count { it.isWebSniff }}",
            )
            return config
        }
        error("配置拉取全部失败:\n" + failures.joinToString("\n"))
    }

    /**
     * 用例一: 解析页 → 嗅探 → 真实视频地址可拉到头字节。
     *
     * 解析页候选先用配置自带的 type=0 jxs (生态真实ijd 息源), 不足再补内置候选;
     * 播放页样本同样按序尝试 —— 任一 (解析站, 播放页) 组合通过即判成功。
     */
    @Test
    fun sniffParsePage_realSniffedVideo() = runBlocking {
        val config = runCatching { loadConfig() }.getOrNull()
        val jxsList = ((config?.parses?.filter { it.isWebSniff }?.map { it.url } ?: emptyList()) + candidateJxs)
            .distinct()
        println("[TvBoxSniffTest] jxs candidates=${jxsList.size}")
        val failures = ArrayList<String>()
        for (page in candidatePlayPages) {
            for (jxs in jxsList) {
                val parsePage = jxs + page
                try {
                    val sniffed = TvBoxSniffer.sniff(parsePage, timeoutMs = SNIFF_TIMEOUT_MS)
                    println("[TvBoxSniffTest] sniff ok jxs=$jxs -> ${sniffed.url.take(180)}")
                    assertPlayable(sniffed)
                    return@runBlocking
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Throwable) {
                    failures += "$jxs -> ${describe(e)}"
                }
            }
        }
        error(
            "解析页全候选嗅探失败 (播放页候选 ${candidatePlayPages.size} × 解析站 ${jxsList.size}):\n" +
                failures.joinToString("\n"),
        )
    }

    /**
     * 用例二: 委派取播全链路 —— 找到一个 playerContent 回 parse=1 (或 .html 播放页) 的站点,
     * 走到取播, 断言内容串经 AnalyzeUrl 解释后可播。
     */
    @Test
    fun delegateChain_parseSiteToRealVideo() = runBlocking {
        val config = loadConfig()
        val failures = ArrayList<String>()
        val sources = preferredSiteKeys.mapNotNull { key -> config.sites.firstOrNull { it.key == key } }
        check(sources.isNotEmpty()) {
            "配置里无优选站点, 可用 key=" + config.sites.take(20).joinToString(",") { it.key }
        }
        for (site in sources) {
            // importedRowOf 是 suspend (dao 查询), 需包进 suspend 的 withContext 才能在
            // 非 suspend 的 runCatching lambda 里调用 (外层已是 runBlocking 的协程)。
            val source = runCatching {
                kotlinx.coroutines.runBlocking(kotlinx.coroutines.Dispatchers.IO) {
                    TvBoxPluginSources.sync(config)
                    importedRowOf(site)
                }
            }.getOrNull()
            if (source == null) {
                failures += "${site.key}: 虚拟书源行未落库"
                continue
            }
            try {
                val content = driveDelegateToContent(source)
                println(
                    "[TvBoxSniffTest] delegate sniff-chain ok source=${source.bookSourceUrl} " +
                        "content=${content.take(200)}",
                )
                assertContentPlayable(content)
                return@runBlocking
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                failures += "${site.key}(${site.api}): ${describe(e)}"
            }
        }
        error("无优选站点走通 parse=1 嗅探全链路:\n" + failures.joinToString("\n"))
    }

    /**
     * 用例三: type=1 (json API) 解析站真机验证 —— 直接调 [TvBoxSniffer.parseJsonApi]。
     * 配置里真实的 type=1 解析站 (如 巧技/巧技二) 逐条对播放页样本请求, 首个返回有效直链即判成功。
     * 站点漂移/失效时如实报出, 不伪造通过。
     */
    @Test
    fun jsonParseApi_realStation() = runBlocking {
        val config = loadConfig()
        val jsonParses = config.parses.filter { it.isJsonApi }
        check(jsonParses.isNotEmpty()) {
            "配置里无 type=1 json 解析站 (parses=${config.parses.size})"
        }
        println("[TvBoxJsonTest] json api parses=${jsonParses.joinToString { "${it.name}(type=${it.type})" }}")
        val failures = ArrayList<String>()
        for (parse in jsonParses) {
            for (page in candidatePlayPages) {
                try {
                    val result = TvBoxSniffer.parseJsonApi(parse, page)
                    println("[TvBoxJsonTest] json ok ${parse.name} -> ${result.url.take(150)}")
                    assertPlayable(result)
                    return@runBlocking
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Throwable) {
                    failures += "${parse.name} + $page: ${describe(e)}"
                }
            }
        }
        error("type=1 json 解析站全候选失败:\n" + failures.joinToString("\n"))
    }

    /** 搜索 → 详情 → 目录 → 逐集取播, 回首个非空内容串。 */
    private suspend fun driveDelegateToContent(source: BookSource): String {
        val page = TvBoxSourceDelegateImpl.getBookListAwait(source, "爱", 1)
        val hit = page.books.firstOrNull()
            ?: error("站点无搜索结果: ${source.bookSourceUrl}")
        val book = Book().apply {
            bookUrl = hit.bookUrl
            origin = hit.origin
            name = hit.name
            type = BookType.video
        }
        val info = TvBoxSourceDelegateImpl.getBookInfoAwait(source, book, canReName = true)
        val chapters = TvBoxSourceDelegateImpl.getChapterListAwait(source, info).getOrThrow()
        val failures = ArrayList<String>()
        for (chapter in chapters.take(4)) {
            val content = runCatching {
                TvBoxSourceDelegateImpl.getContentAwait(source, info, chapter)
            }.onFailure { failures += "${chapter.title}: ${describe(it)}" }.getOrNull() ?: continue
            if (content.contains("://")) return content
        }
        error("前 ${minOf(4, chapters.size)} 集均未取到内容: ${source.bookSourceUrl}\n" + failures.joinToString("\n"))
    }

    /** 断言嗅到的真实媒体地址可拉: HTTP 成功 + 头字节非空; m3u8 必须回 #EXTM3U。 */
    private fun assertPlayable(sniffed: TvBoxSniffResult) {
        val url = sniffed.url
        assertTrue("嗅探结果非 http(s): $url", url.startsWith("http"))
        assertTrue("嗅探结果不符合视频地址形态: $url", isVideoUrl(url))
        val request = Request.Builder().url(url).apply {
            for ((key, value) in sniffed.headers) {
                if (value.isBlank()) continue
                header(key, value)
            }
        }.build()
        com.github.catvod.net.OkHttp.client().newCall(request).execute().use { resp ->
            assertTrue("嗅探地址 HTTP ${resp.code}: $url", resp.isSuccessful)
            val head = readHead(resp)
            assertTrue("嗅探地址头部字节为空: $url", head.second > 0)
            val text = String(head.first, 0, head.second, Charsets.UTF_8)
            assertPlaylistOrBytes(url, text, head.second)
            println(
                "[TvBoxSniffTest] playable ok http=${resp.code} url=${url.take(120)} " +
                    "firstLine=${text.lineSequence().firstOrNull()?.take(80)}",
            )
        }
    }

    /** 断言委派出的内容串经 AnalyzeUrl 解释 (播放器取数同款入口) 后可播。 */
    private fun assertContentPlayable(content: String) {
        val firstLine = content.lineSequence().first()
        // 多线路内容串形如 `线路名::内容`, 取内容段
        val entry = firstLine.substringAfter("::", firstLine)
        val analyzeUrl = AnalyzeUrl(entry)
        assertTrue("内容串未解析出可播地址: ${analyzeUrl.url}", analyzeUrl.url.contains("://"))
        assertTrue(
            "内容串 headers 未合入 headerMap (防盗链头会丢): ${analyzeUrl.headerMap}",
            !entry.contains("{\"headers\"") || analyzeUrl.headerMap.isNotEmpty(),
        )
        val request = Request.Builder().url(analyzeUrl.url).apply {
            for ((key, value) in analyzeUrl.headerMap) header(key, value)
        }.build()
        com.github.catvod.net.OkHttp.client().newCall(request).execute().use { resp ->
            assertTrue("取播地址 HTTP ${resp.code}: ${analyzeUrl.url}", resp.isSuccessful)
            val head = readHead(resp)
            assertTrue("取播地址头部字节为空: ${analyzeUrl.url}", head.second > 0)
            assertPlaylistOrBytes(
                analyzeUrl.url,
                String(head.first, 0, head.second, Charsets.UTF_8),
                head.second,
            )
            println(
                "[TvBoxSniffTest] content playable ok http=${resp.code} " +
                    "url=${analyzeUrl.url.take(120)} headers=${analyzeUrl.headerMap.size}",
            )
        }
    }

    /** m3u8 必须真的是播放列表; 其余格式只要头字节非空即可 (不能回一段 HTML)。 */
    private fun assertPlaylistOrBytes(url: String, text: String, size: Int) {
        if (!url.contains(".m3u8")) return
        assertTrue(
            "非 m3u8 播放列表 (抓到的可能是解析页 HTML): ${text.take(160)}",
            text.contains("#EXTM3U"),
        )
        assertTrue("m3u8 响应为空: $url", size > 0)
    }

    /** 读前 4KB; 返回 (缓冲区, 有效长度)。m3u8 清单通常远小于此, 头字节足够判。
     *  mp4 等大文件只验头字节可读 (完整下载不适合测试)。 */
    private fun readHead(resp: okhttp3.Response): Pair<ByteArray, Int> {
        val stream = resp.body?.byteStream() ?: error("空响应体")
        val buffer = ByteArray(4096)
        var off = 0
        while (off < buffer.size) {
            val n = stream.read(buffer, off, buffer.size - off)
            if (n < 0) break
            off += n
        }
        return buffer to off
    }

    /** 虚拟书源行断言 (与既有 TvBox 远程测试同口径)。 */
    private suspend fun importedRowOf(site: TvBoxSite): BookSource {
        val row = AppDbProviders.get().bookSourceDao
            .getBookSource(TvBoxSourceMapper.siteUrlOf(site.key))
        checkNotNull(row) { "TVBox 虚拟书源行未落库: ${site.key}" }
        assertEquals(BookSourceType.video, row.bookSourceType)
        assertEquals(TvBoxPluginSources.GROUP_NAME, row.bookSourceGroup)
        return row
    }

    private fun describe(t: Throwable): String {
        val root = generateSequence(t) { it.cause }.last()
        return "${t::class.simpleName}/${root::class.simpleName}: ${root.message}"
    }

    companion object {
        /** 配置候选 (与既有 TvBox 远程测试同源)。 */
        private val CONFIG_URLS = listOf(
            "https://raw.githubusercontent.com/gaotianliuyun/gao/master/js.json",
            "https://fastly.jsdelivr.net/gh/gaotianliuyun/gao@master/js.json",
        )

        /** 嗅探超时给足: 真机 WebView 冷启动 + 解析站本身多为多跳跳转。 */
        private const val SNIFF_TIMEOUT_MS = 45_000L
    }
}
