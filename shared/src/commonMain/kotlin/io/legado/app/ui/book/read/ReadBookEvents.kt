package io.legado.app.ui.book.read

import io.legado.app.data.entities.Book
import io.legado.app.data.entities.BookProgress
import io.legado.app.ui.book.read.ReadBookEvents.configChange
import io.legado.app.ui.book.read.ReadBookEvents.newProgressConfirm
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow

// ReadConfigChange enum 已在 shared/commonMain (本文件同包)。

/**
 * read/ 内部事件枢纽。行为对齐 FlowBus：默认非粘性、缓冲 64、DROP_OLDEST、无订阅者时丢弃；
 * 原版同步直调的回调类事件（menuRefresh/loadChapterList/newProgressConfirm）用 replay=1 防漏发。
 * 外部 EventBus 广播（服务/接收器/搜索页）由 ReadBookActivity 单一入口桥接转发到此。
 *
 * 从 app 端下沉到 shared/commonMain：object 本体仅依赖 kotlinx.coroutines.flow + commonMain
 * 已下沉的 Book/BookProgress/ReadConfigChange，无 Android 依赖。生命周期感知的 observe 扩展
 * （依赖 androidx.lifecycle.LifecycleOwner）仍留 app 端 ReadBookEventsObserve.kt。
 *
 * iOS/桌面/鸿蒙端可通过 [configChange].collect { ... } 在 Compose LaunchedEffect 中直接收集。
 */
object ReadBookEvents {

    private fun <T> eventFlow(replay: Int = 0) = MutableSharedFlow<T>(
        replay = replay, extraBufferCapacity = 64, onBufferOverflow = BufferOverflow.DROP_OLDEST
    )

    /** 渲染层配置刷新，按 list 顺序处理（原 EventBus.UP_CONFIG） */
    private val _configChange = eventFlow<List<ReadConfigChange>>()
    val configChange: SharedFlow<List<ReadConfigChange>> get() = _configChange

    /** 阅读菜单/顶栏重建（原 EventBus.UPDATE_READ_ACTION_BAR → readMenu.reset()） */
    private val _actionBarChange = eventFlow<Unit>()
    val actionBarChange: SharedFlow<Unit> get() = _actionBarChange

    /** 进度条行为变更（原 EventBus.UP_SEEK_BAR → readMenu.upSeekBar()） */
    private val _seekBarChange = eventFlow<Unit>()
    val seekBarChange: SharedFlow<Unit> get() = _seekBarChange

    /** 屏幕超时设置变更（原 PreferKey.keepLight 事件） */
    private val _keepLightChange = eventFlow<Unit>()
    val keepLightChange: SharedFlow<Unit> get() = _keepLightChange

    /** 菜单数据刷新（原 ReadBook.CallBack.upMenuView，replay=1 兜底重建期漏发） */
    private val _menuRefresh = eventFlow<Unit>(replay = 1)
    val menuRefresh: SharedFlow<Unit> get() = _menuRefresh

    /** 请求重载目录（原 ReadBook.CallBack.loadChapterList 同步直调，replay=1 兜底无订阅期漏发） */
    private val _loadChapterList = eventFlow<Book>(replay = 1)
    val loadChapterList: SharedFlow<Book> get() = _loadChapterList

    /** 云端进度更新确认（原 ReadBook.CallBack.sureNewProgress 同步直调，replay=1 兜底无订阅期漏发） */
    private val _newProgressConfirm = eventFlow<BookProgress>(replay = 1)
    val newProgressConfirm: SharedFlow<BookProgress> get() = _newProgressConfirm

    /** 朗读状态（EventBus.ALOUD_STATE 桥接，ReadAloudDialog 消费） */
    private val _aloudState = eventFlow<Int>()
    val aloudState: SharedFlow<Int> get() = _aloudState

    /** 朗读定时（EventBus.READ_ALOUD_DS 桥接，ReadAloudDialog 消费） */
    private val _readAloudDs = eventFlow<Int>()
    val readAloudDs: SharedFlow<Int> get() = _readAloudDs

    /** 系统时间变化（EventBus.TIME_CHANGED 桥接，阅读页刷新时间显示） */
    private val _timeChanged = eventFlow<Unit>()
    val timeChanged: SharedFlow<Unit> get() = _timeChanged

    /** 电池电量变化（EventBus.BATTERY_CHANGED 桥接，0-100） */
    private val _batteryChanged = eventFlow<Int>()
    val batteryChanged: SharedFlow<Int> get() = _batteryChanged

    /** 媒体按钮（EventBus.MEDIA_BUTTON 桥接，isDown=按下/释放） */
    private val _mediaButton = eventFlow<Boolean>()
    val mediaButton: SharedFlow<Boolean> get() = _mediaButton

    /**
     * 朗读进度推进（EventBus.TTS_PROGRESS 桥接，chapterStart=当前章朗读起始字符位置）。
     * 原版 EventBus.TTS_PROGRESS 是 sticky，用 replay=1 实现粘性：UI 重建时立即恢复到当前朗读位置。
     */
    private val _ttsProgress = eventFlow<Int>(replay = 1)
    val ttsProgress: SharedFlow<Int> get() = _ttsProgress

    fun postConfig(vararg changes: ReadConfigChange) {
        _configChange.tryEmit(changes.asList())
    }

    fun postConfig(changes: List<ReadConfigChange>) {
        _configChange.tryEmit(changes)
    }

    fun postActionBarChange() {
        _actionBarChange.tryEmit(Unit)
    }

    fun postSeekBarChange() {
        _seekBarChange.tryEmit(Unit)
    }

    fun postKeepLightChange() {
        _keepLightChange.tryEmit(Unit)
    }

    fun postMenuRefresh() {
        _menuRefresh.tryEmit(Unit)
    }

    fun postLoadChapterList(book: Book) {
        _loadChapterList.tryEmit(book)
    }

    fun postConfirmNewProgress(progress: BookProgress) {
        _newProgressConfirm.tryEmit(progress)
    }

    /**
     * 清掉 [newProgressConfirm] 的 replay 缓存。用户确认/取消云进度弹窗后调用，
     * 避免 replay=1 在 UI 重建时把已处理过的确认事件再弹一次。
     */
    @OptIn(ExperimentalCoroutinesApi::class)
    fun clearNewProgressConfirm() {
        _newProgressConfirm.resetReplayCache()
    }

    fun postAloudState(state: Int) {
        _aloudState.tryEmit(state)
    }

    fun postReadAloudDs(minute: Int) {
        _readAloudDs.tryEmit(minute)
    }

    fun postTimeChanged() {
        _timeChanged.tryEmit(Unit)
    }

    fun postBatteryChanged(level: Int) {
        _batteryChanged.tryEmit(level)
    }

    fun postMediaButton(isDown: Boolean) {
        _mediaButton.tryEmit(isDown)
    }

    fun postTtsProgress(chapterStart: Int) {
        _ttsProgress.tryEmit(chapterStart)
    }
}
