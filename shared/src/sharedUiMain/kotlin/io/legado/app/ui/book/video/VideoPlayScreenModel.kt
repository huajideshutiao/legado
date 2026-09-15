package io.legado.app.ui.book.video

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import io.legado.app.data.entities.Book
import io.legado.app.data.entities.BookChapter
import io.legado.app.data.entities.Bookmark
import io.legado.app.data.entities.VideoResolution
import io.legado.app.help.book.ContentProcessorProviders
import io.legado.app.help.book.isNotShelf
import io.legado.app.help.config.AppConfigProviders
import io.legado.app.help.showSourceLogin
import io.legado.app.help.toast.Toasters
import io.legado.app.model.ReadTimeRecorder
import io.legado.app.model.chapter.ChapterLoadState
import io.legado.app.ui.root.PlatformCapabilityProviders
import io.legado.app.ui.root.ScreenModel
import io.legado.app.ui.root.VideoPlayTarget
import io.legado.app.ui.root.screenModelScope
import io.legado.app.utils.systemCurrentTimeMillis
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.flow.updateAndGet
import kotlinx.coroutines.launch
import kotlin.concurrent.Volatile

/**
 * 视频播放页 shared ScreenModel: 适配 [VideoPlayViewModelShared] 各 StateFlow
 * 为统一 [VideoPlayUiState], 供 [VideoPlayerScreenContent] 消费。
 *
 * 对照 app 端 [VideoPlayActivity] onActivityCreated 中各 LiveData observe:
 * - chapterListData.observe → combine(shared.curChapterIndex/chapterSize/curChapterTitle)
 * - videoUrl.observe → 平台渲染层订阅 (shared.videoUrl 由 host 注入播放器)
 * - resolutions.observe → combine(shared.resolutions/currentResolutionIndex) → state.resolutionText
 * - loading/error → combine(shared.loadState)
 *
 * 章节切换 (onPrevChapter/onNextChapter) 委托 [shared] 的 moveToPrevChapter/moveToNextChapter,
 * 对照 Activity playPrevChapter/playNextChapter。
 *
 * 播放器回显 (播放态/倍速/缓冲) 不在本 ScreenModel 缓存: 直接由
 * [VideoPlayerController.playback] 快照流供给 UI (见 [PlaybackSnapshot])。
 *
 * 菜单动作 (onRefreshChapter/onToggleShelf/onShowLogin 等) 对照 Activity 同名方法:
 * - shared VM 能做的 (refreshChapter/switchResolution/loadChapter) 直接调用
 * - 平台能力 (剪贴板/书源变量/书架) 走 [PlatformCapabilityProviders]
 * - 平台专属 (Dialog/Activity 结果) 待 host 注入, 暂空实现
 */
/**
 * 播放器实时态快照 —— 视频播放界面回显的**唯一数据源**。
 *
 * 由各端 [VideoPlayerController] 维护并以 [VideoPlayerController.playback] 暴露，共享层
 * `collectAsState` 现取。上一版把播放态缓存进 [VideoPlayUiState]、靠各端「记得回灌」，
 * 结果桌面/iOS/鸿蒙三端全漏（播放钮恒显示暂停态、控制栏永不自动隐藏），Android 端则因
 * 监听器随渲染面进出组合而丢状态沿（缓冲圈永转 / 丢 ENDED 不自动下一章）—— 故改回
 * 需要时向唯一数据源现取，平台不再背「回灌义务」。
 */
data class PlaybackSnapshot(
    /** 用户播放意图（对应 media3 `playWhenReady`）：驱动播放/暂停钮图标。 */
    val playWhenReady: Boolean = false,
    /** 时钟是否真在前进（缓冲中为 false）：驱动控制栏自动隐藏计时。 */
    val isPlaying: Boolean = false,
    /** 是否在等数据（起播缓冲 / 中途卡顿 / seek 后）。 */
    val isBuffering: Boolean = false,
    /** 当前倍速（驱动倍速钮文字与档位高亮）。 */
    val speed: Float = 1f,
    /** 已播到末尾（对应 media3 `STATE_ENDED`）。 */
    val ended: Boolean = false,
    /** 尚未装载任何媒体（对应 media3 `STATE_IDLE`）。 */
    val idle: Boolean = true,
) {
    /**
     * 播放/暂停钮是否画「播放三角」。
     *
     * 逐字对齐原版 media3 `Util.shouldShowPlayButton`：`!playWhenReady || IDLE || ENDED`。
     * 刻意不用 [isPlaying]：那会让缓冲卡顿的瞬间图标从暂停条跳成播放三角，原版不会。
     */
    val showPlayIcon: Boolean
        get() = !playWhenReady || ended || idle
}

/**
 * 平台播放器控制器。
 *
 * 播放态一律经 [playback] 单一快照流暴露（图标/倍速/缓冲/自动隐藏都读它），
 * 位置/时长/缓冲时间点属高频读数，仍走普通属性由控制层轮询现取。
 */
interface VideoPlayerController {
    /** 播放态快照流（回显唯一数据源，见 [PlaybackSnapshot]）。 */
    val playback: StateFlow<PlaybackSnapshot>

    val positionMs: Long
    val durationMs: Long

    /**
     * 已缓冲到的**绝对**时间点 ms (不是缓冲时长), 供进度条缓冲层绘制。
     *
     * 口径以 media3 `bufferedPosition` 为准: 安卓直取; 桌面读 mpv `demuxer-cache-time`;
     * iOS 取 `loadedTimeRanges` 各段 end 的最大值; 鸿蒙用 AVPlayer CACHED_DURATION
     * 加当前位置。取不到时给 0 (缓冲层不绘制), 不要拿 duration 顶替 —— 那会画出一条
     * 永远铺满的假缓冲条。
     */
    val bufferedMs: Long
    fun playPause()

    /**
     * 无条件暂停 (不切换)。对照原版 `VideoPlayActivity` 点标题进书籍详情前的
     * `player?.pause()` (那条路径是显式暂停, 不是 toggle)。
     */
    fun pause()

    /**
     * 停止并卸载当前媒体（切章 / 刷新时调用）。
     *
     * `videoUrl` 置 null 只是「没有新源」的数据状态，不是命令：不显式 stop 的话上一章
     * 画面与声音会一直播到新章解析完。实现须同时清掉「已装载 url」守卫，让同链接重试可用。
     */
    fun stop()

    /**
     * 按**当前 `videoUrl` 里的原地址 + 原请求头**重新装载一次（外部直投播放失败时页面上
     * 唯一真能用的「重新加载」，由 [VideoPlayScreenModel.onRetryLoad] 驱动）。
     *
     * 为什么必须是控制器上的显式动作，而不是「把同一条地址再发一遍 StateFlow」：四端渲染层
     * 装载的订阅键就是地址字符串（Android/desktop 按 url，iOS/鸿蒙 按 url + headers 的
     * `LaunchedEffect`），同址再发既不会让 effect 重跑，StateFlow 对相等值也根本不发射 ——
     * 靠重发状态在四端都推不动播放器。故实现口径固定两步：先按 [stop] 的契约清掉「已装载
     * url」守卫并让当前媒体下台（三端直接走 [stop]；鸿蒙只取它的「清守卫」那一半，卸媒体交给
     * ArkTS 装源通道开头的 `releasePlayer` —— 那里连旧 fd 一起收，且避开出错后 `stop()` 被
     * 系统回绝再回一条 onError 把新装载打成播放失败），再用当前 videoUrl 的 url + headerMap
     * 走**与首次装载完全相同**的那条装载路径（起播位置照旧读
     * [VideoPlayViewModelShared.startPositionMs]；鸿蒙还须按原地址形态分流：
     * http(s) → setSourceUrl，本地/授权体 URI → setSource + fd 通道）。
     *
     * 刻意不给默认实现：任何「只 stop 不重装」的实现都是一颗死按钮，宁可让编译期逼每端表态。
     * 当前无源（`videoUrl` 为 null）时静默返回 —— 没有地址可重装，也不该凭空造一个错误。
     */
    fun reload()

    fun seekTo(positionMs: Long)
    fun seekBy(deltaMs: Long)
    fun setSpeed(speed: Float)
    fun seekBack()
    fun seekForward()
    fun release()
}

interface VideoPlayPlatformProvider {
    fun createController(
        screenModel: VideoPlayScreenModel,
        onPlaybackEnded: () -> Unit,
    ): VideoPlayerController

    /**
     * [controller] 是否只是降级占位实现。默认 false —— 其它端的「重新加载」永不重建控制器,
     * 行为与引入按需下载前完全一致; 桌面端在媒体组件未就绪时给空控制器, 此时为 true。
     */
    fun isPlaceholderController(controller: VideoPlayerController?): Boolean = false

    /**
     * 渲染平台原生视频画面 (纯 Surface, 如 Android PlayerView / 桌面 mpv Canvas / iOS UIKitView / 鸿蒙 ArkUIView2)。
     * 全部 UI 覆盖层 (手势、加载转圈、缓冲圈、错误重试、播放控制条、锁定钮、手势提示) 统一由共享层 [VideoPlayerHostContainer] 编排。
     */
    @Composable
    fun RenderSurface(
        controller: VideoPlayerController,
        screenModel: VideoPlayScreenModel,
        modifier: Modifier,
    )

    /**
     * 平台手势控制器 (可选)。未重写时使用共享层默认手势控制器。
     */
    @Composable
    fun rememberGestureController(
        controller: VideoPlayerController,
        screenModel: VideoPlayScreenModel,
    ): VideoGestureController? = null

    /**
     * 是否处于缓冲态 (可选)。平台可通过控制器或特定状态源自定义判定, 未提供时依据 UiState 判定。
     */
    /**
     * 平台「系统级全屏」真实态（可选）。非 null 时共享层以它为准渲染，页面不再自存副本：
     * 桌面真全屏由窗口决定（ESC / 标题栏 / 原生控制条都能改），返回窗口实际全屏态即可，
     * 全屏切换失败的平台上它恒 false，页面就不会按全屏渲染。返回 null 表示该平台全屏是
     * 页面意图驱动（安卓横屏 / 鸿蒙锁向 / iOS 隐藏状态栏），沿用 `UiState.isSystemFullScreen`。
     */
    @Composable
    fun rememberSystemFullScreen(): Boolean? = null

    /**
     * 该平台是否有可用的"系统返回"通道 (返回键 / 滑动返回会进 AppBackHandler)。
     * 默认 true。iOS 的 PlatformBackHandler 是 no-op: 视频页一进全屏就把顶栏隐掉,
     * 而“窗口内全屏”的退出口只顶栏菜单里有 → 用户退不出全屏也打不开菜单。
     * 置 false 时路由会在全屏期间常驻一个退出入口。
     */
    val supportsSystemBack: Boolean get() = true

    /** 全屏切换失败时不得置真页面全屏态 */
    fun applyFullscreen(enabled: Boolean) {}

    // 系统级全屏 (隐藏系统底栏/窗口装饰, 对照 app applyFullscreen 在桌面端的增强版);
    // 与 applyFullscreen (右上角菜单的窗口内全屏) 区分
    fun applySystemFullScreen(enabled: Boolean) {}
}

object VideoPlayPlatformProviders {
    @Volatile
    private var impl: VideoPlayPlatformProvider? = null
    fun register(provider: VideoPlayPlatformProvider) {
        impl = provider
    }

    fun getOrNull(): VideoPlayPlatformProvider? = impl
}

class VideoPlayScreenModel : ScreenModel {

    private val scope = screenModelScope("视频播放")

    val shared = VideoPlayViewModelShared(scope = scope)
    val platform = VideoPlayPlatformProviders.getOrNull()

    /**
     * 播控器。不写成 val: 桌面端媒体播放组件是首次进本页才按需下载的, 构造期可能还没装好,
     * 当时拿到的是占位控制器 —— 下载完成后靠 [onRetryLoad] 补建真控制器。其它端一次创建即定型。
     */
    var controller: VideoPlayerController? = platform?.createController(
        screenModel = this,
        onPlaybackEnded = ::onNextChapter,
    )
        private set

    private val _state = MutableStateFlow(VideoPlayUiState())
    val state: StateFlow<VideoPlayUiState> = _state.asStateFlow()

    /**
     * 手势/按键反馈文字 (如 "2.0X" / "音量: 50%"), 由渲染层显示 (null = 隐藏)。
     * 独立 flow 而非并入 [state]: 拖动进度时文字每帧变, 并进主 state 会整页重组。
     * 键盘长按倍速 (快捷键栈 onGestureText) 与鼠标手势 (平台渲染槽) 共用此通道,
     * 桌面/Android 渲染层都订阅它显示。
     */
    private val _gestureText = MutableStateFlow<String?>(null)
    val gestureText: StateFlow<String?> = _gestureText.asStateFlow()

    /** onExit 已执行标记: 一次活跃期只保存一次 (释放后位置归零), [onResume] 重开 */
    private var exited = false

    /** 标题净化计算任务 (章节列表变更时重算, 旧任务取消) */
    private var displayTitleJob: Job? = null

    /** 上次算过标题的章节列表 (按引用比较, 防重复重算) */
    private var lastTitledChapters: List<BookChapter>? = null

    init {
        // 合并 shared 各 StateFlow → 统一 UiState (对照 Activity chapterListData/resolutions observe)
        // chapters 随每次发射一并取回, 避免与其它写入交错时丢失章节列表
        combine(
            shared.curChapterIndex, shared.chapterSize, shared.loadState,
        ) { index, size, loadState ->
            // 返回增量合并函数交给 update{} 原子完成: scope 是线程池, 本收集器与下面的
            // 分辨率收集器、UI 线程直写并发操作同一个 _state, 非原子读改写会整字段丢更新
            val chapters = shared.chapters
            val merge: (VideoPlayUiState) -> VideoPlayUiState = { cur ->
                cur.copy(
                    curChapterIndex = index,
                    chapterSize = size,
                    loadState = loadState,
                    chapters = chapters,
                )
            }
            merge
        }.onEach { merge ->
            val applied = _state.updateAndGet(merge)
            // 章节列表换了才重算标题 (对照 Activity chapterListData.observe → upDisplayTitles)
            if (applied.chapters !== lastTitledChapters) {
                lastTitledChapters = applied.chapters
                upDisplayTitles(applied.chapters)
            }
        }.launchIn(scope)

        // 分辨率列表 + 当前索引 → hasMultiResolution/currentResolutionIndex
        // (对照 Activity resolutions.observe + updateResolutionText)。索引必须是状态流:
        // 上一版它是普通 var、combine 键只有 resolutions/videoSource, 切档后无任何上游发射
        // → 钮文字与对话框高亮永远停在旧档。
        combine(shared.resolutions, shared.currentResolutionIndex) { resolutions, currentIndex ->
            val merge: (VideoPlayUiState) -> VideoPlayUiState = { cur ->
                cur.copy(
                    resolutions = resolutions,
                    currentResolutionIndex = currentIndex,
                    hasMultiResolution = resolutions.size > 1,
                )
            }
            merge
        }.onEach { merge -> _state.update(merge) }.launchIn(scope)
    }

    /** 标题净化规则后台计算 (对照 app VideoChapterGrid 内 upDisplayTitles) */
    private fun upDisplayTitles(chapters: List<BookChapter>) {
        val book = shared.curBook ?: return
        displayTitleJob?.cancel()
        displayTitleJob = scope.launch {
            val useReplace = runCatching {
                AppConfigProviders.get().tocUiUseReplace
            }.getOrDefault(false) && book.getUseReplaceRule()
            val replaceRules = runCatching {
                ContentProcessorProviders.get().getTitleReplaceRules(book)
            }.getOrNull().orEmpty()
            val titles = chapters.map { it.getDisplayTitle(replaceRules, useReplace) }
            _state.update { it.copy(displayTitles = titles) }
        }
    }

    fun dispatch(event: VideoPlayUiEvent) {
        when (event) {
            is VideoPlayUiEvent.ShowBook -> {
                // 初始化书籍名 + 章节列表 + 书架态 (对照 Activity viewModel.initData +
                // chapterListData.observe + BaseReadViewModel.upBook 的 inBookshelf = !book.isNotShelf)
                // 上一版 ShowBook 只写 bookName、从不初始化 inShelf → 从书架进页星恒空心,
                // 下一次点击又拿陈旧 false 当“不在架”去上架 (空点一下)
                _state.update {
                    it.copy(
                        bookName = event.book.name,
                        inShelf = !event.book.isNotShelf,
                        isDirect = false,
                    )
                }
                scope.launch {
                    val book = event.book
                    // 只有显式带了章节定位 (书签/目录回传) 才覆盖已存进度 —— 对照原版
                    // `initData` 的 `if (overrideIndex >= 0)` 门控。上一版无条件
                    // `chapterPos ?: 0` + applyChapterOverride, 而所有常规入口都不带定位
                    // → 进页那一瞬就把用户看到一半的 durChapterPos 清零并 PATCH 落库。
                    // 未覆盖时由 initData 自己从 book.durChapterPos 取恢复位置。
                    val overrideIndex = event.chapterIndex
                    if (overrideIndex != null) {
                        shared.applyChapterOverride(
                            book, overrideIndex, event.chapterPos ?: 0
                        )
                    }
                    // persistProgress=false: 首次装载不得回写进度 (会把 just 取到的
                    // durChapterPos 归零), 与原版 initData 一致
                    shared.initData(
                        book,
                        overrideIndex ?: book.durChapterIndex,
                        persistProgress = false,
                    )
                }
            }

            // 外部直投 (不携书): 跳过书/书源/章节装载链, 直接把地址喂给播放器
            is VideoPlayUiEvent.PlayDirect -> {
                _state.update {
                    it.copy(
                        bookName = event.target.displayTitle,
                        inShelf = false,
                        isDirect = true,
                    )
                }
                shared.playDirect(event.target)
            }

            // 播放器错误归一到 shared VM 的 loadState (单一状态源): 上一版直接写 _state.loadState,
            // 而 combine 每次发射都用 shared.loadState 覆盖回去 → 错误占位要么被无声抹掉
            // (黑屏无重试钮), 要么挂在已恢复的画面上不走
            is VideoPlayUiEvent.ShowError -> shared.reportPlayError(event.message)

            is VideoPlayUiEvent.UpdateInShelf -> _state.update {
                it.copy(inShelf = event.inShelf)
            }
        }
    }

    // ---- 章节切换 (委托 shared VM, 对照 Activity playPrevChapter/playNextChapter) ----

    fun onPrevChapter() {
        shared.moveToPrevChapter()
    }

    fun onNextChapter() {
        // 直投只有一条地址, 无章可切 (不得弹“已播放到最后一章”那种类书提示)
        if (shared.isDirect) return
        // 末章播完给一条提示: 原版什么都不做 (停在末帧 + 控制层自动收起), 看起来像卡死
        if (!shared.moveToNextChapter() && allowEndedToast()) {
            Toasters.get().toast("已播放到最后一章")
        }
    }

    /** 末章提示节流: 引擎 Ended 是电平态, 防连发 */
    private var lastEndedToastAt = 0L
    private fun allowEndedToast(): Boolean {
        val now = systemCurrentTimeMillis()
        if (now - lastEndedToastAt < 5000L) return false
        lastEndedToastAt = now
        return true
    }

    /** 选集网格点击章节 (对照 Activity openChapter) */
    fun onOpenChapter(index: Int) {
        shared.loadChapter(index)
    }

    // ---- 播放器控制 (平台专属, 待 host 注入; 下沉前空实现占位) ----

    fun onPlayPause() = controller?.playPause() ?: Unit
    fun onSeekTo(positionMs: Long) = controller?.seekTo(positionMs) ?: Unit
    fun onSeekDelta(deltaMs: Long) = controller?.seekBy(deltaMs) ?: Unit
    fun onSpeedChange(speed: Float) = controller?.setSpeed(speed) ?: Unit
    fun onToggleControls() {
        _state.update { it.copy(controlsVisible = !it.controlsVisible) }
    }

    /** 锁定 / 解锁 (锁定态只影响鼠标手势与控制层的问题已修: 键盘快捷键同步让位) */
    fun setLocked(locked: Boolean) {
        _state.update { it.copy(isLocked = locked) }
    }

    /**
     * 点标题进书籍详情前暂停播放 (对照原版 toolbar.setOnClickListener 里
     * `bookInfoResult.launch { … player?.pause() }`)。
     *
     * 只覆盖这一个入口: 原版 `onPause()` 本身**不暂停播放器** (只结束计时 + saveRead +
     * uploadProgress), 所以退后台/压其他页仍继续出声是原版行为, 不在这里改。
     */
    fun onPausePlayback() = controller?.pause() ?: Unit

    /** 当前是否锁定 (键盘快捷键分发读它) */
    fun isLocked(): Boolean = _state.value.isLocked

    /** 手势/按键反馈文字 (null = 隐藏), 见 [gestureText]。 */
    fun onGestureText(text: String?) {
        _gestureText.value = text
    }

    fun onSeekBack() = controller?.seekBack() ?: Unit
    fun onSeekForward() = controller?.seekForward() ?: Unit

    // ---- 菜单动作 (对照 Activity onCompatOptionsItemSelected / VideoTitleActions) ----

    /** 刷新当前章节 (对照 Activity refreshChapter: pause + viewModel.refreshChapter) */
    fun onRefreshChapter() {
        // 刷新后从当前位置续播 (对照原版 refreshChapter 不清 position)
        shared.refreshChapter(
            persistProgress = false,
            seekPositionMs = controller?.positionMs ?: 0L,
        )
    }

    /**
     * 失败提示页「重新加载」的唯一入口。
     *
     * 两条形态必须分开: 由书进入的刷新是**重新解析章节内容** (拿一条新直链),
     * 而外部直投根本没有章节可言 —— 地址就挂在 `shared.videoUrl` 上,
     * 能做的只有按原地址、原请求头重新起播一次 (见 [VideoPlayerController.reload])。
     * 上一版直投态这个入口接的是 [onRefreshChapter], 而它在 `isDirect` 下直接早退
     * → 错误页上是一颗死按钮 (只能退页重进)。
     */
    fun onRetryLoad() {
        recreateControllerIfPlaceholder()
        if (shared.isDirect) retryDirectPlay() else onRefreshChapter()
    }

    /**
     * 占位控制器 → 真控制器: 现有实例是占位时就重新走一次 [VideoPlayPlatformProvider.createController]。
     *
     * **不得**先拿平台的"当前是否就绪"判定早退: 取消下载后它恒为 false,
     * 早退会让「重新加载」既不重弹下载确认框也不换控制器 —— 画面永远起不来, 只能退页重进。
     * `createController` 内部已用 `ensureReady()` 把"未就绪"推成确认弹框并返回占位,
     * 用户在那里下载完再点本按钮即可直接起播。
     */
    private fun recreateControllerIfPlaceholder() {
        val p = platform ?: return
        if (!p.isPlaceholderController(controller)) return
        controller = p.createController(screenModel = this, onPlaybackEnded = ::onNextChapter)
    }

    /**
     * 外部直投的重新起播: 不重发 StateFlow (同址不会让渲染层 effect 重跑),
     * 而是给控制器一条显式命令。
     *
     * 取位置的顺序不能反: [VideoPlayerController.reload] 第一步就是卸媒体,
     * 装完之后 `positionMs` 已经是 0 (或新流的缓冲位置), 续播点必须在 reload **前**拿到。
     */
    private fun retryDirectPlay() {
        val c = controller ?: return
        // 地址还没送达渲染层 (首帧未装完就出错) 也没关系: reload 内部会读当前 videoUrl
        shared.prepareDirectReload(seekPositionMs = c.positionMs)
        c.reload()
    }

    /**
     * 上架/下架切换 (对照 Activity toggleShelf)。
     * 平台能力走 [PlatformCapabilityProviders.toggleBookshelf];
     *
     * 在不在架以 [curBook] 的 `isNotShelf` 为权威源 (它是 toggleBookshelfCore 原地增删的位),
     * 不再拿 UiState 缓存值当分支判据; 完成回调按平台契约写回 (true=已上架, false=取消上架,
     * **null=下架成功**, 见 BookExtensionsShared.toggleBookshelfCore)。
     */
    fun onToggleShelf() {
        val book = shared.curBook ?: return
        val inShelf = !book.isNotShelf
        PlatformCapabilityProviders.get().toggleBookshelf(
            book, inShelf, onComplete = { result ->
                // null = 下架成功: 上一版只接 true/false → 书已删但星永远亮着,
                // 再点又是“删一本不存在的书”
                val nowInShelf = if (result == null) false else result
                _state.update { it.copy(inShelf = nowInShelf) }
            }
        )
    }

    /** 切换窗口内全屏 (对照 Activity toggleFullScreen: applyFullScreen(!isFullScreen))。
     *  与系统级全屏互斥: 进入窗口内全屏前退出系统级全屏 */
    fun onToggleFullScreen() {
        val enabled = !_state.value.isFullScreen
        _state.update { it.copy(isFullScreen = enabled, isSystemFullScreen = false) }
        // 互斥: 进入窗口内全屏前退出系统级全屏
        if (enabled) platform?.applySystemFullScreen(false)
        platform?.applyFullscreen(enabled)
    }

    fun setFullScreen(enabled: Boolean) {
        _state.update { it.copy(isFullScreen = enabled) }
        platform?.applyFullscreen(enabled)
    }

    // 系统级全屏切换 (桌面端隐藏系统底栏/窗口装饰; 与窗口内全屏互斥)。
    // 目标值由调用方传入 —— 桌面真全屏由窗口决定, 页面自己取反会与窗口实际态分叉
    fun onToggleSystemFullScreen(enabled: Boolean) {
        val wasWindowFullScreen = _state.value.isFullScreen
        _state.update { it.copy(isSystemFullScreen = enabled, isFullScreen = false) }
        // 互斥反向补齐: 上一版只改标志位不退窗口内全屏, 会留下“页面按新全屏渲染、
        // 旧全屏的系统栏/方向没回收”的错乱
        if (enabled && wasWindowFullScreen) platform?.applyFullscreen(false)
        platform?.applySystemFullScreen(enabled)
    }

    fun setSystemFullScreen(enabled: Boolean) {
        _state.update { it.copy(isSystemFullScreen = enabled) }
        platform?.applySystemFullScreen(enabled)
    }

    /** 登录 (对照原版 VideoPlayActivity menu_login: 预置 IntentData.book + nowChapter 后
     *  showLoginDialog)。统一走 [showSourceLogin], 带当前书与当前章供登录 JS 上下文绑定。 */
    fun onShowLogin() {
        val source = shared.curBookSource ?: return
        val book = shared.curBook
        val chapter = book?.let { shared.chapters.getOrNull(it.durChapterIndex) }
        showSourceLogin(source.getKey(), source, book, chapter)
    }

    /** 复制播放地址 (对照 Activity copyPlayUrl: sendToClip)。
     *  走平台能力 [PlatformCapabilityProviders.copyToClipboard] */
    fun onCopyPlayUrl() {
        val url = shared.videoUrl.value?.url ?: return
        PlatformCapabilityProviders.get().copyToClipboard(url)
    }

    /** 源变量 (对照 Activity showSourceVariable: showSourceVariableDialog)。
     *  走平台能力 [PlatformCapabilityProviders.showBookSourceVariableDialog] */
    fun onShowSourceVariable() {
        val source = shared.curBookSource ?: return
        PlatformCapabilityProviders.get().showBookSourceVariableDialog(source)
    }

    /** 书籍变量 (对照 Activity showBookVariable: showBookVariableDialog)。
     *  走平台能力 [PlatformCapabilityProviders.showBookVariableDialog] */
    fun onShowBookVariable() {
        val book = shared.curBook ?: return
        PlatformCapabilityProviders.get().showBookVariableDialog(book)
    }

    /** 添加书签 (对照 Activity addBookmark: 取 player 真实位置 + createBookmark + 弹 BookmarkDialog)。
     *  从 controller 取当前 positionMs/durationMs, 构造书签后置 pendingBookmark,
     *  由 Route 订阅 state 弹 shared BookmarkDialog 供用户编辑 */
    fun onAddBookmark() {
        val book = shared.curBook ?: return
        val pos = controller?.positionMs ?: 0L
        val dur = controller?.durationMs?.takeIf { it > 0 } ?: 0L
        val bookmark = shared.createBookmark(
            positionMs = pos,
            durationMs = dur,
            bookName = book.name,
            chapterIndex = shared.curChapterIndex.value,
            chapterName = shared.curChapterTitle.value,
        ) ?: return
        _state.update { it.copy(pendingBookmark = bookmark) }
    }

    /** 清除待编辑书签 (Route 端 BookmarkDialog 关闭后调用) */
    fun clearPendingBookmark() {
        _state.update { it.copy(pendingBookmark = null) }
    }

    /** 分辨率切换 (对照 Activity switchResolution: 重建播放器 + seekTo 原位置)。
     *  位置从控制器现取: 上一版不传位置 → 切清晰度必从片头重播 */
    fun onSwitchResolution(index: Int) {
        shared.switchResolution(index, seekPositionMs = controller?.positionMs ?: 0L)
    }

    /** 当前倍速 (键盘长按前快照用): 直读控制器快照, 不在 UiState 里缓存副本 */
    fun currentSpeed(): Float = controller?.playback?.value?.speed ?: 1f

    /** 进入活跃期 (对照原版 onResume): 开始计时; 重开 [exited] 让本次活跃期结束时能再保存。 */
    fun onResume() {
        exited = false
        shared.onResume()
    }

    /** 离开活跃期 (对照原版 onPause: 结束计时 + 落库 + 上传), 走 [onExit] 保证一次活跃期只保存一次。 */
    fun onPause() = onExit()

    /**
     * 保存真实进度 (对照 app `VideoPlayActivity.onPause` + `onDestroy`)。
     *
     * 由宿主 [io.legado.app.ui.route.VideoPlayRoute] 的活跃期回调调用, [onCleared] 兜底
     * (防回调未触发)。先取播放器真实位置保存, 再释放 controller (位置读取后才有意义)。
     */
    fun onExit() {
        if (exited) return
        exited = true
        val c = controller
        val pos = c?.positionMs ?: 0L
        val dur = c?.durationMs?.takeIf { it > 0 } ?: 0L
        // 保存屏障: 媒体已卸载 (切章时 stop() 卸源 / release 后引擎把位置归零) 时读到的 0
        // 不是真实进度, 拿它写库会把用户看到一半的位置抹成 0。此时不覆盖已存进度。
        if (c != null && pos <= 0L && c.playback.value.idle) return
        // 保存进度: Preference 视频位置 + books 表 durChapterPos + WebDav 上传
        shared.onExit(pos, dur)
    }

    override fun onPreRemoved() {
        // 导航 pop 动画开始前先保存视频进度: onExit 幂等 (exited 标志保证 onCleared 跳过),
        // 此处提前取 controller 真实位置保存 (对照原版返回键按下即 onPause 保存),
        // 书架返回立即可见最新进度
        onExit()
    }

    override fun onCleared() {
        // 对照原版 VideoPlayActivity.onDestroy: 立即结束阅读计时 (不等 end 的延迟结算)
        ReadTimeRecorder.endImmediately(ReadTimeRecorder.Source.VIDEO)
        // 先保存进度 (controller 释放前取真实位置; onExit 已执行则跳过)
        runCatching { onExit() }
        // 再释放播放器 (对照 app onDestroy: player.release)
        controller?.release()
        platform?.applyFullscreen(false)
        platform?.applySystemFullScreen(false)
        scope.cancel()
    }
}

/** 视频播放页 UI 状态 (章节 + 加载/错误 + 播放控制回显 + 屏幕状态)。 */
data class VideoPlayUiState(
    val bookName: String = "",
    val curChapterIndex: Int = 0,
    val chapterSize: Int = 0,
    /** 章节装载状态 (空闲/加载中/失败, 单一状态源: shared VM 的 loadState 直传) */
    val loadState: ChapterLoadState = ChapterLoadState.Idle,
    val controlsVisible: Boolean = false,
    /** 锁定态 (手势层与控制层隐藏)。存于页面状态而非渲染层 remember:
     *  remember 会随布局分支切换被子树重建而静默丢失，键盘快捷键也读不到它 */
    val isLocked: Boolean = false,
    /** 是否在书架中 (对照 Activity inShelf) */
    val inShelf: Boolean = false,
    /** 是否全屏 (对照 Activity isFullScreen) */
    val isFullScreen: Boolean = false,
    /** 是否系统级全屏 (仅作无平台真实态钩子端的页面意图; 桌面由
     *  [VideoPlayPlatformProvider.rememberSystemFullScreen] 取窗口真值覆盖) */
    val isSystemFullScreen: Boolean = false,
    /** 是否多分辨率源 (控制分辨率钮显隐) */
    val hasMultiResolution: Boolean = false,
    /** 分辨率列表 */
    val resolutions: List<VideoResolution> = emptyList(),
    /** 当前分辨率索引 */
    val currentResolutionIndex: Int = 0,
    /** 章节列表 (对照 Activity chapters, 供选集网格使用) */
    val chapters: List<BookChapter> = emptyList(),
    /** 章节显示标题 (与 [chapters] 同序, 已过标题净化规则) */
    val displayTitles: List<String> = emptyList(),
    /** 待编辑书签 (onAddBookmark 构造后由 Route 弹 BookmarkDialog) */
    val pendingBookmark: Bookmark? = null,
    /**
     * 外部直投播放态 (地址由其他应用/文件关联交给我们的)。
     *
     * 页面据此隐藏一切依赖“书”的能力: 上架星、选集网格、上/下一章、书签、
     * 刷新、登录、源/书变量、编辑书源、书评、点标题进详情。
     */
    val isDirect: Boolean = false,
)

/**
 * 视频播放页事件, 由宿主 (app Activity / desktop Window / Route) 推入。
 *
 * 只保留真实有生产者的三个: 播放态不再走事件回灌 (改由
 * [VideoPlayerController.playback] 单一快照流现取, 见 [PlaybackSnapshot])。
 */
sealed interface VideoPlayUiEvent {
    /** 书籍数据更新 (对齐 VideoPlayActivity.titleText/durChapterIndex 初始化)。
     *  chapterIndex/chapterPos 用于书签/目录回传定位 (对照 AudioPlayUiEvent.Init) */
    data class ShowBook(
        val book: Book,
        val chapterIndex: Int? = null,
        val chapterPos: Int? = null,
    ) : VideoPlayUiEvent

    /** 显示错误 */
    data class ShowError(val message: String) : VideoPlayUiEvent

    /**
     * 外部直投起播 (对照 [io.legado.app.ui.root.VideoPlayTarget.Direct]):
     * 不携书、不查书源、不拉章节, 页面按最小播放器渲染。
     */
    data class PlayDirect(val target: VideoPlayTarget.Direct) : VideoPlayUiEvent

    /** 书架状态更新 (对齐 Activity inShelf) */
    data class UpdateInShelf(val inShelf: Boolean) : VideoPlayUiEvent
}
