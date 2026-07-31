package io.legado.app.ui.book.video

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import io.legado.app.data.entities.Book
import io.legado.app.data.entities.BookChapter
import io.legado.app.data.entities.Bookmark
import io.legado.app.data.entities.VideoResolution
import io.legado.app.help.book.ContentProcessorProviders
import io.legado.app.help.book.getDisplayTitle
import io.legado.app.help.book.getUseReplaceRule
import io.legado.app.help.config.AppConfigProviders
import io.legado.app.ui.compose.platform.PreferenceStoreProvider
import io.legado.app.ui.root.PlatformCapabilityProviders
import io.legado.app.ui.root.ScreenModel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * 视频播放页 shared ScreenModel: 适配 [VideoPlayViewModelShared] 各 StateFlow
 * 为统一 [VideoPlayUiState], 供 [VideoPlayerScreenContent] 消费。
 *
 * 对照 app 端 [VideoPlayActivity] onActivityCreated 中各 LiveData observe:
 * - chapterListData.observe → combine(shared.curChapterIndex/chapterSize/curChapterTitle)
 * - videoUrl.observe → 平台渲染层订阅 (shared.videoUrl 由 host 注入播放器)
 * - resolutions.observe → combine(shared.resolutions/currentResolutionIndex) → state.resolutionText
 * - loading/error → combine(shared.loading/error)
 *
 * 章节切换 (onPrevChapter/onNextChapter) 委托 [shared] 的 moveToPrevChapter/moveToNextChapter,
 * 对照 Activity playPrevChapter/playNextChapter。
 *
 * 播放器控制 (onPlayPause/onSeekDelta/onSpeedChange/onToggleControls) 属平台专属
 * (ExoPlayer/mpv API), 待 host 注入; 下沉前为空实现占位。
 *
 * 菜单动作 (onRefreshChapter/onToggleShelf/onShowLogin 等) 对照 Activity 同名方法:
 * - shared VM 能做的 (refreshChapter/switchResolution/loadChapter) 直接调用
 * - 平台能力 (剪贴板/书源变量/书架) 走 [PlatformCapabilityProviders]
 * - 平台专属 (Dialog/Activity 结果) 待 host 注入, 暂空实现
 *
 * [prefStore] 由 Route 经 LocalPreferenceStoreProvider 注入真实 PreferenceStoreProvider,
 * 用于视频进度持久化 (key = `video_progress_{bookUrl}`)。
 */
interface VideoPlayerController {
    val positionMs: Long
    val durationMs: Long
    val bufferedMs: Long
    fun playPause()
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

    @Composable
    fun Render(
        controller: VideoPlayerController,
        screenModel: VideoPlayScreenModel,
        modifier: Modifier,
    )

    fun applyFullscreen(enabled: Boolean) {}
    fun toggleOrientation() {}
}

object VideoPlayPlatformProviders {
    @Volatile
    private var impl: VideoPlayPlatformProvider? = null
    fun register(provider: VideoPlayPlatformProvider) {
        impl = provider
    }

    fun getOrNull(): VideoPlayPlatformProvider? = impl
}

class VideoPlayScreenModel(
    private val prefStore: PreferenceStoreProvider,
) : ScreenModel {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    val shared = VideoPlayViewModelShared(scope = scope, prefStore = prefStore)
    val platform = VideoPlayPlatformProviders.getOrNull()
    val controller: VideoPlayerController? = platform?.createController(
        screenModel = this,
        onPlaybackEnded = ::onNextChapter,
    )

    private val _state = MutableStateFlow(VideoPlayUiState())
    val state: StateFlow<VideoPlayUiState> = _state.asStateFlow()

    /** onExit 已执行标记: 防 DisposableEffect.onDispose 与 onCleared 重复保存 (释放后位置归零) */
    private var exited = false

    /** 标题净化计算任务 (章节列表变更时重算, 旧任务取消) */
    private var displayTitleJob: Job? = null

    /** 上次算过标题的章节列表 (按引用比较, 防重复重算) */
    private var lastTitledChapters: List<BookChapter>? = null

    init {
        // 合并 shared 各 StateFlow → 统一 UiState (对照 Activity chapterListData/videoUrl/resolutions observe)
        // chapters 随每次发射一并取回, 避免与其它写入交错时丢失章节列表
        combine(
            shared.curChapterIndex, shared.chapterSize, shared.curChapterTitle,
            shared.loading, shared.error,
        ) { index, size, title, loading, error ->
            _state.value.copy(
                curChapterIndex = index,
                chapterSize = size,
                chapterTitle = title,
                loading = loading,
                error = error,
                chapters = shared.chapters,
            )
        }.onEach {
            _state.value = it
            // 章节列表换了才重算标题 (对照 Activity chapterListData.observe → upDisplayTitles)
            if (it.chapters !== lastTitledChapters) {
                lastTitledChapters = it.chapters
                upDisplayTitles(it.chapters)
            }
        }.launchIn(scope)

        // 分辨率列表 + 当前索引 → resolutionText/hasMultiResolution (对照 Activity resolutions.observe + updateResolutionText)
        combine(shared.resolutions, shared.videoSource) { resolutions, _ ->
            _state.value.copy(
                resolutions = resolutions,
                currentResolutionIndex = shared.currentResolutionIndex,
                hasMultiResolution = resolutions.size > 1,
                resolutionText = if (resolutions.size > 1) {
                    resolutions.getOrNull(shared.currentResolutionIndex)?.name
                } else null,
            )
        }.onEach { _state.value = it }.launchIn(scope)
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
                // 初始化书籍名 + 章节列表 (对照 Activity viewModel.initData + chapterListData.observe)
                _state.update { it.copy(bookName = event.book.name) }
                scope.launch {
                    val targetIndex = event.chapterIndex ?: event.book.durChapterIndex
                    val targetPos = event.chapterPos ?: 0
                    // 先写回 chapterIndex/chapterPos 到 Book + 落库 (对照 app initData override 分支)
                    shared.applyChapterOverride(event.book, targetIndex, targetPos)
                    // 再加载章节 (persistProgress=false 避免覆盖刚写入的 durChapterPos)
                    shared.initData(event.book, targetIndex, persistProgress = false)
                }
            }

            is VideoPlayUiEvent.UpdateChapters -> {
                // 外部已加载章节列表时直接注入 (对照 Activity 经 BaseReadViewModel.upBook 注入)
                _state.update {
                    it.copy(
                        chapterSize = event.chapters.size,
                        curChapterIndex = event.curIndex,
                        chapterTitle = event.chapters.getOrNull(event.curIndex)?.title
                            ?: it.chapterTitle,
                        chapters = event.chapters,
                    )
                }
            }

            is VideoPlayUiEvent.UpdateChapterIndex -> _state.update {
                it.copy(curChapterIndex = event.index)
            }

            is VideoPlayUiEvent.UpdateChapterTitle -> _state.update {
                it.copy(chapterTitle = event.title)
            }

            VideoPlayUiEvent.ShowLoading -> _state.update {
                it.copy(loading = true, error = null)
            }

            is VideoPlayUiEvent.ShowError -> _state.update {
                it.copy(loading = false, error = event.message)
            }

            VideoPlayUiEvent.HideLoading -> _state.update {
                it.copy(loading = false, error = null)
            }

            is VideoPlayUiEvent.UpdatePlaying -> _state.update {
                it.copy(isPlaying = event.isPlaying)
            }

            is VideoPlayUiEvent.UpdatePlaybackSpeed -> _state.update {
                it.copy(playbackSpeed = event.speed)
            }

            is VideoPlayUiEvent.UpdateControlsVisible -> _state.update {
                it.copy(controlsVisible = event.visible)
            }

            is VideoPlayUiEvent.UpdateInShelf -> _state.update {
                it.copy(inShelf = event.inShelf)
            }

            is VideoPlayUiEvent.UpdateFullScreen -> _state.update {
                it.copy(isFullScreen = event.isFullScreen)
            }

            is VideoPlayUiEvent.UpdatePlayWhenReady -> _state.update {
                it.copy(playWhenReady = event.playWhenReady)
            }

            is VideoPlayUiEvent.UpdatePlaybackState -> _state.update {
                it.copy(playbackState = event.playbackState)
            }

            is VideoPlayUiEvent.UpdateResolution -> _state.update {
                it.copy(
                    resolutionText = event.text,
                    hasMultiResolution = event.hasMulti,
                    currentResolutionIndex = event.index,
                    resolutions = event.resolutions,
                )
            }
        }
    }

    // ---- 章节切换 (委托 shared VM, 对照 Activity playPrevChapter/playNextChapter) ----

    fun onPrevChapter() {
        shared.moveToPrevChapter()
    }

    fun onNextChapter() {
        shared.moveToNextChapter()
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

    fun onSeekBack() = controller?.seekBack() ?: Unit
    fun onSeekForward() = controller?.seekForward() ?: Unit

    // ---- 菜单动作 (对照 Activity onCompatOptionsItemSelected / VideoTitleActions) ----

    /** 刷新当前章节 (对照 Activity refreshChapter: pause + viewModel.refreshChapter) */
    fun onRefreshChapter() {
        shared.refreshChapter(persistProgress = false)
    }

    /**
     * 上架/下架切换 (对照 Activity toggleShelf)。
     * 平台能力走 [PlatformCapabilityProviders.toggleBookshelf];
     * 完成回调更新 state.inShelf。
     */
    fun onToggleShelf() {
        val book = shared.curBook ?: return
        val inShelf = _state.value.inShelf
        runCatching {
            PlatformCapabilityProviders.getOrNull()?.toggleBookshelf(book, inShelf) { result ->
                if (result == true) {
                    _state.update { it.copy(inShelf = true) }
                } else if (result == false) {
                    _state.update { it.copy(inShelf = false) }
                }
            }
        }
    }

    /** 切换全屏 (对照 Activity toggleFullScreen: applyFullScreen(!isFullScreen))。
     *  平台专属 (system bar 显隐 + 横竖屏) 待 host 注入, 此处仅翻转 state 标记 */
    fun onToggleFullScreen() {
        val enabled = !_state.value.isFullScreen
        _state.update { it.copy(isFullScreen = enabled) }
        platform?.applyFullscreen(enabled)
    }

    fun setFullScreen(enabled: Boolean) {
        _state.update { it.copy(isFullScreen = enabled) }
        platform?.applyFullscreen(enabled)
    }

    /** 横竖屏互切 (对照 Activity toggleOrientationFullscreen: requestedOrientation 切换)。
     *  平台专属, 待 host 注入 */
    fun onToggleOrientationFullscreen() {
        platform?.toggleOrientation()
    }

    /** 登录 (对照 Activity showLogin: IntentData + showLoginDialog)。
     *  走平台能力 [PlatformCapabilityProviders.showBookSourceLogin] */
    fun onShowLogin() {
        val source = shared.curBookSource ?: return
        runCatching { PlatformCapabilityProviders.getOrNull()?.showBookSourceLogin(source) }
    }

    /** 复制播放地址 (对照 Activity copyPlayUrl: sendToClip)。
     *  走平台能力 [PlatformCapabilityProviders.copyToClipboard] */
    fun onCopyPlayUrl() {
        val url = shared.videoUrl.value?.url ?: return
        runCatching { PlatformCapabilityProviders.getOrNull()?.copyToClipboard(url) }
    }

    /** 源变量 (对照 Activity showSourceVariable: showSourceVariableDialog)。
     *  走平台能力 [PlatformCapabilityProviders.showBookSourceVariableDialog] */
    fun onShowSourceVariable() {
        val source = shared.curBookSource ?: return
        runCatching {
            PlatformCapabilityProviders.getOrNull()?.showBookSourceVariableDialog(source)
        }
    }

    /** 书籍变量 (对照 Activity showBookVariable: showBookVariableDialog)。
     *  走平台能力 [PlatformCapabilityProviders.showBookVariableDialog] */
    fun onShowBookVariable() {
        val book = shared.curBook ?: return
        runCatching { PlatformCapabilityProviders.getOrNull()?.showBookVariableDialog(book) }
    }

    /** 编辑书源 (对照 Activity editSource: sourceEditResult.launch)。
     *  Route 端走导航 push AppRoute.BookSourceEdit, 此处占位 */
    fun onEditSource() = Unit

    /** 打开书评 (对照 Activity openReview: viewModel.openCommentDialog)。
     *  Route 端走导航 push AppRoute.ReviewPost, 此处占位 */
    fun onOpenReview() = Unit

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

    /** 分辨率切换 (对照 Activity switchResolution: 重建 ExoPlayer + seekTo)。
     *  shared VM 仅更新 videoUrl State, UI 层订阅后自行重建播放器 */
    fun onSwitchResolution(index: Int) {
        shared.switchResolution(index)
    }

    /** 显示分辨率对话框 (对照 Activity showResolutionDialog: alert + singleChoiceItems)。
     *  shared 端 VideoPlayerScreenContent 已内置 ResolutionButton 弹窗, 此处占位 */
    fun onShowResolutionDialog() = Unit

    fun onPlayerState(
        isPlaying: Boolean? = null,
        playWhenReady: Boolean? = null,
        playbackState: Int? = null,
        playbackSpeed: Float? = null,
    ) {
        _state.update { current ->
            current.copy(
                isPlaying = isPlaying ?: current.isPlaying,
                playWhenReady = playWhenReady ?: current.playWhenReady,
                playbackState = playbackState ?: current.playbackState,
                playbackSpeed = playbackSpeed ?: current.playbackSpeed,
            )
        }
    }

    /**
     * 退出时保存真实进度 + 释放播放器 (对照 app `VideoPlayActivity.onPause` + `onDestroy`)。
     *
     * 由宿主 [io.legado.app.ui.route.VideoPlayRoute] DisposableEffect.onDispose 调用,
     * [onCleared] 兜底调用 (防 DisposableEffect 未触发)。先取播放器真实位置保存,
     * 再释放 controller (位置读取后才有意义)。
     */
    fun onExit() {
        if (exited) return
        exited = true
        val pos = controller?.positionMs ?: 0L
        val dur = controller?.durationMs?.takeIf { it > 0 } ?: 0L
        // 保存进度: Preference 视频位置 + books 表 durChapterPos + WebDav 上传
        shared.onExit(pos, dur)
    }

    override fun onCleared() {
        // 先保存进度 (controller 释放前取真实位置; onExit 已执行则跳过)
        runCatching { onExit() }
        // 再释放播放器 (对照 app onDestroy: player.release)
        controller?.release()
        platform?.applyFullscreen(false)
        scope.cancel()
    }
}

/** 视频播放页 UI 状态 (章节 + 加载/错误 + 播放控制回显 + 屏幕状态)。 */
data class VideoPlayUiState(
    val bookName: String = "",
    val chapterTitle: String = "",
    val curChapterIndex: Int = 0,
    val chapterSize: Int = 0,
    val loading: Boolean = false,
    val error: String? = null,
    val isPlaying: Boolean = false,
    val playbackSpeed: Float = 1f,
    val controlsVisible: Boolean = false,
    /** 是否在书架中 (对照 Activity inShelf) */
    val inShelf: Boolean = false,
    /** 是否全屏 (对照 Activity isFullScreen) */
    val isFullScreen: Boolean = false,
    /** 播放就绪态 (对照 Activity playWhenReady) */
    val playWhenReady: Boolean = false,
    /** 播放器状态 (对照 Activity playbackState, Player.STATE_*) */
    val playbackState: Int = 0,
    /** 强制分辨率钮回显文字, null 时用资源 resolution (对照 Activity resolutionText) */
    val resolutionText: String? = null,
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
)

/** 视频播放页事件, 由宿主 (app Activity / desktop Window) 推入。 */
sealed interface VideoPlayUiEvent {
    /** 书籍数据更新 (对齐 VideoPlayActivity.titleText/durChapterIndex 初始化)。
     *  chapterIndex/chapterPos 用于书签/目录回传定位 (对照 AudioPlayUiEvent.Init) */
    data class ShowBook(
        val book: Book,
        val chapterIndex: Int? = null,
        val chapterPos: Int? = null,
    ) : VideoPlayUiEvent

    /** 章节列表 + 当前索引更新 (对齐 chapterListData observe) */
    data class UpdateChapters(val chapters: List<BookChapter>, val curIndex: Int) : VideoPlayUiEvent

    /** 当前章节索引更新 (对齐 playPrevChapter/playNextChapter) */
    data class UpdateChapterIndex(val index: Int) : VideoPlayUiEvent

    /** 当前章节标题更新 */
    data class UpdateChapterTitle(val title: String) : VideoPlayUiEvent

    /** 显示加载中 */
    object ShowLoading : VideoPlayUiEvent

    /** 显示错误 */
    data class ShowError(val message: String) : VideoPlayUiEvent

    /** 隐藏加载/错误态 */
    object HideLoading : VideoPlayUiEvent

    /** 播放状态更新 (对齐 onIsPlayingChanged) */
    data class UpdatePlaying(val isPlaying: Boolean) : VideoPlayUiEvent

    /** 倍速更新 (对齐 onPlaybackParametersChanged) */
    data class UpdatePlaybackSpeed(val speed: Float) : VideoPlayUiEvent

    /** 控制层显隐更新 */
    data class UpdateControlsVisible(val visible: Boolean) : VideoPlayUiEvent

    /** 书架状态更新 (对齐 Activity inShelf) */
    data class UpdateInShelf(val inShelf: Boolean) : VideoPlayUiEvent

    /** 全屏状态更新 (对齐 Activity isFullScreen) */
    data class UpdateFullScreen(val isFullScreen: Boolean) : VideoPlayUiEvent

    /** 播放就绪态更新 (对齐 onPlayWhenReadyChanged) */
    data class UpdatePlayWhenReady(val playWhenReady: Boolean) : VideoPlayUiEvent

    /** 播放器状态更新 (对齐 onPlaybackStateChanged) */
    data class UpdatePlaybackState(val playbackState: Int) : VideoPlayUiEvent

    /** 分辨率信息更新 (对齐 Activity resolutions.observe + updateResolutionText) */
    data class UpdateResolution(
        val text: String?,
        val hasMulti: Boolean,
        val index: Int,
        val resolutions: List<VideoResolution>,
    ) : VideoPlayUiEvent
}
