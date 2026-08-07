package io.legado.app.ui.book.audio

import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import io.legado.app.constant.AppLog
import io.legado.app.constant.EventBus
import io.legado.app.constant.Status
import io.legado.app.data.AppDbProviders
import io.legado.app.data.entities.Book
import io.legado.app.help.AppWebDavShared
import io.legado.app.help.book.isNotShelf
import io.legado.app.help.book.simulatedTotalChapterNum
import io.legado.app.help.config.AppConfigProviders
import io.legado.app.help.coroutine.Coroutine
import io.legado.app.help.toast.Toasters
import io.legado.app.model.AudioPlayBookBridges
import io.legado.app.model.AudioPlayShared
import io.legado.app.ui.book.read.fetchChapterListFromSource
import io.legado.app.ui.root.ScreenModel
import io.legado.app.utils.FlowBus
import io.legado.app.utils.postEvent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
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
    /** 是否显示登录项 (对照 source?.hasLogin()) */
    val hasLogin: Boolean,
    /** 唤醒锁切换 (Android 专属, null=不显示; 对照 AppConfig.audioPlayUseWakeLock) */
    val onToggleWakeLock: (() -> Unit)? = null,
    /** 原版 audio_play.xml 无"浏览器打开"菜单项, 菜单已移除; 仅留形参兼容 app 端调用点 */
    val onOpenAudioUrl: () -> Unit = {},
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

        /** 进程级目录拉取去重 (按 bookUrl): 并发重复进页面/重复 Init 不重复拉取与 toast */
        @Volatile
        var fetchingBookUrl: String? = null
    }

    // 自管 scope (ScreenModelStore 调 onCleared 时取消)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    /** 目录缺失时待重放的书签跳转 (targetIndex to targetPos), 后台目录到货后消费 */
    private var pendingBookmarkJump: Pair<Int, Int>? = null

    private val _state = MutableStateFlow(AudioPlayUiState())
    val state: StateFlow<AudioPlayUiState> = _state.asStateFlow()

    init {
        observeAudioEvents()
    }

    /** 订阅音频播放 sticky 事件 */
    private fun observeAudioEvents() {
        // 播放状态: PLAY/PAUSE/STOP/LOADING
        scope.launch {
            FlowBus.withSticky(EventBus.AUDIO_STATE).collect { value ->
                if (value is Int) {
                    AudioPlayShared.status = value
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
        // 歌词数据
        scope.launch {
            FlowBus.withSticky(EventBus.AUDIO_LRC).collect { value ->
                @Suppress("UNCHECKED_CAST")
                if (value is List<*>) {
                    _state.update { it.copy(lrcData = value as List<Pair<Int, String>>) }
                }
            }
        }
        // 歌词滚动进度。ScreenModel 生命周期与路由绑定，页面出栈后自动取消收集。
        scope.launch {
            FlowBus.withSticky(EventBus.AUDIO_LRCPROGRESS).collect { value ->
                if (value is Int) _state.update { it.copy(lrcProgress = value) }
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
                // upBook → upData/resetData → loadOrUpPlayUrl
                scope.launch {
                    AudioPlayShared.inBookshelf = !event.book.isNotShelf
                    val book = event.book
                    // 目录: DB 有则直接用; 缺失不阻塞初始化 (后台回源拉取, 超时+去重+失败 toast),
                    // 到货后若未在播则补触发加载
                    val dbList = runCatching {
                        AppDbProviders.get().bookChapterDao.getChapterList(book.bookUrl)
                    }.getOrDefault(emptyList())
                    AudioPlayShared.chapterList = if (dbList.isNotEmpty()) dbList else null
                    if (AudioPlayShared.book?.bookUrl == book.bookUrl) {
                        AudioPlayShared.upData(book)
                    } else {
                        AudioPlayShared.resetData(book)
                        // 新书立即以书籍默认封面占位, 覆盖 sticky 旧书封面残留; 就绪后 musicCover 再覆盖
                        book.getDisplayCover()?.takeIf { it.isNotBlank() }?.let {
                            postEvent(EventBus.AUDIO_COVER, it)
                        }
                    }
                    _state.update {
                        it.copy(
                            title = event.book.name,
                            inShelf = AudioPlayShared.inBookshelf,
                            hasReview = AudioPlayShared.bookSource?.reviewRule
                                ?.reviewUrl.isNullOrBlank() == false,
                        )
                    }
                    // 清理残留 LOADING=true (未在加载时转圈不该转)
                    if (AudioPlayShared.status == Status.STOP) {
                        postEvent(EventBus.AUDIO_LOADING, false)
                    }
                    if (AudioPlayShared.status == Status.STOP) {
                        AudioPlayShared.loadOrUpPlayUrl()
                    }
                    // 目录缺失: 后台回源拉取 (超时保护 + 进程级按书去重), 成功后补触发加载
                    if (dbList.isEmpty() && fetchingBookUrl != book.bookUrl) {
                        fetchingBookUrl = book.bookUrl
                        scope.launch {
                            val list = try {
                                withTimeoutOrNull(DIRECTORY_FETCH_TIMEOUT_MS) {
                                    fetchChapterListFromSource(
                                        book,
                                        AudioPlayBookBridges.get().getBookSource(book),
                                    )
                                }.orEmpty()
                            } finally {
                                if (fetchingBookUrl == book.bookUrl) fetchingBookUrl = null
                            }
                            if (list.isNotEmpty()) {
                                // 目录就绪: 同步章节计数 (否则 simulatedChapterSize 恒为 0,
                                // 下一章按钮被禁用且 skipTo 边界拦截书签跳转, 见 AudioPlayShared.updateChapterList)
                                AudioPlayShared.updateChapterList(list)
                                // 计数就绪后刷新上下章按钮可用性 (对齐原版: 目录就绪时按钮即可用)
                                _state.update {
                                    it.copy(
                                        prevEnabled = AudioPlayShared.durChapterIndex > 0,
                                        nextEnabled = AudioPlayShared.durChapterIndex <
                                            AudioPlayShared.simulatedChapterSize - 1,
                                    )
                                }
                                // 目录到货后重放书签跳转 (此前 skipTo 被空目录边界拦截)
                                pendingBookmarkJump?.let { (idx, pos) ->
                                    pendingBookmarkJump = null
                                    when {
                                        idx != AudioPlayShared.durChapterIndex ->
                                            AudioPlayShared.skipTo(idx, pos)

                                        pos != AudioPlayShared.durChapterPos ->
                                            AudioPlayShared.adjustProgress(pos)
                                    }
                                }
                                if (AudioPlayShared.status == Status.STOP) {
                                    AudioPlayShared.loadOrUpPlayUrl()
                                }
                            } else if (AudioPlayShared.status == Status.STOP) {
                                Toasters.get().toast("获取目录失败")
                            }
                        }
                    }
                    // 初始同步已加载的歌词 (切回页面时 lrc 可能已在 Service 端就绪)
                    AudioPlayShared.durLrcData?.takeIf { it.isNotEmpty() }?.let { lrc ->
                        _state.update { it.copy(lrcData = lrc) }
                    }
                    // 拉取云端进度, 较新且未越界才自动应用; 相等忽略; 失败记日志
                    if (AudioPlayShared.inBookshelf && AppConfigProviders.get().syncBookProgress) {
                        scope.launch {
                            runCatching {
                                val progress = AppWebDavShared.getBookProgress(book)
                                    ?: return@launch
                                val newer = progress.durChapterIndex > book.durChapterIndex ||
                                    (progress.durChapterIndex == book.durChapterIndex &&
                                        progress.durChapterPos > book.durChapterPos)
                                // 云端较新且未越界才自动应用
                                if (newer &&
                                    progress.durChapterIndex < book.simulatedTotalChapterNum()
                                ) {
                                    AudioPlayShared.setProgress(progress)
                                    Toasters.get().toast("已同步最新音频播放进度")
                                }
                            }.onFailure {
                                AppLog.put("拉取阅读进度失败《${book.name}》\n${it.message}", it)
                            }
                        }
                    }
                    // 书签跳转; 目录缺失时记 pending, 到货后重放
                    val targetIndex = event.chapterIndex ?: -1
                    if (targetIndex >= 0) {
                        val targetPos = event.chapterPos ?: 0
                        if (AudioPlayShared.chapterList == null) {
                            pendingBookmarkJump = targetIndex to targetPos
                        } else {
                            when {
                                targetIndex != AudioPlayShared.durChapterIndex ->
                                    AudioPlayShared.skipTo(targetIndex, targetPos)

                                targetPos != AudioPlayShared.durChapterPos ->
                                    AudioPlayShared.adjustProgress(targetPos)
                            }
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
                // 立即高亮 (不等 Service 回发事件)
                _state.update {
                    val line = it.lrcData?.indexOfLast { pair -> pair.first <= event.time } ?: -1
                    it.copy(lrcProgress = line)
                }
            }

            AudioPlayUiEvent.CoverClick -> _state.update { it.copy(coverVisible = false) }

            is AudioPlayUiEvent.UpdateInShelf -> _state.update {
                it.copy(
                    inShelf = event.inShelf,
                    // 换源后书源已切换, 评论入口显隐一并刷新
                    hasReview = AudioPlayShared.bookSource?.reviewRule
                        ?.reviewUrl.isNullOrBlank() == false,
                )
            }
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
                    if (AppConfigProviders.get().syncBookProgress) {
                        AppWebDavShared.uploadBookProgress(book)
                    }
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
 * lrcData/lrcProgress/lrcColors 由平台 lrcSlot/blurBgSlot 内部管理, 不在此处托管
 * (依赖平台自绘 LrcView + Bitmap.getRepresentativeColor)。
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
    val lrcData: List<Pair<Int, String>>? = null,
    val lrcProgress: Int = -1,
    /** 是否在书架中 (退出时若 false 弹加书架确认) */
    val inShelf: Boolean = true,
    /** 书源是否配置了评论规则 (reviewUrl 判空, 决定右上角评论入口显隐) */
    val hasReview: Boolean = false,
)

/** 音频播放 UI 事件 (对照 [AudioPlayScreenContent] onXxx 回调) */
sealed interface AudioPlayUiEvent {
    /** 初始化书籍 (设置标题, 可携带书签跳转 chapterIndex + chapterPos) */
    data class Init(
        val book: Book,
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
