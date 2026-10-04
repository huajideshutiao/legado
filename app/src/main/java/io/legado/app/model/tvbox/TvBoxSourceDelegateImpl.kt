package io.legado.app.model.tvbox

import io.legado.app.constant.AppLog
import io.legado.app.constant.BookType
import io.legado.app.data.entities.Book
import io.legado.app.data.entities.BookChapter
import io.legado.app.data.entities.BookListPage
import io.legado.app.data.entities.BookSource
import io.legado.app.data.entities.SearchBook
import io.legado.app.help.coroutine.IoDispatcher
import io.legado.app.help.tvbox.TvBoxSite
import io.legado.app.model.webBook.BookChapterList
import io.legado.app.model.webBook.VideoSourceDelegate
import io.legado.app.utils.KS_JSON
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
 *
 * parse=1 (网页嗅探)、CMS 直连站点与本地代理为遗留豁口。
 */
object TvBoxSourceDelegateImpl : VideoSourceDelegate {

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
        if (canReName || book.name.isBlank()) book.name = vod.optString("vod_name").trim()
        if (canReName || book.author.isBlank()) book.author = vod.optString("vod_actor").trim()
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

        val resolved = candidates.mapNotNull { (flag, id) ->
            runCatching {
                val p = parseResult(spider.playerContent(flag, id, emptyList()))
                val u = p.optString("url").trim()
                if (p.optInt("parse", 0) != 0 || !u.startsWith("http")) return@mapNotNull null
                var playUrl = u
                if (site.playUrl.isNotBlank() && !playUrl.startsWith("http")) {
                    playUrl = site.playUrl + playUrl
                }
                val headers = headerOf(p)
                val content = if (headers.isEmpty()) {
                    playUrl
                } else {
                    // legado 原生链接参数语法: AnalyzeUrlCore 会拆出并合并进 headerMap
                    playUrl + "," + KS_JSON.encodeToString<Map<String, Map<String, String>>>(
                        mapOf("headers" to headers),
                    )
                }
                flag.replace("::", "") to content
            }.getOrNull()
        }
        check(resolved.isNotEmpty()) {
            "TVBox 各线路均无直链 (parse=1 网页解析为遗留豁口): ${book.name} ${bookChapter.title}"
        }
        if (resolved.size == 1) {
            resolved[0].second
        } else {
            resolved.joinToString("\n") { (flag, content) -> "$flag::$content" }
        }
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
