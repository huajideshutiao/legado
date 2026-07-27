package io.legado.app.model.rss

import io.legado.app.constant.AppLog
import io.legado.app.constant.BookType
import io.legado.app.data.AppDbProviders
import io.legado.app.data.entities.Book
import io.legado.app.data.entities.BookChapter
import io.legado.app.help.book.addType
import io.legado.app.help.book.isRss
import io.legado.app.help.book.removeType
import io.legado.app.model.webBook.WebBook
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

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
 * 本类把 app 端 `ReadRssActivity` / `ReadRssViewModel` + 桌面端 3 个 Rss Screen 的
 * 核心数据流编排下沉到 commonMain, 让 Android / Desktop / iOS / 鸿蒙 复用同一逻辑,
 * 避免桌面端独立重写。
 *
 * # 下沉范围
 *
 * - [flowRssSources]: 订阅 bookDao.flowAll() 过滤 isRss (对应桌面端 RssSourcesScreen)
 * - [loadRssArticles]: 拉取文章列表 (对应桌面端 RssArticlesScreen + app 端 BaseReadViewModel.upBook)
 * - [loadRssContent]: 拉取正文 (对应桌面端 ReadRssScreen + app 端 ReadRssViewModel.initData)
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
     * RSS 源列表数据流: 订阅 bookDao.flowAll() 过滤 [Book.isRss]。
     *
     * RSS 源以 Book 形式存在 (type 含 BookType.rss 位), 列表行显示 `book.name` + feed URL。
     * 对应桌面端 RssSourcesScreen 的 `produceState { flowAll().map { filter isRss } }`。
     */
    fun flowRssSources(): Flow<List<Book>> =
        appDb.bookDao.flowAll().map { list -> list.filter { it.isRss } }

    /**
     * 拉取 RSS 文章列表 ([BookChapter] 列表)。
     *
     * 流程 (对照桌面端 RssArticlesScreen.loadArticles + app 端 BaseReadViewModel.upBook):
     * 1. RSS Book 兜底: tocUrl 为空时用 bookUrl 作为请求 URL
     *    (app 端 upBook: `if (book.isRss) { book.tocUrl = book.bookUrl }`)
     * 2. 取关联 BookSource (`book.origin = bookSource.bookSourceUrl`)
     * 3. [WebBook.getChapterListAwait] 拉取最新文章列表
     * 4. 落库 bookChapterDao.insert (按 bookUrl 关联)
     * 5. 返回 Result: 成功返回新拉取的列表; 失败抛异常, 调用方用 [getCachedArticles] 回退
     *
     * @param book RSS 源对应的 Book (type 含 BookType.rss 位)
     * @return 成功返回新拉取的章节列表; 失败抛异常, 调用方用 [getCachedArticles] 回退
     */
    suspend fun loadRssArticles(book: Book): Result<List<BookChapter>> = runCatching {
        // RSS Book 兜底: tocUrl 为空时用 bookUrl 作为请求 URL
        // (对照 BaseReadViewModel.upBook: if (book.isRss) { book.tocUrl = book.bookUrl })
        val requestBook = if (book.isRss && book.tocUrl.isBlank()) {
            book.copy(tocUrl = book.bookUrl)
        } else {
            book
        }
        // 取关联的 BookSource (book.origin = bookSource.bookSourceUrl)
        val source = appDb.bookSourceDao.getBookSource(book.origin)
            ?: throw IllegalStateException("未找到书源 (origin=${book.origin})")
        // 联网拉取最新文章列表
        val toc = WebBook.getChapterListAwait(source, requestBook).getOrThrow()
        // 落库 (按 book.bookUrl 关联, RSS Book 已入库则 bookUrl="data:")
        appDb.bookChapterDao.insert(*toc.toTypedArray())
        toc
    }.onFailure {
        // 协程取消需向上抛出, 不被 runCatching 吞掉
        currentCoroutineContext().ensureActive()
    }

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
     * 文章章节已由 [loadRssArticles] 落库, 无需像 app 端再插 chapterListData。
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
     * 分支 (对照桌面端 ReadRssScreen.loadContent + app 端 ReadRssViewModel.initData):
     * 1. `book.originName == "RSS" && !book.intro.isNullOrBlank()` → 直接返回 intro (RSS 源简介)
     * 2. `source.contentRule.content` 为空 → 返回 [RssContentResult.Empty] (无正文规则, UI 提示用浏览器打开)
     * 3. 否则 [WebBook.getContentAwait] 拉正文 → [RssContentResult.Content]
     *
     * 注意: 返回的 [RssContentResult.Content.body] 为原始 HTML, 调用方按平台方式渲染:
     * - app 端: `clHtml(body)` 包 HTML 给 WebView
     * - 桌面端: `HtmlFormatter.format(body)` 转纯文本
     *
     * 失败时返回 [RssContentResult.Error] (内部已记录 [AppLog]), 不会抛异常, 调用方无需 try-catch。
     *
     * @param book RSS 源对应的 Book
     * @param chapterIndex 章节 index (对应 RssArticlesScreen 点击回调)
     * @return [RssContentResult] 三态结果
     */
    suspend fun loadRssContent(book: Book, chapterIndex: Int): RssContentResult {
        return try {
            val source = appDb.bookSourceDao.getBookSource(book.origin)
                ?: throw IllegalStateException("未找到书源 (origin=${book.origin})")
            val chapter = appDb.bookChapterDao.getChapter(book.bookUrl, chapterIndex)
                ?: throw IllegalStateException("未找到章节 (index=$chapterIndex)")
            when {
                // 对照 ReadRssViewModel.initData: originName=="RSS" && intro 非空 → 直接显示 intro
                book.originName == "RSS" && !book.intro.isNullOrBlank() ->
                    RssContentResult.Content(book.intro!!, chapter)
                // 对照 ReadRssViewModel.initData: contentRule.content 为空 → 无正文规则
                source.contentRule.content.isNullOrEmpty() ->
                    RssContentResult.Empty(chapter)
                else -> {
                    val body = WebBook.getContentAwait(source, book, chapter)
                    if (body.isBlank()) RssContentResult.Empty(chapter)
                    else RssContentResult.Content(body, chapter)
                }
            }
        } catch (e: Throwable) {
            // 协程取消需向上抛出, 不被 catch 吞掉
            if (e is kotlinx.coroutines.CancellationException) throw e
            AppLog.put("RSS 正文加载失败\n${e.message}", e)
            RssContentResult.Error(e.message ?: "加载失败", null)
        }
    }
}

/**
 * RSS 正文加载结果 (三态 sealed class)。
 *
 * 供 UI 层 `when` 穷尽分支渲染:
 * - [Content]: 拿到正文 (intro 或 WebBook 拉取的原始 HTML body), 调用方按平台格式化渲染
 * - [Empty]: 无正文规则或内容为空, UI 提示 "无正文规则, 请用浏览器打开"
 * - [Error]: 加载失败, [Error.message] 供 UI 显示; [Error.chapter] 可能为 null (加载章节本身失败时)
 */
sealed class RssContentResult {
    /** 关联的章节 (供 UI 显示标题 + 浏览器打开 chapter.url; Error 态可能为 null) */
    abstract val chapter: BookChapter?

    /** 正文 (intro 或 WebBook.getContentAwait 返回的原始 HTML body, 非空) */
    data class Content(val body: String, override val chapter: BookChapter) : RssContentResult()

    /** 无正文规则或内容为空 (UI 提示用浏览器打开) */
    data class Empty(override val chapter: BookChapter) : RssContentResult()

    /** 加载失败 (message 供 UI 显示; chapter 可能为 null) */
    data class Error(val message: String, override val chapter: BookChapter?) : RssContentResult()
}
