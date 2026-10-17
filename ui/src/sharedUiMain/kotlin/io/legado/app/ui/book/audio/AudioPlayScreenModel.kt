package io.legado.app.ui.book.audio

import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import io.legado.app.constant.AppLog
import io.legado.app.constant.EventBus
import io.legado.app.constant.Status
import io.legado.app.data.entities.Book
import io.legado.app.data.entities.BookChapter
import io.legado.app.data.entities.BookSource
import io.legado.app.help.AppWebDavShared
import io.legado.app.help.book.BookChapterLoader
import io.legado.app.help.book.isNotShelf
import io.legado.app.help.book.simulatedTotalChapterNum
import io.legado.app.help.coroutine.Coroutine
import io.legado.app.help.toast.Toasters
import io.legado.app.model.AudioPlayShared
import io.legado.app.model.Lrc
import io.legado.app.model.audioCoverFallback
import io.legado.app.ui.root.BookRef
import io.legado.app.ui.root.ScreenModel
import io.legado.app.ui.root.asBook
import io.legado.app.ui.root.screenModelScope
import io.legado.app.utils.FlowBus
import io.legado.app.utils.postEvent
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.concurrent.Volatile

/**
 * 音频播放页 shared ScreenModel。
 *
 * 下沉自 app 端 [AudioPlayActivity] 的状态持有与命令派发:
 * - 播放状态 (title/subTitle/coverUrl/progressMs/durationMs/isPlaying/playMode 等) 由本类托管,
 *   通过订阅 [FlowBus] sticky 事件更新 (对照 Activity.observeLiveBus)
 * - 播放命令 (togglePlay/prev/next/seek/setTimer/setSpeed/changePlayMode) 通过 [dispatch]
 *   派发到 [AudioPlayShared], 由其经 AudioPlayCommander 转发到平台 Service/Player
 *
 * 平台专属部分 (coverSlot/blurBgSlot/lrcSlot/timerDialogSlot/speedDialogSlot/titleBarTrailingSlot)
 * 由 [AudioPlayRoute] 注入占位实现, 各端宿主可按需覆盖 (对照 [AudioPlayScreenContent] 参数)。
 *
 * 模式参考 [io.legado.app.ui.book.read.review.ReviewPostScreenModel]。
 */
interface AudioPlayPlatformProvider {
    @Composable
    fun Content(
        state: AudioPlayUiState,
        onBack: () -> Unit,
        onOpenChangeSource: () -> Unit,
        onOpenToc: () -> Unit,
        onOpenBookSourceEdit: (String) -> Unit,
        onOpenReview: () -> Unit,
        overflowActions: AudioPlayOverflowActions,
        onEvent: (AudioPlayUiEvent) -> Unit,
        /** 宽屏右侧面板 (0=窄屏不启用; 见 [AudioPlaySidePanelKind])。 */
        sidePanelWidth: Dp = 0.dp,
        sidePanelVisible: Boolean = false,
        sidePanelKind: AudioPlaySidePanelKind? = null,
        sidePanelSlot: @Composable (AudioPlaySidePanelKind) -> Unit = {},
        /** 点击左侧内容区空白处时回调 (面板打开时点击外部关闭; 窄屏端不传)。 */
        onTapOutsideSidePanel: (() -> Unit)? = null,
        /** 封面长按: 浏览全屏大图 (由 Route 经 navigator.showOverlay 驱动)。 */
        onOpenCover: () -> Unit = {},
    )
}

/**
 * 溢出菜单动作集合 (下沉自 app 端 AudioOverflowMenu)。
 *
 * 各端宿主不再自行实现溢出菜单, 统一由 shared [AudioPlayScreenContent] 渲染,
 * 通过本接口注入回调。
 */
data class AudioPlayOverflowActions(
    val onLogin: () -> Unit,
    val onCopyAudioUrl: () -> Unit,
    val onSetSourceVariable: () -> Unit,
    val onSetBookVariable: () -> Unit,
    val onEditBookSource: () -> Unit,
    val onAddBookmark: () -> Unit,
    val onShowAppLog: () -> Unit,
    /** 是否显示登录项 (取自页面状态, 随书源就绪刷新) */
    val hasLogin: Boolean,
    /** 动态辉光开关是否显示 (仅 Android: 与 DSP offload 互斥) */
    val showGlowToggle: Boolean = false,
    /** 动态辉光当前是否开启 */
    val glowEnabled: Boolean = false,
    /** 切换动态辉光 */
    val onToggleGlow: () -> Unit = {},
)

object AudioPlayPlatformProviders {
    @Volatile
    private var impl: AudioPlayPlatformProvider? = null

    fun register(provider: AudioPlayPlatformProvider) {
        impl = provider
    }

    fun getOrNull(): AudioPlayPlatformProvider? = impl
}

class AudioPlayScreenModel : ScreenModel {

    private companion object {
        /** 目录回源拉取超时 (防止挂起阻塞初始化)。 */
        const val DIRECTORY_FETCH_TIMEOUT_MS = 30_000L
    }

    // 自管 scope (ScreenModelStore 调 onCleared 时取消); 异常兜底见 screenModelScope
    private val scope = screenModelScope("音频播放") {
        postEvent(EventBus.AUDIO_LOADING, false)
    }

    private val _state = MutableStateFlow(AudioPlayUiState())
    val state: StateFlow<AudioPlayUiState> = _state.asStateFlow()

    init {
        observeAudioEvents()
    }

    /** 订阅音频播放 sticky 事件 */
    private fun observeAudioEvents() {
        // 播放状态: PLAY/PAUSE/STOP/LOADING。只驱动界面显示 —— 共享播放状态由会话写
        // (事件与共享字段两个真源会互相覆盖: sticky 重放的旧事件能把当前状态改回去)
        scope.launch {
            FlowBus.withSticky(EventBus.AUDIO_STATE).collect { value ->
                if (value is Int) {
                    _state.update { it.copy(isPlaying = value == Status.PLAY) }
                }
            }
        }
        // 副标题 (章节名) + 上下章可用性
        scope.launch {
            FlowBus.withSticky(EventBus.AUDIO_SUB_TITLE).collect { value ->
                if (value is String) {
                    _state.update {
                        it.copy(
                            subTitle = value,
                            prevEnabled = AudioPlayShared.durChapterIndex > 0,
                            nextEnabled = AudioPlayShared.durChapterIndex <
                                AudioPlayShared.simulatedChapterSize - 1,
                        )
                    }
                }
            }
        }
        // 总时长
        scope.launch {
            FlowBus.withSticky(EventBus.AUDIO_SIZE).collect { value ->
                if (value is Int) _state.update { it.copy(durationMs = value) }
            }
        }
        // 播放进度
        scope.launch {
            FlowBus.withSticky(EventBus.AUDIO_PROGRESS).collect { value ->
                if (value is Int) _state.update { it.copy(progressMs = value) }
            }
        }
        // 缓冲进度
        scope.launch {
            FlowBus.withSticky(EventBus.AUDIO_BUFFER_PROGRESS).collect { value ->
                if (value is Int) _state.update { it.copy(bufferMs = value) }
            }
        }
        // 播放速率
        scope.launch {
            FlowBus.withSticky(EventBus.AUDIO_SPEED).collect { value ->
                if (value is Float) _state.update { it.copy(speed = value) }
            }
        }
        // 定时分钟
        scope.launch {
            FlowBus.withSticky(EventBus.AUDIO_DS).collect { value ->
                if (value is Int) _state.update { it.copy(timerMinute = value) }
            }
        }
        // 加载中
        scope.launch {
            FlowBus.withSticky(EventBus.AUDIO_LOADING).collect { value ->
                if (value is Boolean) _state.update { it.copy(loading = value) }
            }
        }
        // 封面 URL
        scope.launch {
            FlowBus.withSticky(EventBus.AUDIO_COVER).collect { value ->
                if (value is String) _state.update { it.copy(coverUrl = value) }
            }
        }
        // 歌词数据 (StateFlow: 订阅即拿当前值, 不经事件总线, 没有类型擦除与陈旧重放)。
        // 当前高亮行不在此处托管 —— 它是 (歌词, 播放位置) 的派生量, 由 rememberLrcIndex 自适应精准延时调度。
        scope.launch {
            AudioPlayShared.durLrc.collect { lrc ->
                _state.update { it.copy(lrc = lrc) }
            }
        }
        // 播放模式
        scope.launch {
            FlowBus.withSticky(EventBus.PLAY_MODE_CHANGED).collect { value ->
                if (value is AudioPlayShared.PlayMode) {
                    _state.update { it.copy(playMode = value) }
                }
            }
        }
        // 媒体按钮: 收到 true 触发播放/暂停切换 (对照 Activity.observeLiveBus MEDIA_BUTTON)
        scope.launch {
            FlowBus.with(EventBus.MEDIA_BUTTON).collect { value ->
                if (value is Boolean && value) dispatch(AudioPlayUiEvent.TogglePlay)
            }
        }
    }

    fun dispatch(event: AudioPlayUiEvent) {
        when (event) {
            is AudioPlayUiEvent.Init -> {
                // 对照原版 AudioPlayViewModel.initData: upBook(挂起等目录齐, 在架且来自搜索/发现
                // 列表的书并回库里那本) → 设 chapterList → upData/resetData →
                // if (status == STOP) loadOrUpPlayUrl。
                // 目录必须先于 resetData 就位, 否则 upDurChapter 拿不到 durChapter,
                // AudioPlayManager.loadPlayUrl 会静默 return 而界面死在"数据加载中"。
                scope.launch {
                    val incoming = event.book.asBook()
                    val isSearchBook = event.book is BookRef.Search
                    // 书源按装载同一口径对账后取 (同一本书、同一次查询): 登录/评论入口进入页面
                    // 即可见, 且在“搜索书并回异源书架行”时不会指向搜索源
                    val shelfBook = BookChapterLoader.resolveShelfBook(incoming, isSearchBook)
                    val preResolvedSource: BookSource? = AudioPlayShared.bookSourceOf(shelfBook)
                    AudioPlayShared.bookSource = preResolvedSource
                    _state.update { it.withSourceEntries() }
                    val payload = runCatching {
                        withTimeoutOrNull(DIRECTORY_FETCH_TIMEOUT_MS) {
                            BookChapterLoader.upBook(
                                incoming,
                                isSearchBook = isSearchBook,
                            )
                        }
                    }.onFailure {
                        AppLog.put("加载书籍失败\n${it.message}", it)
                    }.getOrNull()
                    val book = payload?.book
                        ?: BookChapterLoader.resolveShelfBook(incoming, isSearchBook)
                    // 采用装载流程给出的书源 (与目录装载同一次查询)
                    val loadedSource = payload?.source ?: preResolvedSource
                    AudioPlayShared.inBookshelf = !book.isNotShelf
                    val list = payload?.chapterList.orEmpty()
                    AudioPlayShared.chapterList = list.ifEmpty { null }
                    if (list.isNotEmpty()) AudioPlayShared.updateChapterList(list)
                    if (AudioPlayShared.book?.bookUrl == book.bookUrl) {
                        AudioPlayShared.upData(book, loadedSource)
                    } else {
                        AudioPlayShared.resetData(book, loadedSource)
                        // 新书立即以书籍默认封面占位, 覆盖 sticky 旧书封面残留; 就绪后 musicCover 再覆盖
                        book.audioCoverFallback()?.let {
                            postEvent(EventBus.AUDIO_COVER, it)
                        }
                    }
                    _state.update {
                        it.copy(
                            title = book.name,
                            inShelf = AudioPlayShared.inBookshelf,
                            prevEnabled = AudioPlayShared.durChapterIndex > 0,
                            nextEnabled = AudioPlayShared.durChapterIndex <
                                AudioPlayShared.simulatedChapterSize - 1,
                        ).withSourceEntries()
                    }
                    // 清理残留 LOADING=true (未在加载时转圈不该转)
                    if (AudioPlayShared.status == Status.STOP) {
                        postEvent(EventBus.AUDIO_LOADING, false)
                    }
                    if (list.isEmpty()) {
                        // 对照原版 loadChapterList 失败分支 toastOnUi(error_get_chapter_list)
                        Toasters.get().toast("获取目录失败")
                    }
                    if (AudioPlayShared.status == Status.STOP) {
                        AudioPlayShared.loadOrUpPlayUrl()
                    }
                    // 同步云端进度
                    if (AudioPlayShared.inBookshelf) {
                        scope.launch {
                            AppWebDavShared.syncProgress(
                                book = book,
                                manual = false,
                                onNewProgress = { progress ->
                                    if (progress.durChapterIndex < book.simulatedTotalChapterNum()) {
                                        AudioPlayShared.setProgress(progress)
                                        Toasters.get().toast("已同步最新音频播放进度")
                                    }
                                }
                            )
                        }
                    }
                    // 书签跳转 (对照原版 AudioPlayActivity.applyBookmarkPosition, 在 initData
                    // 的 onFinally 里执行, 此时目录已齐)
                    val targetIndex = event.chapterIndex ?: -1
                    if (targetIndex >= 0) {
                        val targetPos = event.chapterPos ?: 0
                        when {
                            targetIndex != AudioPlayShared.durChapterIndex ->
                                AudioPlayShared.skipTo(targetIndex, targetPos)

                            targetPos != AudioPlayShared.durChapterPos ->
                                AudioPlayShared.adjustProgress(targetPos)
                        }
                    }
                }
            }

            AudioPlayUiEvent.TogglePlay -> {
                // 对照 viewModel.togglePlay: PLAY→pause / PAUSE→resume / else→loadOrUpPlayUrl
                when (AudioPlayShared.status) {
                    Status.PLAY -> AudioPlayShared.pause()
                    Status.PAUSE -> AudioPlayShared.resume()
                    else -> AudioPlayShared.loadOrUpPlayUrl()
                }
            }

            AudioPlayUiEvent.Prev -> AudioPlayShared.prev()

            AudioPlayUiEvent.Next -> AudioPlayShared.next()

            AudioPlayUiEvent.ChangePlayMode -> AudioPlayShared.changePlayMode()

            is AudioPlayUiEvent.Seek -> AudioPlayShared.adjustProgress(event.positionMs)

            is AudioPlayUiEvent.SetTimer -> AudioPlayShared.setTimer(event.minute)

            is AudioPlayUiEvent.SetSpeed -> AudioPlayShared.adjustSpeed(event.speed)

            // adjustProgress + PAUSE 时 resume
            is AudioPlayUiEvent.LrcClick -> {
                AudioPlayShared.adjustProgress(event.time)
                if (AudioPlayShared.status == Status.PAUSE) AudioPlayShared.resume()
                // 跳转目标随跳转信号一并发布, 歌词调度当帧即按目标位置重定位
            }

            AudioPlayUiEvent.CoverClick -> _state.update { it.copy(coverVisible = false) }

            // 换源后书源已切换, 登录/评论入口显隐一并刷新
            is AudioPlayUiEvent.UpdateInShelf -> _state.update {
                it.copy(inShelf = event.inShelf).withSourceEntries()
            }
        }
    }

    /**
     * 书源相关入口显隐 (登录/评论) 同源计算: 两处一起随当前书源刷新。
     */
    private fun AudioPlayUiState.withSourceEntries(): AudioPlayUiState {
        val source = AudioPlayShared.bookSource
        return copy(
            hasLogin = source?.hasLogin() == true,
            hasReview = source?.reviewRule?.reviewUrl.isNullOrBlank() == false,
        )
    }

    /**
     * 确保目录就绪 (打开目录弹窗前调用; 内存 → 本地库 → 回源), 与装载入口的目录顺序一致。
     */
    suspend fun ensureChapterList(book: Book): List<BookChapter> {
        val list = withTimeoutOrNull(DIRECTORY_FETCH_TIMEOUT_MS) {
            runCatching {
                BookChapterLoader.loadChapterList(
                    book,
                    AudioPlayShared.bookSourceOf(book),
                )
            }.getOrDefault(emptyList())
        }.orEmpty()
        AudioPlayShared.chapterList = list.ifEmpty { null }
        if (list.isNotEmpty()) AudioPlayShared.updateChapterList(list)
        return list
    }

    override fun onPreRemoved() {
        // 导航 pop 动画开始前先落库音频进度 (对照原版返回键按下即保存): 不等动画播完后的
        // retain → onCleared; 音频后台继续播时此处存的是退出界面瞬间进度, onCleared 的
        // saveRead 幂等再存一次无害, 书架 150ms 兜底重查读到的已是新进度
        if (AudioPlayShared.inBookshelf) {
            AudioPlayShared.book?.let { AudioPlayShared.saveRead() }
        }
    }

    override fun onCleared() {
        // status != PLAY 即停止 (含 LOADING, 否则后台继续加载可能自动开播)
        val status = AudioPlayShared.status
        if (status != Status.PLAY) {
            AudioPlayShared.stop()
        }
        // 在书架时上传进度到 WebDav (走进程级 scope, 不随 VM 取消)
        if (AudioPlayShared.inBookshelf) {
            AudioPlayShared.book?.let { book ->
                Coroutine.async {
                    AudioPlayShared.saveRead()
                    AppWebDavShared.syncProgress(book = book, manual = false)
                }.onError {
                    AppLog.put("上传音频进度失败\n${it.message}", it)
                }
            }
        }
        scope.cancel()
    }
}

/**
 * 音频播放页 UI 状态 (对照 [AudioPlayScreenContent] 同名参数)。
 *
 * 歌词只托管数据 ([lrc]); 当前高亮行由 [rememberLrcIndex] 自适应精准延时派生, 配色由
 * [rememberLrcColors] 从封面取色, 都不进本状态。
 */
data class AudioPlayUiState(
    val title: String = "",
    val subTitle: String = "",
    val coverUrl: String? = null,
    val coverVisible: Boolean = true,
    val timerMinute: Int = 0,
    val speed: Float = 1f,
    val progressMs: Int = 0,
    val durationMs: Int = 0,
    val bufferMs: Int = 0,
    val isPlaying: Boolean = false,
    val loading: Boolean = false,
    val playMode: AudioPlayShared.PlayMode = AudioPlayShared.PlayMode.LIST_END_STOP,
    val prevEnabled: Boolean = true,
    val nextEnabled: Boolean = true,
    val lrc: Lrc? = null,
    /** 是否在书架中 (退出时若 false 弹加书架确认) */
    val inShelf: Boolean = true,
    /** 书源是否配置了评论规则 (reviewUrl 判空, 决定右上角评论入口显隐) */
    val hasReview: Boolean = false,
    /** 书源是否配置了登录入口 (loginUrl/loginUi 非空, 决定右上角登录项显隐) */
    val hasLogin: Boolean = false,
)

/** 音频播放 UI 事件 (对照 [AudioPlayScreenContent] onXxx 回调) */
sealed interface AudioPlayUiEvent {
    /** 初始化书籍 (设置标题, 可携带书签跳转 chapterIndex + chapterPos) */
    data class Init(
        val book: BookRef,
        val chapterIndex: Int? = null,
        val chapterPos: Int? = null,
    ) : AudioPlayUiEvent

    /** 播放/暂停切换 (对照 onTogglePlay) */
    object TogglePlay : AudioPlayUiEvent

    /** 上一章 (对照 onPrev) */
    object Prev : AudioPlayUiEvent

    /** 下一章 (对照 onNext) */
    object Next : AudioPlayUiEvent

    /** 切换播放模式 (对照 onChangePlayMode) */
    object ChangePlayMode : AudioPlayUiEvent

    /** 进度跳转 (对照 onSeek) */
    data class Seek(val positionMs: Int) : AudioPlayUiEvent

    /** 设定定时 (对照 onSetTimer) */
    data class SetTimer(val minute: Int) : AudioPlayUiEvent

    /** 设定倍速 (对照 onSetSpeed) */
    data class SetSpeed(val speed: Float) : AudioPlayUiEvent

    /** 歌词点击跳播 (对照 onLrcClick: adjustProgress + PAUSE 时 resume) */
    data class LrcClick(val time: Int) : AudioPlayUiEvent

    /** 封面点击隐藏 (对照 onCoverClick) */
    object CoverClick : AudioPlayUiEvent

    /** 更新书架状态 (对照 Activity 上架/下架后回写 inBookshelf) */
    data class UpdateInShelf(val inShelf: Boolean) : AudioPlayUiEvent
}
