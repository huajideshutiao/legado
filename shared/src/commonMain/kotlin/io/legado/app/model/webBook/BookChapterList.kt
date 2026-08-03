package io.legado.app.model.webBook

import io.legado.app.data.AppDbProviders
import io.legado.app.data.entities.Book
import io.legado.app.data.entities.BookChapter
import io.legado.app.data.entities.BookSource
import io.legado.app.data.entities.rule.TocRule
import io.legado.app.exception.NoStackTraceException
import io.legado.app.exception.TocEmptyException
import io.legado.app.help.book.ContentProcessorProviders
import io.legado.app.help.book.getDisplayTitle
import io.legado.app.help.book.getUseReplaceRule
import io.legado.app.help.book.simulatedTotalChapterNum
import io.legado.app.help.config.AppConfigProviders
import io.legado.app.help.i18n.AppStringKey
import io.legado.app.help.i18n.appString
import io.legado.app.help.source.SourceDebugLoggers
import io.legado.app.model.analyzeRule.AnalyzeRuleFactories
import io.legado.app.model.analyzeRule.AnalyzeUrlFactories
import io.legado.app.utils.isTrue
import io.legado.app.utils.mapAsync
import io.legado.app.utils.systemCurrentTimeMillis
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.flow

/**
 * 获取目录
 *
 * 从 app 下沉到 shared jvmAndAndroidMain, 现下沉到 commonMain。
 * - appDb → AppDbProviders.get() provider 间接
 * - ContentProcessor.get(book).getTitleReplaceRules() → ContentProcessorProviders.get().getTitleReplaceRules(book)
 * - AppConfig.threadCount/tocCountWords → AppConfigProviders.get().threadCount/tocCountWords
 * - AnalyzeRule → AnalyzeRuleFactories.create (各端注册工厂返回平台子类补全 JsExtensions 面, 未注册端裸 AnalyzeRuleCore)
 * - AnalyzeUrl → AnalyzeUrlCore (app 端 AnalyzeUrl 继承 AnalyzeUrlCore, 调用方传 AnalyzeUrl 实例向上转型)
 * - Debug.log(key, msg, state) → SourceDebugLoggers.impl?.log(key, msg, state)
 * - WebBook.parseRulePrefix → WebBookRuleUtils.parseRulePrefix (已抽到独立工具)
 * - mapAsync 扩展已下沉到 shared FlowExtensionsShared.kt, 包名不变
 * - getDisplayTitle/getUseReplaceRule/simulatedTotalChapterNum 扩展已下沉到 shared
 *   BookDisplayExtensionsShared.kt (commonMain), 包名不变
 *   ChineseUtils.t2s/s2t 与 java.time.Period.between 经 expect/actual 桥接
 *   (BookDisplayBridge: chineseT2S/chineseS2T/periodDaysBetween)
 *
 * 包名保持 io.legado.app.model.webBook, app 端调用方 import 零改动。
 */
object BookChapterList {

    suspend fun analyzeChapterList(
        bookSource: BookSource,
        book: Book,
        baseUrl: String,
        redirectUrl: String = baseUrl,
        body: String?
    ): List<BookChapter> {
        body ?: throw NoStackTraceException(
            appString(AppStringKey.error_get_web_content, baseUrl)
        )
        val chapterList = ArrayList<BookChapter>()
        SourceDebugLoggers.impl?.log(bookSource.bookSourceUrl, "≡获取成功:${baseUrl}")
        SourceDebugLoggers.impl?.log(bookSource.bookSourceUrl, body, state = 30)
        val tocRule = bookSource.tocRule
        val nextUrlList = arrayListOf(redirectUrl)
        val (listRule, reverse) = WebBookRuleUtils.parseRulePrefix(tocRule.chapterList)
        var chapterData =
            analyzeChapterList(
                book, baseUrl, redirectUrl, body,
                tocRule, listRule, bookSource, log = true
            )
        chapterList.addAll(chapterData.first)
        when (chapterData.second.size) {
            0 -> Unit
            1 -> {
                var nextUrl = chapterData.second[0]
                while (nextUrl.isNotEmpty() && !nextUrlList.contains(nextUrl)) {
                    nextUrlList.add(nextUrl)
                    val analyzeUrl = AnalyzeUrlFactories.create(
                        rawUrl = nextUrl,
                        source = bookSource,
                        ruleData = book,
                        coroutineContext = currentCoroutineContext()
                    )
                    val res = analyzeUrl.getStrResponseAwait() //控制并发访问
                    res.body?.let { nextBody ->
                        chapterData = analyzeChapterList(
                            book, nextUrl, nextUrl,
                            nextBody, tocRule, listRule, bookSource
                        )
                        nextUrl = chapterData.second.firstOrNull() ?: ""
                        chapterList.addAll(chapterData.first)
                    }
                }
                SourceDebugLoggers.impl?.log(bookSource.bookSourceUrl, "◇目录总页数:${nextUrlList.size}")
            }

            else -> {
                SourceDebugLoggers.impl?.log(
                    bookSource.bookSourceUrl,
                    "◇并发解析目录,总页数:${chapterData.second.size}"
                )
                flow {
                    for (urlStr in chapterData.second) {
                        emit(urlStr)
                    }
                }.mapAsync(AppConfigProviders.get().threadCount) { urlStr ->
                    val analyzeUrl = AnalyzeUrlFactories.create(
                        rawUrl = urlStr,
                        source = bookSource,
                        ruleData = book,
                        coroutineContext = currentCoroutineContext()
                    )
                    val res = analyzeUrl.getStrResponseAwait() //控制并发访问
                    analyzeChapterList(
                        book, urlStr, res.url,
                        res.body!!, tocRule, listRule, bookSource, false
                    ).first
                }.collect {
                    chapterList.addAll(it)
                }
            }
        }
        if (chapterList.isEmpty()) {
            throw TocEmptyException(appString(AppStringKey.chapter_list_empty))
        }
        if (!reverse) {
            chapterList.reverse()
        }
        return updateBook(book, chapterList)
    }

    suspend fun updateBook(book: Book, chapterList: List<BookChapter>): List<BookChapter> {
        currentCoroutineContext().ensureActive()
        //去重
        val lh = LinkedHashSet(chapterList)
        val list = ArrayList(lh)
        if (!book.config.reverseToc) {
            list.reverse()
        }
        SourceDebugLoggers.impl?.log(book.origin, "◇目录总数:${list.size}")
        currentCoroutineContext().ensureActive()
        list.forEachIndexed { index, bookChapter ->
            bookChapter.index = index
        }
        val replaceRules = ContentProcessorProviders.get().getTitleReplaceRules(book)
        book.durChapterTitle = list.getOrElse(book.durChapterIndex) { list.last() }
            .getDisplayTitle(replaceRules, book.getUseReplaceRule())
        if (book.totalChapterNum < list.size) {
            book.lastCheckCount = list.size - book.totalChapterNum
            book.latestChapterTime = systemCurrentTimeMillis()
        }
        book.lastCheckTime = systemCurrentTimeMillis()
        book.totalChapterNum = list.size
        book.latestChapterTitle =
            list.getOrElse(book.simulatedTotalChapterNum() - 1) { list.last() }
                .getDisplayTitle(replaceRules, book.getUseReplaceRule())
        currentCoroutineContext().ensureActive()
        getWordCount(list, book)
        return list
    }

    private suspend fun analyzeChapterList(
        book: Book,
        baseUrl: String,
        redirectUrl: String,
        body: String,
        tocRule: TocRule,
        listRule: String,
        bookSource: BookSource,
        getNextUrl: Boolean = true,
        log: Boolean = false
    ): Pair<List<BookChapter>, List<String>> {
        val analyzeRule = AnalyzeRuleFactories.create(book, bookSource)
        analyzeRule.setContent(body).setBaseUrl(baseUrl)
        analyzeRule.setRedirectUrl(redirectUrl)
        analyzeRule.coroutineContext = currentCoroutineContext()
        //获取目录列表
        val chapterList = arrayListOf<BookChapter>()
        SourceDebugLoggers.impl?.log(bookSource.bookSourceUrl, "┌获取目录列表", log)
        val elements = analyzeRule.getElements(listRule)
        SourceDebugLoggers.impl?.log(bookSource.bookSourceUrl, "└列表大小:${elements.size}", log)
        //获取下一页链接
        val nextUrlList = arrayListOf<String>()
        val nextTocRule = tocRule.nextTocUrl
        if (getNextUrl && !nextTocRule.isNullOrEmpty()) {
            SourceDebugLoggers.impl?.log(bookSource.bookSourceUrl, "┌获取目录下一页列表", log)
            analyzeRule.getStringList(nextTocRule, isUrl = true)?.let {
                for (item in it) {
                    if (item != redirectUrl) {
                        nextUrlList.add(item)
                    }
                }
            }
            SourceDebugLoggers.impl?.log(
                bookSource.bookSourceUrl,
                "└" + nextUrlList.joinToString("，\n"),
                log
            )
        }
        currentCoroutineContext().ensureActive()
        if (elements.isNotEmpty()) {
            SourceDebugLoggers.impl?.log(bookSource.bookSourceUrl, "┌解析目录列表", log)
            val nameRule = analyzeRule.splitSourceRule(tocRule.chapterName)
            val urlRule = analyzeRule.splitSourceRule(tocRule.chapterUrl)
            val vipRule = analyzeRule.splitSourceRule(tocRule.isVip)
            val payRule = analyzeRule.splitSourceRule(tocRule.isPay)
            val upTimeRule = analyzeRule.splitSourceRule(tocRule.updateTime)
            val isVolumeRule = analyzeRule.splitSourceRule(tocRule.isVolume)
            elements.forEachIndexed { index, item ->
                currentCoroutineContext().ensureActive()
                analyzeRule.setContent(item)
                val bookChapter = BookChapter(bookUrl = book.bookUrl)
                analyzeRule.chapter = bookChapter
                bookChapter.title = analyzeRule.getString(nameRule)
                bookChapter.url = analyzeRule.getString(urlRule)
                bookChapter.tag = analyzeRule.getString(upTimeRule)
                val isVolume = analyzeRule.getString(isVolumeRule)
                bookChapter.isVolume = isVolume.isTrue()
                if (bookChapter.url.isEmpty()) {
                    if (bookChapter.isVolume) {
                        bookChapter.url = bookChapter.title + index
                        SourceDebugLoggers.impl?.log(
                            bookSource.bookSourceUrl,
                            "⇒一级目录${index}未获取到url,使用标题替代"
                        )
                    } else {
                        bookChapter.url = baseUrl
                        SourceDebugLoggers.impl?.log(
                            bookSource.bookSourceUrl,
                            "⇒目录${index}未获取到url,使用baseUrl替代"
                        )
                    }
                }
                if (bookChapter.title.isNotEmpty()) {
                    if (analyzeRule.getString(vipRule).isTrue()) {
                        bookChapter.isVip = true
                    }
                    if (analyzeRule.getString(payRule).isTrue()) {
                        bookChapter.isPay = true
                    }
                    chapterList.add(bookChapter)
                }
            }
            SourceDebugLoggers.impl?.log(bookSource.bookSourceUrl, "└目录列表解析完成", log)
            if (chapterList.isEmpty()) {
                SourceDebugLoggers.impl?.log(bookSource.bookSourceUrl, "◇章节列表为空", log)
            } else {
                SourceDebugLoggers.impl?.log(bookSource.bookSourceUrl, "≡首章信息", log)
                SourceDebugLoggers.impl?.log(bookSource.bookSourceUrl, "◇章节名称:${chapterList[0].title}", log)
                SourceDebugLoggers.impl?.log(bookSource.bookSourceUrl, "◇章节链接:${chapterList[0].url}", log)
                SourceDebugLoggers.impl?.log(bookSource.bookSourceUrl, "◇章节信息:${chapterList[0].tag}", log)
                SourceDebugLoggers.impl?.log(bookSource.bookSourceUrl, "◇是否VIP:${chapterList[0].isVip}", log)
                SourceDebugLoggers.impl?.log(bookSource.bookSourceUrl, "◇是否购买:${chapterList[0].isPay}", log)
            }
        }
        return Pair(chapterList, nextUrlList)
    }

    private suspend fun getWordCount(list: ArrayList<BookChapter>, book: Book) {
        if (!AppConfigProviders.get().tocCountWords) {
            return
        }
        val chapterList = AppDbProviders.get().bookChapterDao.getChapterList(book.bookUrl)
        if (chapterList.isNotEmpty()) {
            val map = chapterList.associateBy({ it.getFileName() }, { it.wordCount })
            for (bookChapter in list) {
                val wordCount = map[bookChapter.getFileName()]
                if (wordCount != null) {
                    bookChapter.wordCount = wordCount
                }
            }
        }
    }

}
