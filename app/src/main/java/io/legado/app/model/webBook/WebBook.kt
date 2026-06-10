package io.legado.app.model.webBook

import io.legado.app.constant.AppConst
import io.legado.app.constant.AppLog
import io.legado.app.data.appDb
import io.legado.app.data.entities.Book
import io.legado.app.data.entities.BookChapter
import io.legado.app.data.entities.BookListPage
import io.legado.app.data.entities.BookSource
import io.legado.app.data.entities.ReviewPage
import io.legado.app.exception.NoStackTraceException
import io.legado.app.help.IntentData
import io.legado.app.help.http.StrResponse
import io.legado.app.help.source.getBookType
import io.legado.app.model.Debug
import io.legado.app.model.analyzeRule.AnalyzeRule
import io.legado.app.model.analyzeRule.AnalyzeUrl
import io.legado.app.model.analyzeRule.RuleData
import io.legado.app.utils.GSON
import io.legado.app.utils.NetworkUtils
import io.legado.app.utils.fromJsonObject
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

object WebBook {

    /**
     * 搜索和发现
     */
    suspend fun getBookListAwait(
        bookSource: BookSource,
        key: String,
        page: Int? = 1,
        filter: ((name: String, author: String) -> Boolean)? = null,
        shouldBreak: ((size: Int) -> Boolean)? = null,
        isSearch: Boolean = true,
        onUrlResolved: ((AnalyzeUrl) -> Unit)? = null,
        selectedOptions: Map<String, String>? = null,
    ): BookListPage {
        var url = key
        if (isSearch) {
            if (bookSource.searchUrl.isNullOrBlank()) throw NoStackTraceException("搜索url不能为空")
            else url = bookSource.searchUrl!!
        }
        val ruleData = RuleData()
        val variables = buildMap<AppConst.JsVarName, Any> {
            if (isSearch) put(AppConst.JsVarName.KEY, key)
            if (page != null) put(AppConst.JsVarName.PAGE, page)
        }
        val analyzeUrl = AnalyzeUrl(
            rawUrl = url,
            baseUrl = bookSource.bookSourceUrl,
            source = bookSource,
            ruleData = ruleData,
            coroutineContext = currentCoroutineContext(),
            selectedOptions = selectedOptions,
            variables = variables
        )
        onUrlResolved?.invoke(analyzeUrl)
        val res = checkLogin(analyzeUrl, bookSource)
        return BookList.analyzeBookList(
            bookSource = bookSource,
            ruleData = ruleData,
            analyzeUrl = analyzeUrl,
            baseUrl = res.url,
            body = res.body,
            isSearch = isSearch,
            isRedirect = checkRedirect(bookSource, res),
            filter = filter,
            shouldBreak = shouldBreak,
        )
    }

    /**
     * 书籍信息
     */
    suspend fun getBookInfoAwait(
        bookSource: BookSource,
        book: Book,
        canReName: Boolean = true,
    ): Book {
        if (!book.infoHtml.isNullOrEmpty()) {
            BookInfo.analyzeBookInfo(
                bookSource = bookSource,
                book = book,
                baseUrl = book.bookUrl,
                redirectUrl = book.bookUrl,
                body = book.infoHtml,
                canReName = canReName
            )
        } else {
            val analyzeUrl = AnalyzeUrl(
                rawUrl = book.bookUrl,
                baseUrl = bookSource.bookSourceUrl,
                source = bookSource,
                ruleData = book,
                coroutineContext = currentCoroutineContext()
            )
            val res = checkLogin(analyzeUrl, bookSource)
            checkRedirect(bookSource, res)
            BookInfo.analyzeBookInfo(
                bookSource = bookSource,
                book = book,
                baseUrl = book.bookUrl,
                redirectUrl = res.url,
                body = res.body,
                canReName = canReName
            )
        }
        return book
    }

    suspend fun getBookInfoByUrlAwait(bookUrl: String): Book{
        if(appDb.bookDao.has(bookUrl)) throw NoStackTraceException("已在书架")
        val baseUrl = NetworkUtils.getBaseUrl(bookUrl)
            ?: throw NoStackTraceException("书籍地址格式不对")
        val urlMatcher = AnalyzeUrl.paramPattern.matcher(bookUrl)
        val source = if (urlMatcher.find()) {
            GSON.fromJsonObject<AnalyzeUrl.UrlOption>(
                bookUrl.substring(urlMatcher.end())
            ).getOrNull()?.origin?.let {
                appDb.bookSourceDao.getBookSource(it)
            }
        }else appDb.bookSourceDao.getBookSourceAddBook(baseUrl)
            ?: appDb.bookSourceDao.hasBookUrlPattern.first { source ->
            bookUrl.matches(source.bookUrlPattern!!.toRegex())
        }
        try {
            IntentData.source = source
            val book = Book(
                bookUrl = bookUrl,
                type = source!!.getBookType(),
                origin = source.bookSourceUrl,
                originName = source.bookSourceName
            )
            return getBookInfoAwait(source, book)
        } catch (_: Exception) {
            throw NoStackTraceException("未找到匹配书源")
        }
    }

    suspend fun getChapterListAwait(
        bookSource: BookSource,
        book: Book,
        runPerJs: Boolean = false
    ): Result<List<BookChapter>> {
        return runCatching {
            if (runPerJs) {
                runPreUpdateJs(bookSource, book).getOrThrow()
            }
            val tocRule = bookSource.getTocRule()
            val isSingleChapter = tocRule.chapterList.isNullOrBlank()
            if (isSingleChapter) {
                Debug.log(bookSource.bookSourceUrl, "⇒目录规则为空,作为单章节书籍处理")
                val chapterList = arrayListOf(
                    BookChapter(
                        bookUrl = book.bookUrl,
                        title = "共一章",
                        url = book.tocUrl
                    )
                )
                return@runCatching BookChapterList.updateBook(bookSource, book, chapterList)
            } else if (book.bookUrl == book.tocUrl && !book.tocHtml.isNullOrEmpty()) {
                BookChapterList.analyzeChapterList(
                    bookSource = bookSource,
                    book = book,
                    baseUrl = book.tocUrl,
                    body = book.tocHtml
                )
            } else {
                val analyzeUrl = AnalyzeUrl(
                    rawUrl = book.tocUrl,
                    baseUrl = book.bookUrl,
                    source = bookSource,
                    ruleData = book,
                    coroutineContext = currentCoroutineContext()
                )
                val res = checkLogin(analyzeUrl, bookSource)
                checkRedirect(bookSource, res)
                BookChapterList.analyzeChapterList(
                    bookSource = bookSource,
                    book = book,
                    baseUrl = book.tocUrl,
                    redirectUrl = res.url,
                    body = res.body
                )
            }
        }.onFailure {
            currentCoroutineContext().ensureActive()
        }
    }

    /**
     * 章节内容
     */
    suspend fun getContentAwait(
        bookSource: BookSource,
        book: Book,
        bookChapter: BookChapter,
        nextChapterUrl: String? = null,
        needSave: Boolean = true
    ): String {
        if (bookSource.getContentRule().content.isNullOrEmpty()) {
            Debug.log(bookSource.bookSourceUrl, "⇒正文规则为空,使用章节链接:${bookChapter.url}")
            return bookChapter.url
        }
        if (bookChapter.isVolume && bookChapter.url.startsWith(bookChapter.title)) {
            Debug.log(bookSource.bookSourceUrl, "⇒一级目录正文不解析规则")
            return bookChapter.tag ?: ""
        }
        val chapterUrl = bookChapter.getAbsoluteURL(book)
        return if (bookChapter.url == book.bookUrl && !book.tocHtml.isNullOrEmpty()) {
            BookContent.analyzeContent(
                bookSource = bookSource,
                book = book,
                bookChapter = bookChapter,
                baseUrl = chapterUrl,
                redirectUrl = chapterUrl,
                body = book.tocHtml,
                nextChapterUrl = nextChapterUrl,
                needSave = needSave
            )
        } else {
            val analyzeUrl = AnalyzeUrl(
                rawUrl = chapterUrl,
                baseUrl = book.tocUrl,
                source = bookSource,
                ruleData = book,
                chapter = bookChapter,
                coroutineContext = currentCoroutineContext()
            )
            val res = checkLogin(analyzeUrl, bookSource)
            checkRedirect(bookSource, res)
            BookContent.analyzeContent(
                bookSource = bookSource,
                book = book,
                bookChapter = bookChapter,
                baseUrl = chapterUrl,
                redirectUrl = res.url,
                body = res.body,
                nextChapterUrl = nextChapterUrl,
                needSave = needSave
            )
        }
    }

    /**
     * 获取段评列表
     * paragraphIndex: 0=章节级评论，>=1=正文第 N 段
     * page: 段评分页号（书源用 {{page}} 引用）
     * sort: 排序方式，0=最热，1=最新（书源用 {{sort}} 引用）
     */
    suspend fun getReviewListAwait(
        bookSource: BookSource,
        book: Book,
        bookChapter: BookChapter?,
        paragraphIndex: Int,
        page: Int = 1,
        sort: Int = 0,
    ): Result<ReviewPage> = fetchReviewPage(
        bookSource, book, bookChapter,
        urlRuleSelector = { it.reviewUrl to "reviewUrl 为空" },
        variables = mapOf(
            AppConst.JsVarName.PARAGRAPH_INDEX to paragraphIndex,
            AppConst.JsVarName.SORT to sort,
            AppConst.JsVarName.PAGE to page,
        )
    )

    /**
     * 获取某条段评的回复列表
     * reviewRule.replyListUrl 与 reviewUrl 同样走 AnalyzeUrl，
     * 书源用 @js:/<js></js> 写动态 URL，可访问 paragraphIndex / reviewId 变量。
     * 复用 reviewList/avatarRule 等同一套解析规则。
     */
    suspend fun getReviewRepliesAwait(
        bookSource: BookSource,
        book: Book,
        bookChapter: BookChapter?,
        paragraphIndex: Int,
        reviewId: String,
        page: Int = 1,
    ): Result<ReviewPage> = fetchReviewPage(
        bookSource, book, bookChapter,
        urlRuleSelector = { it.replyListUrl to "书源未配置回复列表URL规则" },
        variables = mapOf(
            AppConst.JsVarName.PARAGRAPH_INDEX to paragraphIndex,
            AppConst.JsVarName.REVIEW_ID to reviewId,
            AppConst.JsVarName.PAGE to page,
        )
    )

    private suspend fun fetchReviewPage(
        bookSource: BookSource,
        book: Book,
        bookChapter: BookChapter?,
        urlRuleSelector: (io.legado.app.data.entities.rule.ReviewRule) -> Pair<String?, String>,
        variables: Map<AppConst.JsVarName, Any>,
    ): Result<ReviewPage> = runCatching {
        val reviewRule = bookSource.ruleReview
            ?: throw NoStackTraceException("书源未配置段评规则")
        val (rawUrl, missingMsg) = urlRuleSelector(reviewRule)
        if (rawUrl.isNullOrBlank()) throw NoStackTraceException(missingMsg)
        val baseUrl = bookChapter?.getAbsoluteURL(book) ?: book.bookUrl
        val analyzeUrl = AnalyzeUrl(
            rawUrl = rawUrl,
            baseUrl = baseUrl,
            source = bookSource,
            ruleData = book,
            chapter = bookChapter,
            coroutineContext = currentCoroutineContext(),
            variables = variables
        )
        val res = checkLogin(analyzeUrl, bookSource)
        checkRedirect(bookSource, res)
        BookReview.analyzeReviewList(
            bookSource = bookSource,
            book = book,
            bookChapter = bookChapter,
            baseUrl = baseUrl,
            redirectUrl = res.url,
            body = res.body,
            reviewRule = reviewRule,
            variables = variables
        )
    }.onFailure {
        currentCoroutineContext().ensureActive()
    }

    /**
     * 获取章节内每段段评数 map
     */
    suspend fun getReviewCountAwait(
        bookSource: BookSource,
        book: Book,
        bookChapter: BookChapter,
    ): Result<Map<Int, Int>> = runCatching {
        val reviewRule = bookSource.ruleReview
            ?: throw NoStackTraceException("书源未配置段评规则")
        val countRule = reviewRule.reviewCountRule
            ?: return@runCatching emptyMap<Int, Int>()
        val analyzeRule = AnalyzeRule(book, bookSource).apply {
            chapter = bookChapter
            setBaseUrl(bookChapter.getAbsoluteURL(book))
            coroutineContext = currentCoroutineContext()
        }
        analyzeRule.setContent("")
        val res = analyzeRule.evalJS(countRule)
        BookReview.analyzeReviewCount(bookSource, res)
    }.onFailure {
        currentCoroutineContext().ensureActive()
    }

    /**
     * 执行一个段评动作规则（点赞/点踩/回复/删除）
     * 规则是 JS：变量包含 paragraphIndex / reviewId（可空）/ selected（点赞点踩用，目标态）/ content（回复用）。
     * 返回规则执行结果（书源可返回字符串作为错误提示，正常成功时通常无返回值）。
     */
    suspend fun evalReviewActionAwait(
        bookSource: BookSource,
        book: Book,
        bookChapter: BookChapter?,
        rule: String,
        paragraphIndex: Int,
        reviewId: String? = null,
        contentText: String? = null,
        selected: Boolean? = null,
    ): Result<Any?> = runCatching {
        val variables =
            mutableMapOf<AppConst.JsVarName, Any>(AppConst.JsVarName.PARAGRAPH_INDEX to paragraphIndex)
        reviewId?.let { variables[AppConst.JsVarName.REVIEW_ID] = it }
        selected?.let { variables[AppConst.JsVarName.SELECTED] = it }
        val analyzeRule = AnalyzeRule(book, bookSource).apply {
            chapter = bookChapter
            setBaseUrl(bookChapter?.getAbsoluteURL(book) ?: book.bookUrl)
            coroutineContext = currentCoroutineContext()
            setContent(contentText ?: "")
            this.variables = variables
        }
        analyzeRule.evalJS(rule)
    }.onFailure {
        currentCoroutineContext().ensureActive()
    }

    /**
     * 精准搜索
     */
    suspend fun preciseSearchAwait(
        bookSource: BookSource,
        name: String,
        author: String,
    ): Result<Book> {
        return runCatching {
            currentCoroutineContext().ensureActive()
            getBookListAwait(
                bookSource, name,
                filter = { fName, fAuthor -> fName == name && fAuthor == author },
                shouldBreak = { it > 0 }
            ).books.firstOrNull()?.let { searchBook ->
                currentCoroutineContext().ensureActive()
                return@runCatching searchBook.toBook()
            }
            throw NoStackTraceException("未搜索到 $name($author) 书籍")
        }.onFailure {
            currentCoroutineContext().ensureActive()
        }
    }

    suspend fun runPreUpdateJs(bookSource: BookSource, book: Book): Result<Unit> {
        return runCatching {
            val preUpdateJs = bookSource.ruleToc?.preUpdateJs
            if (!preUpdateJs.isNullOrBlank()) {
                AnalyzeRule(book, bookSource, true).apply {
                    coroutineContext = currentCoroutineContext()
                }.evalJS(preUpdateJs)
            }
        }.onFailure {
            currentCoroutineContext().ensureActive()
            AppLog.put("执行preUpdateJs规则失败 书源:${bookSource.bookSourceName}", it)
        }
    }

    private suspend fun checkLogin(analyzeUrl: AnalyzeUrl, bookSource: BookSource) : StrResponse{
        var tmp: Throwable? = null
        var res = try {
                analyzeUrl.getStrResponseAwait()
            }catch (e: Throwable){
                tmp = e
                null
            }
        try{
            //检测书源是否已登录
            bookSource.loginCheckJs.let {
                if (!it.isNullOrBlank()) {
                    res = analyzeUrl.evalJS(it, res) as StrResponse
                }
            }

        }catch (e: Throwable){
            throw tmp ?: e
        }
        return res ?: throw tmp!!
    }

    /**
     * 检测重定向
     */
    private fun checkRedirect(bookSource: BookSource, response: StrResponse) : Boolean {
        response.raw.priorResponse?.let {
            if (it.isRedirect) {
                Debug.log(bookSource.bookSourceUrl, "≡检测到重定向(${it.code})")
                Debug.log(bookSource.bookSourceUrl, "┌重定向后地址")
                Debug.log(bookSource.bookSourceUrl, "└${response.url}")
                return true
            }
        }
        return false
    }

    data class ParsedRule(val rule: String, val reverse: Boolean)

    /**
     * JS 规则求值结果 → Boolean
     * 支持原生 Boolean/数值/字符串("false"/"0"/"null"/空 视为 false)
     */
    internal fun parseBoolean(raw: Any?): Boolean = when (raw) {
        null -> false
        is Boolean -> raw
        is Number -> raw.toDouble() != 0.0
        else -> {
            val s = raw.toString().trim()
            s.isNotEmpty() && !s.equals("false", true) && s != "0" && s != "null"
        }
    }

    internal fun parseRulePrefix(rule: String?): ParsedRule {
        var reverse = false
        var r = rule ?: ""
        if (r.startsWith("-")) {
            reverse = true
            r = r.substring(1)
        }
        if (r.startsWith("+")) {
            r = r.substring(1)
        }
        return ParsedRule(r, reverse)
    }

}