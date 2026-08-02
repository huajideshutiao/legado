package io.legado.app.model.rss

import io.legado.app.constant.AppConst
import io.legado.app.constant.AppLog
import io.legado.app.constant.BookType
import io.legado.app.data.AppDbProviders
import io.legado.app.data.entities.Book
import io.legado.app.data.entities.BookChapter
import io.legado.app.data.entities.BookSource
import io.legado.app.help.book.addType
import io.legado.app.help.book.isNotShelf
import io.legado.app.help.book.removeType
import io.legado.app.model.analyzeRule.AnalyzeUrlFactories
import io.legado.app.model.rss.RssHelp.clHtml
import io.legado.app.model.rss.RssHelp.loadRssContent
import io.legado.app.model.script.runScriptWithContext
import io.legado.app.model.webBook.WebBook
import io.legado.app.ui.book.read.fetchChapterListFromSource
import io.legado.app.utils.NetworkUtils
import kotlinx.coroutines.currentCoroutineContext

/**
 * RSS 业务编排层 (shared commonMain, 供多端复用)。
 *
 * # 背景
 *
 * app 端无独立 RssSource / RssArticle 实体, RSS 源 = `Book(type |= BookType.rss)`,
 * RSS 文章 = [BookChapter] (章节列表即文章列表, 正文走 `BookSource.contentRule`)。
 * shared 端已有的 [io.legado.app.data.entities.OldRssSource] 仅用于旧 rssSources.json
 * 迁移, 非运行时实体, 故本模块不新建 RssSource / RssArticle 实体。
 *
 * 本类把 app 端 `ReadRssActivity` / `ReadRssViewModel` 的核心数据流编排下沉到 commonMain,
 * 让 Android / Desktop / iOS / 鸿蒙 复用同一逻辑。
 *
 * # 下沉范围
 *
 * - [loadRssContent]: 拉取正文 (对应 app 端 ReadRssViewModel.initData)
 *
 * # 不下沉项 (留各端 UI 层)
 *
 * - 正文渲染格式化: app 端 `clHtml` (包 HTML 给 WebView), 桌面端 `HtmlFormatter.format` (转纯文本)。
 *   各端渲染方式不同, 本类只返回原始 HTML body, 由调用方格式化。
 * - 浏览器打开外链: 桌面端 `java.awt.Desktop.browse`, app 端 `Intent/Uri`, 留各端 UI 层。
 * - TTS 朗读: app 端 `ReadRssViewModel.tts` 依赖 Android TextToSpeech, 留 app 端。
 *
 * # DAO 访问
 *
 * 走 [AppDbProviders.get()] 间接 (宿主启动时由 app/desktop 端注册 AppDbAccessorImpl),
 * 替代 app 端 `appDb.xxxDao` 单例, 行为与 app 端一致。
 */
object RssHelp {

    /** DAO 容器 (宿主启动时由 app/desktop 端注册 AppDbAccessorImpl)。 */
    private val appDb get() = AppDbProviders.get()

    /**
     * 读取本地缓存的文章列表 (联网失败时回退用)。
     *
     * @param book RSS 源对应的 Book, 按 [Book.bookUrl] 关联章节
     */
    suspend fun getCachedArticles(book: Book): List<BookChapter> =
        appDb.bookChapterDao.getChapterList(book.bookUrl)

    /**
     * 收藏 RSS 源 (对照 app 端 BaseReadViewModel.addToBookshelf):
     * order 置底 + 继承同名书阅读进度 + 清 notShelf 标记后落库。
     * 无需像 app 端再插 chapterListData。
     */
    suspend fun addToBookshelf(book: Book) {
        if (book.order == 0) {
            book.order = appDb.bookDao.minOrder() - 1
        }
        appDb.bookDao.getBook(book.name, book.author)?.let {
            book.durChapterIndex = it.durChapterIndex
            book.durChapterPos = it.durChapterPos
            book.durChapterTitle = it.durChapterTitle
        }
        // 对照 app 端 Book.save(): removeType(notShelf) + insert-or-update
        book.removeType(BookType.notShelf)
        if (appDb.bookDao.has(book.bookUrl)) {
            appDb.bookDao.update(book)
        } else {
            appDb.bookDao.insert(book)
        }
    }

    /**
     * 取消收藏 (对照 app 端 BaseReadViewModel.delBook: 删章节 + 删书 + 标记 notShelf)。
     */
    suspend fun removeFromBookshelf(book: Book) {
        appDb.bookChapterDao.delByBook(book.bookUrl)
        appDb.bookDao.delete(book)
        book.addType(BookType.notShelf)
    }

    /**
     * 拉取 RSS 文章正文。
     *
     * 分支 (对照 app 端 ReadRssViewModel.initData):
     * 1. `book.originName == "RSS" && !book.intro.isNullOrBlank()` → 直接返回 intro (RSS 源简介),
     *    baseUrl 取 `book.tocUrl` (与原版一致, 不解析绝对地址)
     * 2. `source.contentRule.content` 为空 → [RssContentResult.Url]: 无正文规则, 走 AnalyzeUrl
     *    解析出真实地址 + 请求头交 WebView `loadUrl(url, headerMap)`
     * 3. 否则 [WebBook.getContentAwait] 拉正文 → [RssContentResult.Content]
     *
     * 返回的 [RssContentResult.Content.html] 已经过 [clHtml] 包装 (webJs 脚本或图片/视频自适应样式),
     * 调用方直接交 WebView `loadDataWithBaseURL(baseUrl, html, ...)` 即可, 与原版渲染一致。
     *
     * 失败时返回 [RssContentResult.Error] (内部已记录 [AppLog]), 不会抛异常, 调用方无需 try-catch。
     *
     * @param book RSS 源对应的 Book
     * @param chapterIndex 章节 index (文章在章节列表中的位置)
     * @return [RssContentResult] 三态结果
     */
    suspend fun loadRssContent(book: Book, chapterIndex: Int): RssContentResult {
        return try {
            val source = appDb.bookSourceDao.getBookSource(book.origin)
                ?: throw IllegalStateException("未找到书源 (origin=${book.origin})")
            val intro = book.intro
            // 对照 ReadRssViewModel.initData: originName=="RSS" && intro 非空 → 直接显示 intro
            if (book.originName == "RSS" && !intro.isNullOrBlank()) {
                return RssContentResult.Content(
                    html = clHtml(source, intro),
                    baseUrl = book.tocUrl,
                    userAgent = readUserAgent(source),
                    chapter = null,
                )
            }
            // 对照原版 ReadRssViewModel.initData: upBook 拉取目录后从 chapterList 取章;
            // DB 无目录时回源拉取 (书架书落库; runPerJs 对照原版 loadChapterList 的
            // inBookshelf 参数), 避免"未入架/目录未入库的书 → 未找到章节"功能退化
            val chapter = appDb.bookChapterDao.getChapter(book.bookUrl, chapterIndex)
                ?: fetchChapterListFromSource(
                    book,
                    source,
                    runPerJs = !book.isNotShelf,
                ).getOrNull(chapterIndex)
                ?: throw IllegalStateException("未找到章节 (index=$chapterIndex)")
            // 对照 ReadRssViewModel.initData: baseUrl 按是否 RSS 源在书源地址与目录地址间二选一
            val baseUrl = if (book.originName == "RSS") source.bookSourceUrl else book.tocUrl
            if (source.contentRule.content.isNullOrBlank()) {
                // 无正文规则: 原版走 AnalyzeUrl(hasLoginHeader=false) 后 loadUrl(url, headerMap)
                val analyzeUrl = AnalyzeUrlFactories.create(
                    rawUrl = chapter.url,
                    baseUrl = baseUrl,
                    source = source,
                    coroutineContext = currentCoroutineContext(),
                    hasLoginHeader = false,
                )
                return RssContentResult.Url(
                    url = analyzeUrl.url,
                    headerMap = analyzeUrl.headerMap.toMap(),
                    userAgent = analyzeUrl.getUserAgent(),
                    chapter = chapter,
                )
            }
            val body = WebBook.getContentAwait(source, book, chapter)
            RssContentResult.Content(
                html = clHtml(source, body),
                baseUrl = NetworkUtils.getAbsoluteURL(baseUrl, chapter.url),
                userAgent = readUserAgent(source),
                chapter = chapter,
            )
        } catch (e: Throwable) {
            // 协程取消需向上抛出, 不被 catch 吞掉
            if (e is kotlinx.coroutines.CancellationException) throw e
            AppLog.put("RSS 正文加载失败\n${e.message}", e)
            RssContentResult.Error(e.message ?: "加载正文失败", null)
        }
    }

    /** 书源 header 里的 UA (对照 `runScriptWithContext { source.getHeaderMap()[UA_NAME] }`)。 */
    private suspend fun readUserAgent(source: BookSource): String? =
        runCatching { runScriptWithContext { source.getHeaderMap()[AppConst.UA_NAME] } }.getOrNull()

    /**
     * 正文 HTML 包装 (逐行对照 app 端 ReadRssViewModel.clHtml)。
     *
     * 书源配了 webJs 就把它塞进 `<script>`, 否则给一段图片/视频自适应宽度的样式。
     */
    fun clHtml(source: BookSource?, content: String): String {
        val webJs = source?.contentRule?.webJs
        return if (!webJs.isNullOrEmpty()) {
            """
                <script>
                    $webJs
                </script>
                $content
            """.trimIndent()
        } else {
            """
                <style>
                    img{max-width:100% !important; width:auto; height:auto;}
                    video{object-fit:fill; max-width:100% !important; width:auto; height:auto;}
                    body{word-wrap:break-word; height:auto;max-width: 100%; width:auto;}
                </style>
                $content
            """.trimIndent()
        }
    }
}

/**
 * RSS 正文加载结果 (三态 sealed class)。
 *
 * 供 UI 层 `when` 穷尽分支渲染:
 * - [Content]: 正文 HTML (已 clHtml 包装), WebView `loadDataWithBaseURL(baseUrl, html, ...)`
 * - [Url]: 无正文规则, WebView `loadUrl(url, headerMap)`
 * - [Error]: 加载失败, [Error.message] 供 UI 显示
 *
 * [chapter] 供 UI 显示标题 + "浏览器打开"; intro 分支与 Error 分支可能为 null。
 */
sealed class RssContentResult {
    /** 关联的章节 (intro 分支与 Error 分支为 null) */
    abstract val chapter: BookChapter?

    /** 正文 HTML (已 clHtml 包装), 交 WebView loadDataWithBaseURL */
    data class Content(
        val html: String,
        val baseUrl: String,
        val userAgent: String?,
        override val chapter: BookChapter?,
    ) : RssContentResult()

    /** 无正文规则: WebView 直接 loadUrl(url, headerMap) */
    data class Url(
        val url: String,
        val headerMap: Map<String, String>,
        val userAgent: String?,
        override val chapter: BookChapter,
    ) : RssContentResult()

    /** 加载失败 (message 供 UI 显示; chapter 可能为 null) */
    data class Error(val message: String, override val chapter: BookChapter?) : RssContentResult()
}
