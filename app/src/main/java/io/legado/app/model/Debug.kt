package io.legado.app.model

import android.annotation.SuppressLint
import android.util.Log
import io.legado.app.BuildConfig
import io.legado.app.constant.AppPattern
import io.legado.app.data.entities.Book
import io.legado.app.data.entities.BookChapter
import io.legado.app.data.entities.BookSource
import io.legado.app.help.book.isWebFile
import io.legado.app.help.coroutine.CompositeCoroutine
import io.legado.app.help.coroutine.Coroutine
import io.legado.app.model.analyzeRule.AnalyzeRule
import io.legado.app.model.webBook.WebBook
import io.legado.app.model.webBook.WebBook.getBookInfoAwait
import io.legado.app.model.webBook.WebBook.getChapterListAwait
import io.legado.app.model.webBook.WebBook.getContentAwait
import io.legado.app.utils.HtmlFormatter
import io.legado.app.utils.isAbsUrl
import io.legado.app.utils.isDataUrl
import io.legado.app.utils.stackTraceStr
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.currentCoroutineContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object Debug {
    var callback: Callback? = null
    private var debugSource: String? = null
    private val tasks: CompositeCoroutine = CompositeCoroutine()
    val debugMessageMap = HashMap<String, String>()
    private val debugTimeMap = HashMap<String, Long>()
    var isChecking: Boolean = false

    @SuppressLint("ConstantLocale")
    private val debugTimeFormat = SimpleDateFormat("[mm:ss.SSS]", Locale.getDefault())
    private var startTime: Long = System.currentTimeMillis()

    @Synchronized
    fun log(
        sourceUrl: String?,
        msg: String = "",
        print: Boolean = true,
        isHtml: Boolean = false,
        showTime: Boolean = true,
        state: Int = 1
    ) {
        if (BuildConfig.DEBUG) {
            Log.d("sourceDebug", msg)
        }
        //调试信息始终要执行
        callback?.let {
            if ((debugSource != sourceUrl || !print)) return
            var printMsg = msg
            if (isHtml) {
                printMsg = HtmlFormatter.format(msg)
            }
            if (showTime) {
                val time = debugTimeFormat.format(Date(System.currentTimeMillis() - startTime))
                printMsg = "$time $printMsg"
            }
            it.printLog(state, printMsg)
        }
        if (isChecking && sourceUrl != null && (msg).length < 30) {
            var printMsg = msg
            if (isHtml) {
                printMsg = HtmlFormatter.format(msg)
            }
            if (showTime && debugTimeMap[sourceUrl] != null) {
                val time =
                    debugTimeFormat.format(Date(System.currentTimeMillis() - debugTimeMap[sourceUrl]!!))
                printMsg = printMsg.replace(AppPattern.debugMessageSymbolRegex, "")

                debugMessageMap[sourceUrl] = "$time $printMsg"
            }
        }
    }

    @Synchronized
    fun log(msg: String?) {
        log(debugSource, msg ?: "", true)
    }

    fun cancelDebug(destroy: Boolean = false) {
        tasks.clear()

        if (destroy) {
            debugSource = null
            callback = null
        }
    }

    fun startChecking(source: BookSource) {
        isChecking = true
        debugTimeMap[source.bookSourceUrl] = System.currentTimeMillis()
        debugMessageMap[source.bookSourceUrl] = "${debugTimeFormat.format(Date(0))} 开始校验"
    }

    fun finishChecking() {
        isChecking = false
    }

    fun getRespondTime(sourceUrl: String): Long {
        return debugTimeMap[sourceUrl] ?: CheckSource.timeout
    }

    fun updateFinalMessage(sourceUrl: String, state: String) {
        if (debugTimeMap[sourceUrl] != null && debugMessageMap[sourceUrl] != null) {
            val spendingTime = System.currentTimeMillis() - debugTimeMap[sourceUrl]!!
            debugTimeMap[sourceUrl] =
                if (state == "校验成功") spendingTime else CheckSource.timeout + spendingTime
            val printTime = debugTimeFormat.format(Date(spendingTime))
            debugMessageMap[sourceUrl] = "$printTime $state"
        }
    }

    fun startDebug(scope: CoroutineScope, bookSource: BookSource, key: String) {
        cancelDebug()
        debugSource = bookSource.bookSourceUrl
        startTime = System.currentTimeMillis()
        when {
            key.isAbsUrl() || key.isDataUrl() -> {
                val book = Book()
                book.origin = bookSource.bookSourceUrl
                book.bookUrl = key
                log(bookSource.bookSourceUrl, "⇒开始访问详情页:$key")
                infoDebug(scope, bookSource, book)
            }

            key.contains("::") -> {
                val url = key.substringAfter("::")
                log(bookSource.bookSourceUrl, "⇒开始访问发现页:$url")
                exploreDebug(scope, bookSource, url)
            }

            key.startsWith("++") -> {
                val url = key.substring(2)
                val book = Book()
                book.origin = bookSource.bookSourceUrl
                book.tocUrl = url
                log(bookSource.bookSourceUrl, "⇒开始访目录页:$url")
                tocDebug(scope, bookSource, book)
            }

            key.startsWith("--") -> {
                val url = key.substring(2)
                val book = Book()
                book.origin = bookSource.bookSourceUrl
                log(bookSource.bookSourceUrl, "⇒开始访正文页:$url")
                val chapter = BookChapter()
                chapter.title = "调试"
                chapter.url = url
                contentDebug(scope, bookSource, book, chapter, null)
            }

            else -> {
                log(bookSource.bookSourceUrl, "⇒开始搜索关键字:$key")
                searchDebug(scope, bookSource, key)
            }
        }
    }

    private fun exploreDebug(scope: CoroutineScope, bookSource: BookSource, url: String) {
        log(debugSource, "︾开始解析发现页")
        val explore = Coroutine.async(scope) {
            val debugUrl = parseExploreUrl(url)
            WebBook.getBookListAwait(bookSource, debugUrl, 1, isSearch = false)
        }.onSuccess { page ->
            val exploreBooks = page.books
                if (exploreBooks.isNotEmpty()) {
                    log(debugSource, "︽发现页解析完成")
                    log(debugSource, showTime = false)
                    val book = exploreBooks[0]
                    if(book.bookUrl.contains("::"))exploreDebug(scope, bookSource, book.bookUrl.substringAfter("::"))
                    else infoDebug(scope, bookSource, book.toBook())
                } else {
                    log(debugSource, "︽未获取到书籍", state = -1)
                }
            }
            .onError {
                log(debugSource, it.stackTraceStr, state = -1)
            }
        tasks.add(explore)
    }

    private fun parseExploreUrl(url: String): String {
        val regex = "<(\\w+)\\((.*?)\\)>".toRegex()
        var result = url
        regex.findAll(url).forEach { match ->
            val name = match.groupValues[1]
            val pairStrings = match.groupValues[2].split(",")
            val pairs = pairStrings.mapNotNull {
                val split = it.split(":")
                if (split.size >= 2) {
                    split[0].trim() to split[1].trim()
                } else if (split.size == 1) {
                    split[0].trim() to split[0].trim()
                } else {
                    null
                }
            }
            if (pairs.isNotEmpty()) {
                result = result.replace("<$name\\((.*?)\\)>".toRegex(), pairs[0].second)
            }
        }
        return result
    }

    private fun searchDebug(scope: CoroutineScope, bookSource: BookSource, key: String) {
        log(debugSource, "︾开始解析搜索页")
        val search = Coroutine.async(scope) {
            WebBook.getBookListAwait(bookSource, key, 1)
        }.onSuccess { page ->
            val searchBooks = page.books
                if (searchBooks.isNotEmpty()) {
                    log(debugSource, "︽搜索页解析完成")
                    log(debugSource, showTime = false)
                    infoDebug(scope, bookSource, searchBooks[0].toBook())
                } else {
                    log(debugSource, "︽未获取到书籍", state = -1)
                }
            }
            .onError {
                log(debugSource, it.stackTraceStr, state = -1)
            }
        tasks.add(search)
    }

    private fun infoDebug(scope: CoroutineScope, bookSource: BookSource, book: Book) {
        if (book.tocUrl.isNotBlank()) {
            log(debugSource, "≡已获取目录链接,跳过详情页")
            log(debugSource, showTime = false)
            tocDebug(scope, bookSource, book)
            return
        }
        log(debugSource, "︾开始解析详情页")
        val info = Coroutine.async(scope) {
                getBookInfoAwait(bookSource, book)
            }.onSuccess {
                log(debugSource, "︽详情页解析完成")
                log(debugSource, showTime = false)
                if (!book.isWebFile) {
                    tocDebug(scope, bookSource, book)
                } else {
                    log(debugSource, "≡文件类书源跳过解析目录", state = 1000)
                }
            }
            .onError {
                log(debugSource, it.stackTraceStr, state = -1)
            }
        tasks.add(info)
    }

    private fun tocDebug(scope: CoroutineScope, bookSource: BookSource, book: Book) {
        log(debugSource, "︾开始解析目录页")
        val chapterList = Coroutine.async(scope) {
                getChapterListAwait(bookSource, book).getOrThrow()
            }.onSuccess { chapters ->
                log(debugSource, "︽目录页解析完成")
                log(debugSource, showTime = false)
                val toc = chapters.filter { !(it.isVolume && it.url.startsWith(it.title)) }
                if (toc.isEmpty()) {
                    log(debugSource, "≡没有正文章节")
                    return@onSuccess
                }
                val nextChapterUrl = toc.getOrNull(1)?.url ?: toc.first().url
                contentDebug(scope, bookSource, book, toc.first(), nextChapterUrl)
            }
            .onError {
                log(debugSource, it.stackTraceStr, state = -1)
            }
        tasks.add(chapterList)
    }

    private fun contentDebug(
        scope: CoroutineScope,
        bookSource: BookSource,
        book: Book,
        bookChapter: BookChapter,
        nextChapterUrl: String?
    ) {
        log(debugSource, "︾开始解析正文页")
        val content = Coroutine.async(scope) {
            getContentAwait(bookSource, book, bookChapter, nextChapterUrl, false)
        }.onSuccess {
            log(debugSource, "︽正文页解析完成")
            log(debugSource, showTime = false)
            reviewDebug(scope, bookSource, book, bookChapter)
        }.onError {
            log(debugSource, it.stackTraceStr, state = -1)
        }
        tasks.add(content)
    }

    private fun reviewDebug(
        scope: CoroutineScope,
        bookSource: BookSource,
        book: Book,
        bookChapter: BookChapter
    ) {
        if (bookSource.ruleReview.isNullOrEmpty()) {
            log(debugSource, "≡未配置段评规则,跳过")
            log(debugSource, showTime = false)
            lrcDebug(scope, bookSource, book, bookChapter)
            return
        }
        val rule = bookSource.reviewRule
        if (rule.reviewUrl.isNullOrBlank()) {
            log(debugSource, "≡未配置段评规则,跳过")
            log(debugSource, showTime = false)
            lrcDebug(scope, bookSource, book, bookChapter)
            return
        }
        log(debugSource, "︾开始解析段评")
        val task = Coroutine.async(scope) {
            // 优先用 countMap 里有评论的段落号去拉，否则退回章节级(0)
            var paragraphIndex = 0
            if (!rule.reviewCountRule.isNullOrBlank()) {
                val countMap = WebBook.getReviewCountAwait(
                    bookSource, book, bookChapter
                ).getOrThrow()
                log(
                    debugSource, "≡段评数 map(取前 5): " +
                        countMap.entries.take(5).joinToString { "${it.key}=${it.value}" }
                )
                countMap.entries.firstOrNull { it.value > 0 }?.key?.let {
                    paragraphIndex = it
                }
            }
            log(debugSource, "≡使用 paragraphIndex=$paragraphIndex 拉取段评")
            paragraphIndex to WebBook.getReviewListAwait(
                bookSource, book, bookChapter, paragraphIndex
            ).getOrThrow()
        }.onSuccess { (paragraphIndex, page) ->
            log(debugSource, "︽段评解析完成")
            log(debugSource, showTime = false)
            val quoted = page.reviews.firstOrNull { it.replyCount > 0 && !it.id.isNullOrBlank() }
            if (quoted != null && !rule.replyListUrl.isNullOrBlank()) {
                reviewRepliesDebug(
                    scope, bookSource, book, bookChapter, paragraphIndex, quoted.id!!
                )
            } else {
                log(debugSource, "≡未找到带回复的段评或未配置 replyListUrl 规则,跳过回复解析")
                log(debugSource, showTime = false)
                lrcDebug(scope, bookSource, book, bookChapter)
            }
        }.onError {
            log(debugSource, "段评解析出错:${it.localizedMessage}")
            lrcDebug(scope, bookSource, book, bookChapter)
        }
        tasks.add(task)
    }

    private fun reviewRepliesDebug(
        scope: CoroutineScope,
        bookSource: BookSource,
        book: Book,
        bookChapter: BookChapter,
        paragraphIndex: Int,
        reviewId: String,
    ) {
        log(debugSource, "︾开始解析段评回复 reviewId=$reviewId")
        val task = Coroutine.async(scope) {
            WebBook.getReviewRepliesAwait(
                bookSource, book, bookChapter, paragraphIndex, reviewId
            ).getOrThrow()
        }.onSuccess { page ->
            log(debugSource, "≡回复条数:${page.reviews.size}")
            log(debugSource, "︽段评回复解析完成")
            log(debugSource, showTime = false)
            lrcDebug(scope, bookSource, book, bookChapter)
        }.onError {
            log(debugSource, "段评回复解析出错:${it.localizedMessage}")
            lrcDebug(scope, bookSource, book, bookChapter)
        }
        tasks.add(task)
    }

    private fun lrcDebug(
        scope: CoroutineScope,
        bookSource: BookSource,
        book: Book,
        bookChapter: BookChapter
    ) {
        val lrcRule = bookSource.contentRule.lrcRule
        if (lrcRule.isNullOrBlank()) {
            musicCoverDebug(scope, bookSource, book, bookChapter)
            return
        }
        log(debugSource, "︾开始解析歌词规则")
        val task = Coroutine.async(scope) {
            val rule = AnalyzeRule(book, bookSource)
            rule.coroutineContext = currentCoroutineContext()
            rule.setBaseUrl(bookChapter.url)
            rule.chapter = bookChapter
            rule.evalJS(lrcRule)
        }.onSuccess { raw ->
            when (raw) {
                is List<*> -> {
                    log(debugSource, "≡歌词行数:${raw.size}")
                    for (i in 0 until raw.size) {
                        log(debugSource, raw[i]?.toString().orEmpty(), showTime = false)
                    }
                }

                null -> log(debugSource, "≡歌词为空")
                else -> log(debugSource, "≡歌词数据:$raw")
            }
            log(debugSource, "︽歌词解析完成")
            log(debugSource, showTime = false)
            musicCoverDebug(scope, bookSource, book, bookChapter)
        }.onError {
            log(debugSource, "歌词解析出错:${it.localizedMessage}")
            musicCoverDebug(scope, bookSource, book, bookChapter)
        }
        tasks.add(task)
    }

    private fun musicCoverDebug(
        scope: CoroutineScope,
        bookSource: BookSource,
        book: Book,
        bookChapter: BookChapter
    ) {
        val musicCover = bookSource.contentRule.musicCover
        if (musicCover.isNullOrBlank()) {
            log(debugSource, "≡调试流程完成", state = 1000)
            return
        }
        log(debugSource, "︾开始解析音乐封面规则")
        val task = Coroutine.async(scope) {
            val rule = AnalyzeRule(book, bookSource)
            rule.coroutineContext = currentCoroutineContext()
            rule.setBaseUrl(bookChapter.url)
            rule.chapter = bookChapter
            rule.evalJS(musicCover).toString()
        }.onSuccess { url ->
            log(debugSource, "≡音乐封面 URL:$url")
            log(debugSource, "︽音乐封面解析完成", state = 1000)
        }.onError {
            log(debugSource, "音乐封面解析出错:${it.localizedMessage}", state = -1)
        }
        tasks.add(task)
    }

    interface Callback {
        fun printLog(state: Int, msg: String)
    }
}