package io.legado.app.model.webBook

import io.legado.app.data.entities.Book
import io.legado.app.data.entities.BookListPage
import io.legado.app.data.entities.BookSource
import io.legado.app.data.entities.SearchBook
import io.legado.app.data.entities.rule.BookListRule
import io.legado.app.data.entities.rule.ExploreKind
import io.legado.app.exception.NoStackTraceException
import io.legado.app.help.i18n.AppStringKey
import io.legado.app.help.i18n.appString
import io.legado.app.help.source.SourceDebugLoggers
import io.legado.app.model.Debug
import io.legado.app.model.analyzeRule.AnalyzeRuleCore
import io.legado.app.model.analyzeRule.AnalyzeRuleFactories
import io.legado.app.model.analyzeRule.AnalyzeUrlCore
import io.legado.app.model.analyzeRule.RuleData
import io.legado.app.utils.HtmlFormatter
import io.legado.app.utils.NetworkUtils
import io.legado.app.utils.StringUtils.wordCountFormat
import io.legado.app.utils.decodeListWithFallbackOrNull
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/**
 * 获取书籍列表
 *
 * W3-e: 从 app 下沉到 shared jvmAndAndroidMain, 现下沉到 commonMain。
 * - bookSource 参数类型直接用 shared commonMain 的 BookSource 实体 (webBook 编排层与实体同模块)
 * - Debug.log(key, msg, state) → SourceDebugLoggers.impl?.log(key, msg, state)
 * - AnalyzeRule → AnalyzeRuleFactories.create (各端注册工厂返回平台子类补全 JsExtensions 面, 未注册端裸 AnalyzeRuleCore)
 * - analyzeUrl 参数类型 AnalyzeUrl → AnalyzeUrlCore (app 端 AnalyzeUrl 继承 AnalyzeUrlCore, 调用方传 AnalyzeUrl 实例向上转型)
 * - WebBook.parseRulePrefix/parseBoolean → WebBookRuleUtils (解除对 WebBook object 的直接依赖)
 * - bookSource.getBookType()/exploreKindsJson() 为 BookSource 成员方法 (原扩展函数提升, 行为不变)
 */
object BookList {

    @Throws(Exception::class)
    suspend fun analyzeBookList(
        bookSource: BookSource,
        ruleData: RuleData,
        analyzeUrl: AnalyzeUrlCore,
        baseUrl: String,
        body: String?,
        isSearch: Boolean = true,
        isRedirect: Boolean = false,
        filter: ((name: String, author: String) -> Boolean)? = null,
        shouldBreak: ((size: Int) -> Boolean)? = null,
    ): BookListPage {
        body ?: throw NoStackTraceException(
            appString(AppStringKey.error_get_web_content, analyzeUrl.urlAfterJs)
        )
        val bookList = ArrayList<SearchBook>()
        SourceDebugLoggers.impl?.log(bookSource.bookSourceUrl, "≡获取成功:${analyzeUrl.urlAfterJs}")
        SourceDebugLoggers.impl?.log(bookSource.bookSourceUrl, body, state = 10)
        val analyzeRule = AnalyzeRuleFactories.create(ruleData, bookSource)
        analyzeRule.setContent(body).setBaseUrl(baseUrl)
        analyzeRule.setRedirectUrl(baseUrl)
        analyzeRule.coroutineContext = currentCoroutineContext()
        if (!isSearch) {
            checkExploreJson(bookSource)
        }
        if (isSearch) bookSource.bookUrlPattern?.let {
            currentCoroutineContext().ensureActive()
            if (baseUrl.matches(it.toRegex())) {
                SourceDebugLoggers.impl?.log(bookSource.bookSourceUrl, "≡链接为详情页")
                getInfoItem(
                    bookSource,
                    analyzeRule,
                    analyzeUrl,
                    body,
                    baseUrl,
                    ruleData.getVariable(),
                    isRedirect,
                    filter
                )?.let { searchBook ->
                    searchBook.infoHtml = body
                    bookList.add(searchBook)
                }
                return BookListPage(bookList, hasNextPage = false)
            }
        }
        val collections: List<Any>
        val bookListRule: BookListRule = when {
            isSearch -> bookSource.searchRule
            bookSource.exploreRule.bookList.isNullOrBlank() -> bookSource.searchRule
            else -> bookSource.exploreRule
        }
        val (ruleList, reverse) = WebBookRuleUtils.parseRulePrefix(bookListRule.bookList)
        SourceDebugLoggers.impl?.log(bookSource.bookSourceUrl, "┌获取书籍列表")
        collections = analyzeRule.getElements(ruleList)
        currentCoroutineContext().ensureActive()
        if (collections.isEmpty() && bookSource.bookUrlPattern.isNullOrEmpty()) {
            SourceDebugLoggers.impl?.log(bookSource.bookSourceUrl, "└列表为空,按详情页解析")
            getInfoItem(
                bookSource, analyzeRule, analyzeUrl, body, baseUrl, ruleData.getVariable(),
                isRedirect, filter
            )?.let { searchBook ->
                searchBook.infoHtml = body
                bookList.add(searchBook)
            }
        } else {
            val ruleName = analyzeRule.splitSourceRule(bookListRule.name)
            val ruleBookUrl = analyzeRule.splitSourceRule(bookListRule.bookUrl)
            val ruleAuthor = analyzeRule.splitSourceRule(bookListRule.author)
            val ruleCoverUrl = analyzeRule.splitSourceRule(bookListRule.coverUrl)
            val ruleIntro = analyzeRule.splitSourceRule(bookListRule.intro)
            val ruleKind = analyzeRule.splitSourceRule(bookListRule.kind)
            val ruleLastChapter = analyzeRule.splitSourceRule(bookListRule.lastChapter)
            val ruleWordCount = analyzeRule.splitSourceRule(bookListRule.wordCount)
            SourceDebugLoggers.impl?.log(bookSource.bookSourceUrl, "└列表大小:${collections.size}")
            for ((index, item) in collections.withIndex()) {
                getSearchItem(
                    bookSource, analyzeRule, item, baseUrl, ruleData.getVariable(),
                    index == 0,
                    filter,
                    ruleName = ruleName,
                    ruleBookUrl = ruleBookUrl,
                    ruleAuthor = ruleAuthor,
                    ruleCoverUrl = ruleCoverUrl,
                    ruleIntro = ruleIntro,
                    ruleKind = ruleKind,
                    ruleLastChapter = ruleLastChapter,
                    ruleWordCount = ruleWordCount
                )?.let { searchBook ->
                    if (baseUrl == searchBook.bookUrl) {
                        searchBook.infoHtml = body
                    }
                    bookList.add(searchBook)
                }
                if (shouldBreak?.invoke(bookList.size) == true) {
                    break
                }
            }
            // 去重按 bookUrl 判身份: SearchBook 已改结构相等, 而 time 是构造参数 (取当前毫秒),
            // 用 LinkedHashSet 会导致去重几乎恒不命中, 下游 LazyColumn 的 bookUrl key 会重复
            val deduped = bookList.distinctBy { it.bookUrl }
            bookList.clear()
            bookList.addAll(deduped)
            if (reverse) {
                bookList.reverse()
            }
        }
        SourceDebugLoggers.impl?.log(bookSource.bookSourceUrl, "◇书籍总数:${bookList.size}")
        val hasMoreRuleStr = bookListRule.hasMoreRule
        val hasNextPage = if (hasMoreRuleStr.isNullOrBlank()) {
            // 未配置：保守按"列表非空就当还有下一页"，与历史行为一致
            bookList.isNotEmpty()
        } else {
            analyzeRule.setContent(body)
            SourceDebugLoggers.impl?.log(bookSource.bookSourceUrl, "┌判断是否有下一页")
            val raw = runCatching { analyzeRule.evalJS(hasMoreRuleStr, body) }.getOrNull()
            SourceDebugLoggers.impl?.log(bookSource.bookSourceUrl, "└$raw")
            WebBookRuleUtils.parseBoolean(raw)
        }
        return BookListPage(bookList, hasNextPage)
    }

    @Throws(Exception::class)
    private suspend fun getInfoItem(
        bookSource: BookSource,
        analyzeRule: AnalyzeRuleCore,
        analyzeUrl: AnalyzeUrlCore,
        body: String,
        baseUrl: String,
        variable: String?,
        isRedirect: Boolean,
        filter: ((name: String, author: String) -> Boolean)?
    ): SearchBook? {
        val book = Book(variable = variable)
        book.bookUrl = if (isRedirect) {
            baseUrl
        } else {
            NetworkUtils.getAbsoluteURL(analyzeUrl.url, analyzeUrl.urlAfterJs)
        }
        book.origin = bookSource.bookSourceUrl
        book.originName = bookSource.bookSourceName
        book.originOrder = bookSource.customOrder
        book.type = bookSource.getBookType()
        analyzeRule.ruleData = book
        BookInfo.analyzeBookInfo(
            book,
            body,
            analyzeRule,
            bookSource,
            baseUrl,
            baseUrl,
            false
        )
        if (filter?.invoke(book.name, book.author) == false) {
            return null
        }
        if (book.name.isNotBlank()) {
            return book.toSearchBook()
        }
        return null
    }

    @Throws(Exception::class)
    private suspend fun getSearchItem(
        bookSource: BookSource,
        analyzeRule: AnalyzeRuleCore,
        item: Any,
        baseUrl: String,
        variable: String?,
        log: Boolean,
        filter: ((name: String, author: String) -> Boolean)?,
        ruleName: List<AnalyzeRuleCore.SourceRule>,
        ruleBookUrl: List<AnalyzeRuleCore.SourceRule>,
        ruleAuthor: List<AnalyzeRuleCore.SourceRule>,
        ruleKind: List<AnalyzeRuleCore.SourceRule>,
        ruleCoverUrl: List<AnalyzeRuleCore.SourceRule>,
        ruleWordCount: List<AnalyzeRuleCore.SourceRule>,
        ruleIntro: List<AnalyzeRuleCore.SourceRule>,
        ruleLastChapter: List<AnalyzeRuleCore.SourceRule>
    ): SearchBook? {
        val searchBook = SearchBook(variable = variable)
        searchBook.type = bookSource.getBookType()
        searchBook.origin = bookSource.bookSourceUrl
        searchBook.originName = bookSource.bookSourceName
        searchBook.originOrder = bookSource.customOrder
        analyzeRule.ruleData = searchBook
        analyzeRule.setContent(item)
        currentCoroutineContext().ensureActive()
        SourceDebugLoggers.impl?.log(bookSource.bookSourceUrl, "┌获取书名", log)
        searchBook.name = analyzeRule.getString(ruleName)
        SourceDebugLoggers.impl?.log(bookSource.bookSourceUrl, "└${searchBook.name}", log)
        if (searchBook.name.isNotEmpty()) {
            currentCoroutineContext().ensureActive()
            SourceDebugLoggers.impl?.log(bookSource.bookSourceUrl, "┌获取作者", log)
            searchBook.author = analyzeRule.getString(ruleAuthor)
            SourceDebugLoggers.impl?.log(bookSource.bookSourceUrl, "└${searchBook.author}", log)
            if (filter?.invoke(searchBook.name, searchBook.author) == false) {
                return null
            }
            currentCoroutineContext().ensureActive()
            SourceDebugLoggers.impl?.log(bookSource.bookSourceUrl, "┌获取分类", log)
            try {
                searchBook.kind = analyzeRule.getStringList(ruleKind)?.joinToString(",")
                SourceDebugLoggers.impl?.log(bookSource.bookSourceUrl, "└${searchBook.kind ?: ""}", log)
            } catch (e: Exception) {
                currentCoroutineContext().ensureActive()
                SourceDebugLoggers.impl?.log(bookSource.bookSourceUrl, "└${e.message}", log)
            }
            currentCoroutineContext().ensureActive()
            SourceDebugLoggers.impl?.log(bookSource.bookSourceUrl, "┌获取字数", log)
            try {
                searchBook.wordCount = analyzeRule.getStringList(ruleWordCount)
                    ?.joinToString(",") { wordCountFormat(it) }
                SourceDebugLoggers.impl?.log(bookSource.bookSourceUrl, "└${searchBook.wordCount}", log)
            } catch (e: Exception) {
                currentCoroutineContext().ensureActive()
                SourceDebugLoggers.impl?.log(bookSource.bookSourceUrl, "└${e.message}", log)
            }
            currentCoroutineContext().ensureActive()
            SourceDebugLoggers.impl?.log(bookSource.bookSourceUrl, "┌获取最新章节", log)
            try {
                searchBook.latestChapterTitle = analyzeRule.getString(ruleLastChapter)
                SourceDebugLoggers.impl?.log(bookSource.bookSourceUrl, "└${searchBook.latestChapterTitle}", log)
            } catch (e: Exception) {
                currentCoroutineContext().ensureActive()
                SourceDebugLoggers.impl?.log(bookSource.bookSourceUrl, "└${e.message}", log)
            }
            currentCoroutineContext().ensureActive()
            SourceDebugLoggers.impl?.log(bookSource.bookSourceUrl, "┌获取简介", log)
            try {
                searchBook.intro = HtmlFormatter.format(analyzeRule.getString(ruleIntro))
                SourceDebugLoggers.impl?.log(bookSource.bookSourceUrl, "└${searchBook.intro}", log)
            } catch (e: Exception) {
                currentCoroutineContext().ensureActive()
                SourceDebugLoggers.impl?.log(bookSource.bookSourceUrl, "└${e.message}", log)
            }
            currentCoroutineContext().ensureActive()
            SourceDebugLoggers.impl?.log(bookSource.bookSourceUrl, "┌获取封面链接", log)
            try {
                analyzeRule.getString(ruleCoverUrl).let {
                    if (it.isNotEmpty()) {
                        searchBook.coverUrl = NetworkUtils.getAbsoluteURL(baseUrl, it)
                    }
                }
                SourceDebugLoggers.impl?.log(bookSource.bookSourceUrl, "└${searchBook.coverUrl ?: ""}", log)
            } catch (e: Exception) {
                currentCoroutineContext().ensureActive()
                SourceDebugLoggers.impl?.log(bookSource.bookSourceUrl, "└${e.message}", log)
            }
            currentCoroutineContext().ensureActive()
            SourceDebugLoggers.impl?.log(bookSource.bookSourceUrl, "┌获取详情页链接", log)
            searchBook.bookUrl = analyzeRule.getString(ruleBookUrl, isUrl = true)
            if (searchBook.bookUrl.isEmpty()) {
                searchBook.bookUrl = baseUrl
            }
            SourceDebugLoggers.impl?.log(bookSource.bookSourceUrl, "└${searchBook.bookUrl}", log)
            return searchBook
        }
        return null
    }

    private fun checkExploreJson(bookSource: BookSource) {
        // 仅调试窗口打开时才做严格 JSON 校验; Debug.callback 常驻为 null, 用它而非
        // SourceDebugLoggers.impl (宿主常驻注册, 会让每次发现页解析都白跑一遍双栈解析)
        if (Debug.callback == null) {
            return
        }
        val json = bookSource.exploreKindsJson()
        if (json.isEmpty()) {
            return
        }
        // GSONStrict.fromJsonArray<ExploreKind>(json).getOrNull() ?: GSON.fromJsonArray<ExploreKind>(json).getOrNull() 双栈
        // decodeListWithFallbackOrNull 复刻: 先严格, 严格失败但宽松成功才提示格式不规范
        // 仅为校验/打日志, 解析结果不消费 (原代码也只是 getOrNull()?.let { log(...) })
        decodeListWithFallbackOrNull<ExploreKind>(json) {
            SourceDebugLoggers.impl?.log("≡发现地址规则 JSON 格式不规范，请改为规范格式")
        }
    }

}
