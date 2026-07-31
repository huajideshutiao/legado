package io.legado.app.ui.book.manga

import io.legado.app.constant.AppLog
import io.legado.app.data.AppDbProviders
import io.legado.app.data.entities.Book
import io.legado.app.data.entities.BookChapter
import io.legado.app.data.entities.BookProgress
import io.legado.app.data.entities.BookSource
import io.legado.app.help.IntentData
import io.legado.app.help.book.BookStorageProviders
import io.legado.app.help.book.ContentProcessorProviders
import io.legado.app.help.book.getDisplayTitle
import io.legado.app.help.book.getUseReplaceRule
import io.legado.app.help.book.isLocal
import io.legado.app.help.book.isNotShelf
import io.legado.app.help.book.isSameNameAuthor
import io.legado.app.help.book.readSimulating
import io.legado.app.help.book.simulatedTotalChapterNum
import io.legado.app.help.coroutine.Coroutine
import io.legado.app.help.coroutine.IoDispatcher
import io.legado.app.model.ReadTimeRecorder
import io.legado.app.model.webBook.WebBook
import io.legado.app.ui.book.manga.config.MangaColorFilterConfig
import io.legado.app.ui.book.manga.config.MangaFooterConfig
import io.legado.app.ui.book.manga.entities.BaseMangaPage
import io.legado.app.ui.book.manga.entities.MangaChapter
import io.legado.app.ui.book.manga.entities.MangaContent
import io.legado.app.ui.book.manga.entities.MangaPage
import io.legado.app.ui.book.manga.entities.ReaderLoading
import io.legado.app.utils.mapIndexed
import io.legado.app.utils.systemCurrentTimeMillis
import kotlinx.atomicfu.locks.SynchronizedObject
import kotlinx.atomicfu.locks.synchronized
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelChildren
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.withContext
import kotlin.math.min

/**
 * 漫画图片提取器跨平台抽象。
 *
 * 对应 app 端 [io.legado.app.help.book.BookHelp.flowImages], 该方法依赖
 * ChapterContentParser + AnalyzeUrl (重平台绑定), 留 app 端实现。actual 平台
 * 启动时注入实现, 供 [MangaReaderViewModelShared.getManageChapter] 调用。
 */
interface MangaImageExtractor {
    /**
     * 从章节正文提取图片 URL 流 (对应 app 端 `BookHelp.flowImages(bookChapter, content)`)。
     *
     * @param bookChapter 章节信息 (用于取书源 / baseUrl)
     * @param content 章节正文 (BookHelp.getContent / WebBook.getContentAwait 返回)
     * @return 图片 URL 流 (distinctUntilChanged 由调用方处理)
     */
    fun flowImages(bookChapter: BookChapter, content: String): Flow<String>
}

/**
 * 漫画阅读器配置 (actual 平台注入)。
 *
 * 封装 app 端 [io.legado.app.help.config.AppConfig] 中漫画相关配置项,
 * actual 平台 (Android / 桌面) 从各自 AppConfig 读取后注入。
 *
 * @param hideMangaTitle 是否隐藏漫画标题 (对应 AppConfig.hideMangaTitle), 默认 false
 * @param preDownloadNum 预下载数量 (对应 AppConfig.preDownloadNum), 默认 10
 * @param syncBookProgressPlus 是否启用增强版进度同步 (对应 AppConfig.syncBookProgressPlus), 默认 false
 * @param horizontal 横向翻页模式 (对应 AppConfig.enableMangaHorizontalScroll), 默认 false
 * @param autoPageSpeed 自动翻页速度 (对应 AppConfig.mangaAutoPageSpeed), 默认 3
 * @param grayEnabled 灰度滤镜 (对应 AppConfig.enableMangaGray), 默认 false
 * @param colorFilterConfig 颜色滤镜矩阵 (对应 AppConfig.mangaColorFilter), 默认无操作
 * @param gifAutoNext GIF 播完自动翻页 (对应 AppConfig.enableMangaGifAutoNext), 默认 false
 * @param disablePageAnim 禁用翻页动画 (对应 AppConfig.disableMangaPageAnim), 默认 false
 * @param footerConfig 页脚信息条配置 (对应 AppConfig.mangaFooterConfig), 默认全显
 */
data class MangaReaderConfig(
    val hideMangaTitle: Boolean = false,
    val preDownloadNum: Int = 10,
    val syncBookProgressPlus: Boolean = false,
    val horizontal: Boolean = false,
    val autoPageSpeed: Int = 3,
    val grayEnabled: Boolean = false,
    val colorFilterConfig: MangaColorFilterConfig = MangaColorFilterConfig(),
    val gifAutoNext: Boolean = false,
    val disablePageAnim: Boolean = false,
    val footerConfig: MangaFooterConfig = MangaFooterConfig(),
) {
    companion object {
        val DEFAULT = MangaReaderConfig()
    }
}

/**
 * KMP 版漫画阅读 ViewModel: 用 StateFlow 替代 app 端 [io.legado.app.ui.book.manga.ReadMangaViewModel]
 * 的 LiveData, 业务逻辑原样下沉, 依赖经 provider 间接访问。
 *
 * 与 app 端 [io.legado.app.ui.book.manga.ReadMangaViewModel] 的对应:
 * - 状态流 [book]/[bookSource]/[chapterList]/[durChapterIndex]/[durChapter]/[durChapterPos]/
 *   [mangaContent]/[loading]/[error] 对应 app 端 curBook/curBookSource/chapterListData/
 *   durChapterIndex/durChapterPos/upContentLiveData/loadFailLiveData/showLoadingLiveData
 * - [loadContent]/[contentLoadFinish]/[moveToNextChapter]/[moveToPrevChapter]/[saveRead]/
 *   [preDownload]/[cancelPreDownloadTask] 方法签名与 app 端一致
 * - appDb → [AppDbProviders.get]; BookHelp.getContent/hasContent/delContent →
 *   [BookStorageProviders.get]; BookHelp.flowImages → [imageExtractor];
 *   AppConfig.hideMangaTitle/preDownloadNum → [config];
 *   ContentProcessor.get(name, origin).getTitleReplaceRules() →
 *   [ContentProcessorProviders.get].getTitleReplaceRules(book);
 *   book.saveRead() 内联 (AppDbProviders.bookDao.updateProgress + ReadTimeRecorder.flushAll)
 *
 * 不下沉部分 (留 app 端薄壳):
 * - initData(intent: Intent) 的 Intent 解包 (Android 特有)
 * - syncProgress/syncBookProgress/autoChangeSource (BaseReadViewModel 基类, 复杂进度同步留 app 端)
 * - onSourceChanged/applyProgress 回调 (BaseReadViewModel 模板方法)
 *
 * @param scope 协程作用域, actual 平台注入 (Android=viewModelScope / 桌面=应用主作用域)
 * @param imageExtractor 漫画图片提取器 (actual 平台注入, 封装 BookHelp.flowImages)
 * @param config 漫画阅读器配置 (actual 平台从 AppConfig 读取后注入)
 */
class MangaReaderViewModelShared(
    private val scope: CoroutineScope,
    private val imageExtractor: MangaImageExtractor,
    private val config: MangaReaderConfig = MangaReaderConfig.DEFAULT,
) {
    // region 状态流: 外部只读 StateFlow, 适配 Compose 重组
    private val _book = MutableStateFlow<Book?>(null)
    val book: StateFlow<Book?> = _book.asStateFlow()

    private val _bookSource = MutableStateFlow<BookSource?>(null)
    val bookSource: StateFlow<BookSource?> = _bookSource.asStateFlow()

    private val _chapterList = MutableStateFlow<List<BookChapter>>(emptyList())
    val chapterList: StateFlow<List<BookChapter>> = _chapterList.asStateFlow()

    private val _durChapterIndex = MutableStateFlow(0)
    val durChapterIndex: StateFlow<Int> = _durChapterIndex.asStateFlow()

    private val _durChapter = MutableStateFlow<BookChapter?>(null)
    val durChapter: StateFlow<BookChapter?> = _durChapter.asStateFlow()

    private val _durChapterPos = MutableStateFlow(0)
    val durChapterPos: StateFlow<Int> = _durChapterPos.asStateFlow()

    private val _mangaContent = MutableStateFlow<MangaContent?>(null)
    val mangaContent: StateFlow<MangaContent?> = _mangaContent.asStateFlow()

    private val _loading = MutableStateFlow(false)
    val loading: StateFlow<Boolean> = _loading.asStateFlow()

    /** 错误信息 + 是否可重试 (对应 app 端 loadFailLiveData Pair<String, Boolean>)。 */
    private val _error = MutableStateFlow<Pair<String, Boolean>?>(null)
    val error: StateFlow<Pair<String, Boolean>?> = _error.asStateFlow()
    // endregion

    // region 内部状态 (对应 app 端 ReadMangaViewModel 字段)
    var chapterSize = 0
        private set
    private var simulatedChapterSize = 0
    var chapterChanged = false
        private set
    private var prevMangaChapter: MangaChapter? = null
    private var curMangaChapter: MangaChapter? = null
    private var nextMangaChapter: MangaChapter? = null
    private val loadingChapters = mutableSetOf<Int>()

    // 替代原 @Synchronized/synchronized(this) 的 this 监视器 (kotlin.jvm.Synchronized 无 common 变体且 native 无效)
    private val syncLock = SynchronizedObject()
    private var preDownloadTask: Job? = null
    private val downloadedChapters = mutableSetOf<Int>()
    private val downloadFailChapters = mutableMapOf<Int, Int>()
    private val downloadScope = CoroutineScope(SupervisorJob() + IoDispatcher)
    private val preDownloadSemaphore = Semaphore(2)
    val hasNextChapter: Boolean get() = _durChapterIndex.value < simulatedChapterSize - 1
    // endregion

    /** 当前章节图片数 (对照 app 端 curMangaChapter.imageCount), 供 ScreenModel 信息条/书签使用 */
    val currentImageCount: Int get() = curMangaChapter?.imageCount ?: 0

    /**
     * 初始化数据 (对应 app 端 ReadMangaViewModel.initData)。
     *
     * app 端从 Intent 解包 chapterChanged/chapterIndex/chapterPos, shared 版本改为
     * 参数注入 (actual 平台薄壳解包 Intent 后调用本方法)。book 取值顺序与 app 端
     * 一致: IntentData.book as? Book ?: _book.value。
     *
     * @param chapterChanged 是否章节跳转 (对应 intent.getBooleanExtra("chapterChanged"))
     * @param overrideIndex 覆盖章节索引, -1 表示不覆盖 (对应 intent.getIntExtra("chapterIndex", -1))
     * @param overridePos 覆盖章节位置 (对应 intent.getIntExtra("chapterPos", 0))
     * @param success 成功回调 (对应 app 端 onSuccess)
     */
    fun initData(
        chapterChanged: Boolean = false,
        overrideIndex: Int = -1,
        overridePos: Int = 0,
        success: () -> Unit = {},
    ) {
        scope.launch {
            runCatching {
                val book = (IntentData.book as? Book) ?: _book.value
                if (book != null) {
                    this@MangaReaderViewModelShared.chapterChanged = chapterChanged
                    val isSameBook = _book.value?.bookUrl == book.bookUrl
                    upBook(book)
                    // upBook 结束后 _book 已是 Book, 这里再改 durChapter*, 让 initMangaData 走清缓存分支
                    if (overrideIndex >= 0) {
                        _book.value?.durChapterIndex = overrideIndex
                        _book.value?.durChapterPos = overridePos
                    }
                    initManga(_book.value!!, isSameBook)
                } else {
                    _error.value = "没有找到书" to true
                }
            }.onSuccess {
                success()
            }.onFailure {
                AppLog.put("初始化数据失败\n${it.message}", it)
            }
        }
    }

    /**
     * 加载书籍 + 书源 + 章节列表 (对应 app 端 BaseReadViewModel.upBook)。
     *
     * BaseReadViewModel 重 Android 依赖 (IntentData/source 等), 留 app 端;
     * 本方法仅保留 manga VM 用到的核心逻辑: 设置 _book, 读 bookSource, 读 chapterList。
     */
    private suspend fun upBook(book: Book) {
        _book.value = book
        // 加载书源 (对应 app 端 curBookSource = appDb.bookSourceDao.getBookSource(book.origin))
        val source = runCatching {
            AppDbProviders.get().bookSourceDao.getBookSource(book.origin)
        }.getOrNull()
        _bookSource.value = source
        onBookSourceChanged()
        // 加载章节列表 (对应 app 端 chapterListData.postValue(appDb.bookChapterDao.getChapterList))
        val chapterList = runCatching {
            AppDbProviders.get().bookChapterDao.getChapterList(book.bookUrl)
        }.getOrDefault(emptyList())
        _chapterList.value = chapterList
    }

    /** 书源变更回调 (对应 app 端 BaseReadViewModel.onBookSourceChanged), 子类可扩展。 */
    private fun onBookSourceChanged() {
        // app 端重新构造 rateLimiter, shared 版本暂不下沉 ConcurrentRateLimiter (无核心业务依赖)
    }

    /**
     * 初始化漫画阅读数据 (对应 app 端 ReadMangaViewModel.initMangaData)。
     *
     * 同步 chapterSize/simulatedChapterSize/durChapterIndex/durChapterPos,
     * 清空 manga 章节缓存 + loading/download 状态。
     *
     * @param book 当前书籍
     * @param isDiffBook 是否不同书 (true 时清缓存 + 重置 ReadTimeRecorder)
     * @param prefetchedList 调用方预先读取的章节列表 (规避 StateFlow 异步未生效问题,
     *   对应 app 端 initManga 中 withContext(Main) { chapterListData.value } 预取)
     */
    private suspend fun initMangaData(
        book: Book,
        isDiffBook: Boolean = _book.value?.bookUrl != book.bookUrl,
        prefetchedList: List<BookChapter>? = null,
    ) {
        _book.value = book
        if (isDiffBook) {
            ReadTimeRecorder.setBook(ReadTimeRecorder.Source.MANGA, book.name)
        }
        val chapterList = prefetchedList ?: _chapterList.value
        chapterSize = chapterList?.size
            ?: withContext(IoDispatcher) { AppDbProviders.get().bookChapterDao.getChapterCount(book.bookUrl) }
        simulatedChapterSize = if (book.readSimulating()) book.simulatedTotalChapterNum()
        else chapterSize
        if (isDiffBook || _durChapterIndex.value != book.durChapterIndex) {
            _durChapterIndex.value = book.durChapterIndex
            _durChapterPos.value = book.durChapterPos * (if (book.durChapterPos < 0) -1 else 1)
            clearMangaChapter()
        }
        if (_durChapterIndex.value !in 0 until simulatedChapterSize) {
            book.durChapterIndex = 0
            _durChapterIndex.value = 0
            _durChapterPos.value = 0
        }
        synchronized(syncLock) {
            loadingChapters.clear()
            downloadedChapters.clear()
            downloadFailChapters.clear()
        }
    }

    /**
     * 初始化漫画 (对应 app 端 ReadMangaViewModel.initManga)。
     *
     * 调用 [initMangaData] 同步状态, 然后根据 isSameBook 决定首次加载或刷新,
     * 最后处理章节跳转 / 进度同步 / 自动换源 (后者留 app 端薄壳)。
     */
    private suspend fun initManga(book: Book, isSameBook: Boolean) {
        // _chapterList.value 是 upBook 中刚赋值的, IO 线程立即可读 (StateFlow 同步赋值, 无 postValue 异步问题)
        val chapterList = _chapterList.value
        initMangaData(book, isDiffBook = !isSameBook, prefetchedList = chapterList)
        // 开始加载内容
        if (!isSameBook) loadContent()
        else loadOrUpContent()

        if (chapterChanged) {
            // 有章节跳转不同步阅读进度
            chapterChanged = false
        } else if (!book.isNotShelf) {
            // 进度同步留 app 端薄壳 (syncProgress/syncBookProgress 依赖 BaseReadViewModel 复杂逻辑)
            // actual 平台可在 initData 成功回调中触发 syncProgress
        }
        // 自动换源留 app 端薄壳 (autoChangeSource 依赖 BaseReadViewModel)
    }

    fun clearMangaChapter() {
        prevMangaChapter = null
        curMangaChapter = null
        nextMangaChapter = null
    }

    private fun addLoading(index: Int): Boolean = synchronized(syncLock) {
        if (loadingChapters.contains(index)) return false
        loadingChapters.add(index)
        true
    }

    fun removeLoading(index: Int) {
        synchronized(syncLock) {
            loadingChapters.remove(index)
        }
    }

    /**
     * 加载当前章 + 前后章 (对应 app 端 ReadMangaViewModel.loadContent())。
     */
    fun loadContent() {
        clearMangaChapter()
        loadContent(_durChapterIndex.value)
        if (_durChapterIndex.value + 1 < chapterSize) loadContent(_durChapterIndex.value + 1)
        if (_durChapterIndex.value - 1 >= 0) loadContent(_durChapterIndex.value - 1)
    }

    /**
     * 加载或刷新当前章 (对应 app 端 ReadMangaViewModel.loadOrUpContent)。
     */
    fun loadOrUpContent() {
        if (curMangaChapter == null) loadContent(_durChapterIndex.value)
        else upContent()
        if (nextMangaChapter == null) loadContent(_durChapterIndex.value + 1)
        if (prevMangaChapter == null) loadContent(_durChapterIndex.value - 1)
    }

    /**
     * 加载指定章节正文 (对应 app 端 ReadMangaViewModel.loadContent(index))。
     *
     * 优先读本地缓存 (BookStorageProviders.getContent), 未命中则联网下载 (download)。
     */
    private fun loadContent(index: Int) {
        scope.launch {
            runCatching {
                val book = _book.value ?: return@launch
                val chapter = _chapterList.value.getOrNull(index)
                    ?: AppDbProviders.get().bookChapterDao.getChapter(book.bookUrl, index)
                    ?: run {
                        if (index < simulatedChapterSize) {
                            upToc(true)
                        }
                        return@launch
                    }
                if (addLoading(index)) {
                    BookStorageProviders.get().getContent(book, chapter)?.let {
                        contentLoadFinish(chapter, it)
                    } ?: run {
                        download(downloadScope, chapter)
                    }
                }
            }.onFailure {
                AppLog.put("加载正文出错\n${it.message}")
            }
        }
    }

    /**
     * 内容加载完成处理 (对应 app 端 ReadMangaViewModel.contentLoadFinish)。
     *
     * 根据章节与当前章的 offset (前一章/当前章/后一章) 写入 prevMangaChapter/
     * curMangaChapter/nextMangaChapter, 并触发 [upContent] 刷新 [_mangaContent]。
     */
    suspend fun contentLoadFinish(
        chapter: BookChapter,
        content: String?,
        errorMsg: String = "加载内容失败",
        canceled: Boolean = false,
    ) {
        removeLoading(chapter.index)
        if (canceled || chapter.index !in _durChapterIndex.value - 1.._durChapterIndex.value + 1) {
            return
        }
        when (val offset = chapter.index - _durChapterIndex.value) {
            0 -> {
                if (content == null) {
                    _error.value = errorMsg to true
                    _loading.value = false
                    return
                }
                if (content.isEmpty() && !chapter.isVolume) {
                    _error.value = "正文内容为空" to true
                    _loading.value = false
                    return
                }
                val mangaChapter = getManageChapter(chapter, content)
                if (mangaChapter.imageCount == 0 && !chapter.isVolume) {
                    _error.value = "正文没有图片" to true
                    _loading.value = false
                    return
                }
                curMangaChapter = mangaChapter
                _durChapter.value = chapter
                upContent()
            }

            -1, 1 -> {
                if (content == null || (!chapter.isVolume && content.isEmpty())) {
                    return
                }
                val mangaChapter = getManageChapter(chapter, content)
                if (mangaChapter.imageCount == 0 && !chapter.isVolume) {
                    return
                }

                when (offset) {
                    -1 -> prevMangaChapter = mangaChapter
                    1 -> nextMangaChapter = mangaChapter
                }

                // 当前章尚未加载完成时, 不触发 upContent, 避免 submitList 只含 prev/next 内容
                // 导致 RecyclerView 自动定位到 position=0 (prev/next 首页),
                // 误触发 onScrolled -> moveToPrevChapter/Next, 造成章节错位 (清缓存后复现)
                if (curMangaChapter != null) {
                    upContent()
                }
            }
        }
    }

    /**
     * 构造当前 mangaContent (对应 app 端 ReadMangaViewModel.buildMangaContent)。
     *
     * 合并 prev/cur/next 三章的 pages, 计算 pos (含 hideMangaTitle 偏移),
     * coerce durChapterPos 到 cur 章节范围内。
     */
    fun buildMangaContent(): MangaContent {
        val items = arrayListOf<BaseMangaPage>()
        var pos = 0
        var curFinish = false
        var nextFinish = false
        prevMangaChapter?.let {
            pos += it.pages.size
            items.addAll(it.pages)
        }
        curMangaChapter?.let {
            curFinish = true
            items.addAll(it.pages)
            _durChapterPos.value = if (it.imageCount > 0) {
                _durChapterPos.value.coerceIn(0, it.imageCount - 1)
            } else {
                0
            }
            pos += _durChapterPos.value
            if (!config.hideMangaTitle && it.imageCount > 0) {
                pos++
            }
        }
        nextMangaChapter?.let {
            nextFinish = true
            items.addAll(it.pages)
        }
        return MangaContent(pos, items, curFinish, nextFinish)
    }

    /**
     * 加载下一章 (对应 app 端 ReadMangaViewModel.moveToNextChapter)。
     *
     * @return true 已触发切章; false 已到末章
     */
    fun moveToNextChapter(toFirst: Boolean = false): Boolean {
        if (_durChapterIndex.value < simulatedChapterSize - 1) {
            if (toFirst) {
                _loading.value = true
                _durChapterPos.value = 0
            }
            _durChapterIndex.value++
            prevMangaChapter = curMangaChapter
            curMangaChapter = nextMangaChapter
            nextMangaChapter = null
            if (curMangaChapter == null) {
                _loading.value = true
                loadContent(_durChapterIndex.value)
            } else {
                upContent()
            }
            loadContent(_durChapterIndex.value + 1)
            saveRead()
            curPageChanged()
            return true
        } else {
            return false
        }
    }

    /**
     * 加载上一章 (对应 app 端 ReadMangaViewModel.moveToPrevChapter)。
     *
     * @return true 已触发切章; false 已到首页
     */
    fun moveToPrevChapter(toFirst: Boolean = false): Boolean {
        if (_durChapterIndex.value > 0) {
            if (toFirst) {
                _loading.value = true
                _durChapterPos.value = 0
            }
            _durChapterIndex.value--
            nextMangaChapter = curMangaChapter
            curMangaChapter = prevMangaChapter
            prevMangaChapter = null
            if (curMangaChapter == null) {
                loadContent(_durChapterIndex.value)
            } else {
                upContent()
            }
            loadContent(_durChapterIndex.value - 1)
            saveRead()
            return true
        }
        return false
    }

    fun curPageChanged() {
        preDownload()
    }

    /**
     * 保存阅读进度 (对应 app 端 ReadMangaViewModel.saveRead)。
     *
     * 内联 app 端 `book.saveRead()` 逻辑 (updateProgress + ReadTimeRecorder.flushAll),
     * 因 shared 无 Book.saveRead 扩展 (其依赖 runBlocking + appDb, 已由 provider 替代)。
     */
    fun saveRead() {
        scope.launch {
            runCatching {
                val book = _book.value ?: return@launch
                book.durChapterIndex = _durChapterIndex.value
                book.durChapterPos = _durChapterPos.value * (
                    if (curMangaChapter?.imageCount == _durChapterPos.value + 1) -1 else 1
                )
                AppDbProviders.get().bookChapterDao.getChapter(book.bookUrl, _durChapterIndex.value)?.let {
                    book.durChapterTitle = it.getDisplayTitle(
                        ContentProcessorProviders.get().getTitleReplaceRules(book),
                        book.getUseReplaceRule()
                    )
                    _durChapter.value = it
                }
                // book.saveRead() 内联 (app 端 BookExtensions.kt: updateProgress + ReadTimeRecorder.flushAll)
                book.lastCheckCount = 0
                book.durChapterTime = systemCurrentTimeMillis()
                AppDbProviders.get().bookDao.updateProgress(
                    book.bookUrl,
                    book.durChapterIndex,
                    book.durChapterPos,
                    book.durChapterTime,
                    book.durChapterTitle
                )
                ReadTimeRecorder.flushAll()
            }.onFailure {
                AppLog.put("保存漫画阅读进度信息出错\n$it", it)
            }
        }
    }

    /**
     * 联网下载章节正文 (对应 app 端 ReadMangaViewModel.downloadNetworkContent + download)。
     *
     * 使用 shared [Coroutine.async] (LAZY + semaphore) 编排, 与 app 端一致;
     * 成功 → saveImages + contentLoadFinish; 失败 → 累计失败次数 + contentLoadFinish(null);
     * 取消 → contentLoadFinish(null, canceled=true)。
     */
    private fun download(
        scope: CoroutineScope,
        chapter: BookChapter,
        semaphore: Semaphore? = null,
    ) {
        val book = _book.value ?: return removeLoading(chapter.index)
        val bookSource = _bookSource.value
        if (bookSource != null) {
            downloadNetworkContent(bookSource, scope, chapter, book, semaphore, success = { content ->
                downloadedChapters.add(chapter.index)
                downloadFailChapters.remove(chapter.index)
                contentLoadFinish(chapter, content)
            }, error = {
                downloadFailChapters[chapter.index] =
                    (downloadFailChapters[chapter.index] ?: 0) + 1
                contentLoadFinish(chapter, null)
            }, cancel = {
                contentLoadFinish(chapter, null, canceled = true)
            })
        } else {
            // contentLoadFinish 是 suspend, download 非 suspend, 借 scope 启动
            scope.launch { contentLoadFinish(chapter, null, "加载内容失败 没有书源") }
        }
    }

    /**
     * 联网拉取正文 (对应 app 端 ReadMangaViewModel.downloadNetworkContent)。
     *
     * 用 [Coroutine.async] (LAZY + semaphore) 包装 [WebBook.getContentAwait],
     * onSuccess/onError/onCancel 回调与 app 端一致。
     */
    private fun downloadNetworkContent(
        bookSource: BookSource,
        scope: CoroutineScope,
        chapter: BookChapter,
        book: Book,
        semaphore: Semaphore?,
        success: suspend (String) -> Unit = {},
        error: suspend () -> Unit = {},
        cancel: suspend () -> Unit = {},
    ) {
        val nextChapterUrl = _chapterList.value.getOrNull(chapter.index + 1)?.url
        Coroutine.async(
            scope,
            start = CoroutineStart.LAZY,
            semaphore = semaphore
        ) {
            WebBook.getContentAwait(bookSource, book, chapter, nextChapterUrl)
        }.onSuccess { content ->
            success.invoke(content)
        }.onError {
            error.invoke()
        }.onCancel {
            cancel.invoke()
        }.start()
    }

    /**
     * 预下载前后章节 (对应 app 端 ReadMangaViewModel.preDownload)。
     *
     * 本地书不预下载; preDownloadNum < 2 时仅 upToc; 否则并发预下载前后各 preDownloadNum 章。
     */
    fun preDownload() {
        if (_book.value?.isLocal == true) return
        scope.launch {
            if (config.preDownloadNum < 2) {
                upToc()
                return@launch
            }
            preDownloadTask?.cancel()
            preDownloadTask = downloadScope.launch {
                // 预下载
                launch {
                    val maxChapterIndex = min(_durChapterIndex.value + config.preDownloadNum, chapterSize)
                    for (i in _durChapterIndex.value.plus(2)..maxChapterIndex) {
                        if (downloadedChapters.contains(i)) continue
                        if ((downloadFailChapters[i] ?: 0) >= 3) continue
                        downloadIndex(i)
                    }
                }
                launch {
                    val minChapterIndex = _durChapterIndex.value - min(5, config.preDownloadNum)
                    for (i in _durChapterIndex.value.minus(2) downTo minChapterIndex) {
                        if (downloadedChapters.contains(i)) continue
                        if ((downloadFailChapters[i] ?: 0) >= 3) continue
                        downloadIndex(i)
                    }
                }
            }
        }
    }

    /**
     * 取消预下载任务 (对应 app 端 ReadMangaViewModel.cancelPreDownloadTask)。
     *
     * 当前章 + 下一章均已加载完成时取消预下载, 避免无谓网络请求。
     */
    fun cancelPreDownloadTask() {
        if (curMangaChapter != null && nextMangaChapter != null) {
            preDownloadTask?.cancel()
            downloadScope.coroutineContext.cancelChildren()
        }
    }

    /**
     * 预下载指定章节 (对应 app 端 ReadMangaViewModel.downloadIndex)。
     *
     * 已缓存 → 加入 downloadedChapters; 未缓存 → delay(1000) 后调 [download] (带 semaphore 限流)。
     */
    private suspend fun downloadIndex(index: Int) {
        if (index < 0) return
        if (index > chapterSize - 1) {
            upToc()
            return
        }
        val book = _book.value ?: return
        val chapter = _chapterList.value.getOrNull(index)
            ?: AppDbProviders.get().bookChapterDao.getChapter(book.bookUrl, index)
            ?: run {
                upToc(true)
                return
            }
        if (BookStorageProviders.get().hasContent(book, chapter)) {
            downloadedChapters.add(chapter.index)
        } else {
            delay(1000)
            if (addLoading(index)) {
                download(downloadScope, chapter, preDownloadSemaphore)
            }
        }
    }

    /**
     * 同步目录 (对应 app 端 ReadMangaViewModel.upToc)。
     *
     * 拉取最新章节列表, 若章节增多则更新 DB + 刷新 _chapterList + 加载下一章。
     * force=false 时受 canUpdate / 章节余量 / lastCheckTime 限制。
     */
    fun upToc(force: Boolean = false) {
        synchronized(syncLock) {
            val bookSource = _bookSource.value ?: return
            val book = _book.value ?: return
            if (!force) {
                if (!book.canUpdate) return
                if (chapterSize - _durChapterIndex.value - 1 >= 3) return
                if (systemCurrentTimeMillis() - book.lastCheckTime < 600000) return
            }
            book.lastCheckTime = systemCurrentTimeMillis()
            val oldBook = book.copy()
            scope.launch {
                runCatching {
                    WebBook.getChapterListAwait(bookSource, book).getOrThrow()
                }.onSuccess { cList ->
                    ensureActive()
                    if (cList.size > chapterSize) {
                        if (oldBook.bookUrl == book.bookUrl) {
                            AppDbProviders.get().bookDao.update(book)
                        } else {
                            AppDbProviders.get().bookDao.replace(oldBook, book)
                            BookStorageProviders.get().updateCacheFolder(oldBook, book)
                        }
                        if (!oldBook.isNotShelf) {
                            AppDbProviders.get().bookChapterDao.delByBook(oldBook.bookUrl)
                            AppDbProviders.get().bookChapterDao.insert(*cList.toTypedArray())
                        }
                        _chapterList.value = cList
                        onChapterListUpdated(book, false)
                        if (nextMangaChapter == null) loadContent(_durChapterIndex.value + 1)
                    }
                }.onFailure {
                    _error.value = "目录加载失败" to true
                }
            }
        }
    }

    /**
     * 章节列表更新后的处理 (对应 app 端 ReadMangaViewModel.onChapterListUpdated)。
     *
     * 同名同作者时刷新 curBook + simulatedChapterSize + chapterSize, 可选触发 loadContent。
     */
    fun onChapterListUpdated(newBook: Book, loadContent: Boolean = true) {
        if (newBook.isSameNameAuthor(_book.value)) {
            _book.value = newBook
            simulatedChapterSize = newBook.simulatedTotalChapterNum()
            if (simulatedChapterSize > 0 && _durChapterIndex.value > simulatedChapterSize - 1) {
                _durChapterIndex.value = simulatedChapterSize - 1
            }
            if (chapterSize == 0 || loadContent) {
                chapterSize = newBook.totalChapterNum
                loadContent()
            }
        }
    }

    /**
     * 设置阅读进度 (对应 app 端 ReadMangaViewModel.setProgress)。
     *
     * 进度变化时刷新 durChapterIndex/durChapterPos, 同章仅刷 pos, 跨章重载内容。
     */
    fun setProgress(progress: BookProgress) {
        if (progress.durChapterIndex < chapterSize &&
            (_durChapterIndex.value != progress.durChapterIndex ||
                _durChapterPos.value != progress.durChapterPos)
        ) {
            _loading.value = true
            if (progress.durChapterIndex == _durChapterIndex.value) {
                _durChapterPos.value = progress.durChapterPos
                upContent()
            } else {
                _durChapterIndex.value = progress.durChapterIndex
                _durChapterPos.value = progress.durChapterPos
                loadContent()
            }
            saveRead()
        }
    }

    /**
     * 打开指定章节 (对应 app 端 ReadMangaViewModel.openChapter)。
     */
    fun openChapter(index: Int, durChapterPos: Int = 0) {
        if (index < chapterSize) {
            _loading.value = true
            _durChapterIndex.value = index
            _durChapterPos.value = durChapterPos * (if (durChapterPos < 0) -1 else 1)
            saveRead()
            loadContent()
        }
    }

    /**
     * 刷新当前章节内容 (对应 app 端 ReadMangaViewModel.refreshContentDur)。
     *
     * 删除当前章缓存后重新加载。
     */
    fun refreshContentDur(book: Book) {
        scope.launch {
            runCatching {
                AppDbProviders.get().bookChapterDao.getChapter(book.bookUrl, _durChapterIndex.value)
                    ?.let { chapter ->
                        BookStorageProviders.get().delContent(book, chapter)
                        openChapter(_durChapterIndex.value, _durChapterPos.value)
                    }
            }
        }
    }

    /**
     * 章节列表 + 书源就绪回调 (对应 app 端 ReadMangaViewModel.onSourceChanged)。
     *
     * actual 平台薄壳在书源加载完成后调用本方法, 直接触发 initMangaData + loadContent
     * (规避 StateFlow 异步时序, 与 app 端 initManga 同类修复)。
     */
    suspend fun onSourceChanged(book: Book, toc: List<BookChapter>) {
        _chapterList.value = toc
        initMangaData(book, prefetchedList = toc)
        loadContent()
    }

    /**
     * VM 销毁时清理资源 (对应 app 端 ReadMangaViewModel.onCleared)。
     *
     * actual 平台在 ViewModel.onCleared / DisposableEffect.onDispose 中调用。
     */
    fun onCleared() {
        preDownloadTask?.cancel()
        downloadScope.coroutineContext.cancelChildren()
    }

    /**
     * 构造 MangaChapter (对应 app 端 ReadMangaViewModel.getManageChapter)。
     *
     * 用 [imageExtractor.flowImages] 提取图片 URL, 构造 [MangaPage] 列表;
     * hideMangaTitle=true 时不含 ReaderLoading 头, 否则在首页插入 ReaderLoading。
     */
    private suspend fun getManageChapter(chapter: BookChapter, content: String): MangaChapter {
        val list = imageExtractor.flowImages(chapter, content)
            .distinctUntilChanged().mapIndexed { index, src ->
                MangaPage(
                    chapterIndex = chapter.index,
                    chapterSize = chapterSize,
                    mImageUrl = src,
                    index = index,
                    mChapterName = chapter.title
                )
            }.toList()

        val imageCount = list.size

        list.forEach {
            it.imageCount = imageCount
        }

        if (config.hideMangaTitle && imageCount > 0) {
            return MangaChapter(chapter, list, imageCount)
        }

        val pages = mutableListOf<BaseMangaPage>()

        if (imageCount == 0 && chapter.isVolume) {
            pages.add(ReaderLoading(chapter.index, -1, chapter.title, true))
        } else {
            pages.add(ReaderLoading(chapter.index, -1, "阅读 ${chapter.title}"))
            pages.addAll(list)
        }

        return MangaChapter(chapter, pages, imageCount)
    }

    /**
     * 刷新 [_mangaContent] (对应 app 端 upContentLiveData.postValue(Unit) 触发的 UI 刷新)。
     *
     * app 端用 LiveData 通知 Activity 调 buildMangaContent; shared 版本直接在 VM 内
     * 调 [buildMangaContent] 并写入 StateFlow, 减少 UI/VM 往返。
     */
    private fun upContent() {
        _mangaContent.value = buildMangaContent()
        _loading.value = false
    }
}
