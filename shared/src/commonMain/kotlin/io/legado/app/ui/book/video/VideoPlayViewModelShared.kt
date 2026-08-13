package io.legado.app.ui.book.video

import io.legado.app.constant.AppLog
import io.legado.app.constant.EventBus
import io.legado.app.data.AppDbProviders
import io.legado.app.data.entities.Book
import io.legado.app.data.entities.BookChapter
import io.legado.app.data.entities.BookSource
import io.legado.app.data.entities.Bookmark
import io.legado.app.data.entities.VideoResolution
import io.legado.app.data.entities.VideoSource
import io.legado.app.help.AppWebDavShared
import io.legado.app.help.IntentData
import io.legado.app.help.book.isNotShelf
import io.legado.app.help.config.AppConfigProviders
import io.legado.app.help.coroutine.IoDispatcher
import io.legado.app.model.analyzeRule.AnalyzeUrlCore
import io.legado.app.model.analyzeRule.AnalyzeUrlFactories
import io.legado.app.model.webBook.WebBook
import io.legado.app.ui.compose.platform.PreferenceStoreProvider
import io.legado.app.ui.root.screenModelScope
import io.legado.app.utils.postEvent
import io.legado.app.utils.systemCurrentTimeMillis
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
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
 *   moveToNextChapter / moveToPrevChapter / refreshChapter / saveVideoProgress),
 *   仅在协程 scope 来源 / 状态暴露 (StateFlow vs LiveData) / 字符串 i18n 上有平台差异。
 * - desktop VM 当前自实现整套逻辑, 与 app 端大量重复; 本类把业务流程下沉到 commonMain,
 *   供 desktop / iOS / 鸿蒙 复用, desktop VM 改为薄壳注入 scope + PreferenceStoreProvider。
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
    private val prefStore: PreferenceStoreProvider,
) {
    /** 进度同步专用作用域: 不随 UI scope 取消 (对照 ReadBookViewModelShared.progressSyncScope) */
    private val progressSyncScope = screenModelScope("视频进度同步", IoDispatcher)

    /** 当前书籍 (initData 写入, 退出时清空) */
    var curBook: Book? = null
        private set

    /** 当前书源 (按 book.origin 查 DB, 拉取章节内容用) */
    var curBookSource: BookSource? = null
        private set

    /** 章节列表 (内存缓存, 首次加载章节内容时拉取, 后续章节切换直接查) */
    private var chapterList: List<BookChapter>? = null

    /** 章节列表只读视图 (供选集网格渲染, 随 [chapterSize] 变更一并刷新) */
    val chapters: List<BookChapter> get() = chapterList.orEmpty()

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

    /** 当前分辨率索引 (切换分辨率时更新) */
    var currentResolutionIndex: Int = 0
        public set

    // ---- 加载状态 (UI 订阅) ----

    private val _loading = MutableStateFlow(false)
    /** 加载中标记 (覆盖层显示/隐藏) */
    val loading: StateFlow<Boolean> = _loading.asStateFlow()

    private val _error = MutableStateFlow<String?>(null)
    /** 加载失败消息 (null = 无错误) */
    val error: StateFlow<String?> = _error.asStateFlow()

    /** 播放错误已重试标记 (配合 [retryOnPlayError] 仅首次重试) */
    private var hasRetriedOnError = false

    /** [loadChapter] 轮次令牌: 连点切章时只有最新一轮可以复位 [_loading] */
    private var loadChapterToken = 0

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
        curBook = book
        // 查书源 (对照原版 upBook 的 curBookSource 赋值, 整个 upBook 跑在 execute{} 里,
        // 查库异常不外泄; 同文件后续网络调用亦全部 runCatching, 此处对齐)
        curBookSource = runCatching {
            withContext(IoDispatcher) {
                AppDbProviders.get().bookSourceDao.getBookSource(book.origin)
            }
        }.onFailure {
            AppLog.put("读取书源出错\n${it.message}", it)
        }.getOrNull()
        // 拉章节列表
        val source = curBookSource
        if (source == null) {
            _error.value = "书源不存在"
            return
        }
        // 书籍信息未加载 (tocUrl 空) 时先自动加载, 对照原版
        // BaseReadViewModel.upBook → loadBookInfo: 搜索/发现直达播放或 DB 信息不全时,
        // 先 getBookInfoAwait 补齐书籍信息 (tocUrl/章节数/简介等) 再拉目录。
        if (book.tocUrl.isEmpty() || book.totalChapterNum == 0) {
            runCatching {
                WebBook.getBookInfoAwait(source, book)
            }.onFailure {
                AppLog.put("加载书籍信息出错\n${it.message}", it)
                _error.value = "加载书籍信息失败: ${it.message}"
                return
            }
            // 书架书信息更新落库 (对照原版 loadBookInfo 尾部: inBookshelf → book.save())
            if (!book.isNotShelf) {
                runCatching {
                    AppDbProviders.get().bookDao.update(book)
                }.onFailure {
                    AppLog.put("保存书籍信息出错\n${it.message}", it)
                }
            }
        }
        chapterList = runCatching {
            // 目录来源对照原版 BaseReadViewModel.upBook: 内存交接 (IntentData, 带 bookUrl 校验)
            // 优先, 未交接才回源; 不然未加书架的书目录不落库, 每次进页面都要重拉
            IntentData.chapterList?.takeIf { it.firstOrNull()?.bookUrl == book.bookUrl }
                ?: WebBook.getChapterListAwait(source, book).getOrThrow()
        }.onFailure {
            AppLog.put("加载章节列表出错\n${it.message}", it)
            _error.value = "加载章节列表失败: ${it.message}"
        }.getOrNull()
        _chapterSize.value = chapterList?.size ?: 0
        // 加载初始章节
        val targetIndex = initialChapterIndex.coerceIn(0, (_chapterSize.value - 1).coerceAtLeast(0))
        loadChapter(targetIndex, persistProgress)
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
        loadChapter(targetIndex, persistProgress = false)
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
     * @param index 章节序号 (0-based, 越界自动 clamp)
     * @param persistProgress 是否在加载完成后持久化章节进度 (调用 [saveRead])。
     *   默认 true (desktop 行为); app 端 BaseReadViewModel 已自行调用 `Book.saveRead()`
     *   保存 durChapterPos, 传 false 避免shared VM 用 durChapterPos=0 覆盖 app 端写入的位置。
     */
    fun loadChapter(index: Int, persistProgress: Boolean = true) {
        val book = curBook ?: return
        val source = curBookSource ?: run {
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
        // 标记加载中, 清空旧视频源 (避免显示上一章视频)
        _loading.value = true
        _error.value = null
        _videoUrl.value = null
        _videoSource.value = null
        _resolutions.value = emptyList()
        currentResolutionIndex = 0
        _curChapterIndex.value = clampedIndex
        _curChapterTitle.value = chapter.title
        // 本轮加载令牌: 连点切章时旧一轮的 finally 不得把新一轮的 loading 打成 false
        val token = ++loadChapterToken
        scope.launch {
            try {
                // 拉取管线挪 IO: getContentAwait 内部无 withContext (AnalyzeUrl 构造 +
                // header @js: 求值 + 规则解析均同步), 跑主线程会卡住进入转场窗口。
                // StateFlow 写入线程安全, withContext 返回后 finally 在主线程落 loading。
                withContext(IoDispatcher) {
                    // 拉章节内容 (needSave=false: 视频内容是 URL 字符串非文件, 不写本地缓存)
                    val nextChapterUrl = chapters.getOrNull(clampedIndex + 1)?.url
                    val content = runCatching {
                        WebBook.getContentAwait(
                            source,
                            book,
                            chapter,
                            nextChapterUrl,
                            needSave = false
                        )
                    }.onFailure {
                        if (it is CancellationException) throw it
                        AppLog.put("加载章节内容出错\n${it.message}", it)
                        _error.value = "加载失败: ${it.message}"
                    }.getOrNull()
                    if (content == null) {
                        return@withContext
                    }
                    if (content.isEmpty()) {
                        _error.value = "未获取到资源链接"
                        return@withContext
                    }
                    // 解析视频源 (复用同包工具函数)
                    parseVideoContent(content, source)
                    // 持久化阅读进度
                    if (persistProgress) {
                        saveRead(clampedIndex)
                    }
                    // 新章节加载成功, 重置错误重试标记
                    hasRetriedOnError = false
                }
            } catch (e: CancellationException) {
                // 取消不是错误: 对照原版 Coroutine.dispatchCallback 的 !scope.isActive 早退,
                // 被取消的 execute{} 不会走到 onError 弹提示
                throw e
            } catch (e: Exception) {
                AppLog.put("加载章节出错\n${e.message}", e)
                _error.value = "加载出错: ${e.message}"
            } finally {
                // 失败分支也要落 loading, 否则 UI 停在加载态看不到错误
                if (token == loadChapterToken) _loading.value = false
            }
        }
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
    private suspend fun parseVideoContent(content: String, source: BookSource) {
        val videoSource = parseVideoSource(content)

        if (videoSource != null && videoSource.resolutions.isNotEmpty()) {
            // 多分辨率源: 取默认分辨率 URL
            _videoSource.value = videoSource
            _resolutions.value = videoSource.resolutions
            currentResolutionIndex = videoSource.defaultIndex
            val resolution = videoSource.getResolution()
            if (resolution != null) {
                _videoUrl.value = AnalyzeUrlFactories.create(
                    rawUrl = resolution.url,
                    source = source,
                    headerMapF = videoSource.headers,
                )
            }
        } else {
            // 直接 URL / 内存 m3u8
            _videoSource.value = null
            _resolutions.value = emptyList()
            currentResolutionIndex = 0
            _videoUrl.value = if (content.startsWith("http")) {
                // http 直链: 用 AnalyzeUrlCore 包装 (带书源 header / cookie / charset)
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
     * @param seekPositionMs 切换后跳转到的位置 (毫秒, 0 = 从头播; 仅供 UI 层参考, 本 VM 不做 seek)
     */
    fun switchResolution(index: Int, seekPositionMs: Long = 0L) {
        val source = _videoSource.value ?: return
        val resolution = source.getResolution(index) ?: return
        val bookSource = curBookSource ?: return
        currentResolutionIndex = index
        _videoUrl.value = AnalyzeUrlFactories.create(
            rawUrl = resolution.url,
            source = bookSource,
            headerMapF = source.headers,
        )
    }

    /**
     * 刷新当前章节 (对照 desktop `VideoPlayerViewModel.refreshChapter` /
     * app `VideoViewModel.refreshChapter`)。
     *
     * 清空当前视频源并重新加载 (用于播放出错时重试)。
     *
     * @param persistProgress 是否持久化章节进度, 透传给 [loadChapter]; app 端传 false
     *   避免覆盖已保存的播放位置。
     */
    fun refreshChapter(persistProgress: Boolean = true) {
        loadChapter(_curChapterIndex.value, persistProgress)
    }

    /**
     * 播放器错误统一重试策略 (仅首次重试)。
     *
     * 供 app 端 onPlayerError / desktop 端 mpv 播放失败事件复用, 消除两端重复实现。
     * 未重试过则置标记并刷新章节 (重新拉取章节内容), 已重试过返回 false 由调用方自行处理。
     * 新章节加载成功后由 [loadChapter] 重置标记, 允许下次出错再次重试。
     *
     * @return true 已触发重试, false 已重试过需调用方自行处理
     */
    fun retryOnPlayError(): Boolean {
        if (hasRetriedOnError) return false
        hasRetriedOnError = true
        refreshChapter(persistProgress = false)
        return true
    }

    /**
     * 切换到下一章 (对照 desktop `VideoPlayerViewModel.moveToNextChapter` /
     * app `VideoPlayActivity.playNextChapter`)。
     *
     * 切换前重置视频播放位置为 0 (对照 app `VideoViewModel.changeChapter`:
     * `position = 0L; saveRead(0L)`), 避免下次进入新章节误 seek 到旧位置。
     *
     * @return true 切换成功, false 已到末章
     */
    fun moveToNextChapter(): Boolean {
        val cur = _curChapterIndex.value
        val size = _chapterSize.value
        if (cur >= size - 1) return false
        curBook?.bookUrl?.let { saveVideoProgress(it, 0L) }
        loadChapter(cur + 1)
        return true
    }

    /**
     * 切换到上一章 (对照 desktop `VideoPlayerViewModel.moveToPrevChapter` /
     * app `VideoPlayActivity.playPrevChapter`)。
     *
     * 切换前重置视频播放位置为 0 (与 [moveToNextChapter] 一致)。
     *
     * @return true 切换成功, false 已到首章
     */
    fun moveToPrevChapter(): Boolean {
        val cur = _curChapterIndex.value
        if (cur <= 0) return false
        curBook?.bookUrl?.let { saveVideoProgress(it, 0L) }
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
        val chapters = chapterList ?: return
        val chapter = chapters.getOrNull(index) ?: return
        // 写回内存字段, 供 BookProgress 上传使用 (对照 app VideoViewModel.saveRead: curBook.durChapterPos = position)
        book.durChapterIndex = index
        book.durChapterPos = positionMs.toInt()
        book.durChapterTitle = chapter.title
        runCatching {
            AppDbProviders.get().bookDao.updateProgress(
                bookUrl = book.bookUrl,
                durChapterIndex = index,
                durChapterPos = positionMs.toInt(),
                durChapterTime = systemCurrentTimeMillis(),
                durChapterTitle = chapter.title,
            )
        }.onFailure {
            AppLog.put("保存阅读进度出错\n${it.message}", it)
        }
    }

    // ---- 视频播放位置持久化 (对照 desktop VM saveVideoProgress / getSavedVideoProgress) ----

    /**
     * 保存视频播放位置到 [PreferenceStoreProvider] (对照 desktop VM `saveVideoProgress`,
     * key = `video_progress_{bookUrl}`)。
     *
     * app 端 `VideoViewModel.saveRead` 写 `book.durChapterPos` (Int), desktop 端用
     * Preference 单独存储 (毫秒); shared 沿用 desktop 端 Preference 方案, 与 desktop
     * Preference 底座一致, 不依赖 app 端 Int 字段精度。
     *
     * @param bookUrl 书籍唯一标识 (curBook.bookUrl)
     * @param positionMs 当前播放位置 (毫秒), 0 = 从头播
     */
    fun saveVideoProgress(bookUrl: String, positionMs: Long) {
        runCatching {
            prefStore.putString("video_progress_$bookUrl", positionMs.coerceAtLeast(0L).toString())
        }.onFailure {
            AppLog.put("保存播放位置出错\n${it.message}", it)
        }
    }

    /**
     * 退出时保存视频进度 (片尾归零)。
     *
     * 接近片尾 (positionMs > durationMs - 1s) 存 0 下次从头播, 否则存当前位置。
     * 供 app 端 onPause / desktop 端 onDispose 复用, 消除两端重复实现。
     *
     * @param positionMs 当前播放位置 (毫秒)
     * @param durationMs 媒体总时长 (毫秒, 0 = 未知时长按原位置存)
     */
    fun saveVideoProgressOnExit(positionMs: Long, durationMs: Long) {
        val book = curBook ?: return
        val toSave = if (durationMs > 0 && positionMs > durationMs - 1000L) 0L else positionMs
        saveVideoProgress(book.bookUrl, toSave)
    }

    /**
     * 读取上次保存的视频播放位置 (对照 desktop VM `getSavedVideoProgress`)。
     *
     * @param bookUrl 书籍唯一标识
     * @return 上次播放位置 (毫秒), 0 = 无记录或从头播
     */
    fun getSavedVideoProgress(bookUrl: String): Long =
        prefStore.getString("video_progress_$bookUrl", "0")?.toLongOrNull() ?: 0L

    /**
     * 应用书签/目录回传的章节定位 (对照 app `VideoViewModel.initData` override 分支:
     * curBook.durChapterIndex = overrideIndex; curBook.durChapterPos = overridePos;
     * saveRead(overridePos.toLong()))。
     *
     * 写回 [curBook] 内存字段 + PATCH books 表 + 更新 Preference 视频位置,
     * 供 [VideoPlayScreenModel] ShowBook / TOC 回填调用。
     *
     * @param book 当前书籍 (与 [curBook] 同一引用)
     * @param chapterIndex 章节序号
     * @param chapterPos 视频位置 (Int, 对齐 Book.durChapterPos 精度; 0 = 从头播)
     */
    suspend fun applyChapterOverride(book: Book, chapterIndex: Int, chapterPos: Int) {
        curBook = book
        saveRead(chapterIndex, chapterPos.toLong())
        saveVideoProgress(book.bookUrl, chapterPos.toLong())
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
        if (!runCatching { AppConfigProviders.get().syncBookProgress }.getOrDefault(false)) return
        progressSyncScope.launch {
            runCatching {
                val fresh = AppDbProviders.get().bookDao.getBook(book.bookUrl) ?: return@launch
                AppWebDavShared.uploadBookProgress(fresh)
            }.onFailure {
                AppLog.put("上传视频进度失败\n${it.message}", it)
            }
        }
    }

    /**
     * 退出时保存真实进度 (对照 app `VideoPlayActivity.onPause`:
     * saveVideoProgressOnExit + saveRead + uploadProgress)。
     *
     * 三步编排:
     * 1. [saveVideoProgressOnExit] Preference 存视频位置 (片尾归零)
     * 2. [saveRead] books 表存 durChapterPos (片尾 -1 编码"停在章末", 对照 app)
     * 3. [uploadProgress] WebDav 上传 (书架内且开启同步时)
     *
     * @param positionMs 当前播放位置 (毫秒)
     * @param durationMs 媒体总时长 (毫秒, 0 = 未知时长按原位置存)
     */
    fun onExit(positionMs: Long, durationMs: Long) {
        // 片尾归零: 接近片尾存 0 下次从头播, 否则存当前位置 (对照 app onPause 片尾判断)
        val atEnd = durationMs > 0 && positionMs > durationMs - 1000L
        val bookPos = if (atEnd) -1L else positionMs
        // Preference 视频位置 (精确毫秒)
        saveVideoProgressOnExit(positionMs, durationMs)
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
