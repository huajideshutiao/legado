package io.legado.app.model.tvbox

import com.github.catvod.crawler.Spider
import io.legado.app.constant.BookType
import io.legado.app.data.entities.Book
import io.legado.app.data.entities.BookChapter
import io.legado.app.data.entities.BookListPage
import io.legado.app.data.entities.BookSource
import io.legado.app.data.entities.SearchBook
import io.legado.app.data.entities.VideoResolution
import io.legado.app.data.entities.VideoSource
import io.legado.app.data.entities.rule.ExploreKind
import io.legado.app.help.coroutine.IoDispatcher
import io.legado.app.help.toast.Toasters
import io.legado.app.help.tvbox.TvBoxParse
import io.legado.app.help.tvbox.TvBoxSite
import io.legado.app.help.tvbox.TvBoxSniffer
import io.legado.app.help.tvbox.TvBoxSniffResult
import io.legado.app.help.tvbox.TvBoxVideoPredicate
import io.legado.app.help.tvbox.isParsePageUrl
import io.legado.app.help.tvbox.pickAggregate
import io.legado.app.help.tvbox.pickJsonApi
import io.legado.app.help.tvbox.pickWebSniff
import io.legado.app.model.webBook.BookChapterList
import io.legado.app.model.webBook.PluginSourceDelegate
import io.legado.app.utils.GSON
import io.legado.app.utils.KS_JSON
import io.legado.app.utils.toJson
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.encodeToString
import org.json.JSONArray
import org.json.JSONObject

/**
 * TVBox 站点取数委派 (data 层 [PluginSourceDelegate] 的 TVBox 源实现, 与 VideoSourceDelegateImpl 同构):
 *
 * 搜索 → spider.searchContent; 详情 → detailContent; 目录 → detailContent 的
 * vod_play_from/vod_play_url 按 "$$$" 配对拆行、"#" 拆集 (集名$id, tag 存线路 flag);
 * 取播 → 本线路 playerContent 拿直链, 多清晰度输出 VideoSource JSON; 直链行拼
 * `url,{"headers":{…}}`。
 * 全线路均非直链 (parse=1 / jx=1 / 网页播放页: `.html` 或外网无媒体信号地址, 见 [isPlayPage])
 * 时退 [TvBoxSniffer] 网页嗅探出真实媒体地址。
 *
 * 本地代理 9978 由 TvBoxManager 随配置装载自动起停 (Android/桌面同一链路, 见 help/tvbox/README.md)。
 */
object TvBoxSourceDelegateImpl : PluginSourceDelegate {

    /** FongMi `Vod.isFolder`: `"folder".equals(vod_tag) || cate != null`。 */
    private fun JSONObject.isFolderVod(): Boolean =
        optString("vod_tag").trim() == "folder" || (has("cate") && !isNull("cate"))

    override fun handles(bookSource: BookSource): Boolean =
        bookSource.bookSourceUrl.startsWith(TvBoxSourceMapper.SOURCE_URL_PREFIX)

    override fun tocFailMessage(bookSource: BookSource, e: Exception): String =
        "获取TVBox源 ${bookSource.bookSourceName} 的书籍目录失败\n${e.message}"

    /**
     * 发现分类: 站点首页 class 数组 (type_id/type_name), "推荐" 置顶 (对应 getExploreAwait
     * 的 popular 段)。分类是 spider 运行时数据, 不写进 exploreUrl 字段; 取数结果由
     * exploreKinds() 落盘缓存 (每站首次进发现页抓一次)。
     */
    override suspend fun getExploreKinds(bookSource: BookSource): List<ExploreKind>? =
        withContext(IoDispatcher) {
            val siteKey = TvBoxSourceMapper.siteKeyOf(bookSource.bookSourceUrl)
            val (site, spider) = TvBoxManager.spiderFor(siteKey)
            val home = parseResult(spider.homeContent(true), site)
            val classes = home.optJSONArray("class") ?: JSONArray()
            val kinds = ArrayList<ExploreKind>(classes.length() + 1)
            kinds += ExploreKind(title = "推荐", url = "popular")
            for (i in 0 until classes.length()) {
                val item = classes.optJSONObject(i) ?: continue
                val typeId = item.optString("type_id").trim()
                val name = item.optString("type_name").trim()
                if (typeId.isEmpty() || name.isEmpty()) continue
                kinds += ExploreKind(title = name, url = typeId)
            }
            kinds
        }

    override suspend fun getBookListAwait(
        bookSource: BookSource,
        key: String,
        page: Int,
    ): BookListPage = withContext(IoDispatcher) {
        val siteKey = TvBoxSourceMapper.siteKeyOf(bookSource.bookSourceUrl)
        val (site, spider) = TvBoxManager.spiderFor(siteKey)
        // 翻页走三参签名 (FongMi 以 String pg 承载页码); 未实现翻页的 spider 返回空串
        val json = if (page <= 1) {
            spider.searchContent(key, false)
        } else {
            spider.searchContent(key, false, page.toString())
        }
        // 生态约定: 无命中时 jar 返回空串/空白, 不是错误 (仅非空非法 JSON 才抛错)
        if (json.isNullOrBlank()) return@withContext BookListPage(ArrayList(), false)
        val root = parseResult(json, site)
        bookListPageOf(bookSource, siteKey, root, page)
    }

    /**
     * 发现取数 (对应 WebBook.getBookListAwait 的 isSearch=false 路径)。
     *
     * TVBox 的"发现"语义是**站点首页推荐**, 不是搜索。按虚拟行 exploreUrl 里的 url 分派:
     * - `popular` → spider 的 `homeVideoContent()` (FongMi homeVod), 无 list 时退 `homeContent(false)`;
     * - `latest` → 同源取最新: TVBox 无独立"最新"接口, 以 `homeContent` 的 `class[0].type_id`
     *   走 `categoryContent(tid, pg, filter=false)` 第 1 页 (CMS/jar 同款分类页首屏即最新);
     *   站点无 class 时如实报错, 不伪造数据。
     *
     * 未知 url 一律报错 (而非静默回空), 让发现页把失败原因显示出来。
     */
    override suspend fun getExploreAwait(
        bookSource: BookSource,
        url: String,
        page: Int,
    ): BookListPage = withContext(IoDispatcher) {
        val siteKey = TvBoxSourceMapper.siteKeyOf(bookSource.bookSourceUrl)
        val (site, spider) = TvBoxManager.spiderFor(siteKey)
        val segment = url.trim().ifBlank { "popular" }
        // 动作段: 执行 spider.action 并提示结果, 返回空页 (该条目不是分类, 无可列内容)
        if (TvBoxSourceMapper.isActionSegment(segment)) {
            return@withContext runSiteAction(spider, TvBoxSourceMapper.actionOfSegment(segment))
        }
        val root = when (segment) {
            "popular" -> explorePopular(site, spider)
            "latest" -> exploreLatest(site, spider, page)
            // folder 条目与普通分类同构: 段就是分类 id (FongMi openFolder 走 categoryContent)
            else -> parseResult(
                spider.categoryContent(segment, page.coerceAtLeast(1).toString(), false, HashMap()),
                site,
            )
        }
        bookListPageOf(bookSource, siteKey, root, page)
    }

    /**
     * 执行站点动作 (FongMi `SiteApi.action` 同语义): 取 spider 返回 JSON 的 `msg` 弹提示,
     * 返回空页使发现页不展示任何条目。
     *
     * FongMi 只把 `msg` 弹给用户 (`TypeFragment.getAction().observe(... Notify.show(result.getMsg()))`),
     * 且 `code != 0` 时 `Result.getMsg()` 返回空串 (即失败不弹) —— 此处照搬该判定。
     */
    private fun runSiteAction(spider: Spider, action: String): BookListPage {
        val json = spider.action(action)
        val root = parseResultOrNull(json)
        if (root != null && root.optInt("code", 0) == 0) {
            val msg = root.optString("msg").trim()
            if (msg.isNotEmpty()) runCatching { Toasters.get().toast(msg) }
        }
        return BookListPage(ArrayList(), false)
    }

    /**
     * 首页推荐: `homeVideoContent()` (FongMi homeVod) 优先, 无 list 时退 `homeContent(false)`。
     * 两个面生态 spider 常只实现其一 (cat 系给 homeContent 的 class+list, drpy2 系给 homeVod)。
     */
    private fun explorePopular(site: TvBoxSite, spider: Spider): JSONObject {
        val homeVod = runCatching { spider.homeVideoContent() }.getOrNull()
        parseResultOrNull(homeVod)?.takeIf { (it.optJSONArray("list")?.length() ?: 0) > 0 }
            ?.let { return it }
        return parseResult(spider.homeContent(false), site)
    }

    /**
     * 同源取最新: TVBox 无独立"最新"接口, 用首页第一个分类 (`class[0].type_id`) 的分类页。
     * 无 class 即该 spider 不提供可定位的列表页, 如实抛错。
     */
    private fun exploreLatest(site: TvBoxSite, spider: Spider, page: Int): JSONObject {
        val home = parseResult(spider.homeContent(false), site)
        val tid = home.optJSONArray("class")
            ?.optJSONObject(0)?.optString("type_id")?.trim()
            .orEmpty()
        check(tid.isNotEmpty()) { "TVBox 站点无分类(class)可定位最新, 不支持 latest 发现" }
        return parseResult(
            spider.categoryContent(tid, page.coerceAtLeast(1).toString(), false, HashMap()),
            site,
        )
    }

    /**
     * Spider 列表结果 → [BookListPage]: 逐项映射 vod_id/vod_name/vod_pic/vod_remarks。
     * 搜索与发现共用 (两路响应结构同为 `{list:[Vod], pagecount?}`)。
     *
     * 两类非视频条目按 FongMi 语义改写成 `"::"` 伪 URL (点击即进发现页, 不当视频打开):
     * - `action` 非空 (FongMi `Vod.isAction`, 扫码登录/刷新 token 类) → [TvBoxSourceMapper.actionBookUrlOf];
     * - FongMi `Vod.isFolder` (`vod_tag == "folder"` 或 `cate` 非 null, 子分类) →
     *   [TvBoxSourceMapper.folderBookUrlOf]。
     * 两者判定次序与 FongMi `TypeFragment.onItemClick` 一致: action 优先于 folder。
     *
     * 另一类非视频条目直接丢弃: 换源指令伪 vod_id (含 `tvbox://`, FongMi DetailActivity 的
     * 跨站跳转, 站「看球」列表即此形态) —— legado 按站点拆虚拟书源、无换源面, 进种子
     * 只会在详情阶段空数据报错。
     *
     * `cate` 在 FongMi 里只被 `Vod.isFolder()` 消费 (其 land/circle/ratio 无任何调用点,
     * `Vod.getStyle()` 走的是平铺字段), 故此处也只当 folder 标记用, 不解析样式。
     */
    private fun bookListPageOf(
        bookSource: BookSource,
        siteKey: String,
        root: JSONObject,
        page: Int,
    ): BookListPage {
        val items = root.optJSONArray("list") ?: org.json.JSONArray()
        val books = ArrayList<SearchBook>(items.length())
        for (i in 0 until items.length()) {
            val item = items.optJSONObject(i) ?: continue
            val vodId = item.optString("vod_id").trim()
            val name = item.optString("vod_name").trim()
            if (vodId.isEmpty() || name.isEmpty()) continue
            val action = item.optString("action").trim()
            val bookUrl = when {
                action.isNotEmpty() -> TvBoxSourceMapper.actionBookUrlOf(name, action)
                item.isFolderVod() -> TvBoxSourceMapper.folderBookUrlOf(name, vodId)
                // tvbox:// 伪 vod_id 是换源指令 (站「看球」列表项形如
                // "tvbox://看球/http%3A%2F%2Fwww.88kanqiu.us"), 不是本站可取数视频;
                // legado 无换源面, 条目丢弃不进种子, 否则 detailContent 空详情报
                // "TVBox 详情无数据: tvbox://…"。
                vodId.contains("tvbox://") -> continue
                else -> TvBoxSourceMapper.bookUrlOf(siteKey, vodId)
            }
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
        // pagecount 缺失时以本页有数据作为"还有下一页"的近似 (生态多数列表接口无总页数)
        val hasNextPage = if (root.has("pagecount")) {
            page < root.optInt("pagecount", page)
        } else {
            books.isNotEmpty()
        }
        return BookListPage(books, hasNextPage)
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
            // TVBox 分流模型: vod_play_from/vod_play_url 按 "$$$" 一一配对, 每条线路是一套
            // 平行剧集目录。全线路展开进目录, 线路名作卷级分组头 (isVolume, 目录页/选集网格
            // 可收合): 选集即选线路, 换线路=换章节。
            val flags = vod.optString("vod_play_from").split("$$$")
            val playLines = vod.optString("vod_play_url").split("$$$")
            // 按位配对 (FongMi 同语义): 同名线路在生态配置里常见 (如 qq$$$m3u8$$$qq),
            // 按名字反查索引会把第二条并到第一条, 导致整线剧集丢失/首线重复输出。
            val lines = flags.mapIndexedNotNull { index, flag ->
                val name = flag.trim()
                if (name.isEmpty()) null else name to playLines.getOrNull(index).orEmpty()
            }
            val multiLine = lines.size > 1
            val chapters = ArrayList<BookChapter>()
            for ((lineFlag, line) in lines) {
                if (multiLine) {
                    chapters.add(
                        BookChapter(
                            bookUrl = book.bookUrl,
                            url = "tvbox-line://$lineFlag",
                            title = lineFlag,
                            index = chapters.size,
                            isVolume = true,
                        ),
                    )
                }
                for (entry in line.split("#")) {
                    val trimmed = entry.trim()
                    if (trimmed.isEmpty()) continue
                    val name = trimmed.substringBefore('$').trim()
                    val id = trimmed.substringAfter('$', missingDelimiterValue = trimmed).trim()
                    if (id.isEmpty()) continue
                    chapters.add(
                        BookChapter(
                            bookUrl = book.bookUrl,
                            // 跨线路同 id 集靠线路前缀区分: BookChapterList.updateBook 按 url 去重,
                            // 裸 id 会被静默吞掉 (取播时剥回裸 id)
                            url = episodeUrlOf(lineFlag, id),
                            title = name.ifBlank { id },
                            index = chapters.size,
                            tag = lineFlag.ifBlank { null },
                        ),
                    )
                }
            }
            check(chapters.size > if (multiLine) lines.size else 0) {
                "TVBox 站点无剧集数据: ${vod.optString("vod_name")}"
            }
            // 与规则链同构: updateBook 负责 reverse/index/totalChapterNum 等目录簿记。
            // updateBook 契约输入=新章在前 (小说接口方向, 默认 reverse 成正序); 委派目录
            // 天然正序 (线路卷头→剧集), 预反转一次让默认 reverse 恢复正序; reverseToc=true
            // 时保持"新章在前"即倒序显示, 与小说"目录倒序"语义一致
            BookChapterList.updateBook(book, chapters.asReversed())
        }
    }.onFailure {
        if (it is kotlinx.coroutines.CancellationException) throw it
        currentCoroutineContext().ensureActive()
    }

    override suspend fun getContentAwait(
        bookSource: BookSource,
        book: Book,
        bookChapter: BookChapter,
    ): String = withContext(IoDispatcher) {
        val siteKey = TvBoxSourceMapper.siteKeyOf(bookSource.bookSourceUrl)
        val (site, spider) = TvBoxManager.spiderFor(siteKey)
        // 章节自带线路归属 (目录卷级分组, tag=线路名):
        // 取数即本章线路的本集 → 多档数组全收 → VideoSource JSON (resolutions=清晰度档,
        // 播放器内切换); 非直链走解析/嗅探链路 (单链内容串)。
        check(!bookChapter.isVolume) { "分组标题不可播放: ${bookChapter.title}" }
        // 线路名恒随目录落在章节 tag 上 (getChapterListAwait 卷级分组), 取数无需二次 detailContent
        val flag = bookChapter.tag.orEmpty()
        check(flag.isNotEmpty()) { "TVBox 站点无线路数据: ${book.name}" }
        val parses = TvBoxManager.config?.parses.orEmpty()
        // vipFlags 对齐 FongMi SiteApi.playerContent 的 VodConfig.get().getFlags()
        val vipFlags = TvBoxManager.config?.flags.orEmpty()
        val p = parseResult(spider.playerContent(flag, episodeIdOf(flag, bookChapter.url), vipFlags), site)
        val playUrl = playUrlOf(p, site)
        // 空地址与"非直链"是两回事: 前者站点没给任何可取内容, 后者有页可嗅探/解析;
        // 混进嗅探链会把"站点没数据"误报成"无法嗅探", 排查时被带偏。
        check(playUrl.isNotEmpty()) { "TVBox 站点未返回播放地址: ${site.name} ($flag)" }
        if (!needsParse(p, flag, parses) && !isPlayPage(playUrl) && playUrl.startsWith("http")) {
            return@withContext GSON.toJson(VideoSource(resolutions = qualitiesOf(p, site)))
        }
        // 嗅探复用本次 playerContent 结果: 同一 flag/id 重调一次既多一次站点请求 (jar 侧实测
        // 单站 16~28s), 对一次性 token 类 spider 还会因第二次调用拿到不同结果而失败
        return@withContext sniffContent(site, spider, p, flag, parses)
    }

    /**
     * playerContent 结果 → 本线路清晰度档: url 为多档数组串取全部 [名,址] 对, 单链取「默认」一档;
     * 档共享该次取数的请求头 (FongMi getRealUrl 同语义: playUrl 解析前缀恒拼在档址前)
     * 与源声明的媒体类型 (FongMi PlaySpec.format 同语义: playerContent 的 format 字段,
     * BiliGuard 等 DASH 源返回 application/dash+xml)。
     */
    private fun qualitiesOf(root: JSONObject, site: TvBoxSite): List<VideoResolution> {
        val headers = headerOf(root)
        val mime = root.optString("format").trim()
        val prefix = root.optString("playUrl").trim().ifBlank { site.playUrl }
        val raw = root.optString("url").trim()
        val arr = if (raw.startsWith("[")) runCatching { JSONArray(raw) }.getOrNull() else null
        if (arr != null) {
            val qualities = ArrayList<VideoResolution>()
            var index = 0
            while (index + 1 < arr.length()) {
                val url = arr.optString(index + 1)
                if (url.startsWith("http")) {
                    qualities.add(VideoResolution(name = arr.optString(index), url = prefix + url, headers = headers, mime = mime))
                }
                index += 2
            }
            if (qualities.isNotEmpty()) return qualities
        }
        val url = playUrlOf(root, site)
        return if (url.startsWith("http")) {
            listOf(VideoResolution(name = "默认", url = url, headers = headers, mime = mime))
        } else {
            emptyList()
        }
    }

    /**
     * 单线路非直链取播: 按解析配置 type 分支取真实媒体地址 (FongMi ParseJob.doInBackground 同语义):
     * type=1 json API (纯 HTTP, 快) → type=0 WebView 嗅探 → type=2/3 jar 聚合类; 首个成功即用。
     *
     * @param p 本次取播的 playerContent 结果 (调用方已调, 本函数不重调 spider):
     *   FongMi `SiteApi.playerContent` 也是一次取数一个 Result 贯穿解析全程,
     *   重调既多一次站点请求, 也会让一次性 token 类 spider 的第二次调用失败。
     *   播放页与请求头均从它取 ([playUrlOf] / [headerOf])。
     */
    private suspend fun sniffContent(
        site: TvBoxSite,
        spider: Spider,
        p: JSONObject,
        flag: String,
        parses: List<TvBoxParse>,
    ): String {
        val videoPage = playUrlOf(p, site)
        val headers = headerOf(p)
        var lastError: Throwable? = null
        val startedAt = System.currentTimeMillis()
        // 总预算按剩余下传: 各段独立超时叠加最坏可达分钟级 (json 30s + 嗅探 30s×≤4 页 + 聚合)
        fun remainingMs(): Long =
            (TvBoxSniffer.TOTAL_TIMEOUT_MS - (System.currentTimeMillis() - startedAt)).coerceAtLeast(1L)

        // 本地 suspend 函数: 嗅探/解析 API 均为挂起调用, 逐个形态串行尝试
        suspend fun attempt(block: suspend (Long) -> TvBoxSniffResult): String? = try {
            val budget = remainingMs()
            val result = withTimeout(budget) { block(budget) }
            contentOf(result.url, headers + result.headers)
        } catch (e: TimeoutCancellationException) {
            // 预算耗尽: 记为普通失败, 不让调用方把它当协程取消静默吞掉
            lastError = e
            null
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
        attempt { budget ->
            TvBoxSniffer.sniff(
                page,
                headers,
                timeoutMs = minOf(budget, TvBoxSniffer.DEFAULT_TIMEOUT_MS),
                videoChecker = videoPredicateOf(spider),
            )
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
        if (remainingMs() <= 1L) {
            error(
                "TVBox 解析链超过总预算 ${TvBoxSniffer.TOTAL_TIMEOUT_MS}ms " +
                    "(json API/网页嗅探/聚合): ${site.name}",
            )
        }
        error(
            "TVBox 解析站全形态失败 (json API/网页嗅探/聚合): ${lastError?.message ?: "无适用解析配置"}",
        )
    }

    /**
     * 结果指向的播放地址: playUrl 作前缀 + url (FongMi Result.getRealUrl() 同语义);
     * url 为多清晰度数组串时取第一路地址 (形态对齐 FongMi bean/Video: ["名","址",…], 按清晰度降序),
     * 且与 [qualitiesOf] 主路径同口径拼上解析前缀。 */
    private fun playUrlOf(root: JSONObject, site: TvBoxSite): String {
        val prefix = root.optString("playUrl").trim().ifBlank { site.playUrl }
        val url = root.optString("url").trim()
        if (url.startsWith("[")) {
            val first = firstQualityUrl(url)
            return if (first.startsWith("http") && prefix.isNotEmpty()) prefix + first else first
        }
        if (url.startsWith("http")) return url
        return if (prefix.isBlank()) url else prefix + url
    }

    /** 多清晰度数组 `[名,址,名,址…]` 取首个 http 地址; 解析失败/无地址退回原文。 */
    private fun firstQualityUrl(raw: String): String {
        val arr = runCatching { JSONArray(raw) }.getOrNull() ?: return raw
        for (i in 0 until arr.length() - 1 step 2) {
            val candidate = arr.optString(i + 1)
            if (candidate.startsWith("http")) return candidate
        }
        return raw
    }

    /**
     * 需要解析的判定 (FongMi `bean/Result.isUseParse()` 同语义, 刻意非 needParse()):
     * 配置无解析项时恒直连; playUrl 解析站前缀为空且线路命中解析 flags, 或 `jx=1`。
     * 注意 `parse=1` 单独存在不触发解析 —— jar 常对自有代理媒体地址 (proxy?do=…) 标 parse=1,
     * 按 needParse() 处理会把它误送网页嗅探。
     */
    private fun needsParse(root: JSONObject, flag: String, parses: List<TvBoxParse>): Boolean {
        if (parses.isEmpty()) return false
        if (root.optInt("jx", 0) != 0) return true
        val parsePrefix = root.optString("playUrl").trim()
        return parsePrefix.isEmpty() && parses.any { flag in it.flags }
    }

    /**
     * 网页播放页判定 (直链 vs 嗅探分界; 真机 fty.json 48 站排查扩展)。
     *
     * 恒直链:
     * - 本地地址 ([isLocalHostAddress]): jar 本地代理 (proxy?do=…) 是 jar 已处理好的
     *   媒体资源, 永不作网页嗅探对象;
     * - 带媒体信号的地址 ([hasMediaSignal])。
     *
     * 判嗅探:
     * - 既有肉眼形态: `.html/.htm/.shtml` 路径或带参直链之外的解析站形态
     *   (`?url=http` / `?v=http`), 判据见 [isParsePageUrl];
     * - 兜底: 外网 http(s) 地址路径与 query 均无任何媒体信号 → 视为播放页送嗅探。
     *   依据是媒体后缀判定取反: 直链必带媒体信号 (CDN 直链后缀或 type=m3u8 类媒体参数
     *   二者居一), 网页播放页两样皆无 —— 真机实测 3 站 playerContent 返回 parse=0 且
     *   url 为网页地址被旧判定误放行直链 (Dm84 → hhjx.hhplayer.com/?url=<hex> 解析页;
     *   虎牙/斗鱼 drpy2 js 源 → m.huya.com/<房间号> 等直播房间页)。不用域名白名单
     *   (特例适配被禁), 也不用直播类源特征 (站点配置字段不可靠) 做特判。
     */
    private fun isPlayPage(url: String): Boolean {
        if (isLocalHostAddress(url)) return false
        if (isParsePageUrl(url)) return true
        return url.startsWith("http") && !hasMediaSignal(url)
    }

    /** 剧集 url 入目录前按线路名加前缀 (与 `tvbox-line://` 卷头同族): 跨线路同 id 集靠它不被去重。 */
    private fun episodeUrlOf(lineFlag: String, id: String): String = "$EPISODE_SCHEME$lineFlag/$id"

    /** 取播时剥回裸 id (FongMi `playerContent` 第二参即 vod_play_url 的原值)。 */
    private fun episodeIdOf(lineFlag: String, url: String): String =
        url.removePrefix("$EPISODE_SCHEME$lineFlag/")

    private const val EPISODE_SCHEME = "tvbox-ep://"

    /**
     * 播放页兜底判定用的媒体扩展名表: AndroidX Media3 `Util.inferContentType` 的
     * mpd/m3u8/ism 与常见流式后缀并集, 覆盖 [TvBoxSniffer.MEDIA_EXTENSIONS] 全集
     * (嗅探面仍以 TvBoxSniffer 自身的表为准, 此处是判定直链用的只读副本);
     * hls/dash 不是文件扩展名, 仅供 query 类型参数值 (type=hls) 比对。
     */
    private val MEDIA_STREAM_EXTENSIONS = listOf(
        "m3u8", "mpd", "ism", "isml", "mp4", "mkv", "flv", "ts", "m4s", "m4v", "mov",
        "webm", "avi", "mpg", "mpeg", "wmv", "3gp",
        "mp3", "m4a", "aac", "flac", "ogg", "opus", "wav",
        "hls", "dash",
    )

    /**
     * URL 是否带媒体信号 (直链的宽松面, 命中任一即不进嗅探; 纯字符串判定):
     * - 字节系 CDN 无后缀直链路径 `video/tos` (对齐 TvBoxSniffer SNIFFER 的同名特例)
     *   与 udpxy 组播代理路径 `/udp/`;
     * - 路径末段以媒体扩展名结尾 (带参 CDN 直链很常见, query 不参与此项);
     * - query 任一参数值为媒体扩展名 (`type=mpd` / `do=m3u8`) 或以 `.媒体扩展名` 结尾
     *   (`url=…/x.m3u8`, 编码形态 `…%2Fx.m3u8` 同样命中)。
     */
    private fun hasMediaSignal(url: String): Boolean {
        val lower = url.lowercase()
        if (lower.contains("video/tos") || lower.contains("/udp/")) return true
        val file = lower.substringBefore('?').substringBefore('#').substringAfterLast('/')
        if (MEDIA_STREAM_EXTENSIONS.any { file.endsWith(".$it") }) return true
        val query = lower.substringAfter('?', "").substringBefore('#')
        return query.split('&').any { param ->
            val value = param.substringAfter('=', "")
            MEDIA_STREAM_EXTENSIONS.any { value == it || value.endsWith(".$it") }
        }
    }

    /**
     * http(s) 地址是否指向本机 (host 为 127.0.0.1/localhost/[::1])。旧实现只认
     * `127.0.0.1:<Proxy端口>/proxy` 前缀, 端口漂移或 localhost 写法会漏判而误入嗅探。
     */
    private fun isLocalHostAddress(url: String): Boolean {
        if (!url.startsWith("http")) return false
        val authority = url.substringAfter("://", "").substringBefore('/')
        if (authority.isEmpty()) return false
        val host = if (authority.startsWith("[")) {
            authority.substringAfter('[').substringBefore(']')
        } else {
            authority.substringBefore(':')
        }.lowercase()
        return host == "127.0.0.1" || host == "localhost" || host == "::1"
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

    /** 容错解析: 空串/非法 JSON 一律 null (用于"某一路可选接口没给数据"的探测)。 */
    private fun parseResultOrNull(json: String?): JSONObject? {
        if (json.isNullOrBlank()) return null
        return runCatching { JSONObject(json.trim()) }.getOrNull()
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
