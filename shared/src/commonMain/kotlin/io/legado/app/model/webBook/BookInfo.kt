package io.legado.app.model.webBook

import io.legado.app.constant.AppLog
import io.legado.app.constant.BookType
import io.legado.app.data.entities.Book
import io.legado.app.data.entities.BookSource
import io.legado.app.exception.NoStackTraceException
import io.legado.app.help.i18n.AppStringKey
import io.legado.app.help.i18n.appString
import io.legado.app.help.source.SourceDebugLoggers
import io.legado.app.model.analyzeRule.AnalyzeRuleCore
import io.legado.app.model.analyzeRule.AnalyzeRuleFactories
import io.legado.app.utils.HtmlFormatter
import io.legado.app.utils.NetworkUtils
import io.legado.app.utils.StringUtils.wordCountFormat
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive


/**
 * 获取详情
 *
 * W3-e: 从 app 下沉到 shared jvmAndAndroidMain, 现下沉到 commonMain。
 * - bookSource 参数类型直接用 shared commonMain 的 BookSource 实体 (webBook 编排层与实体同模块)
 * - Debug.log(key, msg, state) → SourceDebugLoggers.impl?.log(key, msg, state)
 * - AnalyzeRule → AnalyzeRuleFactories.create (各端注册工厂返回平台子类补全 JsExtensions 面, 未注册端裸 AnalyzeRuleCore)
 * - book.isWebFile → (book.type and BookType.webFile > 0) (isWebFile/isType 扩展在 app 端, 内联位运算避免新增 shared 扩展)
 * - LogUtils.e(tag, msg) → AppLog.put(msg, e) (AppLog 已在 shared commonMain, 经 AppLogHost 走 LogUtils.d 落盘)
 */
object BookInfo {

    @Throws(Exception::class)
    suspend fun analyzeBookInfo(
        bookSource: BookSource,
        book: Book,
        baseUrl: String,
        redirectUrl: String,
        body: String?,
        canReName: Boolean,
    ) {
        body ?: throw NoStackTraceException(
            appString(AppStringKey.error_get_web_content, baseUrl)
        )
        SourceDebugLoggers.impl?.log(bookSource.bookSourceUrl, "≡获取成功:${baseUrl}")
        SourceDebugLoggers.impl?.log(bookSource.bookSourceUrl, body, state = 20)
        val analyzeRule = AnalyzeRuleFactories.create(book, bookSource)
        analyzeRule.setContent(body).setBaseUrl(baseUrl)
        analyzeRule.setRedirectUrl(redirectUrl)
        analyzeRule.coroutineContext = currentCoroutineContext()
        analyzeBookInfo(book, body, analyzeRule, bookSource, baseUrl, redirectUrl, canReName)
    }

    suspend fun analyzeBookInfo(
        book: Book,
        body: String,
        analyzeRule: AnalyzeRuleCore,
        bookSource: BookSource,
        baseUrl: String,
        redirectUrl: String,
        canReName: Boolean,
    ) {
        val infoRule = bookSource.bookInfoRule
        infoRule.init?.let {
            if (it.isNotBlank()) {
                currentCoroutineContext().ensureActive()
                SourceDebugLoggers.impl?.log(bookSource.bookSourceUrl, "≡执行详情页初始化规则")
                analyzeRule.setContent(analyzeRule.getElement(it))
            }
        }
        val mCanReName = canReName && !infoRule.canReName.isNullOrBlank()
        if (!infoRule.name.isNullOrBlank()) {
            currentCoroutineContext().ensureActive()
            SourceDebugLoggers.impl?.log(bookSource.bookSourceUrl, "┌获取书名")
            analyzeRule.getString(infoRule.name).let {
                if (it.isNotEmpty() && (mCanReName || book.name.isEmpty())) {
                    book.name = it
                }
                SourceDebugLoggers.impl?.log(bookSource.bookSourceUrl, "└${it}")
            }
        }
        if (!infoRule.author.isNullOrBlank()) {
            currentCoroutineContext().ensureActive()
            SourceDebugLoggers.impl?.log(bookSource.bookSourceUrl, "┌获取作者")
            analyzeRule.getString(infoRule.author).let {
                if (it.isNotEmpty() && (mCanReName || book.author.isEmpty())) {
                    book.author = it
                }
                SourceDebugLoggers.impl?.log(bookSource.bookSourceUrl, "└${it}")
            }
        }
        if (!infoRule.kind.isNullOrBlank()) {
            currentCoroutineContext().ensureActive()
            SourceDebugLoggers.impl?.log(bookSource.bookSourceUrl, "┌获取分类")
            try {
                analyzeRule.getStringList(infoRule.kind)?.joinToString(",")?.let {
                    book.kind = it
                    SourceDebugLoggers.impl?.log(bookSource.bookSourceUrl, "└${it}")
                } ?: SourceDebugLoggers.impl?.log(bookSource.bookSourceUrl, "└")
            } catch (e: Exception) {
                currentCoroutineContext().ensureActive()
                SourceDebugLoggers.impl?.log(bookSource.bookSourceUrl, "└${e.message}")
                AppLog.put("获取分类出错", e)
            }
        }
        if (!infoRule.wordCount.isNullOrBlank()) {
            currentCoroutineContext().ensureActive()
            SourceDebugLoggers.impl?.log(bookSource.bookSourceUrl, "┌获取字数")
            try {
                analyzeRule.getStringList(infoRule.wordCount)
                    ?.joinToString(",") { wordCountFormat(it) }.let {
                    book.wordCount = it
                    SourceDebugLoggers.impl?.log(bookSource.bookSourceUrl, "└${it}")
                }
            } catch (e: Exception) {
                currentCoroutineContext().ensureActive()
                SourceDebugLoggers.impl?.log(bookSource.bookSourceUrl, "└${e.message}")
                AppLog.put("获取字数出错", e)
            }
        }
        if (!infoRule.lastChapter.isNullOrBlank()) {
            currentCoroutineContext().ensureActive()
            SourceDebugLoggers.impl?.log(bookSource.bookSourceUrl, "┌获取最新章节")
            try {
                analyzeRule.getString(infoRule.lastChapter).let {
                    book.latestChapterTitle = it
                    SourceDebugLoggers.impl?.log(bookSource.bookSourceUrl, "└${it}")
                }
            } catch (e: Exception) {
                currentCoroutineContext().ensureActive()
                SourceDebugLoggers.impl?.log(bookSource.bookSourceUrl, "└${e.message}")
                AppLog.put("获取最新章节出错", e)
            }
        }
        if (!infoRule.intro.isNullOrBlank()) {
            currentCoroutineContext().ensureActive()
            SourceDebugLoggers.impl?.log(bookSource.bookSourceUrl, "┌获取简介")
            try {
                HtmlFormatter.formatKeepRichTags(analyzeRule.getString(infoRule.intro)).let {
                    book.intro = it
                    SourceDebugLoggers.impl?.log(bookSource.bookSourceUrl, "└${it}")
                }
            } catch (e: Exception) {
                currentCoroutineContext().ensureActive()
                SourceDebugLoggers.impl?.log(bookSource.bookSourceUrl, "└${e.message}")
                AppLog.put("获取简介出错", e)
            }
        }
        if (!infoRule.coverUrl.isNullOrBlank()) {
            currentCoroutineContext().ensureActive()
            SourceDebugLoggers.impl?.log(bookSource.bookSourceUrl, "┌获取封面链接")
            try {
                analyzeRule.getString(infoRule.coverUrl).let {
                    if (it.isNotEmpty()) {
                        book.coverUrl = NetworkUtils.getAbsoluteURL(redirectUrl, it)
                    }
                    SourceDebugLoggers.impl?.log(bookSource.bookSourceUrl, "└${it}")
                }
            } catch (e: Exception) {
                currentCoroutineContext().ensureActive()
                SourceDebugLoggers.impl?.log(bookSource.bookSourceUrl, "└${e.message}")
                AppLog.put("获取封面出错", e)
            }
        }
        currentCoroutineContext().ensureActive()
        if (!(book.type and BookType.webFile > 0)) {
            if (!infoRule.tocUrl.isNullOrBlank()) {
                SourceDebugLoggers.impl?.log(bookSource.bookSourceUrl, "┌获取目录链接")
                book.tocUrl = analyzeRule.getString(infoRule.tocUrl, isUrl = true)
                SourceDebugLoggers.impl?.log(bookSource.bookSourceUrl, "└${book.tocUrl}")
            }
            if (book.tocUrl.isEmpty()) book.tocUrl = baseUrl
            if (book.tocUrl == baseUrl) {
                book.tocHtml = body
            }
        } else {
            SourceDebugLoggers.impl?.log(bookSource.bookSourceUrl, "┌获取文件下载链接")
            if (!infoRule.downloadUrls.isNullOrBlank()) {
                book.downloadUrls = analyzeRule.getStringList(infoRule.downloadUrls, isUrl = true)
            }
            if (book.downloadUrls.isNullOrEmpty()) {
                SourceDebugLoggers.impl?.log(bookSource.bookSourceUrl, "└")
                throw NoStackTraceException("下载链接为空")
            } else {
                SourceDebugLoggers.impl?.log(
                    bookSource.bookSourceUrl, "└" + book.downloadUrls!!.joinToString("，\n")
                )
            }
        }
    }


}
