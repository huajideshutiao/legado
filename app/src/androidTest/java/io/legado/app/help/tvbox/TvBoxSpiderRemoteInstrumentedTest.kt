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
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * 真实远程全链路验证 (从导入源到解析视频链接): 配置与 spider jar 均运行时远程获取
 * (社区活跃配置 gaotianliuyun/gao, 内含 pg.jar;md5 规格), 不把样本打进仓库。
 * 社区站点时效性强, 候选按序扫描, 首个全链路通过者即判成功; 全部失败才断言失败
 * (失败明细随消息输出)。
 */
@RunWith(AndroidJUnit4::class)
class TvBoxSpiderRemoteInstrumentedTest {

    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    /** 候选站点 api (跨配置扫描; CMS 直连站与影视直链站优先, 站点漂移时逐个尝试)。 */
    private val candidateApis = listOf(
        "https://cj.ffzyapi.com/api.php/provide/vod",
        "https://api.kuaifan.tv/api.php/provide/vod",
        "https://cj.lziapi.com/api.php/provide/vod",
        "https://bfzyapi.com/api.php/provide/vod",
        "https://suoniapi.com/api.php/provide/vod",
        "https://haiwaikan.com/api.php/provide/vod",
        "csp_Xinshijue",
        "csp_Gaoqing",
        "csp_Bdys01",
        "csp_Moli",
        "csp_Libvio",
        "csp_Ddys",
        "csp_Meijumi",
        "csp_Wo4k",
        "csp_Hdh",
        "csp_PikaSo",
        "csp_Xpanpan",
        "csp_TTian",
        "csp_Jianpian",
        "csp_Ppxzy",
        "csp_Dm84",
        "csp_Star",
    )

    /** 加载全部可达配置 (js.json 与 0827.json 各自挂不同 jar, 装载器按站点 jar 分流)。 */
    private suspend fun loadConfigs(): List<TvBoxConfig> {
        TvBoxManager.init(context)
        val configs = ArrayList<TvBoxConfig>()
        val failures = ArrayList<String>()
        for (url in CONFIG_URLS) {
            try {
                val config = TvBoxManager.setConfigFromUrl(url)
                if (config.sites.isNotEmpty()) configs.add(config)
                else failures.add(url + ": sites 为空")
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                failures.add(url + ": " + e.message)
            }
        }
        check(configs.isNotEmpty()) { "配置拉取全部失败:\n" + failures.joinToString("\n") }
        println("[TVBoxTest] configs loaded=" + configs.size + " failures=" + failures)
        return configs
    }

    @Test
    fun spiderJarChain_realHomeCategoryDetailPlayer() = runBlocking {
        val configs = loadConfigs()
        scanCandidates(configs) { site, spider -> driveSpiderChain(site, spider) }
    }

    @Test
    fun delegateChain_importedSourceToVideoLinkParse() = runBlocking {
        val configs = loadConfigs()
        scanCandidates(configs) { site, spider ->
            // 导入面: 重同步本配置虚拟行后断言 DB 行存在且身份正确 (导入→落库→取数全场景)
            TvBoxPluginSources.sync(configs.first { it.sites.contains(site) })
            val source = importedRowOf(site)
            // 搜索种子: 搜索空回退分类页首条, 保证后续链路有 vod_id 可用
            val vodId = seedVodId(spider)
            assertTrue("候选 " + site.api + " 无 vod 种子", vodId.isNotBlank())
            driveDelegateChain(source, vodId)
        }
    }

    private suspend fun scanCandidates(
        configs: List<TvBoxConfig>,
        chain: suspend (TvBoxSite, com.github.catvod.crawler.Spider) -> Unit,
    ) {
        val failures = ArrayList<String>()
        for (api in candidateApis) {
            for (config in configs) {
                val site = config.sites.firstOrNull { it.api == api } ?: continue
                try {
                    val (_, spider) = TvBoxManager.spiderFor(site, config.spider)
                    chain(site, spider)
                    return
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Throwable) {
                    failures.add(site.api + "@" + site.key + ": " + describe(e))
                }
            }
        }
        error("候选站点全链路扫描无一通过:\n" + failures.joinToString("\n"))
    }

    /** 失败诊断: 异常链最深处类名+消息+前几个栈帧 (站点漂移时快速定位是宿主面还是源侧问题)。 */
    private fun describe(t: Throwable): String {
        val root = generateSequence(t) { it.cause }.last()
        val frames = root.stackTrace.take(4)
            .joinToString(" <- ") { it.className.substringAfterLast('.') + "." + it.methodName + ":" + it.lineNumber }
        return t::class.simpleName + " / " + root::class.simpleName + ": " + root.message + " @ " + frames
    }

    /** 导入面断言: 配置导入后虚拟行已落库 (分组/类型/URL 身份正确), 返回 DB 行供后续链路使用。 */
    private suspend fun importedRowOf(site: TvBoxSite): BookSource {
        val row = AppDbProviders.get().bookSourceDao
            .getBookSource(TvBoxSourceMapper.siteUrlOf(site.key))
        checkNotNull(row) { "TVBox 虚拟书源行未落库: " + site.key }
        assertEquals(BookSourceType.video, row.bookSourceType)
        assertEquals(TvBoxPluginSources.GROUP_NAME, row.bookSourceGroup)
        return row
    }

    /** spider 级四路链: homeContent → categoryContent → detailContent → playerContent + 直链头部字节。 */
    private suspend fun driveSpiderChain(
        site: TvBoxSite,
        spider: com.github.catvod.crawler.Spider,
    ) {
        val home = JSONObject(spider.homeContent(true))
        val classes = home.optJSONArray("class")
        assertTrue(site.api + " homeContent class[] 为空", classes != null && classes.length() > 0)

        // 首个分类可能是首页/专题等特殊 tab, 顺序扫前几个直到分类页有数据
        val catResult = categoryWithList(spider, site.api, classes!!, 4)
        val vodId = firstString(catResult.second, "vod_id")
            ?: error(site.api + " 分类页均无 vod 数据 (扫描 " + catResult.first + " 个 tab)")

        val vod = JSONObject(spider.detailContent(listOf(vodId)))
            .optJSONArray("list")?.optJSONObject(0)
            ?: error(site.api + " detailContent(" + vodId + ") list[] 为空")
        val playFrom = vod.optString("vod_play_from").trim()
        val playUrl = vod.optString("vod_play_url").trim()
        assertTrue(site.api + " 详情无播放线路", playFrom.isNotBlank() && playUrl.isNotBlank())

        // 多线路×多集扫描: 首条为解析页 (parse=1, 网页嗅探遗留豁口) 时换下一条, 直到拿到直链
        var player: JSONObject? = null
        val flagLines = playFrom.split("$$$")
        outer@ for ((lineIndex, line) in playUrl.split("$$$").withIndex()) {
            val flag = flagLines.getOrNull(lineIndex)?.trim().orEmpty()
            for (episode in line.split("#").filter { it.contains('$') }.take(3)) {
                val episodeId = episode.substringAfter('$', episode).trim()
                if (episodeId.isEmpty()) continue
                val p = runCatching {
                    JSONObject(spider.playerContent(flag, episodeId, emptyList()))
                }.getOrNull() ?: continue
                val u = p.optString("url").trim()
                if (p.optInt("parse", 0) == 0 && u.startsWith("http")) {
                    player = p
                    break@outer
                }
            }
        }
        val result = player
            ?: error(site.api + " playerContent 全线路均需网页解析 (parse=1, 遗留豁口) 或无直链")
        val url = result.optString("url").trim()
        println(
            "[TVBoxTest] spiderChain ok api=" + site.api + " key=" + site.key +
                " homeClasses=" + classes.length() + " catList=" + catResult.second.length() +
                " vodId=" + vodId + " playUrl=" + url.take(120) +
                " linesTried=" + playUrl.split("$$$").size
        )

        // 直链可下载头部字节 (防盗链头随 playerContent.header 注入)
        val headers = headerOf(result)
        val request = Request.Builder().url(url).apply {
            headers.forEach { (k, v) -> header(k, v) }
        }.build()
        com.github.catvod.net.OkHttp.client().newCall(request).execute().use { resp ->
            assertTrue(site.api + " 直链 HTTP " + resp.code + ": " + url, resp.isSuccessful)
            val stream = resp.body?.byteStream() ?: error("直链空响应体")
            val head = ByteArray(2048)
            var off = 0
            while (off < head.size) {
                val n = stream.read(head, off, head.size - off)
                if (n < 0) break
                off += n
            }
            stream.close()
            assertTrue(site.api + " 直链头部字节为空", off > 0)
            // 真的能播: m3u8 直链须回 #EXTM3U 播放列表 (ExoPlayer 可解析), 防把解析页当直链放过
            if (url.contains(".m3u8")) {
                val headText = String(head, 0, off, Charsets.UTF_8)
                assertTrue(
                    site.api + " 直链非 m3u8 播放列表: " + headText.take(80),
                    headText.contains("#EXTM3U"),
                )
            }
        }
    }

    /** 委派级链: DB 虚拟行 → 搜索/详情/目录/取播 → AnalyzeUrl 内容串真链路解析。 */
    private suspend fun driveDelegateChain(source: BookSource, vodId: String) {
        val page = TvBoxSourceDelegateImpl.getBookListAwait(source, "爱", 1)
        assertTrue("搜索无结果: " + source.bookSourceUrl, page.books.isNotEmpty())
        val hit = page.books.first()
        val book = Book().apply {
            bookUrl = hit.bookUrl
            origin = hit.origin
            name = hit.name
            type = BookType.video
        }
        val info = TvBoxSourceDelegateImpl.getBookInfoAwait(source, book, canReName = true)
        assertTrue("详情标题为空", info.name.isNotBlank())
        val chapters = TvBoxSourceDelegateImpl.getChapterListAwait(source, info).getOrThrow()
        assertTrue("目录为空", chapters.isNotEmpty())
        // 逐章试播: 首章可能落在需网页解析的线路上 (parse=1 遗留豁口), 用户语义等同手动换集/换线
        var content: String? = null
        for (chapter in chapters.take(6)) {
            content = runCatching {
                TvBoxSourceDelegateImpl.getContentAwait(source, info, chapter)
            }.getOrNull()
            if (content != null && content.contains("://")) break
        }
        content ?: error("全部尝试章节均无可播直链")
        // 多线路内容串: 每行 `线路名::内容` (播放器换分辨率入口即换线路), 逐行断言含直链
        val contentLines = content.split("\n")
        for (line in contentLines) {
            assertTrue(
                "取播内容行应含直链: " + line.take(120),
                line.contains("://"),
            )
        }
        // vod 种子与搜索命中的站点一致性通过委派内部反解保证, 此处再校验 bookUrl 前缀
        assertTrue(hit.bookUrl.startsWith(source.bookSourceUrl + "/"))
        // 真实内容解析链: 播放器取数同款 AnalyzeUrl 拆 url,{"headers":{…}} 链接参数语法,
        // 取首行内容段 (多行时剥离 `线路名::` 前缀) 断言可解析出可播地址且防盗链头进入 headerMap
        val firstEntry = contentLines.first().substringAfter("::", contentLines.first())
        val analyzeUrl = AnalyzeUrl(firstEntry)
        assertTrue("内容串未解析出可播地址: " + analyzeUrl.url, analyzeUrl.url.contains("://"))
        println(
            "[TVBoxTest] delegateChain ok source=" + source.bookSourceUrl +
                " search=" + page.books.size + " chapters=" + chapters.size +
                " contentLines=" + contentLines.size +
                " analyzeUrl=" + analyzeUrl.url.take(120) +
                " headers=" + analyzeUrl.headerMap.size
        )
        if (firstEntry.contains("{\"headers\"")) {
            assertTrue(
                "内容串内 headers 未合入 headerMap (防盗链头会丢): " + analyzeUrl.headerMap,
                analyzeUrl.headerMap.isNotEmpty(),
            )
        }
    }

    /** 搜索种子: searchContent 优先, 空则回退多分类扫描; 原始输出进 system-out 供诊断。 */
    private suspend fun seedVodId(spider: com.github.catvod.crawler.Spider): String {
        val search = runCatching { spider.searchContent("爱", false) }.getOrDefault("")
        println("[TVBoxTest] search(爱) raw=" + search.take(300))
        runCatching { JSONObject(search).optJSONArray("list") }
            .getOrNull()
            ?.let { list -> firstString(list, "vod_id")?.let { return it } }
        val homeRaw = runCatching { spider.homeContent(true) }.getOrDefault("")
        println("[TVBoxTest] home raw=" + homeRaw.take(600))
        val home = runCatching { JSONObject(homeRaw) }.getOrNull() ?: return ""
        val classes = home.optJSONArray("class") ?: return ""
        return runCatching {
            val (scanned, list) = categoryWithList(spider, "seed", classes, 4)
            println("[TVBoxTest] seed tabs scanned=" + scanned + " list=" + list.toString().take(300))
            firstString(list, "vod_id") ?: ""
        }.getOrDefault("")
    }

    /** 顺序扫描前 [maxTabs] 个分类 tab, 返回首个非空 (扫描数, list)。 */
    private suspend fun categoryWithList(
        spider: com.github.catvod.crawler.Spider,
        api: String,
        classes: JSONArray,
        maxTabs: Int,
    ): Pair<Int, JSONArray> {
        var last: JSONArray = JSONArray()
        var scanned = 0
        for (i in 0 until minOf(classes.length(), maxTabs)) {
            val tid = classes.optJSONObject(i)?.optString("type_id")?.trim().orEmpty()
            if (tid.isEmpty()) continue
            scanned++
            val list = runCatching {
                JSONObject(spider.categoryContent(tid, "1", true, HashMap())).optJSONArray("list")
            }.getOrNull() ?: JSONArray()
            if (list.length() > 0) return scanned to list
            last = list
        }
        return scanned to last
    }

    /** 取数组首个对象的指定字段字符串 (数字值兼容)。 */
    private fun firstString(list: JSONArray, key: String): String? {
        for (i in 0 until list.length()) {
            val obj = list.optJSONObject(i) ?: continue
            val id = obj.optString(key).trim()
            if (id.isNotEmpty()) return id
        }
        return null
    }

    private fun headerOf(root: JSONObject): Map<String, String> {
        val header = when (val raw = root.opt("header")) {
            is JSONObject -> raw
            is String -> runCatching { JSONObject(raw) }.getOrNull() ?: return emptyMap()
            else -> return emptyMap()
        }
        val map = linkedMapOf<String, String>()
        for (key in header.keys()) {
            val value = header.opt(key)?.toString().orEmpty()
            if (value.isNotEmpty()) map[key] = value
        }
        return map
    }

    companion object {
        /** 配置候选 (GitHub 直连优先; jsDelivr 镜像作设备网络受限时兑底)。 */
        private val CONFIG_URLS = listOf(
            "https://raw.githubusercontent.com/gaotianliuyun/gao/master/js.json",
            "https://raw.githubusercontent.com/gaotianliuyun/gao/master/0827.json",
            "https://fastly.jsdelivr.net/gh/gaotianliuyun/gao@master/0827.json",
        )
    }
}
