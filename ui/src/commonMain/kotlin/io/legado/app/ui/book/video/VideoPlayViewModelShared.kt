package io.legado.app.ui.book.video

import io.legado.app.constant.AppLog
import io.legado.app.constant.EventBus
import io.legado.app.data.entities.Book
import io.legado.app.data.entities.BookChapter
import io.legado.app.data.entities.BookSource
import io.legado.app.data.entities.Bookmark
import io.legado.app.data.entities.VideoResolution
import io.legado.app.data.entities.VideoSource
import io.legado.app.help.AppWebDavShared
import io.legado.app.help.book.BookChapterLoader
import io.legado.app.help.book.isLocal
import io.legado.app.help.book.isNotShelf
import io.legado.app.help.coroutine.IoDispatcher
import io.legado.app.model.ReadTimeRecorder
import io.legado.app.model.ResourceUrlPreloader
import io.legado.app.model.analyzeRule.AnalyzeUrlCore
import io.legado.app.model.analyzeRule.AnalyzeUrlFactories
import io.legado.app.model.chapter.ChapterLoadState
import io.legado.app.model.chapter.ChapterLoadingGuard
import io.legado.app.model.chapter.ChapterProgressStore
import io.legado.app.model.chapter.ChapterTocUpdater
import io.legado.app.model.chapter.resolveChapter
import io.legado.app.model.chapter.updateResourceUrl
import io.legado.app.model.webBook.WebBook
import io.legado.app.ui.root.VideoPlayTarget
import io.legado.app.ui.root.screenModelScope
import io.legado.app.utils.hasPlayableScheme
import io.legado.app.utils.postEvent
import io.legado.app.utils.systemCurrentTimeMillis
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 视频播放 VM 共享核心 (KMP 版, commonMain)。
 *
 * # 背景
 *
 * 对照 desktop 端 `io.legado.desktop.ui.book.video.VideoPlayerViewModel` (自实现 VM)
 * 与 app 端 `io.legado.app.ui.book.video.VideoViewModel` (继承 BaseReadViewModel):
 * - 两端业务流程一致 (initData → loadChapter → parseVideoContent → saveRead,
 *   moveToNextChapter / moveToPrevChapter / refreshChapter),
 *   仅在协程 scope 来源 / 状态暴露 (StateFlow vs LiveData) / 字符串 i18n 上有平台差异。
 * - desktop VM 当前自实现整套逻辑, 与 app 端大量重复; 本类把业务流程下沉到 commonMain,
 *   供 desktop / iOS / 鸿蒙 复用。
 * - app 端 VideoViewModel 因继承 BaseReadViewModel (重 Android 依赖), 暂不强制迁移,
 *   后续可由 app 端自行薄壳化。
 *
 * # 与 desktop VM 的差异
 *
 * - **协程 scope**: 由构造函数注入 (desktop = Compose `rememberCoroutineScope`, 与原 desktop VM 一致)
 * - **状态暴露**: 用 [MutableStateFlow] (与 desktop VM 一致), Compose `collectAsState()` 订阅
 * - **字符串 i18n**: shared commonMain 不依赖 jvmGetString (desktop 专属) / R.string (app 专属),
 *   错误消息与日志走硬编码中文 + [AppLog] (参考 `BookController` /
 *   `ReadBookViewModelShared` 模式); 后续如需 i18n 可迁移到 `appString` 通道
 * - **进度持久化**: 用 [io.legado.app.data.dao.BookDao.updateProgress] PATCH 进度字段
 *   (参考 `ReadBookViewModelShared.saveProgress`), 避免整行 update 冲掉后台
 *   updateToc/refreshBookInfo 写入的最新元数据 (原 desktop VM 用 `bookDao.update(book)`
 *   整行更新, 行为等价但 PATCH 更安全)
 * - **视频源解析**: 直接复用本文件同包的 [parseVideoSource] + [extractVideoUrlAndReferer]
 *   (已下沉工具函数, 与 app 端 VideoViewModel.parseVideoContent 解析逻辑一致)
 *
 * # 状态暴露
 *
 * - [videoUrl]: 当前播放视频的 [AnalyzeUrlCore] (含 URL + header), null = 加载中或失败
 * - [videoSource]: 多分辨率源 (null = 单一直链)
 * - [resolutions]: 分辨率列表 (空 = 单一直链或未加载)
 * - [currentResolutionIndex]: 当前分辨率索引
 * - [curChapterIndex]: 当前章节序号 (0-based)
 * - [chapterSize]: 总章节数
 * - [curChapterTitle]: 当前章节标题 (标题栏显示)
 * - [loading]: 加载中标记 (覆盖层显示/隐藏)
 * - [error]: 加载失败消息 (null = 无错误)
 */
class VideoPlayViewModelShared(
    private val scope: CoroutineScope,
) {
    /** 进度同步专用作用域: 不随 UI scope 取消 (对照 ReadBookViewModelShared.progressSyncScope) */
    private val progressSyncScope = screenModelScope("视频进度同步", IoDispatcher)

    /** 前后各一章的直链预解析器 (窗口写死 ±1, 不读 preDownloadNum); 跟随 [scope] 寿命 */
    private val preloader = ResourceUrlPreloader(scope)

    /** 当前书籍 (initData 写入, 退出时清空) */
    var curBook: Book? = null
        private set

    /** 当前书源 (按 book.origin 查 DB, 拉取章节内容用) */
    var curBookSource: BookSource? = null
        private set

    /**
     * 外部直投目标 (null = 由书进入的常规视频书)。
     *
     * 直投态下 [curBook] / [curBookSource] / 章节列表恒为空: 外部给的已经是可播地址,
     * 不存在“书”也不存在“书源规则”, 因此不查库、不拉目录、不落进度、不写书签。
     */
    var directTarget: VideoPlayTarget.Direct? = null
        private set

    /** 是否外部直投播放 (页面据此隐藏上架/书签/刷新等依赖书的能力) */
    val isDirect: Boolean get() = directTarget != null

    /** 章节列表 (内存缓存, 首次加载章节内容时拉取, 后续章节切换直接查) */
    private var chapterList: List<BookChapter>? = null

    /** 章节列表只读视图 (供选集网格渲染, 随 [chapterSize] 变更一并刷新) */
    val chapters: List<BookChapter> get() = chapterList.orEmpty()

    /**
     * 书源编辑保存后刷新书源引用 (对照原版 VideoViewModel.onUpSource:
     * curBookSource = book.getBookSource(); 这里直接采用回传的已保存对象, 不查库)。
     */
    fun upBookSource(source: BookSource) {
        curBookSource = source
    }

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

    // ---- 视频源状态 (UI 订阅) ----

    private val _videoUrl = MutableStateFlow<AnalyzeUrlCore?>(null)
    /**
     * 当前播放视频的 [AnalyzeUrlCore] (含 URL + header / cookie / charset / JS 解析)。
     *
     * null = 加载中或加载失败; UI 层订阅此状态后, 调用底层播放器 (mpv / ExoPlayer /
     * AVPlayer 等) 加载 [AnalyzeUrlCore.url] 并带 [AnalyzeUrlCore.headerMap] 发请求。
     */
    val videoUrl: StateFlow<AnalyzeUrlCore?> = _videoUrl.asStateFlow()

    private val _videoSource = MutableStateFlow<VideoSource?>(null)
    /** 多分辨率源 (null = 单一直链, 不显示分辨率切换钮) */
    val videoSource: StateFlow<VideoSource?> = _videoSource.asStateFlow()

    private val _resolutions = MutableStateFlow<List<VideoResolution>>(emptyList())
    /** 分辨率列表 (空 = 单一直链或未加载) */
    val resolutions: StateFlow<List<VideoResolution>> = _resolutions.asStateFlow()

    /**
     * 当前分辨率索引 (切换分辨率时更新)。
     *
     * 必须是 StateFlow: 上一版是普通 var，而回显它的 combine 只 listen
     * resolutions/videoSource，切档后无任何上游发射 → 钮文字与对话框高亮停在旧档。
     */
    private val _currentResolutionIndex = MutableStateFlow(0)
    val currentResolutionIndex: StateFlow<Int> = _currentResolutionIndex.asStateFlow()

    /**
     * 下一次装载的起播位置 ms (对照原版 `VideoViewModel.position`)。
     *
     * 与 [_videoUrl] 一同下发 (先写位置再写 url)：UI 层在订阅到 url 时取这里读，
     * 不再在组合期快照 `book.durChapterPos` (那样会快照到上一次重组的旧值，
     * 与切章位置重置赛跑 → 新章从上一章位置起播)。约定：装载消费后归 0，
     * 所以切章自然从头播，只有显式传了 seekPositionMs 的入口才恢复位置。
     */
    private val _startPositionMs = MutableStateFlow(0L)
    val startPositionMs: StateFlow<Long> = _startPositionMs.asStateFlow()

    /**
     * [loadChapter] 入口传入的待用位置，由 [parseVideoContent] 在写 [_videoUrl] 前
     * 搬到 [_startPositionMs] 并消耗 (保证“位置与 url 同拍到达”，UI 无需比较时间戳)。
     */
    private var pendingSeekMs = 0L

    // ---- 加载状态 (UI 订阅) ----

    /**
     * 章节加载状态 ([ChapterLoadState], 视频与漫画模式共用)。
     *
     * 初值 Loading (对齐漫画侧 `MangaReaderViewModelShared`): 进入页面到首次
     * [loadChapter] 之间要跑 `getBookInfoAwait` + 拉目录 (可数秒)，上一版初值 Idle
     * 使那整段黑屏且无任何转圈提示。
     */
    private val _loadState = MutableStateFlow<ChapterLoadState>(ChapterLoadState.Loading)
    val loadState: StateFlow<ChapterLoadState> = _loadState.asStateFlow()

    /** 播放错误已重试标记 (配合 [retryOnPlayError] 仅首次重试) */
    private var hasRetriedOnError = false

    /**
     * 章节装载守卫 (四模式共用, 见 [ChapterLoadingGuard])。
     *
     * 视频引擎一次只播一章, 每次装载先 [ChapterLoadingGuard.clear] 作废上一轮 ——
     * 语义等价于原先的 loadChapterToken 计数, 但作废是真取消 (旧一轮的网络请求不再空跑),
     * 且与其余三模式共用同一套记账。
     */
    private val loadGuard = ChapterLoadingGuard(scope)

    /** 目录自动更新 (四模式共用实现; 原版音视频侧没有, 追更书播到末章就停) */
    private val tocUpdater = ChapterTocUpdater(
        scope = scope,
        onUpdated = { _, chapters ->
            chapterList = chapters
            _chapterSize.value = chapters.size
        },
    )

    /**
     * 初始化数据 (对照 desktop `VideoPlayerViewModel.initData` /
     * app `VideoViewModel.initData`)。
     *
     * @param book 待播放的视频书
     * @param initialChapterIndex 初始章节序号 (通常取 book.durChapterIndex)
     * @param persistProgress 是否在加载初始章节时持久化进度 (对照 [loadChapter] persistProgress)。
     *   shared 路由入口若已通过 [applyChapterOverride] 写回 chapterIndex/chapterPos,
     *   传 false 避免覆盖; app 端经 [initWithExternalChapters] 走 false 同理。
     */
    suspend fun initData(
        book: Book,
        initialChapterIndex: Int = book.durChapterIndex,
        persistProgress: Boolean = true,
    ) {
        directTarget = null
        val result = runCatching {
            BookChapterLoader.upBook(book)
        }.onFailure {
            AppLog.put("加载书籍失败\n${it.message}", it)
            _loadState.value = ChapterLoadState.Error("加载书籍失败: ${it.message}")
        }.getOrNull()

        if (result == null) return

        curBook = result.book
        curBookSource = result.source
        chapterList = result.chapterList
        _chapterSize.value = result.chapterList.size

        if (result.chapterList.isEmpty()) {
            _loadState.value = ChapterLoadState.Error("章节列表为空")
            return
        }
        // 启动阅读计时 (对照原版 VideoViewModel.initData)
        ReadTimeRecorder.setBook(ReadTimeRecorder.Source.VIDEO, result.book.name)
        ReadTimeRecorder.start(ReadTimeRecorder.Source.VIDEO, result.book.name)
        // 加载初始章节: 从已存进度恢复起播位置 (对照原版
        // `VideoViewModel.position = curBook.durChapterPos.coerceAtLeast(0)`;
        // 片尾编码 -1 被 coerce 抹成 0 = 从头播)
        val targetIndex =
            initialChapterIndex.coerceIn(0, (result.chapterList.size - 1).coerceAtLeast(0))
        loadChapter(
            targetIndex,
            persistProgress,
            seekPositionMs = result.book.durChapterPos.coerceAtLeast(0).toLong(),
        )
        // 书架书自动同步阅读进度
        if (!result.book.isNotShelf) {
            progressSyncScope.launch {
                AppWebDavShared.syncProgress(
                    book = result.book,
                    manual = false,
                )
            }
        }
    }

    /**
     * 用外部已加载的章节列表初始化状态 (供 app 端 [io.legado.app.base.BaseReadViewModel.upBook] 后注入)。
     *
     * 与 [initData] 的区别: 不查书源 / 不拉章节列表 (调用方已通过 BaseReadViewModel.upBook
     * 拉到 `chapterListData`), 直接注入 [book] / [source] / [chapters] 后加载初始章节。
     * 用于 app 端 VideoViewModel 组合本类时, 避免重复拉取章节列表。
     *
     * @param book 待播放的视频书
     * @param source 书源 (app 端 BaseReadViewModel.curBookSource)
     * @param chapters 章节列表 (app 端 BaseReadViewModel.chapterListData.value)
     * @param initialChapterIndex 初始章节序号
     */
    fun initWithExternalChapters(
        book: Book,
        source: BookSource,
        chapters: List<BookChapter>,
        initialChapterIndex: Int = book.durChapterIndex,
    ) {
        curBook = book
        curBookSource = source
        chapterList = chapters
        _chapterSize.value = chapters.size
        val targetIndex = initialChapterIndex.coerceIn(0, chapters.lastIndex.coerceAtLeast(0))
        loadChapter(
            targetIndex,
            persistProgress = false,
            seekPositionMs = book.durChapterPos.coerceAtLeast(0).toLong(),
        )
    }

    /**
     * 为直投重载预备装载参数 (只服务直投态, 由书进入的刷新/重试一律不经过这里)。
     *
     * 与 [refreshChapter] / [retryOnPlayError] 的分工: 后两者是「重拉章节内容 + 重跑解析规则」,
     * 直投没有章节可重拉 (故在 isDirect 下早退是原设计); 本方法是直投专属的另一条腿 ——
     * 地址不变、请求头不变, 只把「下一次起播从哪开始」与「页面不再停在错误态」两件事定下来,
     * 真正的重装由调用方交给控制器上的 `VideoPlayerController.reload`。
     *
     * 为什么这里清错误态而不是置 Loading: 直投没有任何后台装载任务会把它收回去,
     * 停在 Loading 就是错误遮罩换成了永久转圈。清成 [ChapterLoadState.Idle] 后,
     * 起播等待由播放引擎自己报 (平台侧的 `PlaybackSnapshot.isBuffering`),
     * 再次失败时平台侧会重新 [reportPlayError] 写回错误。
     *
     * @param seekPositionMs 重装后的起播位置 (调用方必须在 reload **前**从播放器现取)
     */
    fun prepareDirectReload(seekPositionMs: Long) {
        if (!isDirect) return
        // 与首次 playDirect 同口径: 不允许把旧进度当作负数喂给引擎 (片尾编码 -1 不在此链上)
        _startPositionMs.value = seekPositionMs.coerceAtLeast(0L)
        if (_loadState.value is ChapterLoadState.Error) {
            _loadState.value = ChapterLoadState.Idle
        }
    }

    /**
     * 外部直投起播: 把地址直接喂给播放器, 跳过 书 → 书源 → 章节 → 规则解析 整条装载链。
     *
     * 与 [initData] 互斥: 进页后只会走其中一条。直投不做任何解析与持久化:
     * 不查书源 ([BookChapterLoader] 会因“书源不存在”直接失败)、不拉章节、
     * 不 [saveRead]、不进阅读计时 ([ReadTimeRecorder])、不写书签。
     *
     * @param target 已归一化的可播地址 + 请求头 + 显示标题
     */
    fun playDirect(target: VideoPlayTarget.Direct) {
        directTarget = target
        curBook = null
        curBookSource = null
        chapterList = null
        _chapterSize.value = 0
        _curChapterIndex.value = 0
        _curChapterTitle.value = target.displayTitle
        _videoSource.value = null
        _resolutions.value = emptyList()
        _currentResolutionIndex.value = 0
        _startPositionMs.value = 0L
        // 地址已是可直接交给播放器的 URI, 不得再过 AnalyzeUrl 的规则/JS 解析:
        // 那会把 content:// 地址里的 ',' ':' 等当规则语法切掉。与内存 m3u8 分支同一写法
        // (先建对象再直填 url + header)。
        _videoUrl.value = AnalyzeUrlFactories.create("").apply {
            url = target.url
            headerMap.putAll(target.headers)
        }
        _loadState.value = ChapterLoadState.Idle
    }

    /**
     * 加载指定章节 (对照 desktop `VideoPlayerViewModel.loadChapter` /
     * app `VideoViewModel.initChapter`)。
     *
     * 内部流程:
     * 1. 调 [WebBook.getContentAwait] 拉取章节内容 (视频源 JSON / URL 字符串)
     * 2. 用 [parseVideoContent] 解析为 [AnalyzeUrlCore] (视频直链) 或 [VideoSource] (多分辨率)
     * 3. 更新 [_videoUrl] / [_videoSource] / [_resolutions] / [_curChapterIndex] / [_curChapterTitle]
     * 4. 持久化阅读进度
     *
     * @param index 章节序号 (0-based)
     * @param persistProgress 是否在加载完成后持久化章节进度 (调用 [saveRead])。
     *   默认 true (desktop 行为); app 端 BaseReadViewModel 已自行调用 `Book.saveRead()`
     *   保存 durChapterPos, 传 false 避免shared VM 用 durChapterPos=0 覆盖 app 端写入的位置。
     * @param seekPositionMs 装载完成后起播位置 ms (0 = 从头播)。只有显式入口 (首次进页恢复
     *   进度、书签/目录定位、刷新/重试续播) 会传非 0; 切章不传 = 新章从头播。
     */
    fun loadChapter(
        index: Int,
        persistProgress: Boolean = true,
        seekPositionMs: Long = 0L,
    ) {
        // 直投态没有章节可言: 入口 UI (选集网格 / 上下一条 / 刷新) 已按 isDirect 隐藏,
        // 快捷键等旁路调到这里就直接返回, 不得去读不存在的书源与章节表
        if (isDirect) return
        val book = curBook ?: return
        val source = curBookSource ?: if (book.isLocal) null else run {
            _loadState.value = ChapterLoadState.Error("书源不存在")
            return
        }
        val chapters = chapterList ?: run {
            _loadState.value = ChapterLoadState.Error("章节列表未加载")
            return
        }
        if (chapters.isEmpty()) {
            _loadState.value = ChapterLoadState.Error("章节列表为空")
            return
        }
        // 标记加载中, 清空旧视频源 (清空只是「没有新源」的数据状态, 停旧媒体由
        // 渲染层观察 null 后调 controller.stop() 完成 —— 不清会让上一章一直响到新章解析完)
        _loadState.value = ChapterLoadState.Loading
        _videoUrl.value = null
        _videoSource.value = null
        _resolutions.value = emptyList()
        _currentResolutionIndex.value = 0
        pendingSeekMs = seekPositionMs
        _curChapterIndex.value = index
        _curChapterTitle.value = chapters.getOrNull(index)?.title.orEmpty()
        // 开解当前章前先作废上一轮预解析: 两者共用书源, 并行跑会拖慢用户等的这一章
        preloader.cancel()
        // loadGuard.launch 同章替换 + clear 作废其它章: 视频一次只播一章, 连点切章时
        // 旧一轮被真取消 (原先用 loadChapterToken 只是让旧一轮不写状态, 网络请求仍空跑完)
        loadGuard.clear()
        loadGuard.launch(index) {
            val currentJob = currentCoroutineContext()[Job]
            try {
                // 拉取管线挪 IO: getContentAwait 内部无 withContext (AnalyzeUrl 构造 +
                // header @js: 求值 + 规则解析均同步), 跑主线程会卡住进入转场窗口。
                // StateFlow 写入线程安全, withContext 返回后 finally 在主线程落 loadState。
                withContext(IoDispatcher) {
                    // 内存目录优先, 库兜底 (实现已收敛至 resolveChapter, 四模式共用)
                    val chapter = resolveChapter(book, index, chapters) ?: run {
                        if (loadGuard.isCurrentJob(index, currentJob)) {
                            _loadState.value = ChapterLoadState.Error("章节不存在: $index")
                        }
                        return@withContext
                    }
                    _curChapterTitle.value = chapter.title

                    // 拉章节内容 (needSave=false: 视频内容是 URL 字符串非文件, 不写本地缓存)
                    val nextChapterUrl = chapters.getOrNull(index + 1)?.url
                    val content = runCatching {
                        if (book.isLocal) {
                            chapter.url
                        } else if (source != null) {
                            // 复用已解析的直链 (原版 VideoViewModel.initChapter 同款口径):
                            // 上次播放与 [preloader] 都把结果写在 resourceUrl 上, 命中就不再跑一遍书源规则
                            chapter.resourceUrl ?: WebBook.getContentAwait(
                                source,
                                book,
                                chapter,
                                nextChapterUrl,
                                needSave = false
                            )
                        } else {
                            null
                        }
                    }.onFailure {
                        if (it is CancellationException) throw it
                        AppLog.put("加载章节内容出错: ${it.message}", it)
                        if (loadGuard.isCurrentJob(index, currentJob)) {
                            _loadState.value = ChapterLoadState.Error("加载失败: ${it.message}")
                        }
                    }.getOrNull()
                    if (content == null) {
                        return@withContext
                    }
                    if (content.isEmpty()) {
                        if (loadGuard.isCurrentJob(index, currentJob)) {
                            _loadState.value = ChapterLoadState.Error("未获取到资源链接")
                        }
                        return@withContext
                    }
                    // 直链回写 + 在架书 PATCH 落库 (实现已收敛至 updateResourceUrl, 音视频共用)
                    if (!book.isLocal) {
                        chapter.updateResourceUrl(content, inBookshelf = !book.isNotShelf)
                    }
                    // 解析视频源 (复用同包工具函数)
                    parseVideoContent(content, source)
                    // 当前章就绪后再预解析前后各一章 (对标小说 contentLoadFinish → preDownload 的时机)
                    if (source != null) {
                        preloader.preload(
                            book = book,
                            chapters = chapters,
                            centerIndex = index,
                            inBookshelf = !book.isNotShelf,
                        ) { target ->
                            WebBook.getContentAwait(
                                source,
                                book,
                                target,
                                chapters.getOrNull(target.index + 1)?.url,
                                needSave = false
                            )
                        }
                    }
                    // 持久化阅读进度
                    if (persistProgress) {
                        saveRead(index)
                    }
                    // 播到目录尾部时检查新章 (四模式共用 [ChapterTocUpdater]; 原版音视频没有此步,
                    // 追更书播到末章就停, 属缺陷)
                    upToc()
                }
            } catch (e: CancellationException) {
                // 取消不是错误: 对照原版 Coroutine.dispatchCallback 的 !scope.isActive 早退,
                // 被取消的 execute{} 不会走到 onError 弹提示
                throw e
            } catch (e: Exception) {
                AppLog.put("加载章节出错: ${e.message}", e)
                if (loadGuard.isCurrentJob(index, currentJob)) {
                    _loadState.value = ChapterLoadState.Error("加载出错: ${e.message}")
                }
            } finally {
                // 成功/失败都收掉加载态; 失败分支的 Error 已在上面写入, 不覆盖。
                // isCurrentJob 守卫: 连点切章时被替换掉的旧一轮不得把新一轮的 Loading 打成 Idle
                // (等价于原先的 loadChapterToken 判定)
                if (loadGuard.isCurrentJob(index, currentJob) &&
                    _loadState.value is ChapterLoadState.Loading
                ) {
                    _loadState.value = ChapterLoadState.Idle
                }
            }
        }
    }

    /**
     * 播到目录尾部时检查目录有无新章 (四模式共用 [ChapterTocUpdater])。
     *
     * @param force 跳过 canUpdate / 剩余章数 / 节流三重守卫
     */
    fun upToc(force: Boolean = false) {
        val book = curBook ?: return
        tocUpdater.upToc(
            book = book,
            bookSource = curBookSource,
            chapterSize = _chapterSize.value,
            durChapterIndex = _curChapterIndex.value,
            force = force,
        )
    }

    /**
     * 解析视频源内容 (对照 desktop `VideoPlayerViewModel.parseVideoContent` /
     * app `VideoViewModel.parseVideoContent`)。
     *
     * 三段式解析 (与两端完全一致, 保证视频源识别行为对齐):
     *
     * 1. **JSON 对象**: 尝试解析为 [VideoSource] (含 resolutions 列表), 命中则取默认分辨率 URL
     * 2. **`name::url\n` 多分辨率**: 逐行解析 `name::url` 为 [VideoResolution] 列表, 取首个
     * 3. **直接 URL / 内存 m3u8**:
     *    - `http` 开头: 当作直链, 用 [AnalyzeUrlCore] 包装 (带书源 header)
     *    - 其他: 当作内存 m3u8 内容, 用 fakeUrl `https://example.com/memory.m3u8` 作 Referer
     *    - `#BASE:` 前缀: 提取真正的 Referer (前缀行), 余下为 m3u8 内容
     *
     * 解析算法委托同包 [parseVideoSource] + [extractVideoUrlAndReferer] (已下沉工具函数),
     * 此处仅做平台无关的 StateFlow 推送与 [AnalyzeUrlCore] 构造。
     *
     * @param content 章节正文 (JSON / `name::url\n` 多行 / URL / m3u8 内容)
     * @param source 书源 (AnalyzeUrlCore 构造用)
     */
    private fun parseVideoContent(content: String, source: BookSource?) {
        val videoSource = parseVideoSource(content)

        if (videoSource != null && videoSource.resolutions.isNotEmpty()) {
            // 多分辨率源: 取默认分辨率 URL。
            // 写序必须先改状态源再发 url: 上一版把 currentResolutionIndex 排在两个 flow
            // 之后且它根本不是状态源, 收集器抢跑时只能读到刚被 loadChapter 重置的 0
            // → defaultIndex>0 的源「钮显示第 1 档、实际播第 N 档」。
            _videoSource.value = videoSource
            _resolutions.value = videoSource.resolutions
            _currentResolutionIndex.value = videoSource.defaultIndex
            val resolution = videoSource.getResolution()
            if (resolution == null) {
                // defaultIndex 越界等坏配置: 不得静默回 Idle (那是“纯黑且连重试入口都没有”)
                _loadState.value = ChapterLoadState.Error(
                    "视频源分辨率配置错误 (defaultIndex=${videoSource.defaultIndex})"
                )
                return
            }
            _startPositionMs.value = pendingSeekMs
            pendingSeekMs = 0L
            _videoUrl.value = AnalyzeUrlFactories.create(
                rawUrl = resolution.url,
                source = source,
                headerMapF = videoSource.headers,
            )
        } else {
            // 直接 URL / 内存 m3u8
            _videoSource.value = null
            _resolutions.value = emptyList()
            _currentResolutionIndex.value = 0
            _startPositionMs.value = pendingSeekMs
            pendingSeekMs = 0L
            _videoUrl.value = if (content.hasPlayableScheme()) {
                // 可播直链: 用 AnalyzeUrlCore 包装 (带书源 header / cookie / charset)
                AnalyzeUrlFactories.create(rawUrl = content, source = source)
            } else {
                // 内存 m3u8: 保留 fakeUrl + Referer 语义, 供 UI 层播放库接入时复用
                val (videoUrl, fakeUrl) = extractVideoUrlAndReferer(content)
                AnalyzeUrlFactories.create("").apply {
                    url = videoUrl
                    headerMap["Referer"] = fakeUrl
                }
            }
        }
    }

    /**
     * 切换分辨率 (对照 desktop `VideoPlayerViewModel.switchResolution` /
     * app `VideoPlayActivity.switchResolution`)。
     *
     * 注: app 端切换分辨率会重建 ExoPlayer 并 seekTo 原位置; desktop 端因播放库不同,
     * 本 VM 只更新 [videoUrl] State, UI 层订阅后自行处理播放器重建与 seek。
     *
     * @param index 分辨率索引 (0-based)
     * @param seekPositionMs 切换后跳转到的位置 (毫秒, 0 = 从头播)。与 [_videoUrl] 同拍写入，
     *   渲染层订阅新 url 时从 [startPositionMs] 取到它就是它。
     */
    fun switchResolution(index: Int, seekPositionMs: Long = 0L) {
        val source = _videoSource.value ?: return
        val resolution = source.getResolution(index) ?: return
        _currentResolutionIndex.value = index
        _startPositionMs.value = seekPositionMs
        _videoUrl.value = AnalyzeUrlFactories.create(
            rawUrl = resolution.url,
            // 本地书无书源 (loadChapter 对 null source 已明确容忍), 此处不得提前 return:
            // 上一版 `curBookSource ?: return` 使本地多分辨率书点清晰度完全无反应
            source = curBookSource,
            headerMapF = source.headers,
        )
    }

    /**
     * 刷新当前章节 (对照 desktop `VideoPlayerViewModel.refreshChapter` /
     * app `VideoViewModel.refreshChapter`)。
     *
     * 清空当前视频源并重新加载 (用于播放出错时重试)。
     * 若章节列表尚未加载或为空，重新触发 [initData] 完整流程。
     *
     * 先清 [BookChapter.resourceUrl] 再重拉 (原版 VideoViewModel.refreshChapter 同款):
     * 不清的话 [loadChapter] 会命中缓存拿到同一条失效直链, 重试等于空转。
     *
     * @param persistProgress 是否持久化章节进度, 透传给 [loadChapter]; app 端传 false
     *   避免覆盖已保存的播放位置。
     * @param seekPositionMs 刷新后从哪续播 (调用方从播放器现取当前位置; 0 = 从头)。
     */
    fun refreshChapter(persistProgress: Boolean = true, seekPositionMs: Long = 0L) {
        // 直投地址就是一条固定 URI, 没有“重新拉取章节内容”可做 (刷新钮在直投态不渲染)
        if (isDirect) return
        val book = curBook
        if (book != null && chapterList.isNullOrEmpty()) {
            scope.launch {
                initData(book, _curChapterIndex.value, persistProgress)
            }
            return
        }
        chapterList?.getOrNull(_curChapterIndex.value)?.resourceUrl = null
        loadChapter(_curChapterIndex.value, persistProgress, seekPositionMs)
    }

    /**
     * 播放器错误统一重试策略 (仅首次重试)。
     *
     * 供 app 端 onPlayerError / desktop 端 mpv 播放失败事件复用, 消除两端重复实现。
     * 未重试过则置标记并刷新章节 (重新拉取章节内容), 已重试过返回 false 由调用方自行处理。
     *
     * 标记重置时机对齐原版 (VideoPlayActivity listener): 播放器真正进入 STATE_READY
     * (播放成功) 才由调用方经 [resetRetryOnPlayError] 重置; 链接不可用时播放器永不
     * READY, 标记不再被重置, 同一章节只自动重试一次, 避免无限重试循环 (双重进度条闪烁)。
     *
     * @param seekPositionMs 重试时续播位置 (调用方从播放器现取; 0 = 从头)。
     * @return true 已触发重试, false 已重试过需调用方自行处理
     */
    fun retryOnPlayError(seekPositionMs: Long = 0L): Boolean {
        // 直投态的“重试”等于重新解析章节内容, 而直投根本没有章节可重新解析:
        // 这里必须返回 false 让平台层把错误真报给用户, 严禁返回 true 把错误静默吞成一次空重试。
        // （不=直投无救: 报错后遮罩上的「重新加载」走 prepareDirectReload + 控制器 reload,
        //  按原地址与原请求头重新起播; 不做成自动重试是为了避开“无人接管的失败循环”）
        if (isDirect) return false
        if (hasRetriedOnError) return false
        hasRetriedOnError = true
        refreshChapter(persistProgress = false, seekPositionMs = seekPositionMs)
        return true
    }

    /**
     * 重置播放错误重试标记 (对齐原版 VideoPlayActivity: STATE_READY 时置回 false)。
     *
     * 播放器真正开始播放 (READY) 后调用, 允许后续再次出错时再自动重试一次;
     * 由平台层播放器监听 STATE_READY 回调驱动, 内容解析成功不重置 (见 [retryOnPlayError])。
     */
    fun resetRetryOnPlayError() {
        hasRetriedOnError = false
    }

    /**
     * 播放侧错误上报为加载失败 ([ChapterLoadState.Error]) —— [loadState] 的唯一写入口。
     *
     * 上一版由 ScreenModel 直接写 `UiState.loadState`，而 combine 每次发射都用本 VM 的
     * loadState 覆盖回去 → 错误占位会被下一次无关发射无声抹掉 (黑屏无重试钮)。
     */
    fun reportPlayError(message: String) {
        // 直投态的失败与「章节加载失败」不是一回事: 没有书源规则可重跑, 也没有新直链可拿,
        // 遮罩上那颗钮只会按原地址重新起播 —— 不说清楚, 用户会以为它能“刷新出”一条新地址
        _loadState.value = if (isDirect) {
            ChapterLoadState.Error(
                "$message\n外部地址无章节可重新解析：重新加载 = 按原地址与原请求头重新起播"
            )
        } else {
            ChapterLoadState.Error(message)
        }
    }

    /**
     * 切换到下一章 (对照 desktop `VideoPlayerViewModel.moveToNextChapter` /
     * app `VideoPlayActivity.playNextChapter`)。
     *
     * 切章重置视频播放位置为 0: [loadChapter] 默认 persistProgress=true → saveRead(index, 0)
     * (对照 app `VideoViewModel.changeChapter`: `position = 0L; saveRead(0L)`),
     * 避免下次进入新章节误 seek 到旧位置。
     *
     * @return true 切换成功, false 已到末章
     */
    fun moveToNextChapter(): Boolean {
        val cur = _curChapterIndex.value
        val size = _chapterSize.value
        if (isDirect) return false
        if (cur >= size - 1) return false
        loadChapter(cur + 1)
        return true
    }

    /**
     * 切换到上一章 (对照 desktop `VideoPlayerViewModel.moveToPrevChapter` /
     * app `VideoPlayActivity.playPrevChapter`)。
     *
     * 切章重置视频播放位置为 0: 同 [moveToNextChapter], 由 [loadChapter] 持久化。
     *
     * @return true 切换成功, false 已到首章
     */
    fun moveToPrevChapter(): Boolean {
        if (isDirect) return false
        val cur = _curChapterIndex.value
        if (cur <= 0) return false
        loadChapter(cur - 1)
        return true
    }

    /**
     * 持久化阅读进度 (对照 desktop `VideoPlayerViewModel.saveRead` /
     * app `VideoViewModel.saveRead` + `Book.saveRead` 扩展)。
     *
     * PATCH books 表进度字段 (durChapterIndex / durChapterPos / durChapterTime / durChapterTitle),
     * 避免整行 update 冲掉后台 updateToc/refreshBookInfo 写入的最新元数据
     * (参考 `ReadBookViewModelShared.saveProgress`)。
     *
     * 同步回写 [curBook] 内存字段, 供 [uploadProgress] 构造 [BookProgress] 使用。
     *
     * @param index 当前章节序号
     * @param positionMs 视频位置 (毫秒, 转 Int 写入 durChapterPos; 默认 0 = 章节切换重置)
     */
    private suspend fun saveRead(index: Int, positionMs: Long = 0L) {
        val book = curBook ?: return
        runCatching {
            // 落库 + 回写内存 book 实体 (实现已收敛至 [ChapterProgressStore], 四模式共用);
            // 章名过标题替换规则 —— 原先直接用 chapter.title 不过规则, 书架/详情显示的章名
            // 会与阅读页不一致
            ChapterProgressStore.save(
                book = book,
                durChapterIndex = index,
                durChapterPos = positionMs.toInt(),
                chapter = resolveChapter(book, index, chapterList),
            )
        }.onFailure {
            AppLog.put("保存阅读进度出错: ${it.message}", it)
        }
    }

    /**
     * 应用书签/目录回传的章节定位 (对照 app `VideoViewModel.initData` override 分支:
     * curBook.durChapterIndex = overrideIndex; curBook.durChapterPos = overridePos;
     * saveRead(overridePos.toLong()))。
     *
     * 写回 [curBook] 内存字段 + PATCH books 表,
     * 供 [VideoPlayScreenModel] ShowBook / TOC 回填调用。
     *
     * @param book 当前书籍 (与 [curBook] 同一引用)
     * @param chapterIndex 章节序号
     * @param chapterPos 视频位置 (Int, 对齐 Book.durChapterPos 精度; 0 = 从头播)
     */
    suspend fun applyChapterOverride(book: Book, chapterIndex: Int, chapterPos: Int) {
        curBook = book
        book.durChapterIndex = chapterIndex
        book.durChapterPos = chapterPos
        saveRead(chapterIndex, chapterPos.toLong())
    }

    /**
     * 上传阅读进度到 WebDav (对照 app `BaseReadViewModel.uploadProgress` +
     * `ReadBookViewModelShared.uploadProgressAwait`)。
     *
     * 走 [progressSyncScope] (不随 UI scope 取消), 先读 DB 最新行再上传,
     * 避免内存 book 实体与 DB 不一致。仅在书架内 (非 notShelf) 且开启同步时上传。
     */
    fun uploadProgress() {
        val book = curBook ?: return
        if (book.isNotShelf) return
        progressSyncScope.launch {
            AppWebDavShared.syncProgress(
                book = book,
                manual = false,
            )
        }
    }

    /** 进入活跃期 (对照原版 VideoPlayActivity.onResume): 开始阅读计时。 */
    fun onResume() {
        // 直投不进阅读计时 (外部分享一个链接不构成任何一本书的阅读行为)
        if (isDirect) return
        ReadTimeRecorder.start(ReadTimeRecorder.Source.VIDEO, curBook?.name ?: "")
    }

    /**
     * 离开活跃期 / 退出时保存真实进度 (对照 app `VideoPlayActivity.onPause`:
     * saveRead + uploadProgress)。
     *
     * 两步编排:
     * 1. [saveRead] books 表存 durChapterPos (片尾 -1 编码"停在章末", 对照 app)
     * 2. [uploadProgress] WebDav 上传 (书架内且开启同步时)
     *
     * @param positionMs 当前播放位置 (毫秒)
     * @param durationMs 媒体总时长 (毫秒, 0 = 未知时长按原位置存)
     */
    fun onExit(positionMs: Long, durationMs: Long) {
        // 直投不留痕: 既不存进度也不上传, 还不通知书架刷新
        if (isDirect) return
        // 结束视频计时 (对照 app onPause: ReadTimeRecorder.end)
        ReadTimeRecorder.end(ReadTimeRecorder.Source.VIDEO)
        ReadTimeRecorder.flushAll()
        // 片尾编码: 接近片尾存 -1 (停在章末, 下次从头播), 否则存当前毫秒
        // (对照 app onPause: saveRead(if (position > duration - 1s) -1 else position))
        val atEnd = durationMs > 0 && positionMs > durationMs - 1000L
        val bookPos = if (atEnd) -1L else positionMs
        // books 表 durChapterPos (Int, 片尾 -1 编码章末); 走 progressSyncScope 不随 UI scope 取消
        val index = _curChapterIndex.value
        progressSyncScope.launch { saveRead(index, bookPos) }
        // 通知书架刷新: 视频退出落库后 books 表 durChapterTime 已更新,
        // UP_BOOKSHELF 让书架重查 (双保险; Room 失效推送实证正常, 见 Book.kt equals 定案)
        // (对齐阅读器 uploadProgress 行为, 回归 2026-08)。
        curBook?.let { postEvent(EventBus.UP_BOOKSHELF, it.bookUrl) }
        // WebDav 上传
        uploadProgress()
    }

    /**
     * 构造视频书签 (不写库, 由调用方决定是否落库)。
     *
     * 供 app 端 addBookmark / desktop 端书签入口复用, 消除两端重复构造逻辑。
     * 字段映射参考 app 端 addBookmark, 视频场景 chapterPos=0 / bookText=""。
     *
     * @param positionMs 当前播放位置 (毫秒, 写入 content 显示用)
     * @param durationMs 媒体总时长 (毫秒, 写入 content 显示用)
     * @param bookName 书名 (调用方取 curBook.name)
     * @param chapterIndex 章节序号
     * @param chapterName 章节标题
     * @return 构造好的 [Bookmark], curBook 为 null 时返回 null
     */
    fun createBookmark(
        positionMs: Long,
        durationMs: Long,
        bookName: String,
        chapterIndex: Int,
        chapterName: String,
    ): Bookmark? {
        val book = curBook ?: return null
        return Bookmark(
            time = systemCurrentTimeMillis(),
            bookName = bookName,
            bookAuthor = book.author,
            chapterIndex = chapterIndex,
            chapterPos = 0,
            chapterName = chapterName,
            bookText = "",
            content = "${positionMs / 1000}s / ${durationMs / 1000}s",
        )
    }
}
