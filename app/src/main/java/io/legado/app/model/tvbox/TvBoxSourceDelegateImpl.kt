package io.legado.app.model.tvbox

import com.github.catvod.crawler.Spider
import io.legado.app.constant.AppLog
import io.legado.app.constant.BookType
import io.legado.app.data.entities.Book
import io.legado.app.data.entities.BookChapter
import io.legado.app.data.entities.BookListPage
import io.legado.app.data.entities.BookSource
import io.legado.app.data.entities.SearchBook
import io.legado.app.help.coroutine.IoDispatcher
import io.legado.app.help.tvbox.TvBoxParse
import io.legado.app.help.tvbox.TvBoxSite
import io.legado.app.help.tvbox.TvBoxSniffer
import io.legado.app.help.tvbox.TvBoxSniffResult
import io.legado.app.help.tvbox.TvBoxVideoPredicate
import io.legado.app.help.tvbox.pickAggregate
import io.legado.app.help.tvbox.pickJsonApi
import io.legado.app.help.tvbox.pickWebSniff
import io.legado.app.model.webBook.BookChapterList
import io.legado.app.model.webBook.VideoSourceDelegate
import io.legado.app.utils.KS_JSON
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import org.json.JSONObject

/**
 * TVBox 站点取数委派 (data 层 VideoSourceDelegate 的宿主实现, 与 VideoSourceDelegateImpl 同构):
 *
 * 搜索 → spider.searchContent; 详情 → detailContent; 目录 → detailContent 的
 * vod_play_from/vod_play_url 按 "$$$" 配对拆行、"#" 拆集 (集名$id, tag 存线路 flag);
 * 取播 → 逐线 playerContent 拿直链, 多线路拼 legado 多行 `线路名::内容` 语义 —— 换线路
 * 即播放器换分辨率入口 (本质是换 URL, 进度由播放器保留), 直链行拼 `url,{"headers":{…}}`。
 * 全线路均非直链 (parse=1 / jx=1 / .html 播放页) 时退 [TvBoxSniffer] 网页嗅探出真实媒体地址。
 *
 * 本地代理 9978 与本轮未实现 (理由见 help/tvbox/README.md); CMS 直连站点已支持。
 */
object TvBoxSourceDelegateImpl : VideoSourceDelegate {

    /** 取播时最多动用几次网页嗅探 (每次一个 WebView, 逐个串行)。 */
    private const val MAX_SNIFF_TRIES = 2

    /** 解析站/播放页的查询参数形态: `?url=http…` 或 `?v=http…` (允许 URL 编码后的 https%3A)。 */
    private val PAGE_QUERY_PARAM = Regex("[?&](?:url|v)=https?")

    override fun handles(bookSource: BookSource): Boolean =
        bookSource.bookSourceUrl.startsWith(TvBoxSourceMapper.SOURCE_URL_PREFIX)

    override suspend fun getBookListAwait(
        bookSource: BookSource,
        key: String,
        page: Int,
    ): BookListPage = withContext(IoDispatcher) {
        val siteKey = TvBoxSourceMapper.siteKeyOf(bookSource.bookSourceUrl)
        val (_, spider) = TvBoxManager.spiderFor(siteKey)
        // 翻页走三参签名 (FongMi 以 String pg 承载页码); 未实现翻页的 spider 返回空串
        val json = if (page <= 1) {
            spider.searchContent(key, false)
        } else {
            spider.searchContent(key, false, page.toString())
        }
        val root = parseResult(json)
        val items = root.optJSONArray("list") ?: org.json.JSONArray()
        val books = ArrayList<SearchBook>(items.length())
        for (i in 0 until items.length()) {
            val item = items.optJSONObject(i) ?: continue
            val vodId = item.optString("vod_id").trim()
            val name = item.optString("vod_name").trim()
            if (vodId.isEmpty() || name.isEmpty()) continue
            val bookUrl = TvBoxSourceMapper.bookUrlOf(siteKey, vodId)
            books.add(
                SearchBook(
                    bookUrl = bookUrl,
                    origin = bookSource.bookSourceUrl,
                    originName = bookSource.bookSourceName,
                    type = BookType.video,
                    name = name,
                    kind = item.optString("vod_remarks").ifBlank { null },
                    coverUrl = item.optString("vod_pic").ifBlank { null },
                    tocUrl = bookUrl,
                ),
            )
        }
        // pagecount 缺失时以本页有数据作为"还有下一页"的近似 (生态多数搜索接口无总页数)
        val hasNextPage = if (root.has("pagecount")) {
            page < root.optInt("pagecount", page)
        } else {
            books.isNotEmpty()
        }
        BookListPage(books, hasNextPage)
    }

    override suspend fun getBookInfoAwait(
        bookSource: BookSource,
        book: Book,
        canReName: Boolean,
    ): Book = withContext(IoDispatcher) {
        val siteKey = TvBoxSourceMapper.siteKeyOf(bookSource.bookSourceUrl)
        val vod = detailVod(siteKey, book.bookUrl)
        // 空值不覆盖 (原版 BookInfo.kt 同语义): 不少站点详情不返回 vod_name/vod_actor
        // (比特/立播/原创 实测均为空), 无条件赋值会把搜索结果里已有的正确书名清掉。
        vod.optString("vod_name").trim().takeIf { it.isNotEmpty() }?.let {
            if (canReName || book.name.isBlank()) book.name = it
        }
        vod.optString("vod_actor").trim().takeIf { it.isNotEmpty() }?.let {
            if (canReName || book.author.isBlank()) book.author = it
        }
        book.kind = listOf(
            vod.optString("vod_class").trim(),
            vod.optString("vod_remarks").trim(),
        ).filter { it.isNotEmpty() }.joinToString(",").ifBlank { null }
        book.coverUrl = vod.optString("vod_pic").ifBlank { null }
        book.intro = vod.optString("vod_content").ifBlank { null }
        book.tocUrl = book.bookUrl
        book
    }

    override suspend fun getChapterListAwait(
        bookSource: BookSource,
        book: Book,
    ): Result<List<BookChapter>> = runCatching {
        withContext(IoDispatcher) {
            val siteKey = TvBoxSourceMapper.siteKeyOf(bookSource.bookSourceUrl)
            val vod = detailVod(siteKey, book.bookUrl)
            // 多线路 vod_play_from/vod_play_url 以 "$$$" 一一配对, 线内 "#分隔", 集内 "集名$id"
            val flags = vod.optString("vod_play_from").split("$$$")
            val lines = vod.optString("vod_play_url").split("$$$")
            val chapters = ArrayList<BookChapter>()
            for ((flagIndex, flag) in flags.withIndex()) {
                val line = lines.getOrNull(flagIndex) ?: continue
                for (entry in line.split("#")) {
                    val trimmed = entry.trim()
                    if (trimmed.isEmpty()) continue
                    val name = trimmed.substringBefore('$').trim()
                    val id = trimmed.substringAfter('$', missingDelimiterValue = trimmed).trim()
                    if (id.isEmpty()) continue
                    chapters.add(
                        BookChapter(
                            bookUrl = book.bookUrl,
                            url = id,
                            title = name.ifBlank { id },
                            index = chapters.size,
                            tag = flag.trim().ifBlank { null },
                        ),
                    )
                }
            }
            check(chapters.isNotEmpty()) { "TVBox 站点无剧集数据: ${vod.optString("vod_name")}" }
            // 与规则链同构: updateBook 负责 reverse/index/totalChapterNum 等目录簿记
            BookChapterList.updateBook(book, chapters)
        }
    }.onFailure {
        if (it is kotlinx.coroutines.CancellationException) throw it
        AppLog.put("获取 TVBox 目录失败 ${bookSource.bookSourceName}", it)
    }

    override suspend fun getContentAwait(
        bookSource: BookSource,
        book: Book,
        bookChapter: BookChapter,
    ): String = withContext(IoDispatcher) {
        val siteKey = TvBoxSourceMapper.siteKeyOf(bookSource.bookSourceUrl)
        val (site, spider) = TvBoxManager.spiderFor(siteKey)
        // 换线路走播放器多分辨率入口 (同一集的线路切换本质是换 URL, 进度由播放器保留):
        // 重取详情拿各线路集表, 当前线路优先, 同名集 (缺则同序号) 定位各线路对应集,
        // 逐线 playerContent, 仅直链 (parse=0) 入列表, 拼 legado 多行 `线路名::内容` 语义。
        val vodId = TvBoxSourceMapper.vodIdOf(book.bookUrl, siteKey)
            ?: error("无法从 bookUrl 反解 TVBox vod_id: ${book.bookUrl}")
        val vod = parseResult(spider.detailContent(listOf(vodId)))
            .optJSONArray("list")?.optJSONObject(0)
            ?: error("TVBox 详情无数据: ${book.bookUrl}")
        val flags = vod.optString("vod_play_from").split("$$$")
        val playUrlLines = vod.optString("vod_play_url").split("$$$")
        val lineEpisodes = flags.mapIndexed { index, flag ->
            flag.trim() to (playUrlLines.getOrNull(index).orEmpty().split("#"))
                .mapNotNull { entry ->
                    val trimmed = entry.trim()
                    if (trimmed.isEmpty()) null
                    else trimmed.substringBefore('$').trim() to trimmed.substringAfter('$', trimmed).trim()
                }
        }
        val currentFlag = bookChapter.tag.orEmpty()
        val currentList = lineEpisodes.firstOrNull { it.first == currentFlag }?.second
            ?: lineEpisodes.firstOrNull()?.second
            ?: emptyList()
        val currentIndex = currentList.indexOfFirst { it.second == bookChapter.url }.takeIf { it >= 0 } ?: 0
        val currentName = currentList.getOrNull(currentIndex)?.first.orEmpty()

        val candidates = ArrayList<Pair<String, String>>()
        candidates += currentFlag to bookChapter.url
        for ((flag, episodes) in lineEpisodes) {
            if (flag.isEmpty() || flag == currentFlag) continue
            val target = episodes.firstOrNull { it.first == currentName && currentName.isNotEmpty() }?.second
                ?: episodes.getOrNull(currentIndex)?.second
            if (!target.isNullOrBlank()) candidates += flag to target
        }

        // 先按原语义收直链: 多线路一次拿全 (换线路=换 URL), 无 WebView 开销
        val direct = candidates.mapNotNull { (flag, id) ->
            runCatching { directContent(site, spider, flag, id) }.getOrNull()
        }
        if (direct.isNotEmpty()) {
            return@withContext if (direct.size == 1) {
                direct[0].second
            } else {
                direct.joinToString("\n") { (flag, content) -> "$flag::$content" }
            }
        }
        // 全线路都拿不到直链 → 逐个网页嗅探, 首个成功即用。嗅探较重 (每次一个 WebView + 超时),
        // 故只试前两条: 同一站点的线路通常同属一类页面 (一起成功或一起失败), 全量试只会拖时间。
        val parses = TvBoxManager.config?.parses.orEmpty()
        val sniffFailures = ArrayList<String>()
        for ((flag, id) in candidates.take(MAX_SNIFF_TRIES)) {
            val content = runCatching { sniffContent(site, spider, flag, id, parses) }
                .onFailure { e -> sniffFailures += "$flag: ${e.message}" }
                .getOrNull() ?: continue
            return@withContext content
        }
        error(
            "TVBox 全线路均无直链且网页嗅探失败: ${book.name} ${bookChapter.title}\n" +
                sniffFailures.joinToString("\n"),
        )
    }

    /**
     * 单线路的直链内容串; 非直链返回 null 交由 [sniffContent]。
     *
     * 直链判据比旧实现严了一层: 排除 .html 播放页与解析站形态 (`?url=http`), 它们是
     * spider 忘了标 parse=1 的播放页 —— 直投 ExoPlayer 只会拿到一段 HTML 报错。
     * 无扩展名的兜底 URL 仍按旧语义当直链 (部分站点直链确实不带后缀, 不能误伤)。
     */
    private fun directContent(
        site: TvBoxSite,
        spider: Spider,
        flag: String,
        id: String,
    ): Pair<String, String>? {
        val p = parseResult(spider.playerContent(flag, id, emptyList()))
        val playUrl = playUrlOf(p, site)
        if (needsParse(p) || isPlayPage(playUrl) || !playUrl.startsWith("http")) return null
        return flag.replace("::", "") to contentOf(playUrl, headerOf(p))
    }

    /**
     * 单线路非直链取播: 按解析配置 type 分支取真实媒体地址 (FongMi ParseJob.doInBackground 同语义):
     * type=1 json API (纯 HTTP, 快) → type=0 WebView 嗅探 → type=2/3 jar 聚合类; 首个成功即用。
     */
    private suspend fun sniffContent(
        site: TvBoxSite,
        spider: Spider,
        flag: String,
        id: String,
        parses: List<TvBoxParse>,
    ): String {
        val p = parseResult(spider.playerContent(flag, id, emptyList()))
        val videoPage = playUrlOf(p, site)
        val headers = headerOf(p)
        var lastError: Throwable? = null
        // 本地 suspend 函数: 嗅探/解析 API 均为挂起调用, 逐个形态串行尝试
        suspend fun attempt(block: suspend () -> TvBoxSniffResult): String? = try {
            val result = block()
            contentOf(result.url, headers + result.headers)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            lastError = e
            null
        }

        // FongMi ParseJob 的 type=1 分支加载的是 `parse.getUrl() + 播放页`, json API 请求它取直链
        parses.pickJsonApi(flag)?.let { json ->
            attempt { TvBoxSniffer.parseJsonApi(json, videoPage, headers) }?.let { return it }
        }
        // FongMi ParseJob 的 type=0 分支加载的是 `parse.getUrl() + 播放页`, 即先把站点/全局
        // jxs 拼在前面; 没有适用 jxs 时退回直接加载播放页 (CMS 站的 .html 播放值常属此类)。
        val page = parses.pickWebSniff(flag)?.pageOf(videoPage) ?: videoPage
        check(page.startsWith("http")) { "播放页地址非 http(s), 无法嗅探: $page" }
        attempt {
            TvBoxSniffer.sniff(page, headers, videoChecker = videoPredicateOf(spider))
        }?.let { return it }
        // type=2/3 走 jar 内聚合解析类; JS spider 站点无 jar, 此步如实失败
        parses.pickAggregate()?.let { agg ->
            attempt {
                TvBoxSniffer.parseJsonAggregate(
                    parse = agg,
                    allParses = parses,
                    flag = flag,
                    playUrl = videoPage,
                    spider = spider,
                    headers = headers,
                    videoChecker = videoPredicateOf(spider),
                )
            }?.let { return it }
        }
        error(
            "TVBox 解析站全形态失败 (json API/网页嗅探/聚合): ${lastError?.message ?: "无适用解析配置"}",
        )
    }

    /** 结果指向的播放地址: playUrl 作前缀 + url (FongMi Result.getRealUrl() 同语义)。 */
    private fun playUrlOf(root: JSONObject, site: TvBoxSite): String {
        val url = root.optString("url").trim()
        if (url.startsWith("http")) return url
        val prefix = root.optString("playUrl").trim().ifBlank { site.playUrl }
        return if (prefix.isBlank()) url else prefix + url
    }

    /**
     * 需要网页解析的标记: `parse=1` 或 `jx=1` (FongMi `bean/Result.needParse()` 逐字同义),
     * 部分站点只写 jx 不写 parse。
     */
    private fun needsParse(root: JSONObject): Boolean =
        root.optInt("parse", 0) != 0 || root.optInt("jx", 0) != 0

    /**
     * 肉眼可辨的网页形态 (判据刻意保守, 只排除确定是页面的地址):
     * .html 播放页, 以及解析站形态 `?url=http` / `?v=http` (允许编码后的 https%3A)。
     */
    private fun isPlayPage(url: String): Boolean {
        if (PAGE_QUERY_PARAM.containsMatchIn(url)) return true
        val path = runCatching { java.net.URI(url).path }.getOrNull().orEmpty().lowercase()
        return path.endsWith(".html") || path.endsWith(".htm") || path.endsWith(".shtml")
    }

    /**
     * 视频地址判据: spider 声明 `manualVideoCheck()` 时改用它自己的 `isVideoFormat()`
     * (FongMi `CustomWebView.isVideoFormat()` 的 spider 委托分支), 否则用 URL 形态判据。
     */
    private fun videoPredicateOf(spider: Spider): TvBoxVideoPredicate =
        if (runCatching { spider.manualVideoCheck() }.getOrDefault(false)) {
            TvBoxVideoPredicate { url -> runCatching { spider.isVideoFormat(url) }.getOrDefault(false) }
        } else {
            TvBoxVideoPredicate.Sniffer
        }

    /** 拼 legado 内容串: `url` 或 `url,{"headers":{…}}` (AnalyzeUrlCore 拆出并入 headerMap)。 */
    private fun contentOf(url: String, headers: Map<String, String>): String {
        if (headers.isEmpty()) return url
        return url + "," + KS_JSON.encodeToString<Map<String, Map<String, String>>>(
            mapOf("headers" to headers),
        )
    }

    /** 详情取数公共路径: bookUrl 反解 vod_id → detailContent → list[0]。 */
    private suspend fun detailVod(siteKey: String, bookUrl: String): JSONObject {
        val (site, spider) = TvBoxManager.spiderFor(siteKey)
        val vodId = TvBoxSourceMapper.vodIdOf(bookUrl, siteKey)
            ?: error("无法从 bookUrl 反解 TVBox vod_id: $bookUrl")
        val root = parseResult(spider.detailContent(listOf(vodId)), site)
        val items = root.optJSONArray("list")
        val vod = items?.optJSONObject(0)
        checkNotNull(vod) { "TVBox 详情无数据: $bookUrl" }
        return vod
    }

    private fun parseResult(json: String?, site: TvBoxSite? = null): JSONObject {
        val text = json?.trim().orEmpty()
        check(text.isNotEmpty()) { "TVBox Spider 返回为空 (站点=${site?.name ?: "?"}, api=${site?.api ?: "?"})" }
        return runCatching { JSONObject(text) }.getOrElse { e ->
            throw IllegalStateException(
                "TVBox Spider 返回非法 JSON (站点=${site?.name ?: "?"}, api=${site?.api ?: "?"}): ${e.message}",
                e,
            )
        }
    }

    /** playerContent.header 可为对象或 JSON 字符串, 值一律取字符串形态。 */
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

    /** 站点排查辅助 (委派自身不消费, 供调试/测试取站点面)。 */
    fun siteOf(bookSource: BookSource): TvBoxSite? =
        TvBoxManager.siteOf(TvBoxSourceMapper.siteKeyOf(bookSource.bookSourceUrl))
}
