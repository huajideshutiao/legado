package io.legado.desktop.ui.book.manga

import io.legado.app.constant.AppLog
import io.legado.app.data.AppDbProviders
import io.legado.app.data.entities.Book
import io.legado.app.data.entities.BookChapter
import io.legado.app.data.entities.BookSource
import io.legado.app.help.coroutine.ConcurrentRateLimiter
import io.legado.app.help.book.BookHelpProviders
import io.legado.app.help.book.isImage
import io.legado.app.help.book.isLocal
import io.legado.app.model.fileBook.CbzFile
import io.legado.app.model.webBook.WebBook
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelChildren
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.withContext
import kotlin.math.min

/**
 * 桌面端漫画阅读 ViewModel (对照 app 端 [io.legado.app.ui.book.manga.ReadMangaViewModel])。
 *
 * # 与 app 端差异
 *
 * - **不继承 BaseReadViewModel**: app 端 BaseReadViewModel 重 Android 依赖 (appCtx /
 *   LiveData / appDb 单例 / SourceHelp 等), 桌面端不可复用, 改为普通 class 持有
 *   [CoroutineScope] (由 Compose `rememberCoroutineScope` 注入)
 * - **单章节加载模式**: app 端管理 prev/cur/next 三个 [MangaChapter] 实现无缝衔接,
 *   桌面端简化为只暴露当前章节图片 URL 列表 ([curChapterImages]), 章节切换时整体替换;
 *   预下载仅做"下一章内容缓存", 不维护三章节链表 (降低复杂度, 满足桌面端阅读体验)
 * - **图片 URL 提取**: app 端用 `BookHelp.flowImages(chapter, content)` (内部走
 *   `ChapterContentParser.extractImages`, 该 parser 在 app 模块未下沉), 桌面端复制
 *   简化版 [extractImageUrls] 直接解析 `<img src="...">`
 * - **章节缓存**: app 端 `BookHelp.getContent` / `BookHelp.saveContent` / `BookHelp.hasContent`
 *   全套缓存生命周期; 桌面端只调 [BookHelpProviders.get].getContent 读缓存, 写缓存由
 *   shared `WebBook.getContentAwait(needSave=true)` 内部完成 (走 BookHelpAccessor.saveContent)
 * - **预下载限流**: 与 app 端一致用 [Semaphore] + [ConcurrentRateLimiter], 但并发度固定 2
 *   (app 端可配置 preDownloadNum, 桌面端配置项 [PreferKey.mangaPreDownloadNum] 仅影响预下载章节数)
 *
 * # 状态暴露
 *
 * 用 [MutableStateFlow] 暴露 UI 状态, Compose 经 `collectAsState()` 订阅:
 * - [curChapterImages]: 当前章节图片 URL 列表 (空列表 = 加载中或失败)
 * - [curChapterIndex]: 当前章节序号
 * - [chapterSize]: 总章节数
 * - [curChapterTitle]: 当前章节标题 (标题栏显示)
 * - [loading]: 加载中标记 (覆盖层显示/隐藏)
 * - [error]: 加载失败消息 (null = 无错误)
 *
 * @param scope Compose `rememberCoroutineScope()` 注入的作用域, VM 内 launch 全部派生于此,
 *   退出 Screen 时随作用域取消; 预下载用独立 [downloadScope] 避免被章节切换取消
 */
class MangaReaderViewModel(
    private val scope: CoroutineScope,
) {
    /** 当前书籍 (initData 写入, 退出时清空) */
    var curBook: Book? = null
        private set

    /** 当前书源 (按 book.origin 查 DB, 拉取章节内容用) */
    var curBookSource: BookSource? = null
        private set

    /** 章节列表 (内存缓存, 首次加载章节内容时拉取, 后续章节切换直接查) */
    private var chapterList: List<BookChapter>? = null

    // ---- 章节状态 (UI 订阅) ----

    private val _curChapterIndex = MutableStateFlow(0)
    /** 当前章节序号 (0-based) */
    val curChapterIndex: StateFlow<Int> = _curChapterIndex.asStateFlow()

    private val _chapterSize = MutableStateFlow(0)
    /** 总章节数 */
    val chapterSize: StateFlow<Int> = _chapterSize.asStateFlow()

    private val _curChapterTitle = MutableStateFlow("")
    /** 当前章节标题 (标题栏显示) */
    val curChapterTitle: StateFlow<String> = _curChapterTitle.asStateFlow()

    private val _curChapterImages = MutableStateFlow<List<String>>(emptyList())
    /** 当前章节图片 URL 列表 (空列表 = 加载中或失败) */
    val curChapterImages: StateFlow<List<String>> = _curChapterImages.asStateFlow()

    private val _loading = MutableStateFlow(false)
    /** 加载中标记 (覆盖层显示/隐藏) */
    val loading: StateFlow<Boolean> = _loading.asStateFlow()

    private val _error = MutableStateFlow<String?>(null)
    /** 加载失败消息 (null = 无错误) */
    val error: StateFlow<String?> = _error.asStateFlow()

    // ---- 预下载 (对照 app 端 ReadMangaViewModel.downloadScope / preDownloadSemaphore) ----

    /** 预下载独立作用域, 章节切换时不取消 (避免重复下载已预下载的章节) */
    private val downloadScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** 预下载并发限流 (与 app 端 preDownloadSemaphore = Semaphore(2) 一致) */
    private val preDownloadSemaphore = Semaphore(2)

    /** 预下载任务 (取消用) */
    private var preDownloadTask: Job? = null

    /** 已下载章节集合 (避免重复下载, 对照 app 端 ReadMangaViewModel.downloadedChapters) */
    private val downloadedChapters = hashSetOf<Int>()

    /** 下载失败章节计数 (达 3 次跳过, 对照 app 端 downloadFailChapters) */
    private val downloadFailChapters = hashMapOf<Int, Int>()

    /** 加载中的章节集合 (避免并发重复加载, 对照 app 端 ReadMangaViewModel.loadingChapters) */
    private val loadingChapters = mutableSetOf<Int>()

    /** 速率限制器 (按书源限流, 对照 app 端 ReadMangaViewModel.rateLimiter) */
    private var rateLimiter = ConcurrentRateLimiter(null)

    /** 预下载数量 (PreferKey.mangaPreDownloadNum, initData 注入, 默认 0=不预下载) */
    var preDownloadNum: Int = 0
        private set

    /**
     * 初始化数据 (对照 app 端 ReadMangaViewModel.initMangaData + initManga)。
     *
     * @param book 待阅读的漫画书
     * @param initialChapterIndex 初始章节序号 (通常取 book.durChapterIndex)
     * @param preDownloadNum 预下载数量 (PreferKey.mangaPreDownloadNum, 0=不预下载)
     */
    suspend fun initData(book: Book, initialChapterIndex: Int = book.durChapterIndex, preDownloadNum: Int = 0) {
        curBook = book
        this.preDownloadNum = preDownloadNum
        // 本地 cbz/zip 漫画: 直接走 CbzFile 解析, 无需书源 (对照 app 端 Book.getHandler() 调度)
        if (book.isLocalCbz()) {
            chapterList = withContext(Dispatchers.IO) {
                runCatching { CbzFile.getChapterList(book) }
                    .onFailure {
                        AppLog.put("桌面漫画(cbz)获取章节列表出错\n${it.message}", it)
                        _error.value = "获取章节列表失败: ${it.message}"
                    }.getOrNull()
            }
            _chapterSize.value = chapterList?.size ?: 0
            val targetIndex = initialChapterIndex.coerceIn(0, (_chapterSize.value - 1).coerceAtLeast(0))
            loadChapter(targetIndex)
            return
        }
        // 查书源 (对照 app 端 BaseReadViewModel.upBook -> curBookSource = SourceHelp.getSource)
        curBookSource = withContext(Dispatchers.IO) {
            AppDbProviders.get().bookSourceDao.getBookSource(book.origin)
        }
        rateLimiter = ConcurrentRateLimiter(curBookSource)
        // 拉章节列表 (对照 app 端 upToc: WebBook.getChapterListAwait)
        val source = curBookSource
        if (source == null) {
            _error.value = "书源不存在"
            return
        }
        chapterList = runCatching {
            WebBook.getChapterListAwait(source, book).getOrThrow()
        }.onFailure {
            AppLog.put("桌面漫画获取章节列表出错\n${it.message}", it)
            _error.value = "获取章节列表失败: ${it.message}"
        }.getOrNull()
        _chapterSize.value = chapterList?.size ?: 0
        // 加载初始章节
        val targetIndex = initialChapterIndex.coerceIn(0, (_chapterSize.value - 1).coerceAtLeast(0))
        loadChapter(targetIndex)
    }

    /**
     * 判断是否为本地 cbz/zip 漫画文件 (对照 app 端 FileBook.getHandler 调度逻辑)。
     *
     * - `.cbz` 后缀: 直接走 CbzFile
     * - `.zip` 后缀 + book.isImage: 走 CbzFile (与 app 端 originName.endsWith(".zip") && isImage 对齐)
     */
    private fun Book.isLocalCbz(): Boolean =
        isLocal && (originName.endsWith(".cbz", true) ||
                originName.endsWith(".zip", true) && isImage)

    /**
     * 加载指定章节 (对照 app 端 ReadMangaViewModel.loadContent + contentLoadFinish)。
     *
     * 内部流程:
     * 1. 优先读缓存 [BookHelpProviders.get].getContent (本地书 / 已下载章节)
     * 2. 缓存未命中调 [WebBook.getContentAwait] 拉取并写缓存
     * 3. 用 [extractImageUrls] 解析 `<img src="...">` 提取图片 URL 列表
     * 4. 更新 [_curChapterImages] / [_curChapterIndex] / [_curChapterTitle]
     * 5. 触发预下载下一章 (按 [preDownloadNum])
     *
     * @param index 章节序号 (0-based, 越界自动 clamp)
     */
    fun loadChapter(index: Int) {
        val book = curBook ?: return
        val isCbz = book.isLocalCbz()
        // cbz 本地漫画无需书源, 直接走 CbzFile 解析 (对照 app 端 Book.getHandler() 调度)
        val source = if (isCbz) null else curBookSource ?: run {
            _error.value = "书源不存在"
            return
        }
        val chapters = chapterList ?: run {
            _error.value = "章节列表未加载"
            return
        }
        val clampedIndex = index.coerceIn(0, chapters.lastIndex.coerceAtLeast(0))
        val chapter = chapters.getOrNull(clampedIndex) ?: run {
            _error.value = "章节不存在: $clampedIndex"
            return
        }
        // 取消旧预下载任务 (避免旧章节预下载占用并发槽)
        preDownloadTask?.cancel()
        // 标记加载中, 清空旧数据 (避免显示上一章图片)
        _loading.value = true
        _error.value = null
        _curChapterImages.value = emptyList()
        _curChapterIndex.value = clampedIndex
        _curChapterTitle.value = chapter.title
        scope.launch {
            try {
                // 1. 优先读缓存 (对照 app 端 BookHelp.getContent)
                var content = withContext(Dispatchers.IO) {
                    BookHelpProviders.get().getContent(book, chapter)
                }
                // 2. 缓存未命中: cbz 走 CbzFile.getContent, 网络书走 WebBook.getContentAwait
                if (content == null) {
                    content = if (isCbz) {
                        // 本地 cbz/zip: 直接从 zip 读取 (对照 app 端 Book.getHandler().getContent)
                        withContext(Dispatchers.IO) {
                            runCatching { CbzFile.getContent(book, chapter) }
                                .onFailure {
                                    AppLog.put("桌面漫画(cbz)获取章节内容出错\n${it.message}", it)
                                    _error.value = "加载失败: ${it.message}"
                                }.getOrNull()
                        }
                    } else {
                        val nextChapterUrl = chapters.getOrNull(clampedIndex + 1)?.url
                        runCatching {
                            WebBook.getContentAwait(source!!, book, chapter, nextChapterUrl)
                        }.onFailure {
                            AppLog.put("桌面漫画获取章节内容出错\n${it.message}", it)
                            _error.value = "加载失败: ${it.message}"
                        }.getOrNull()
                    }
                }
                if (content == null) {
                    // 错误已由 _error 暴露
                    return@launch
                }
                if (content.isEmpty()) {
                    _error.value = "正文内容为空"
                    return@launch
                }
                // 3. 解析图片 URL 列表 (对照 app 端 BookHelp.flowImages + ChapterContentParser.extractImages)
                // cbz 本地漫画: 图片 URL 是 zip entry name, 加 cbz:// 前缀让 loadMangaImage 识别
                val images = if (isCbz) {
                    extractImageUrls(content).map { "cbz://$it" }
                } else {
                    extractImageUrls(content)
                }
                if (images.isEmpty()) {
                    _error.value = "正文没有图片"
                    return@launch
                }
                // 标记已下载
                synchronized(this) {
                    downloadedChapters.add(clampedIndex)
                    downloadFailChapters.remove(clampedIndex)
                }
                // 4. 更新 UI 状态
                _curChapterImages.value = images
                _loading.value = false
                // 5. 触发预下载下一章 (仅当 preDownloadNum > 0 且非本地书)
                if (preDownloadNum > 0 && !book.isLocal) {
                    preDownload(clampedIndex)
                }
                // 持久化阅读进度
                saveRead(clampedIndex)
            } catch (e: Exception) {
                AppLog.put("桌面漫画加载章节出错\n${e.message}", e)
                _error.value = "加载出错: ${e.message}"
            }
        }
    }

    /**
     * 切换到下一章 (对照 app 端 ReadMangaViewModel.moveToNextChapter)。
     *
     * @return true 切换成功, false 已到末章
     */
    fun moveToNextChapter(): Boolean {
        val cur = _curChapterIndex.value
        val size = _chapterSize.value
        if (cur >= size - 1) return false
        loadChapter(cur + 1)
        return true
    }

    /**
     * 切换到上一章 (对照 app 端 ReadMangaViewModel.moveToPrevChapter)。
     *
     * @return true 切换成功, false 已到首章
     */
    fun moveToPrevChapter(): Boolean {
        val cur = _curChapterIndex.value
        if (cur <= 0) return false
        loadChapter(cur - 1)
        return true
    }

    /**
     * 预下载后续章节 (对照 app 端 ReadMangaViewModel.preDownload)。
     *
     * 仅下载并写缓存, 不更新 UI 状态 (UI 状态只由 [loadChapter] 更新)。
     * 失败章节计数达 3 次跳过 (与 app 端一致)。
     *
     * @param curIndex 当前章节序号, 预下载 curIndex+1 .. curIndex+preDownloadNum
     */
    private fun preDownload(curIndex: Int) {
        val book = curBook ?: return
        val source = curBookSource ?: return
        val chapters = chapterList ?: return
        preDownloadTask?.cancel()
        preDownloadTask = downloadScope.launch {
            val maxIndex = min(curIndex + preDownloadNum, chapters.lastIndex)
            for (i in (curIndex + 1)..maxIndex) {
                if (!isActive) break
                if (downloadedChapters.contains(i)) continue
                if ((downloadFailChapters[i] ?: 0) >= 3) continue
                preDownloadChapter(book, source, chapters, i)
            }
        }
    }

    /**
     * 预下载单章 (对照 app 端 ReadMangaViewModel.download + downloadNetworkContent)。
     *
     * 加锁防并发重复加载; 走 [Semaphore] 限流; 走 [ConcurrentRateLimiter.withLimit] 限速
     * (与 [io.legado.app.model.analyzeRule.AnalyzeUrlCore.getStrResponseAwait] 内
     * `concurrentRateLimiter.withLimit { ... }` 一致, withLimit 是 suspend inline fun);
     * 成功写缓存 (WebBook.getContentAwait 内部 needSave=true 自动写), 失败计数。
     */
    private suspend fun preDownloadChapter(
        book: Book,
        source: BookSource,
        chapters: List<BookChapter>,
        index: Int,
    ) {
        val chapter = chapters.getOrNull(index) ?: return
        // 加锁防并发重复加载 (对照 app 端 addLoading)
        synchronized(loadingChapters) {
            if (index in loadingChapters) return
            loadingChapters.add(index)
        }
        try {
            preDownloadSemaphore.acquire()
            // 已有缓存跳过 (对照 app 端 BookHelp.hasContent)
            if (BookHelpProviders.get().getContent(book, chapter) != null) {
                synchronized(this) { downloadedChapters.add(index) }
                return
            }
            val nextChapterUrl = chapters.getOrNull(index + 1)?.url
            // 速率限制 (对照 AnalyzeUrlCore.getStrResponseAwait 内 concurrentRateLimiter.withLimit)
            rateLimiter.withLimit {
                runCatching {
                    WebBook.getContentAwait(source, book, chapter, nextChapterUrl)
                }.onSuccess {
                    synchronized(this) {
                        downloadedChapters.add(index)
                        downloadFailChapters.remove(index)
                    }
                }.onFailure {
                    AppLog.put("桌面漫画预下载章节 $index 失败\n${it.message}", it)
                    synchronized(this) {
                        downloadFailChapters[index] = (downloadFailChapters[index] ?: 0) + 1
                    }
                }
            }
        } finally {
            preDownloadSemaphore.release()
            synchronized(loadingChapters) { loadingChapters.remove(index) }
        }
    }

    /**
     * 取消预下载任务 (退出 Screen 时调用, 对照 app 端 ReadMangaActivity.onPause -> cancelPreDownloadTask)。
     */
    fun cancelPreDownloadTask() {
        preDownloadTask?.cancel()
        downloadScope.coroutineContext.cancelChildren()
    }

    /**
     * 持久化阅读进度 (对照 app 端 ReadMangaViewModel.saveRead)。
     *
     * 写回 book.durChapterIndex / durChapterTitle / durChapterTime, 经 BookDao.update 落库。
     * 注: 不写 durChapterPos (app 端用负值标记末页的语义在桌面端简化掉, 始终从首页加载)。
     *
     * @param index 当前章节序号
     */
    private suspend fun saveRead(index: Int) {
        val book = curBook ?: return
        val chapters = chapterList ?: return
        val chapter = chapters.getOrNull(index) ?: return
        runCatching {
            book.durChapterIndex = index
            book.durChapterPos = 0
            book.durChapterTitle = chapter.title
            book.durChapterTime = System.currentTimeMillis()
            AppDbProviders.get().bookDao.update(book)
        }.onFailure {
            AppLog.put("桌面漫画保存阅读进度出错\n${it.message}", it)
        }
    }

    /**
     * 从章节正文提取图片 URL 列表 (对照 app 端 ChapterContentParser.extractImages + BookHelp.flowImages)。
     *
     * 简化实现: 仅解析 `<img src="...">` 的 src 属性 (单/双引号), 不处理 style/onclick;
     * 漫画正文经 HtmlFormatter.formatKeepImg 标准化后, src 通常是绝对 URL 或可被书源 baseUrl
     * 解析的相对路径, 这里直接返回原始 src (图片加载时由 OkHttp 按 src 发请求)。
     *
     * 注: app 端 `BookHelp.flowImages` 是 Flow<String>, 这里直接返回 List<String>
     * (桌面端不需要 Flow 的延迟发射特性, 一次性返回更简单)。
     *
     * @param content 章节正文 (含 `<img>` 标签的 HTML 片段)
     * @return 图片 URL 列表 (空列表 = 无图片)
     */
    private fun extractImageUrls(content: String): List<String> {
        if (!content.contains("<img", ignoreCase = true)) return emptyList()
        val images = mutableListOf<String>()
        var i = 0
        val len = content.length
        while (i < len) {
            val tagStart = content.indexOf("<img", i, ignoreCase = true)
            if (tagStart == -1) break
            val tagEnd = content.indexOf('>', tagStart)
            if (tagEnd == -1) break
            val fullTag = content.substring(tagStart, tagEnd + 1)
            val src = getAttr(fullTag, "src")
            if (src != null && src.isNotBlank()) {
                images.add(src)
            }
            i = tagEnd + 1
        }
        return images
    }

    /**
     * 从 HTML 标签提取属性值 (对照 app 端 ChapterContentParser.getAttr)。
     *
     * 支持单引号 / 双引号 / 无引号三种属性值写法。
     */
    private fun getAttr(tag: String, attrName: String): String? {
        val search = "$attrName="
        val index = tag.indexOf(search, ignoreCase = true)
        if (index == -1) return null
        val valueStart = index + search.length
        if (valueStart >= tag.length) return null
        val quote = tag[valueStart]
        return if (quote == '"' || quote == '\'') {
            val endQuote = tag.indexOf(quote, valueStart + 1)
            if (endQuote == -1) null else tag.substring(valueStart + 1, endQuote)
        } else {
            var end = valueStart
            while (end < tag.length && tag[end] != ' ' && tag[end] != '>' && tag[end] != '/') {
                end++
            }
            if (end > valueStart) tag.substring(valueStart, end) else null
        }
    }
}
