package io.legado.app.ui.book.manga

import io.legado.app.constant.AppLog
import io.legado.app.constant.EventBus
import io.legado.app.data.entities.Book
import io.legado.app.data.entities.BookChapter
import io.legado.app.data.entities.BookProgress
import io.legado.app.data.entities.BookSource
import io.legado.app.help.AppWebDavShared
import io.legado.app.help.IntentData
import io.legado.app.help.book.BookChapterLoader
import io.legado.app.help.book.BookStorageProviders
import io.legado.app.help.book.isLocal
import io.legado.app.help.book.isNotShelf
import io.legado.app.help.book.isSameNameAuthor
import io.legado.app.help.book.readSimulating
import io.legado.app.help.book.simulatedTotalChapterNum
import io.legado.app.help.config.AppConfigProviders
import io.legado.app.help.coroutine.Coroutine
import io.legado.app.help.coroutine.IoDispatcher
import io.legado.app.model.ReadTimeRecorder
import io.legado.app.model.chapter.ChapterLoadState
import io.legado.app.model.chapter.ChapterLoadingGuard
import io.legado.app.model.chapter.ChapterPreDownloader
import io.legado.app.model.chapter.ChapterProgressStore
import io.legado.app.model.chapter.ChapterTocUpdater
import io.legado.app.model.chapter.ChapterWindowSlot
import io.legado.app.model.chapter.chapterWindowIndices
import io.legado.app.model.chapter.chapterWindowSlotOf
import io.legado.app.model.chapter.resolveChapter as resolveChapterShared
import io.legado.app.model.webBook.WebBook
import io.legado.app.ui.book.manga.config.MangaColorFilterConfig
import io.legado.app.ui.book.manga.config.MangaFooterConfig
import io.legado.app.ui.book.manga.entities.BaseMangaPage
import io.legado.app.ui.book.manga.entities.MangaChapter
import io.legado.app.ui.book.manga.entities.MangaContent
import io.legado.app.ui.book.manga.entities.MangaPage
import io.legado.app.ui.book.manga.entities.ReaderLoading
import io.legado.app.ui.book.read.ReadBookEvents
import io.legado.app.ui.root.screenModelScope
import io.legado.app.utils.mapIndexed
import io.legado.app.utils.postEvent
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore

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
 * @param preDownloadNum 页级图片预载页数 (对应 AppConfig.mangaPreDownloadNum, 原版
 *   setRecyclerViewPreloader 的预载窗口; 章节批量预下载原版走小说的 AppConfig.preDownloadNum,
 *   不在本配置内, 见 [MangaReaderViewModelShared.preDownload]), 默认 10
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
 *   [mangaContent]/[loadState] 对应 app 端 curBook/curBookSource/chapterListData/
 *   durChapterIndex/durChapterPos/upContentLiveData/loadFailLiveData/showLoadingLiveData
 * - [loadContent]/[contentLoadFinish]/[moveToNextChapter]/[moveToPrevChapter]/[saveRead]/
 *   [preDownload]/[cancelPreDownloadTask] 方法签名与 app 端一致
 * - BookHelp.getContent/hasContent/delContent → [BookStorageProviders.get];
 *   BookHelp.flowImages → [imageExtractor]; AppConfig.hideMangaTitle → [config]
 *
 * 三章窗口装载的公共部分已收敛至 `io.legado.app.model.chapter` (四模式共用):
 * 窗口判定 [io.legado.app.model.chapter.chapterWindowSlotOf]、装载守卫与任务表
 * [io.legado.app.model.chapter.ChapterLoadingGuard]、章节解析
 * [io.legado.app.model.chapter.resolveChapter]、预下载
 * [io.legado.app.model.chapter.ChapterPreDownloader]、目录自动更新
 * [io.legado.app.model.chapter.ChapterTocUpdater]、进度落库与云同步
 * [io.legado.app.model.chapter.ChapterProgressStore]。
 *
 * 不下沉部分 (留 app 端薄壳):
 * - initData(intent: Intent) 的 Intent 解包 (Android 特有)
 * - syncBookProgress (非 plus 路径, 云端新进度自动应用) / autoChangeSource (BaseReadViewModel 基类)
 * - onSourceChanged/applyProgress 回调 (BaseReadViewModel 模板方法)
 *
 * @param scope 协程作用域, actual 平台注入 (Android=viewModelScope / 桌面=应用主作用域)
 * @param imageExtractor 漫画图片提取器 (actual 平台注入, 封装 BookHelp.flowImages)
 * @param config 漫画阅读器配置 (actual 平台从 AppConfig 读取后注入; 菜单项切换后由
 *   ScreenModel 经 [config] 刷新, 保证切章/重载时读到新值, 不是一次性快照)
 */
class MangaReaderViewModelShared(
    private val scope: CoroutineScope,
    private val imageExtractor: MangaImageExtractor,
    var config: MangaReaderConfig = MangaReaderConfig.DEFAULT,
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

    /**
     * 章节加载状态 (四模式共用类型 [ChapterLoadState])。
     *
     * 初值 Loading: 对照 app 端 activity_manga.xml 的 fl_loading 默认可见 / ReadMangaActivity
     * loadingVisible=true, 进入即转圈, 由 upContent 或加载失败置回。
     *
     * 旧实现是 `_loading: StateFlow<Boolean>` + `_error: SharedFlow<Pair<String, Boolean>>`
     * 两份状态: error 用 SharedFlow 是为了绕过 StateFlow 的去重 (同一错误重试后不再发 →
     * 转圈停不下来)。合并后不再需要该绕路: 重试先置 Loading 再置 Error, 两次变化必发。
     */
    private val _loadState = MutableStateFlow<ChapterLoadState>(ChapterLoadState.Loading)
    val loadState: StateFlow<ChapterLoadState> = _loadState.asStateFlow()
    // endregion

    // region 内部状态 (对应 app 端 ReadMangaViewModel 字段)
    /**
     * 章节总数上界 = 内存目录长度 ([_chapterList], 唯一真源)。
     *
     * 派生而非 var 快照: 旧代码只在 initMangaData 赋值一次, 之后 fetchChapterList / upToc /
     * onSourceChanged / [resolveChapter] 把目录补全都不会回写, 上界与真实目录长期脱节 ——
     * openChapter 的 `index < chapterSize`、上一章/下一章的 simulatedChapterSize 判据全都按
     * 旧值静默拒绝 (目录里看得见的章节点不开)。
     *
     * 内存目录若偏短/未就绪, 由 [resolveChapter] 的库兜底顺带整表重载来自愈, 不在这里掺
     * 第二个真源 (原版靠 `?: getChapterCount(bookUrl)` 兜底, 是因为 LiveData 的 postValue
     * 让 `.value` 在此刻恒为 null; StateFlow 赋值同步, 主路径本就该是内存)。
     */
    val chapterSize: Int get() = _chapterList.value.size

    /** 模拟追读解锁上界; 未开启模拟阅读时等于 [chapterSize] (同样改为派生, 不再快照)。 */
    private val simulatedChapterSize: Int
        get() = _book.value?.takeIf { it.readSimulating() }?.simulatedTotalChapterNum()
            ?: chapterSize
    var chapterChanged = false
        private set
    private var prevMangaChapter: MangaChapter? = null
    private var curMangaChapter: MangaChapter? = null
    private var nextMangaChapter: MangaChapter? = null

    /** 装载守卫 + 任务表 (四模式共用, 见 [ChapterLoadingGuard]); 同章新任务替换旧任务。 */
    private val loadGuard = ChapterLoadingGuard(scope)
    private val downloadScope = screenModelScope("漫画预下载", IoDispatcher)

    /** 正文预下载 (与文字模式共用实现)。 */
    private val preDownloader = ChapterPreDownloader(
        scope = scope,
        downloadScope = downloadScope,
        guard = loadGuard,
        preDownloadNum = { AppConfigProviders.get().preDownloadNum },
        chapterSize = { chapterSize },
        durChapterIndex = { _durChapterIndex.value },
        isLocalBook = { _book.value?.isLocal == true },
        upToc = { force -> upToc(force) },
        resolveChapter = { index ->
            _book.value?.let { resolveChapter(it, index) }
        },
        hasContent = { chapter ->
            _book.value?.let { BookStorageProviders.get().hasContent(it, chapter) } == true
        },
        download = { chapter, semaphore -> download(downloadScope, chapter, semaphore) },
    )

    /** 目录自动更新 (与文字模式共用实现)。 */
    private val tocUpdater = ChapterTocUpdater(
        scope = scope,
        onUpdated = { book, chapters ->
            _chapterList.value = chapters
            onChapterListUpdated(book, false)
            if (nextMangaChapter == null) loadContent(_durChapterIndex.value + 1)
        },
        onError = { _loadState.value = ChapterLoadState.Error("目录加载失败") },
    )

    /** 退出时落库/上传专用作用域: UI scope 取消不打断 (对照 ReadBookViewModelShared.progressSyncScope) */
    private val progressSyncScope = screenModelScope("漫画进度同步", IoDispatcher)

    /** 已触发过云进度拉取的 bookUrl：每本书打开只拉一次 (原版 initManga 仅入口同步一次)。 */
    private var cloudSyncedBookUrl: String? = null
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
                    _loadState.value = ChapterLoadState.Error("没有找到书")
                }
            }.onSuccess {
                success()
            }.onFailure {
                AppLog.put("初始化数据失败\n${it.message}", it)
                // 关键路径: 初始化失败必须可见 (错误页可重试), 否则 UI 永久转圈无提示
                _loadState.value = ChapterLoadState.Error("初始化数据失败\n${it.message}")
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
        val result = runCatching {
            BookChapterLoader.upBook(book)
        }.onFailure {
            AppLog.put("读取书籍失败\n${it.message}", it)
            _loadState.value = ChapterLoadState.Error("获取目录失败: ${it.message}")
        }.getOrNull()

        if (result != null) {
            _book.value = result.book
            _bookSource.value = result.source
            onBookSourceChanged()
            _chapterList.value = result.chapterList
        } else {
            _loadState.value = ChapterLoadState.Error("获取目录失败")
        }
    }

    /** 书源变更回调 (对应 app 端 BaseReadViewModel.onBookSourceChanged), 子类可扩展。 */
    private fun onBookSourceChanged() {
        // app 端重新构造 rateLimiter, shared 版本暂不下沉 ConcurrentRateLimiter (无核心业务依赖)
    }

    /**
     * 初始化漫画阅读数据 (对应 app 端 ReadMangaViewModel.initMangaData)。
     *
     * 同步 DB 章节计数/durChapterIndex/durChapterPos (章节数上界已改为派生属性),
     * 清空 manga 章节缓存 + loading/download 状态。
     *
     * @param book 当前书籍
     * @param isDiffBook 是否不同书 (true 时清缓存 + 重置 ReadTimeRecorder)
     */
    private suspend fun initMangaData(
        book: Book,
        isDiffBook: Boolean = _book.value?.bookUrl != book.bookUrl,
    ) {
        _book.value = book
        if (isDiffBook) {
            ReadTimeRecorder.setBook(ReadTimeRecorder.Source.MANGA, book.name)
        }
        // chapterSize/simulatedChapterSize 是派生属性, 随 _chapterList 自动跟进, 无需在此同步
        if (isDiffBook || _durChapterIndex.value != book.durChapterIndex) {
            _durChapterIndex.value = book.durChapterIndex
            _durChapterPos.value = book.durChapterPos * (if (book.durChapterPos < 0) -1 else 1)
            clearMangaChapter()
        }
        // `> 0` 守卫: 目录还没装载时上界为 0, 无守卫会把进度直接抹成第一章并落库
        // (口径同 onChapterListUpdated 里的 simulatedChapterSize > 0)
        if (simulatedChapterSize > 0 && _durChapterIndex.value !in 0 until simulatedChapterSize) {
            book.durChapterIndex = 0
            _durChapterIndex.value = 0
            _durChapterPos.value = 0
        }
        loadGuard.clear()
        preDownloader.reset()
    }

    /**
     * 初始化漫画 (对应 app 端 ReadMangaViewModel.initManga)。
     *
     * 调用 [initMangaData] 同步状态, 然后根据 isSameBook 决定首次加载或刷新,
     * 最后处理章节跳转 / 进度同步 / 自动换源 (后者留 app 端薄壳)。
     */
    private suspend fun initManga(book: Book, isSameBook: Boolean) {
        initMangaData(book, isDiffBook = !isSameBook)
        // 开始加载内容
        if (!isSameBook) loadContent()
        else loadOrUpContent()

        if (chapterChanged) {
            // 有章节跳转不同步阅读进度
            chapterChanged = false
        } else if (!book.isNotShelf) {
            // 进度同步 (对照 app 端 initManga: syncBookProgressPlus → syncProgress 三路比对 + 确认框;
            // 非 plus 路径的 syncBookProgress 自动应用仍留 app 端薄壳)
            if (cloudSyncedBookUrl != book.bookUrl) {
                cloudSyncedBookUrl = book.bookUrl
                pullCloudProgress(book)
            }
        }
        // 自动换源留 app 端薄壳 (autoChangeSource 依赖 BaseReadViewModel)
    }

    fun clearMangaChapter() {
        prevMangaChapter = null
        curMangaChapter = null
        nextMangaChapter = null
    }

    /**
     * 加载当前章 + 前后章 (对应 app 端 ReadMangaViewModel.loadContent())。
     */
    fun loadContent() {
        clearMangaChapter()
        for (index in chapterWindowIndices(_durChapterIndex.value)) {
            if (index < 0 || index >= chapterSize) continue
            loadContent(index)
        }
    }

    /**
     * 加载或刷新当前章 (对应 app 端 ReadMangaViewModel.loadOrUpContent)。
     */
    fun loadOrUpContent() {
        if (curMangaChapter == null) {
            // 对照原版 "重新加载" 按钮: 先显示加载中再重载 (原版 loadingRowVisible=true → retryVisible=false → loadOrUpContent);
            // 漏置加载态会导致失败页点击重载后 error 消失、转圈也不出现 → 无任何反馈
            _loadState.value = ChapterLoadState.Loading
            loadContent(_durChapterIndex.value)
        } else upContent()
        if (nextMangaChapter == null) loadContent(_durChapterIndex.value + 1)
        if (prevMangaChapter == null) loadContent(_durChapterIndex.value - 1)
    }

    /**
     * 解析第 [index] 章: **内存目录优先, 库兜底** (实现已收敛至
     * [io.legado.app.model.chapter.resolveChapter], 四模式共用)。
     */
    private suspend fun resolveChapter(book: Book, index: Int): BookChapter? =
        resolveChapterShared(book, index, _chapterList.value)

    /**
     * 加载指定章节正文 (对应 app 端 ReadMangaViewModel.loadContent(index))。
     *
     * 优先读本地缓存 (BookStorageProviders.getContent), 未命中则联网下载 (download)。
     * 经 [loadGuard] 记账: 同章新任务取消并替换旧任务, 窗口外的在途任务切章时被取消。
     */
    private fun loadContent(index: Int) {
        // launchIfIdle (不做同章替换): 本任务只负责启动下载 (download 内部的
        // Coroutine.async 跑在独立 downloadScope 上, 不随本任务取消), 装载标记必须
        // 活到 [contentLoadFinish] 回调 —— 原版同款 (removeLoading 只在 contentLoadFinish 入口)
        loadGuard.launchIfIdle(index) {
            // 未交接给下载/contentLoadFinish 的路径 (早退 / 抛错 / 取消) 必须自己释放标记,
            // 否则该章永久堵住: 后续 loadContent 与重试按钮全被装载权挡下
            var handedOff = false
            try {
                val book = _book.value ?: return@launchIfIdle
                val chapter = resolveChapter(book, index)
                    ?: run {
                        if (index < simulatedChapterSize) {
                            upToc(true)
                        }
                        return@launchIfIdle
                    }
                val cached = BookStorageProviders.get().getContent(book, chapter)
                if (cached != null) {
                    contentLoadFinish(chapter, cached)
                    handedOff = true
                } else {
                    download(downloadScope, chapter)
                    handedOff = true
                }
            } catch (e: CancellationException) {
                // 切章/切书作废不是错误: 旧代码用 runCatching 整包, 把取消当成失败打了
                // 错误页 (连点切章必现), 此处必须原样抛出
                throw e
            } catch (e: Exception) {
                AppLog.put("加载正文出错\n${e.message}", e)
                // 关键路径: 当前章加载失败必须可见 (错误页可重试), 否则 UI 永久转圈/空白无提示;
                // prev/next 章为可选预加载, 失败不影响当前显示, 仅记日志
                if (index == _durChapterIndex.value) {
                    _loadState.value = ChapterLoadState.Error("加载正文出错\n${e.message}")
                }
            } finally {
                if (!handedOff) loadGuard.release(index)
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
        loadGuard.release(chapter.index)
        if (canceled) return
        when (chapterWindowSlotOf(chapter.index, _durChapterIndex.value)) {
            null -> return

            ChapterWindowSlot.CUR -> {
                if (content == null) {
                    _loadState.value = ChapterLoadState.Error(errorMsg)
                    return
                }
                if (content.isEmpty() && !chapter.isVolume) {
                    _loadState.value = ChapterLoadState.Error("正文内容为空")
                    return
                }
                val mangaChapter = getManageChapter(chapter, content)
                if (mangaChapter.imageCount == 0 && !chapter.isVolume) {
                    _loadState.value = ChapterLoadState.Error("正文没有图片")
                    return
                }
                curMangaChapter = mangaChapter
                _durChapter.value = chapter
                upContent()
            }

            ChapterWindowSlot.PREV, ChapterWindowSlot.NEXT -> {
                if (content == null || (!chapter.isVolume && content.isEmpty())) {
                    return
                }
                val mangaChapter = getManageChapter(chapter, content)
                if (mangaChapter.imageCount == 0 && !chapter.isVolume) {
                    return
                }
                if (chapter.index < _durChapterIndex.value) {
                    prevMangaChapter = mangaChapter
                } else {
                    nextMangaChapter = mangaChapter
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
                _loadState.value = ChapterLoadState.Loading
                _durChapterPos.value = 0
            }
            _durChapterIndex.value++
            prevMangaChapter = curMangaChapter
            curMangaChapter = nextMangaChapter
            nextMangaChapter = null
            if (curMangaChapter == null) {
                _loadState.value = ChapterLoadState.Loading
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
                _loadState.value = ChapterLoadState.Loading
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
        scope.launch { saveReadAwait() }
    }

    /** [saveRead] 的 suspend 核心, 供退出时"先落库再上传"复用 (原版 onPause 顺序)。 */
    private suspend fun saveReadAwait() {
        runCatching {
            val book = _book.value ?: return
            val index = _durChapterIndex.value
            // 末图停留时 durChapterPos 取负编码"停在章末" (口径同文字模式的末页编码)
            val pos = _durChapterPos.value * (
                if (curMangaChapter?.imageCount == _durChapterPos.value + 1) -1 else 1
                )
            // 每翻一页/切一章都会走这里: 章名从内存目录取, 内存没有才兜底查库
            val chapter = resolveChapter(book, index)
            chapter?.let { _durChapter.value = it }
            ChapterProgressStore.save(book, index, pos, chapter)
        }.onFailure {
            AppLog.put("保存漫画阅读进度信息出错\n$it", it)
        }
    }

    /** 进入活跃期 (对照 app 端 ReadMangaActivity.onResume): 开始阅读计时。 */
    fun onEnter() {
        ReadTimeRecorder.start(ReadTimeRecorder.Source.MANGA, _book.value?.name ?: "")
    }

    /**
     * 离开活跃期 (对照 app 端 ReadMangaActivity.onPause)：被压栈 / 退到后台 / 出栈时,
     * 结束阅读计时 → 在架的书落库并上传进度 → 取消预下载。
     *
     * 走进程级 [progressSyncScope]，UI 侧 scope 取消不打断本次落库/上传
     * (口径同 [io.legado.app.ui.book.read.ReadBookViewModelShared.onLeaveReader])。
     */
    fun onLeave() {
        ReadTimeRecorder.end(ReadTimeRecorder.Source.MANGA)
        val book = _book.value
        if (book != null && !book.isNotShelf) {
            progressSyncScope.launch {
                saveReadAwait()
                // 通知书架刷新: 落库后 books 表 durChapterTime 已更新,
                // UP_BOOKSHELF 让书架重查 (双保险; Room 失效推送实证正常, 见 Book.kt equals 定案)
                // (对齐阅读器 uploadProgress 行为, 回归 2026-08)。
                postEvent(EventBus.UP_BOOKSHELF, book.bookUrl)
                // 原版 onPause: syncBookProgressPlus → syncProgress() 三路比对 (云端较新则不上传,
                // 避免旧进度覆盖云端); 未开启 → 无条件上传
                if (config.syncBookProgressPlus) {
                    syncProgressOnLeave(book)
                } else {
                    uploadProgressAwait(book.bookUrl)
                }
            }
        }
        cancelPreDownloadTask()
    }

    /**
     * 退出阅读时的云进度比对 (原版 ReadMangaActivity.onPause 的 syncProgress() 无 newProgressAction):
     * 云端无进度或本地较新 → 上传; 云端较新/相等 → 不上传。
     */
    private suspend fun syncProgressOnLeave(book: Book) {
        AppWebDavShared.syncProgress(
            book = book,
            manual = false,
        )
    }

    /**
     * 拉云进度三路比对 (实现已收敛至 [ChapterProgressStore.pullCloud], 四模式共用):
     * 云端较新 → 发 [ReadBookEvents.newProgressConfirm] 确认事件, UI 弹窗后由
     * [confirmSyncProgress] / [dismissSyncProgress] 收尾。
     */
    private fun pullCloudProgress(book: Book) {
        ChapterProgressStore.pullCloud(
            scope = progressSyncScope,
            book = book,
            enabled = config.syncBookProgressPlus,
            onNewProgress = { ReadBookEvents.postConfirmNewProgress(it) },
        )
    }

    /**
     * 用户确认同步云端进度 (原版 ReadMangaActivity.sureNewProgress okButton → viewModel.setProgress)：
     * 清事件 replay 缓存后按云端进度跳转 (setProgress 自带越界/未变守卫)。
     */
    fun confirmSyncProgress(progress: BookProgress) {
        ReadBookEvents.clearNewProgressConfirm()
        setProgress(progress)
    }

    /** 用户取消同步云端进度：仅清事件 replay 缓存，避免 UI 重建时重复弹窗。 */
    fun dismissSyncProgress() {
        ReadBookEvents.clearNewProgressConfirm()
    }

    /** 上传进度 (实现已收敛至 [ChapterProgressStore.uploadAwait], 四模式共用)。 */
    private suspend fun uploadProgressAwait(bookUrl: String) {
        ChapterProgressStore.uploadAwait(bookUrl)
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
        val book = _book.value ?: return loadGuard.releaseNow(chapter.index)
        val bookSource = _bookSource.value
        if (bookSource != null) {
            downloadNetworkContent(bookSource, scope, chapter, book, semaphore, success = { content ->
                preDownloader.markDownloaded(chapter.index)
                contentLoadFinish(chapter, content)
            }, error = {
                preDownloader.markFailed(chapter.index)
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
     * 预下载前后章节 (实现已收敛至 [ChapterPreDownloader], 与文字模式共用一份)。
     *
     * 章节批量预下载读小说的 AppConfig.preDownloadNum (原版同款, 2026-08-29 对齐):
     * 漫画自己的 mangaPreDownloadNum 只管页级图片预载页数 (setRecyclerViewPreloader /
     * renderState.preloadCount), 不控制本方法。
     */
    fun preDownload() {
        preDownloader.preDownload()
    }

    /**
     * 取消预下载任务 (对应 app 端 ReadMangaViewModel.cancelPreDownloadTask)。
     *
     * 不带原版的 "当前章+下一章均就绪" 守卫: 该守卫会让 "停在最后一章" 或
     * "下一章加载失败" 时离开阅读页也不取消, 预下载继续跑到跑完。
     */
    fun cancelPreDownloadTask() {
        preDownloader.cancel()
    }

    /**
     * 同步目录 (实现已收敛至 [ChapterTocUpdater], 四模式共用一份)。
     *
     * 拉取最新章节列表, 若章节增多则更新 DB + 刷新 _chapterList + 加载下一章。
     * force=false 时受 canUpdate / 章节余量 / lastCheckTime 限制。
     */
    fun upToc(force: Boolean = false) {
        val book = _book.value ?: return
        tocUpdater.upToc(
            book = book,
            bookSource = _bookSource.value,
            chapterSize = chapterSize,
            durChapterIndex = _durChapterIndex.value,
            force = force,
        )
    }

    /**
     * 章节列表更新后的处理 (对应 app 端 ReadMangaViewModel.onChapterListUpdated)。
     *
     * 同名同作者时刷新 curBook, 收敛越界的 durChapterIndex, 可选触发 loadContent。
     */
    fun onChapterListUpdated(newBook: Book, loadContent: Boolean = true) {
        if (newBook.isSameNameAuthor(_book.value)) {
            _book.value = newBook
            // chapterSize/simulatedChapterSize 是派生属性, upToc 刚写完 _chapterList 就已生效,
            // 不再需要 (也不能) 在此写快照 —— 旧代码把赋值门在 `chapterSize == 0 || loadContent`
            // 里, upToc 追加新章时 loadContent=false, 上界就停在旧值 (目录里看得见、点了没反应)
            if (simulatedChapterSize > 0 && _durChapterIndex.value > simulatedChapterSize - 1) {
                _durChapterIndex.value = simulatedChapterSize - 1
            }
            // curMangaChapter 为空 = 当前什么都没显示 (对应旧代码 chapterSize == 0 的强制加载分支)
            if (loadContent || curMangaChapter == null) loadContent()
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
            _loadState.value = ChapterLoadState.Loading
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
            _loadState.value = ChapterLoadState.Loading
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
                resolveChapter(book, _durChapterIndex.value)
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
    suspend fun onSourceChanged(book: Book, toc: List<BookChapter>, source: BookSource? = null) {
        if (source != null) _bookSource.value = source
        _chapterList.value = toc
        initMangaData(book)
        loadContent()
    }

    /**
     * 居中页变化时同步章内页码 (对应 app 端 `viewModel.durChapterPos = item.index`)。
     * 不落库, 落库由 [saveRead] 负责。
     */
    fun setDurChapterPos(pos: Int) {
        _durChapterPos.value = pos
    }

    /**
     * VM 销毁时清理资源 (对应 app 端 ReadMangaViewModel.onCleared + Activity.onDestroy)。
     *
     * actual 平台在 ViewModel.onCleared / DisposableEffect.onDispose 中调用。
     */
    fun onCleared() {
        // 对照原版 ReadMangaActivity.onDestroy: 立即结束阅读计时 (不等 end 的延迟结算)
        ReadTimeRecorder.endImmediately(ReadTimeRecorder.Source.MANGA)
        cancelPreDownloadTask()
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
        val content = buildMangaContent()
        _mangaContent.value = content
        // 对照原版 ReadMangaActivity.upContent: 仅当前章加载完成 (curFinish) 才收起整页
        // loading; 无条件收起会在当前章未就绪时提前暴露空白列表
        // (如 setProgress 同章刷新时 cur 尚未加载完成)
        if (content.curFinish) _loadState.value = ChapterLoadState.Idle
    }
}
