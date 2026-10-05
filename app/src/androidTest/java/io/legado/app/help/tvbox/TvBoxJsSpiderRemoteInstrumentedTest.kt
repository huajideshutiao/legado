package io.legado.app.help.tvbox

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.legado.app.model.tvbox.TvBoxManager
import io.legado.app.model.tvbox.TvBoxSourceMapper
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * JS Spider (type=1/3 且 api 指向 .js) 真机全链路验证。
 *
 * 配置与 spider 的 .js 模块全部运行时远程获取 (社区活跃配置 gaotianliuyun/gao),
 * 不把样本打进仓库; 站点漂移时按候选顺序扫描, 首个全链路通过者即判成功。
 *
 * 覆盖: 装载器能否建 QuickJS scope 并解析 ESM 导出面 → init → home → search/category
 * → detail → playerContent 取播 → (m3u8 时) 直链头部字节可拉。
 */
@RunWith(AndroidJUnit4::class)
class TvBoxJsSpiderRemoteInstrumentedTest {

    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    /**
     * JS 站点候选 (按活配置实测: gao/0827.json 的 bili_open 走 FongMi cat 系 ESM 格式,
     * api 为相对模块路径 "./cat/js/bili_open.js")。
     */
    private val candidateApis = listOf(
        "cat/js/bili_open.js",
        "bili_open",
        "drpy_js_豆瓣",
        "drpy_js_B站影视",
        "drpy_js_哔哩影视",
        "drpy_js_我的哔哩",
        "lf_js_search",
        "bb",
        "cc",
    )

    @Test
    fun jsSpiderChain_realHomeSearchDetailPlayer() = runBlocking {
        TvBoxManager.init()
        val config = loadConfig()
        val failures = ArrayList<String>()
        for (api in candidateApis) {
            val site = config.sites.firstOrNull { it.isJsSpider && apiOf(it) == api }
                ?: config.sites.firstOrNull { it.isJsSpider && it.key == api }
                ?: continue
            try {
                driveJsChain(config, site)
                return@runBlocking
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                failures.add(site.api + "@" + site.key + ": " + describe(e))
            }
        }
        error("JS 站点全链路扫描无一通过:\n" + failures.joinToString("\n"))
    }

    /** 站点 api 去掉配置基准前缀, 便于候选用相对形态匹配。 */
    private fun apiOf(site: TvBoxSite): String = site.api
        .removePrefix("https://raw.githubusercontent.com/gaotianliuyun/gao/master/")
        .removePrefix("./")

    private suspend fun loadConfig(): TvBoxConfig {
        val failures = ArrayList<String>()
        for (url in CONFIG_URLS) {
            try {
                val config = TvBoxManager.setConfigFromUrl(url)
                if (config.sites.any { it.isJsSpider }) return config
                failures.add(url + ": 无 JS 站点")
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                failures.add(url + ": " + e.message)
            }
        }
        error("JS 站点配置拉取失败:\n" + failures.joinToString("\n"))
    }

    /** spider 级四路链: init → home → search/category → detail → play → 直链头字节。 */
    private suspend fun driveJsChain(config: TvBoxConfig, site: TvBoxSite) {
        val (_, spider) = TvBoxManager.spiderFor(site, config.spider)
        assertTrue("站点未走 JS 运行时: " + site.api, spider is TvBoxJsSpider)

        val home = JSONObject(spider.homeContent(true).ifBlank { "{}" })
        val classes = home.optJSONArray("class")
        println("[TvBoxJsTest] home=" + home.toString().take(300))
        assertTrue(site.api + " homeContent class[] 为空", classes != null && classes.length() > 0)

        // 种子: 搜索优先 (生态 spider search 常由 category 复用), 空则回退分类扫描
        var vodId = firstString(
            runCatching { JSONObject(spider.searchContent("爱", false)) }.getOrNull()
                ?.optJSONArray("list") ?: JSONArray(),
            "vod_id",
        )
        println("[TvBoxJsTest] search(爱) vodId=" + vodId)
        if (vodId.isNullOrBlank()) {
            vodId = scanCategory(spider, classes!!, 4)
            println("[TvBoxJsTest] category fallback vodId=" + vodId)
        }
        check(!vodId.isNullOrBlank()) { site.api + " 搜索/分类均无 vod 数据" }

        val vod = JSONObject(spider.detailContent(listOf(vodId!!)))
            .optJSONArray("list")?.optJSONObject(0)
        checkNotNull(vod) { site.api + " detailContent(" + vodId + ") list[] 为空" }
        val playFrom = vod.optString("vod_play_from").trim()
        val playUrl = vod.optString("vod_play_url").trim()
        assertTrue(site.api + " 详情无播放线路: " + vod.toString().take(200),
            playFrom.isNotBlank() && playUrl.isNotBlank())

        // 多线路×多集扫描: 首条常为解析页/代理页 (parse=1 或本地代理链路), 逐个试到直链。
        // 生态里 url 段可为 "清晰度1,url1,清晰度2,url2" (多清晰度一行), 取首个 http 段。
        var player: JSONObject? = null
        var playUrlOut = ""
        val flagLines = playFrom.split("$$$")
        outer@ for ((lineIndex, line) in playUrl.split("$$$").withIndex()) {
            val flag = flagLines.getOrNull(lineIndex)?.trim().orEmpty()
            for (episode in line.split("#").filter { it.contains('$') }.take(3)) {
                val episodeId = episode.substringAfter('$', episode).trim()
                if (episodeId.isEmpty()) continue
                val p = runCatching {
                    JSONObject(spider.playerContent(flag, episodeId, emptyList()))
                }.getOrNull() ?: continue
                val u = directUrlOf(p.optString("url"))
                if (p.optInt("parse", 0) == 0 && u.startsWith("http")) {
                    player = p
                    playUrlOut = u
                    break@outer
                }
            }
        }
        val result = player
            ?: error(site.api + " playerContent 全线路均需网页解析/本地代理, 或无直链")
        println(
            "[TvBoxJsTest] chain ok api=" + site.api + " key=" + site.key +
                " classes=" + classes!!.length() + " vodId=" + vodId +
                " playUrl=" + playUrlOut.take(120)
        )

        // mp4 直链可下载头部字节 (防盗链头随 playerContent.header 注入; m3u8 断言播放列表头)
        val headers = headerOf(result)
        val request = Request.Builder().url(playUrlOut).apply {
            headers.forEach { (k, v) -> header(k, v) }
        }.build()
        com.github.catvod.net.OkHttp.client().newCall(request).execute().use { resp ->
            assertTrue(
                site.api + " 直链 HTTP " + resp.code + ": " + playUrlOut.take(120),
                resp.isSuccessful,
            )
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
            if (playUrlOut.contains(".m3u8")) {
                val headText = String(head, 0, off, Charsets.UTF_8)
                assertTrue(
                    site.api + " 直链非 m3u8 播放列表: " + headText.take(80),
                    headText.contains("#EXTM3U"),
                )
            }
        }
    }

    /**
     * playerContent.url 的直链抽取: 逗号分隔段里取首个 http 段。
     * 生态两种形态: 纯 URL, 或 "清晰度名1,url1,清晰度名2,url2" (多清晰度一行)。
     */
    private fun directUrlOf(raw: String): String = raw.split(",")
        .map { it.trim() }
        .firstOrNull { it.startsWith("http") }
        .orEmpty()

    /** 顺序扫描分类 tab 取首个 vod_id (JS spider 分页参数形态各异, 只取第 1 页)。 */
    private fun scanCategory(
        spider: com.github.catvod.crawler.Spider,
        classes: JSONArray,
        maxTabs: Int,
    ): String? {
        for (i in 0 until minOf(classes.length(), maxTabs)) {
            val tid = classes.optJSONObject(i)?.optString("type_id")?.trim().orEmpty()
            if (tid.isEmpty()) continue
            val list = runCatching {
                JSONObject(spider.categoryContent(tid, "1", true, HashMap()))
                    .optJSONArray("list")
            }.getOrNull() ?: JSONArray()
            val id = firstString(list, "vod_id")
            if (!id.isNullOrBlank()) return id
        }
        return null
    }

    /** bookUrl 反解校验: JS 站点同样走 tvbox://<key>/<vod_id> 身份映射。 */
    @Suppress("unused")
    private fun assertIdentity(siteKey: String, vodId: String) {
        val bookUrl = TvBoxSourceMapper.bookUrlOf(siteKey, vodId)
        assertTrue(bookUrl.startsWith("tvbox://"))
        assertTrue(TvBoxSourceMapper.vodIdOf(bookUrl, siteKey) == vodId)
    }

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

    private fun describe(t: Throwable): String {
        val root = generateSequence(t) { it.cause }.last()
        val frames = root.stackTrace.take(4)
            .joinToString(" <- ") {
                it.className.substringAfterLast('.') + "." + it.methodName + ":" + it.lineNumber
            }
        return t::class.simpleName + " / " + root::class.simpleName + ": " + root.message + " @ " + frames
    }

    companion object {
        /** 配置候选: 0827.json 内含 cat 系 ESM JS 站点, js.json 内含 drpy2 系 JS 站点。 */
        private val CONFIG_URLS = listOf(
            "https://raw.githubusercontent.com/gaotianliuyun/gao/master/0827.json",
            "https://raw.githubusercontent.com/gaotianliuyun/gao/master/js.json",
            "https://fastly.jsdelivr.net/gh/gaotianliuyun/gao@master/0827.json",
        )
    }
}
