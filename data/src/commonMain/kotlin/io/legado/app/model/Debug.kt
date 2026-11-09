package io.legado.app.model

import io.legado.app.constant.AppLog
import io.legado.app.constant.AppPattern
import io.legado.app.data.entities.Book
import io.legado.app.data.entities.BookChapter
import io.legado.app.data.entities.BookSource
import io.legado.app.help.book.isWebFile
import io.legado.app.help.coroutine.CompositeCoroutine
import io.legado.app.help.coroutine.Coroutine
import io.legado.app.help.source.SourceDebugLogger
import io.legado.app.help.source.SourceDebugLoggerElement
import io.legado.app.model.Debug.formatDuration
import io.legado.app.model.analyzeRule.AnalyzeRuleCore
import io.legado.app.model.analyzeRule.AnalyzeUrlCore
import io.legado.app.model.analyzeRule.AnalyzeRuleFactories
import io.legado.app.model.webBook.WebBook
import io.legado.app.model.webBook.WebBook.getBookInfoAwait
import io.legado.app.model.webBook.WebBook.getChapterListAwait
import io.legado.app.model.webBook.WebBook.getContentAwait
import io.legado.app.model.webBook.ExploreOption
import io.legado.app.model.webBook.ExploreOptionSelection
import io.legado.app.model.webBook.pluginFilterSessionOf
import io.legado.app.utils.HtmlFormatter
import io.legado.app.utils.isAbsUrl
import io.legado.app.utils.isDataUrl
import io.legado.app.utils.stackTraceStr
import io.legado.app.utils.systemCurrentTimeMillis
import kotlinx.atomicfu.locks.SynchronizedObject
import kotlinx.atomicfu.locks.synchronized
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * 书源调试编排器 (shared commonMain 下沉版)。
 *
 * 原 app 端 Debug 依赖 `android.util.Log` / `BuildConfig.DEBUG` / `java.text.SimpleDateFormat`
 * / `CheckSource.timeout`, 下沉后替换:
 * - `android.util.Log.d` + `BuildConfig.DEBUG` → [AppLog.putDebug] (DEBUG 门控由 AppLogHost 保留)
 * - `SimpleDateFormat("[mm:ss.SSS]", Locale.getDefault())` + `Date(...)` → 自实现 [formatDuration]
 *   (commonMain 无 java.text, 用纯 Kotlin 拼接, 行为与原 mm:ss.SSS 格式一致)
 * - `CheckSource.timeout` → [CheckSourceShared.timeout] (同已下沉 commonMain, 用户配置生效)
 *
 * 嵌套 [Callback] 接口与 `var callback: Callback?` 字段保留, app 端 DebugWsHandler /
 * BookSourceDebugModel 实现该接口并 `Debug.callback = this` 的代码零改动。
 *
 * [Coroutine.async] / [HtmlFormatter] / [isWebFile] / [WebBook.*Await] / [AnalyzeRuleCore]
 * 等依赖均已下沉 commonMain, 直接复用。
 */
object Debug {
    var callback: Callback? = null
    private var debugSource: String? = null

    /** 全局单调递增的调试会话 id: 跨页面也不会重复。 */
    private var nextRequestId: Long = 0L

    /** 当前真实调试请求身份: 旧任务/旧回调只能带着旧 id 到达，由消费方丢弃。 */
    private var activeRequestId: Long = 0L
    private var sessionActive: Boolean = false

    /** 新调试会话 id 由唯一所有者分配, 页面不得从 0 自行编号。 */
    fun newDebugRequestId(): Long = synchronized(lock) { ++nextRequestId }
    private val tasks: CompositeCoroutine = CompositeCoroutine()
    private val debugMessageMap = HashMap<String, String>()
    private val debugTimeMap = HashMap<String, Long>()
    private val _checkState = MutableStateFlow(CheckState())
    val checkState: StateFlow<CheckState> = _checkState.asStateFlow()

    var isChecking: Boolean
        get() = _checkState.value.isChecking
        set(value) = synchronized(lock) {
            publishCheckState(isChecking = value)
        }

    private var startTime: Long = systemCurrentTimeMillis()

    private val lock = SynchronizedObject()

    data class CheckState(
        val isChecking: Boolean = false,
        val messages: Map<String, String> = emptyMap(),
    )

    private fun publishCheckState(isChecking: Boolean = _checkState.value.isChecking) {
        _checkState.value = CheckState(
            isChecking = isChecking,
            messages = debugMessageMap.toMap(),
        )
    }

    fun clearCheckMessages() = synchronized(lock) {
        debugMessageMap.clear()
        publishCheckState()
    }

    /**
     * 格式化时间差为 `[mm:ss.SSS]` 字符串 (对应原 `SimpleDateFormat("[mm:ss.SSS]")`)。
     *
     * commonMain 无 `java.text.SimpleDateFormat`, 用纯 Kotlin 拼接实现, 行为与原一致:
     * mm=分钟(0-59), ss=秒(0-59), SSS=毫秒(0-999), 不足补零。
     */
    private fun formatDuration(diffMillis: Long): String {
        val totalSeconds = diffMillis / 1000
        val minutes = (totalSeconds / 60) % 60
        val seconds = totalSeconds % 60
        val millis = diffMillis % 1000
        return "[" +
            minutes.toString().padStart(2, '0') + ":" +
            seconds.toString().padStart(2, '0') + "." +
            millis.toString().padStart(3, '0') + "]"
    }

    fun log(
        sourceUrl: String?,
        msg: String = "",
        print: Boolean = true,
        isHtml: Boolean = false,
        showTime: Boolean = true,
        state: Int = 1
    ) = synchronized(lock) {
        logForLocked(activeRequestId, sourceUrl, msg, print, isHtml, showTime, state)
    }

    /** 带请求身份的日志: 旧调试流程即使仍回调也不能写进新页面。 */
    private fun logFor(
        requestId: Long,
        sourceUrl: String?,
        msg: String = "",
        print: Boolean = true,
        isHtml: Boolean = false,
        showTime: Boolean = true,
        state: Int = 1
    ) = synchronized(lock) {
        logForLocked(requestId, sourceUrl, msg, print, isHtml, showTime, state)
    }

    /** 调用方必须持有 [lock]。 */
    private fun logForLocked(
        requestId: Long,
        sourceUrl: String?,
        msg: String = "",
        print: Boolean = true,
        isHtml: Boolean = false,
        showTime: Boolean = true,
        state: Int = 1
    ) {
        // 公共记录/校验副作用不属于调试页会话: 校验期间或调试页关闭后仍必须执行。
        if (AppLog.isRecordLogEnabled) AppLog.putDebug("sourceDebug: $msg")
        if (isChecking && sourceUrl != null && msg.length < 30) {
            var printMsg = msg
            if (isHtml) {
                printMsg = HtmlFormatter.format(msg)
            }
            if (showTime && debugTimeMap[sourceUrl] != null) {
                val time =
                    formatDuration(systemCurrentTimeMillis() - debugTimeMap[sourceUrl]!!)
                printMsg = printMsg.replace(AppPattern.debugMessageSymbolRegex, "")

                debugMessageMap[sourceUrl] = "$time $printMsg"
                publishCheckState()
            }
        }
        // 只有仍属于当前调试会话的 UI 回调投递受 token 约束。
        if (!sessionActive || requestId != activeRequestId) return
        callback?.let {
            if ((debugSource != sourceUrl || !print)) return
            var printMsg = msg
            if (isHtml) {
                printMsg = HtmlFormatter.format(msg)
            }
            if (showTime) {
                val time = formatDuration(systemCurrentTimeMillis() - startTime)
                printMsg = "$time $printMsg"
            }
            it.printLog(state, printMsg, requestId)
        }
    }

    fun log(msg: String?) = synchronized(lock) {
        logForLocked(activeRequestId, debugSource, msg ?: "", true)
    }

    private fun isCurrentSession(requestId: Long): Boolean = synchronized(lock) {
        sessionActive && activeRequestId == requestId
    }

    /** 供 WS 等外部入口在异步终态前核本次会话是否仍有效。 */
    fun isActiveRequest(requestId: Long): Boolean = isCurrentSession(requestId)

    /** 只把属于当前会话的任务放进全局任务组; 旧阶段迟到的 task 直接取消。 */
    private fun addSessionTask(requestId: Long, task: Coroutine<*>) {
        synchronized(lock) {
            if (!sessionActive || activeRequestId != requestId) {
                task.cancel()
                return
            }
            tasks.add(task)
        }
    }

    fun cancelDebug(destroy: Boolean = false, requestId: Long = 0L) {
        val accepted = synchronized(lock) {
            if (requestId != 0L && requestId != activeRequestId) {
                false
            } else {
                // 保留最新已接受世代, 旧 id 迟到不能重新复活启动。
                tasks.clear()
                sessionActive = false
                if (destroy) {
                    debugSource = null
                    callback = null
                }
                true
            }
        }
        if (!accepted) return
    }

    fun startChecking(source: BookSource) = synchronized(lock) {
        debugTimeMap[source.bookSourceUrl] = systemCurrentTimeMillis()
        debugMessageMap[source.bookSourceUrl] = "${formatDuration(0)} 开始校验"
        publishCheckState(isChecking = true)
    }

    fun finishChecking() = synchronized(lock) {
        publishCheckState(isChecking = false)
    }

    fun getRespondTime(sourceUrl: String): Long {
        return debugTimeMap[sourceUrl] ?: CheckSourceShared.timeout
    }

    fun updateFinalMessage(sourceUrl: String, state: String) = synchronized(lock) {
        if (debugTimeMap[sourceUrl] != null && debugMessageMap[sourceUrl] != null) {
            val spendingTime = systemCurrentTimeMillis() - debugTimeMap[sourceUrl]!!
            debugTimeMap[sourceUrl] =
                if (state == "校验成功") spendingTime else CheckSourceShared.timeout + spendingTime
            val printTime = formatDuration(spendingTime)
            debugMessageMap[sourceUrl] = "$printTime $state"
            publishCheckState()
        }
    }

    fun startDebug(
        scope: CoroutineScope,
        bookSource: BookSource,
        key: String,
        optionSelections: Map<String, ExploreOptionSelection>? = null,
        requestId: Long = 0L,
        callback: Callback? = null,
    ): Long {
        var session = 0L
        var boundCallback: Callback? = null
        val accepted = synchronized(lock) {
            if (requestId != 0L && requestId <= activeRequestId) {
                false
            } else {
                if (requestId != 0L) {
                    activeRequestId = requestId
                } else {
                    // 老入口没有显式 id: 由全局所有者分配, 不能沿用别的页面 id。
                    activeRequestId = ++nextRequestId
                }
                session = activeRequestId
                sessionActive = true
                this.callback = callback ?: this.callback
                boundCallback = this.callback
                debugSource = bookSource.bookSourceUrl
                startTime = systemCurrentTimeMillis()
                tasks.clear()
                true
            }
        }
        if (!accepted) return 0L
        val boundSource = bookSource.bookSourceUrl
        val sessionLogger = object : SourceDebugLogger {
            override fun log(
                key: String,
                msg: String,
                print: Boolean,
                state: Int,
                showTime: Boolean,
            ) = logFor(session, key, msg, print, state = state, showTime = showTime)

            // 隐式入口只使用本会话启动时的 sourceUrl, 不读全局最新 debugSource。
            override fun log(msg: String) = logFor(session, boundSource, msg)
        }
        val debugScope = CoroutineScope(
            scope.coroutineContext + SourceDebugLoggerElement(sessionLogger)
        )
        when {
            key.isAbsUrl() || key.isDataUrl() -> {
                val book = Book()
                book.origin = bookSource.bookSourceUrl
                book.bookUrl = key
                logFor(session, bookSource.bookSourceUrl, "⇒开始访问详情页:$key")
                infoDebug(debugScope, bookSource, book, session)
            }

            key.contains("::") -> {
                val url = key.substringAfter("::")
                logFor(session, bookSource.bookSourceUrl, "⇒开始访问发现页:$url")
                exploreDebug(
                    debugScope,
                    bookSource,
                    url,
                    optionSelections ?: emptyMap(),
                    session,
                    boundCallback,
                )
            }

            key.startsWith("++") -> {
                val url = key.substring(2)
                val book = Book()
                book.origin = bookSource.bookSourceUrl
                book.tocUrl = url
                logFor(session, bookSource.bookSourceUrl, "⇒开始访目录页:$url")
                tocDebug(debugScope, bookSource, book, session)
            }

            key.startsWith("--") -> {
                val url = key.substring(2)
                val book = Book()
                book.origin = bookSource.bookSourceUrl
                book.bookUrl = url
                val chapter = BookChapter()
                chapter.title = "调试"
                chapter.url = url
                logFor(session, bookSource.bookSourceUrl, "⇒开始访正文页:$url")
                contentDebug(debugScope, bookSource, book, chapter, null, session)
            }

            else -> {
                logFor(session, bookSource.bookSourceUrl, "⇒开始搜索关键字:$key")
                searchDebug(
                    debugScope,
                    bookSource,
                    key,
                    optionSelections ?: emptyMap(),
                    session,
                    boundCallback,
                )
            }
        }
        return session
    }

    private fun exploreDebug(
        scope: CoroutineScope,
        bookSource: BookSource,
        url: String,
        optionSelections: Map<String, ExploreOptionSelection>? = null,
        requestId: Long,
        callback: Callback?,
    ) {
        if (!isCurrentSession(requestId)) return
        logFor(requestId, debugSource, "︾开始解析发现页")
        val explore = Coroutine.async(scope, start = CoroutineStart.LAZY) {
            WebBook.getBookListAwait(
                bookSource,
                url,
                1,
                isSearch = false,
                optionSelections = optionSelections,
                onUrlResolved = { analyzeUrl ->
                    logExploreUrl(requestId, analyzeUrl)
                    if (synchronized(lock) {
                            sessionActive && activeRequestId == requestId
                        }
                    ) {
                        callback?.onExploreOptionsResolved(
                            analyzeUrl.resolvedExploreOptions,
                            analyzeUrl.optionSelectionsInvalidated,
                            requestId,
                        )
                    }
                },
                pluginFilters = pluginFilterSessionOf(bookSource),
            )
        }.onSuccess { page ->
            val exploreBooks = page.books
                if (exploreBooks.isNotEmpty()) {
                    logFor(requestId, debugSource, "︽发现页解析完成")
                    logFor(requestId, debugSource, showTime = false)
                    val book = exploreBooks[0]
                    if(book.bookUrl.contains("::"))exploreDebug(scope, bookSource, book.bookUrl.substringAfter("::"), null, requestId, callback)
                    else infoDebug(scope, bookSource, book.toBook(), requestId)
                } else {
                    logFor(requestId, debugSource, "︽未获取到书籍", state = -1)
                }
            }
            .onError {
                logFor(requestId, debugSource, it.stackTraceStr, state = -1)
            }
        addSessionTask(requestId, explore)
        explore.start()
    }

    /** 只记录真实请求已解析出的模板/选择/最终地址, 不额外求值。
     * 模板与最终地址相同时，这两行与后续的获取成功重复，不再输出。 */
    private fun logExploreUrl(requestId: Long, analyzeUrl: AnalyzeUrlCore) {
        val substituted = analyzeUrl.ruleUrl != analyzeUrl.url
        if (substituted) {
            logFor(requestId, debugSource, "请求模板: ${analyzeUrl.ruleUrl}")
        }
        analyzeUrl.resolvedExploreOptions.forEach { option ->
            val labels = option.options.joinToString(", ") { "${it.first}:${it.second}" }
            val selected = option.selectionSnapshot().values.joinToString(",")
            logFor(requestId, debugSource, "参数 ${option.name}: [$labels] 有效原始选择=$selected")
        }
        if (substituted) {
            logFor(requestId, debugSource, "最终地址: ${analyzeUrl.url}")
        }
    }

    private fun searchDebug(
        scope: CoroutineScope,
        bookSource: BookSource,
        key: String,
        optionSelections: Map<String, ExploreOptionSelection>? = null,
        requestId: Long,
        callback: Callback?,
    ) {
        if (!isCurrentSession(requestId)) return
        logFor(requestId, debugSource, "︾开始解析搜索页")
        val search = Coroutine.async(scope, start = CoroutineStart.LAZY) {
            WebBook.getBookListAwait(
                bookSource,
                key,
                1,
                optionSelections = optionSelections,
                onUrlResolved = { analyzeUrl ->
                    logExploreUrl(requestId, analyzeUrl)
                    if (synchronized(lock) {
                            sessionActive && activeRequestId == requestId
                        }
                    ) {
                        callback?.onExploreOptionsResolved(
                            analyzeUrl.resolvedExploreOptions,
                            analyzeUrl.optionSelectionsInvalidated,
                            requestId,
                        )
                    }
                },
                pluginFilters = pluginFilterSessionOf(bookSource),
            )
        }.onSuccess { page ->
            val searchBooks = page.books
                if (searchBooks.isNotEmpty()) {
                    logFor(requestId, debugSource, "︽搜索页解析完成")
                    logFor(requestId, debugSource, showTime = false)
                    val book = searchBooks[0]
                    if (book.bookUrl.contains("::")) {
                        exploreDebug(scope, bookSource, book.bookUrl.substringAfter("::"), null, requestId, callback)
                    } else {
                        infoDebug(scope, bookSource, book.toBook(), requestId)
                    }
                } else {
                    logFor(requestId, debugSource, "︽未获取到书籍", state = -1)
                }
            }
            .onError {
                logFor(requestId, debugSource, it.stackTraceStr, state = -1)
            }
        addSessionTask(requestId, search)
        search.start()
    }

    private fun infoDebug(scope: CoroutineScope, bookSource: BookSource, book: Book, requestId: Long) {
        if (!isCurrentSession(requestId)) return
        if (book.tocUrl.isNotBlank()) {
            logFor(requestId, debugSource, "≡已获取目录链接,跳过详情页")
            logFor(requestId, debugSource, showTime = false)
            tocDebug(scope, bookSource, book, requestId)
            return
        }
        logFor(requestId, debugSource, "︾开始解析详情页")
        val info = Coroutine.async(scope, start = CoroutineStart.LAZY) {
                getBookInfoAwait(bookSource, book)
            }.onSuccess {
                logFor(requestId, debugSource, "︽详情页解析完成")
                logFor(requestId, debugSource, showTime = false)
                if (!book.isWebFile) {
                    tocDebug(scope, bookSource, book, requestId)
                } else {
                    logFor(requestId, debugSource, "≡文件类书源跳过解析目录", state = 1000)
                }
            }
            .onError {
                logFor(requestId, debugSource, it.stackTraceStr, state = -1)
            }
        addSessionTask(requestId, info)
        info.start()
    }

    private fun tocDebug(scope: CoroutineScope, bookSource: BookSource, book: Book, requestId: Long) {
        if (!isCurrentSession(requestId)) return
        logFor(requestId, debugSource, "︾开始解析目录页")
        val chapterList = Coroutine.async(scope, start = CoroutineStart.LAZY) {
                getChapterListAwait(bookSource, book).getOrThrow()
            }.onSuccess { chapters ->
                logFor(requestId, debugSource, "︽目录页解析完成")
                logFor(requestId, debugSource, showTime = false)
                val toc = chapters.filter { !(it.isVolume && it.url.startsWith(it.title)) }
                if (toc.isEmpty()) {
                    logFor(requestId, debugSource, "≡没有正文章节")
                    return@onSuccess
                }
                val nextChapterUrl = toc.getOrNull(1)?.url ?: toc.first().url
                contentDebug(scope, bookSource, book, toc.first(), nextChapterUrl, requestId)
            }
            .onError {
                logFor(requestId, debugSource, it.stackTraceStr, state = -1)
            }
        addSessionTask(requestId, chapterList)
        chapterList.start()
    }

    private fun contentDebug(
        scope: CoroutineScope,
        bookSource: BookSource,
        book: Book,
        bookChapter: BookChapter,
        nextChapterUrl: String?,
        requestId: Long,
    ) {
        if (!isCurrentSession(requestId)) return
        logFor(requestId, debugSource, "︾开始解析正文页")
        val content = Coroutine.async(scope, start = CoroutineStart.LAZY) {
            getContentAwait(bookSource, book, bookChapter, nextChapterUrl, false)
        }.onSuccess {
            logFor(requestId, debugSource, "︽正文页解析完成")
            logFor(requestId, debugSource, showTime = false)
            reviewDebug(scope, bookSource, book, bookChapter, requestId)
        }.onError {
            logFor(requestId, debugSource, it.stackTraceStr, state = -1)
        }
        addSessionTask(requestId, content)
        content.start()
    }

    private fun reviewDebug(
        scope: CoroutineScope,
        bookSource: BookSource,
        book: Book,
        bookChapter: BookChapter,
        requestId: Long,
    ) {
        if (!isCurrentSession(requestId)) return
        if (bookSource.ruleReview.isNullOrEmpty()) {
            logFor(requestId, debugSource, "≡未配置段评规则,跳过")
            logFor(requestId, debugSource, showTime = false)
            subContentDebug(scope, bookSource, book, bookChapter, requestId)
            return
        }
        val rule = bookSource.reviewRule
        if (rule.reviewUrl.isNullOrBlank()) {
            logFor(requestId, debugSource, "≡未配置段评规则,跳过")
            logFor(requestId, debugSource, showTime = false)
            subContentDebug(scope, bookSource, book, bookChapter, requestId)
            return
        }
        logFor(requestId, debugSource, "︾开始解析段评")
        val task = Coroutine.async(scope, start = CoroutineStart.LAZY) {
            // 优先用 countMap 里有评论的段落号去拉，否则退回章节级(0)
            var paragraphIndex = 0
            if (!rule.reviewCountRule.isNullOrBlank()) {
                val countMap = WebBook.getReviewCountAwait(
                    bookSource, book, bookChapter
                ).getOrThrow()
                logFor(
                    requestId, debugSource, "≡段评数 map(取前 5): " +
                        countMap.entries.take(5).joinToString { "${it.key}=${it.value}" }
                )
                countMap.entries.firstOrNull { it.value > 0 }?.key?.let {
                    paragraphIndex = it
                }
            }
            logFor(requestId, debugSource, "≡使用 paragraphIndex=$paragraphIndex 拉取段评")
            paragraphIndex to WebBook.getReviewListAwait(
                bookSource, book, bookChapter, paragraphIndex
            ).getOrThrow()
        }.onSuccess { (paragraphIndex, page) ->
            logFor(requestId, debugSource, "︽段评解析完成")
            logFor(requestId, debugSource, showTime = false)
            val quoted = page.reviews.firstOrNull { it.replyCount > 0 && !it.id.isNullOrBlank() }
            if (quoted != null && !rule.replyListUrl.isNullOrBlank()) {
                reviewRepliesDebug(
                    scope, bookSource, book, bookChapter, paragraphIndex, quoted.id!!, requestId
                )
            } else {
                logFor(requestId, debugSource, "≡未找到带回复的段评或未配置 replyListUrl 规则,跳过回复解析")
                logFor(requestId, debugSource, showTime = false)
                subContentDebug(scope, bookSource, book, bookChapter, requestId)
            }
        }.onError {
            logFor(requestId, debugSource, "段评解析出错:${it.message}")
            subContentDebug(scope, bookSource, book, bookChapter, requestId)
        }
        addSessionTask(requestId, task)
        task.start()
    }

    private fun reviewRepliesDebug(
        scope: CoroutineScope,
        bookSource: BookSource,
        book: Book,
        bookChapter: BookChapter,
        paragraphIndex: Int,
        reviewId: String,
        requestId: Long,
    ) {
        if (!isCurrentSession(requestId)) return
        logFor(requestId, debugSource, "︾开始解析段评回复 reviewId=$reviewId")
        val task = Coroutine.async(scope, start = CoroutineStart.LAZY) {
            WebBook.getReviewRepliesAwait(
                bookSource, book, bookChapter, paragraphIndex, reviewId
            ).getOrThrow()
        }.onSuccess { page ->
            logFor(requestId, debugSource, "≡回复条数:${page.reviews.size}")
            logFor(requestId, debugSource, "︽段评回复解析完成")
            logFor(requestId, debugSource, showTime = false)
            subContentDebug(scope, bookSource, book, bookChapter, requestId)
        }.onError {
            logFor(requestId, debugSource, "段评回复解析出错:${it.message}")
            subContentDebug(scope, bookSource, book, bookChapter, requestId)
        }
        addSessionTask(requestId, task)
        task.start()
    }

    private fun subContentDebug(
        scope: CoroutineScope,
        bookSource: BookSource,
        book: Book,
        bookChapter: BookChapter,
        requestId: Long,
    ) {
        if (!isCurrentSession(requestId)) return
        val subContent = bookSource.contentRule.subContent
        if (subContent.isNullOrBlank()) {
            musicCoverDebug(scope, bookSource, book, bookChapter, requestId)
            return
        }
        logFor(requestId, debugSource, "︾开始解析附加内容规则")
        val task = Coroutine.async(scope, start = CoroutineStart.LAZY) {
            val rule = AnalyzeRuleFactories.create(book, bookSource)
            rule.coroutineContext = currentCoroutineContext()
            rule.setBaseUrl(bookChapter.url)
            rule.chapter = bookChapter
            rule.evalJS(subContent)
        }.onSuccess { raw ->
            when (raw) {
                is List<*> -> {
                    logFor(requestId, debugSource, "≡内容行数:${raw.size}")
                    for (i in 0 until raw.size) {
                        logFor(requestId, debugSource, raw[i]?.toString().orEmpty(), showTime = false)
                    }
                }

                null -> logFor(requestId, debugSource, "≡附加内容为空")
                else -> logFor(requestId, debugSource, "≡附加内容:$raw")
            }
            logFor(requestId, debugSource, "︽附加内容解析完成")
            logFor(requestId, debugSource, showTime = false)
            musicCoverDebug(scope, bookSource, book, bookChapter, requestId)
        }.onError {
            logFor(requestId, debugSource, "附加内容解析出错:${it.message}")
            musicCoverDebug(scope, bookSource, book, bookChapter, requestId)
        }
        addSessionTask(requestId, task)
        task.start()
    }

    private fun musicCoverDebug(
        scope: CoroutineScope,
        bookSource: BookSource,
        book: Book,
        bookChapter: BookChapter,
        requestId: Long,
    ) {
        if (!isCurrentSession(requestId)) return
        val musicCover = bookSource.contentRule.musicCover
        if (musicCover.isNullOrBlank()) {
            logFor(requestId, debugSource, "≡调试流程完成", state = 1000)
            return
        }
        logFor(requestId, debugSource, "︾开始解析音乐封面规则")
        val task = Coroutine.async(scope, start = CoroutineStart.LAZY) {
            val rule = AnalyzeRuleFactories.create(book, bookSource)
            rule.coroutineContext = currentCoroutineContext()
            rule.setBaseUrl(bookChapter.url)
            rule.chapter = bookChapter
            rule.evalJS(musicCover).toString()
        }.onSuccess { url ->
            logFor(requestId, debugSource, "≡音乐封面 URL:$url")
            logFor(requestId, debugSource, "︽音乐封面解析完成", state = 1000)
        }.onError {
            logFor(requestId, debugSource, "音乐封面解析出错:${it.message}", state = -1)
        }
        addSessionTask(requestId, task)
        task.start()
    }

    interface Callback {
        /** 原有公开契约保持不变 (DebugWsHandler 等旧实现只实现这个)。 */
        fun printLog(state: Int, msg: String)

        /** 内部带身份重载: 默认转发到原 2 参数, 旧实现无需改。 */
        fun printLog(state: Int, msg: String, requestId: Long) = printLog(state, msg)

        /** 本主题新增的调试参数定义回调, 带真实请求身份; 非历史 API。 */
        fun onExploreOptionsResolved(
            options: List<ExploreOption>,
            invalidated: Boolean,
            requestId: Long,
        ) {}
    }
}
