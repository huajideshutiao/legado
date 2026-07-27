package io.legado.desktop.ui.book.video

import io.legado.app.constant.AppLog
import io.legado.app.data.AppDbProviders
import io.legado.app.data.entities.Book
import io.legado.app.data.entities.BookChapter
import io.legado.app.data.entities.BookSource
import io.legado.app.data.entities.VideoResolution
import io.legado.app.data.entities.VideoSource
import io.legado.app.model.analyzeRule.AnalyzeUrlCore
import io.legado.app.model.webBook.WebBook
import io.legado.app.ui.compose.platform.PreferenceStoreProvider
import io.legado.app.utils.GSON
import io.legado.app.utils.fromJsonObject
import io.legado.app.utils.isJsonObject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 桌面端视频播放 ViewModel (对照 app 端 [io.legado.app.ui.book.video.VideoViewModel])。
 *
 * # 与 app 端差异
 *
 * - **不继承 BaseReadViewModel**: app 端 BaseReadViewModel 重 Android 依赖 (appCtx /
 *   LiveData / appDb 单例 / SourceHelp 等), 桌面端不可复用, 改为普通 class 持有
 *   [CoroutineScope] (由 Compose `rememberCoroutineScope` 注入)
 * - **视频源 URL 解析**: 完整复刻 app 端 [VideoViewModel.parseVideoContent] 的三段式
 *   解析 (JSON VideoSource / `name::url\n` 多分辨率 / 直接 URL 或内存 m3u8), 保证
 *   desktop 与 app 端视频源识别行为一致
 * - **网络请求**: app 端用 `AnalyzeUrl` (app 模块), desktop 端用 [AnalyzeUrlCore]
 *   (shared KMP 等价类, 内部走 OkHttp + JS 引擎), 视频源 header 通过 `headerMapF` 注入
 * - **章节缓存**: app 端 `chapter.resourceUrl` 缓存解析后的资源 URL 并写 DB; desktop 端
 *   简化为不写 DB (视频内容是 URL 字符串非文件, 缓存意义不大), 每次进章节都走网络解析
 * - **实际播放**: app 端用 ExoPlayer (androidx.media3), desktop 端 JVM 无现成视频
 *   播放库, 本 VM 只暴露 [videoUrl] State 给 UI 层, 由 UI 层接入 vlcj/JavaFX Media
 *   等播放库 (当前 [VideoPlayerScreen] 用占位渲染, 标 TODO)
 *
 * # 状态暴露
 *
 * 用 [MutableStateFlow] 暴露 UI 状态, Compose 经 `collectAsState()` 订阅:
 * - [videoUrl]: 当前播放视频的 [AnalyzeUrlCore] (含 URL + header), null = 加载中或失败
 * - [videoSource]: 多分辨率源 (null = 单一直链)
 * - [resolutions]: 分辨率列表 (空 = 单一直链或未加载)
 * - [currentResolutionIndex]: 当前分辨率索引
 * - [curChapterIndex]: 当前章节序号
 * - [chapterSize]: 总章节数
 * - [curChapterTitle]: 当前章节标题 (标题栏显示)
 * - [loading]: 加载中标记 (覆盖层显示/隐藏)
 * - [error]: 加载失败消息 (null = 无错误)
 *
 * @param scope Compose `rememberCoroutineScope()` 注入的作用域, VM 内 launch 全部派生于此,
 *   退出 Screen 时随作用域取消
 * @param prefStore PreferenceProvider (由 [VideoPlayerScreen] 注入), 用于持久化视频播放位置
 *   (key = `video_progress_{bookUrl}`, 对照 app 端 `book.durChapterPos` 字段)
 */
class VideoPlayerViewModel(
    private val scope: CoroutineScope,
    private val prefStore: PreferenceStoreProvider,
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

    // ---- 视频源状态 (UI 订阅) ----

    private val _videoUrl = MutableStateFlow<AnalyzeUrlCore?>(null)
    /**
     * 当前播放视频的 [AnalyzeUrlCore] (含 URL + header / cookie / charset / JS 解析)。
     *
     * null = 加载中或加载失败; UI 层订阅此状态后, 调用底层播放库 (vlcj / JavaFX Media)
     * 加载 [AnalyzeUrlCore.url] 并带 [AnalyzeUrlCore.headerMap] 发请求。
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
        private set

    // ---- 加载状态 (UI 订阅) ----

    private val _loading = MutableStateFlow(false)
    /** 加载中标记 (覆盖层显示/隐藏) */
    val loading: StateFlow<Boolean> = _loading.asStateFlow()

    private val _error = MutableStateFlow<String?>(null)
    /** 加载失败消息 (null = 无错误) */
    val error: StateFlow<String?> = _error.asStateFlow()

    /**
     * 初始化数据 (对照 app 端 [VideoViewModel.initData])。
     *
     * @param book 待播放的视频书
     * @param initialChapterIndex 初始章节序号 (通常取 book.durChapterIndex)
     */
    suspend fun initData(book: Book, initialChapterIndex: Int = book.durChapterIndex) {
        curBook = book
        // 查书源 (对照 app 端 BaseReadViewModel.upBook -> curBookSource = SourceHelp.getSource)
        curBookSource = withContext(Dispatchers.IO) {
            AppDbProviders.get().bookSourceDao.getBookSource(book.origin)
        }
        // 拉章节列表 (对照 app 端 upToc: WebBook.getChapterListAwait)
        val source = curBookSource
        if (source == null) {
            _error.value = "书源不存在"
            return
        }
        chapterList = runCatching {
            WebBook.getChapterListAwait(source, book).getOrThrow()
        }.onFailure {
            AppLog.put("桌面视频获取章节列表出错\n${it.message}", it)
            _error.value = "获取章节列表失败: ${it.message}"
        }.getOrNull()
        _chapterSize.value = chapterList?.size ?: 0
        // 加载初始章节
        val targetIndex = initialChapterIndex.coerceIn(0, (_chapterSize.value - 1).coerceAtLeast(0))
        loadChapter(targetIndex)
    }

    /**
     * 加载指定章节 (对照 app 端 [VideoViewModel.initChapter])。
     *
     * 内部流程:
     * 1. 调 [WebBook.getContentAwait] 拉取章节内容 (视频源 JSON / URL 字符串)
     * 2. 用 [parseVideoContent] 解析为 [AnalyzeUrlCore] (视频直链) 或 [VideoSource] (多分辨率)
     * 3. 更新 [_videoUrl] / [_videoSource] / [_resolutions] / [_curChapterIndex] / [_curChapterTitle]
     * 4. 持久化阅读进度
     *
     * 注: 与 app 端不同, 不缓存 chapter.resourceUrl 到 DB (视频内容是 URL 字符串非文件,
     * 缓存意义不大); 也不读 BookHelp.getContent 本地缓存 (视频源 URL 可能带时效签名)。
     *
     * @param index 章节序号 (0-based, 越界自动 clamp)
     */
    fun loadChapter(index: Int) {
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
        scope.launch {
            try {
                // 拉章节内容 (对照 app 端 VideoViewModel.initChapter: getContentAwait needSave=false)
                // needSave=false: 视频内容是 URL 字符串非文件, 不需要写本地缓存
                val nextChapterUrl = chapters.getOrNull(clampedIndex + 1)?.url
                val content = runCatching {
                    WebBook.getContentAwait(source, book, chapter, nextChapterUrl, needSave = false)
                }.onFailure {
                    AppLog.put("桌面视频获取章节内容出错\n${it.message}", it)
                    _error.value = "加载失败: ${it.message}"
                }.getOrNull()
                if (content == null) {
                    return@launch
                }
                if (content.isEmpty()) {
                    _error.value = "未获取到资源链接"
                    return@launch
                }
                // 解析视频源 (对照 app 端 VideoViewModel.parseVideoContent)
                parseVideoContent(content, source)
                _loading.value = false
                // 持久化阅读进度
                saveRead(clampedIndex)
            } catch (e: Exception) {
                AppLog.put("桌面视频加载章节出错\n${e.message}", e)
                _error.value = "加载出错: ${e.message}"
            }
        }
    }

    /**
     * 解析视频源内容 (对照 app 端 [VideoViewModel.parseVideoContent])。
     *
     * 三段式解析 (与 app 端完全一致, 保证视频源识别行为对齐):
     *
     * 1. **JSON 对象**: 尝试解析为 [VideoSource] (含 resolutions 列表), 命中则取默认分辨率 URL
     * 2. **`name::url\n` 多分辨率**: 逐行解析 `name::url` 为 [VideoResolution] 列表, 取首个
     * 3. **直接 URL / 内存 m3u8**:
     *    - `http` 开头: 当作直链, 用 [AnalyzeUrlCore] 包装 (带书源 header)
     *    - 其他: 当作内存 m3u8 内容, 用 fakeUrl `https://example.com/memory.m3u8` 作 Referer
     *      (app 端 ExoPlayer 用 ByteArrayDataSource 加载内存 m3u8; desktop 端播放库 TODO)
     *    - `#BASE:` 前缀: 提取真正的 Referer (前缀行), 余下为 m3u8 内容
     *
     * @param content 章节正文 (JSON / `name::url\n` 多行 / URL / m3u8 内容)
     * @param source 书源 (AnalyzeUrlCore 构造用)
     */
    private suspend fun parseVideoContent(content: String, source: BookSource) {
        // 1. JSON 对象: 尝试解析为 VideoSource
        val videoSource = if (content.isJsonObject()) {
            runCatching {
                GSON.fromJsonObject<VideoSource>(content).getOrNull()
                    ?.takeIf { it.resolutions.any { r -> r.url.isNotEmpty() } }
            }.onFailure {
                AppLog.put("桌面视频解析源JSON出错\n${it.message}", it)
            }.getOrNull()
        } else if (content.contains("::") && content.contains("\n")) {
            // 2. name::url\n 多分辨率格式
            val resolutions = content.lines().filter { it.contains("::") }.mapNotNull { line ->
                val parts = line.split("::", limit = 2)
                if (parts.size == 2) {
                    VideoResolution(
                        name = parts[0].trim(), url = parts[1].trim()
                    )
                } else null
            }.filter { it.url.isNotEmpty() }
            if (resolutions.isNotEmpty()) VideoSource(resolutions = resolutions) else null
        } else null

        if (videoSource != null && videoSource.resolutions.isNotEmpty()) {
            // 多分辨率源: 取默认分辨率 URL
            _videoSource.value = videoSource
            _resolutions.value = videoSource.resolutions
            currentResolutionIndex = videoSource.defaultIndex
            val resolution = videoSource.getResolution()
            if (resolution != null) {
                _videoUrl.value = AnalyzeUrlCore(
                    rawUrl = resolution.url,
                    source = source,
                    headerMapF = videoSource.headers,
                )
            }
        } else {
            // 3. 直接 URL / 内存 m3u8
            _videoSource.value = null
            _resolutions.value = emptyList()
            currentResolutionIndex = 0
            _videoUrl.value = if (content.startsWith("http")) {
                // http 直链: 用 AnalyzeUrlCore 包装 (带书源 header / cookie / charset)
                // 注: AnalyzeUrlCore 构造顺序为 (rawUrl, baseUrl, source, ...), 用命名参数避免歧义
                AnalyzeUrlCore(rawUrl = content, source = source)
            } else {
                // 内存 m3u8: app 端用 ByteArrayDataSource 加载, desktop 端播放库 TODO
                // 保留 app 端 fakeUrl + Referer 语义, 供 UI 层 vlcj 接入时复用
                // #BASE: 前缀提取真正 Referer, 余下为 m3u8 内容
                val (referer, m3u8Content) = if (content.startsWith("#BASE:")) {
                    val index = content.indexOf("\n") + 1
                    content.substring(6, index - 1) to content.substring(index)
                } else {
                    "https://example.com/memory.m3u8" to content
                }
                AnalyzeUrlCore("").apply {
                    url = m3u8Content
                    headerMap["Referer"] = referer
                }
            }
        }
    }

    /**
     * 切换分辨率 (对照 app 端 [VideoPlayActivity.switchResolution])。
     *
     * 注: app 端切换分辨率会重建 ExoPlayer 并 seekTo 原位置; desktop 端因播放库 TODO,
     * 当前只更新 [videoUrl] State, UI 层订阅后自行处理播放器重建 (vlcj 接入后补 seekTo)。
     *
     * @param index 分辨率索引 (0-based)
     * @param seekPositionMs 切换后跳转到的位置 (毫秒, -1 = 从头播)
     */
    fun switchResolution(index: Int, seekPositionMs: Long = 0L) {
        val source = _videoSource.value ?: return
        val resolution = source.getResolution(index) ?: return
        val bookSource = curBookSource ?: return
        currentResolutionIndex = index
        _videoUrl.value = AnalyzeUrlCore(
            rawUrl = resolution.url,
            source = bookSource,
            headerMapF = source.headers,
        )
        // TODO(desktop-video): vlcj 接入后, 这里需要通知 UI 层 seekTo(seekPositionMs)
    }

    /**
     * 刷新当前章节 (对照 app 端 [VideoViewModel.refreshChapter])。
     *
     * 清空当前视频源并重新加载 (用于播放出错时重试)。
     */
    fun refreshChapter() {
        loadChapter(_curChapterIndex.value)
    }

    /**
     * 切换到下一章 (对照 app 端 [VideoPlayActivity.playNextChapter])。
     *
     * 切换前重置视频播放位置为 0 (对照 app 端 [VideoViewModel.changeChapter]:
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
     * 切换到上一章 (对照 app 端 [VideoPlayActivity.playPrevChapter])。
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
     * 持久化阅读进度 (对照 app 端 [VideoViewModel.saveRead])。
     *
     * 写回 book.durChapterIndex / durChapterTitle / durChapterTime, 经 BookDao.update 落库。
     * 注: 视频播放位置 (毫秒) 不走 Book.durChapterPos (app 端字段), 改由 [saveVideoProgress]
     * 写入 [PreferenceStoreProvider] (key = `video_progress_{bookUrl}`), 与 desktop 端
     * Preference 底座一致; 章节切换时由 [moveToNextChapter] / [moveToPrevChapter] 重置为 0。
     *
     * @param index 当前章节序号
     */
    private suspend fun saveRead(index: Int) {
        val book = curBook ?: return
        val chapters = chapterList ?: return
        val chapter = chapters.getOrNull(index) ?: return
        runCatching {
            book.durChapterIndex = index
            book.durChapterTitle = chapter.title
            book.durChapterTime = System.currentTimeMillis()
            AppDbProviders.get().bookDao.update(book)
        }.onFailure {
            AppLog.put("桌面视频保存阅读进度出错\n${it.message}", it)
        }
    }

    // ---- 视频播放位置持久化 (对照 app 端 book.durChapterPos + VideoViewModel.position) ----

    /**
     * 保存视频播放位置到 [PreferenceStoreProvider] (对照 app 端 [VideoViewModel.saveRead]
     * 写 `book.durChapterPos`)。
     *
     * key = `video_progress_{bookUrl}`, value = 位置毫秒数 (字符串)。退出 Screen / 切换章节时
     * 调用; 接近片尾 (位置 > 时长 - 1s) 时存 0, 下次从头播 (对照 app 端 onPause:
     * `if (position > duration - 1000) saveRead(-1L)`)。
     *
     * @param bookUrl 书籍唯一标识 (curBook.bookUrl)
     * @param positionMs 当前播放位置 (毫秒), 0 = 从头播
     */
    fun saveVideoProgress(bookUrl: String, positionMs: Long) {
        runCatching {
            prefStore.putString("video_progress_$bookUrl", positionMs.coerceAtLeast(0L).toString())
        }.onFailure {
            AppLog.put("桌面视频保存播放位置出错\n${it.message}", it)
        }
    }

    /**
     * 读取上次保存的视频播放位置 (对照 app 端 initData:
     * `position = curBook.durChapterPos.coerceAtLeast(0).toLong()`)。
     *
     * @param bookUrl 书籍唯一标识
     * @return 上次播放位置 (毫秒), 0 = 无记录或从头播
     */
    fun getSavedVideoProgress(bookUrl: String): Long =
        prefStore.getString("video_progress_$bookUrl", "0")?.toLongOrNull() ?: 0L
}
