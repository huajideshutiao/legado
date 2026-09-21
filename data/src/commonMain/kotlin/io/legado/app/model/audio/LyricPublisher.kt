package io.legado.app.model.audio

import io.legado.app.constant.EventBus
import io.legado.app.constant.PreferKey
import io.legado.app.constant.Status
import io.legado.app.help.config.PreferenceProviders
import io.legado.app.model.AudioPlayShared
import io.legado.app.model.Lrc
import io.legado.app.utils.FlowBus
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.launch
import kotlin.concurrent.Volatile

/**
 * 对外歌词发布目标。
 *
 * 实现必须幂等: 同一行可能被重复 publish, [clear] 也可能被重复调用。
 */
interface LyricSink {

    /** 当前歌词行变化 (单行, 已去掉双语歌词的第二行)。 */
    fun publish(line: String)

    /** 没有歌词可发了 (关开关 / 换章 / 停播 / 本章无歌词): 标题等回落章节名, 不留残词。 */
    fun clear()
}

/**
 * 对外歌词发布器 (全端共享)。
 *
 * 开关打开后把当前歌词行推给各端 [LyricSink] —— 车载与锁屏读 now-playing 标题。
 *
 * 与歌词界面是两个独立消费者, 共用 [Lrc.indexAt] 一份判定:
 * - 界面按帧求值, 不可见即停 (Compose 帧时钟自带门控), 精度到帧
 * - 本发布器按行唤醒, 息屏/后台照发 —— 车载正是无界面场景, 界面那套顶不上
 *
 * 每次唤醒都回读 [AudioPlayShared] 之外的引擎位置 (attach 传入), 所以调度延迟不累积:
 * 醒晚了直接算出当下该高亮的行, 不会像"到点就 ++"那样落后一行。
 */
object LyricPublisher {

    @Volatile
    private var sinks: List<LyricSink> = emptyList()

    private var job: Job? = null

    private var lastLrc: Lrc? = null
    private var lastPublishedIndex: Int = -1
    private var lastPublishedText: String? = null

    /** 宿主启动早期注册。四端各一个 (Android 进 MediaSession metadata, 其余端进 now-playing)。 */
    fun register(sink: LyricSink) {
        sinks = sinks + sink
    }

    /** seek 后重算 (由 [AudioPlayManager.onSeekTo] 统一调用, 四端只此一处)。 */
    fun invalidate() {
        AudioPlayShared.seekEpoch.value = AudioPlayShared.seekEpoch.value + 1
    }

    /**
     * 绑定播放会话, 由 [AudioPlayManager] 构造时调用 —— 宿主没有调用点, 也就没有"忘了调"。
     *
     * @param positionMs 播放位置真源 (`AudioPlayManager.positionMs`, 已消化引擎 seek 异步)
     */
    fun attach(scope: CoroutineScope, positionMs: () -> Int) {
        job?.cancel()
        job = scope.launch {
            combine(
                AudioPlayShared.durLrc,
                statusFlow(),
                speedFlow(),
                enabledFlow(),
                AudioPlayShared.seekEpoch,
            ) { lrc, status, speed, enabled, _ -> Drive(lrc, status, speed, enabled) }
                // 输入变了就重算: 换章 / 播放态 / 倍速 / 开关 / seek, 一律取消上一轮重入
                .collectLatest { publishLoop(it, positionMs) }
        }
    }

    /** 会话结束 (Service onDestroy)。 */
    fun detach() {
        job?.cancel()
        job = null
        clear()
    }

    private class Drive(
        val lrc: Lrc?,
        val status: Int,
        val speed: Float,
        val enabled: Boolean,
    )

    private suspend fun publishLoop(drive: Drive, positionMs: () -> Int) {
        val lrc = drive.lrc
        if (!drive.enabled || lrc == null || !lrc.hasTimeline || drive.status == Status.STOP) {
            clear()
            return
        }
        if (lrc !== lastLrc) {
            lastLrc = lrc
            lastPublishedIndex = -1
            lastPublishedText = null
        }
        while (true) {
            val now = positionMs() + Lrc.OFFSET_MS
            val index = lrc.indexAt(now)
            if (index != lastPublishedIndex) {
                lastPublishedIndex = index
                val lineText = lrc.lines.getOrNull(index)?.text?.substringBefore('\n')
                if (!lineText.isNullOrBlank()) {
                    publish(lineText)
                } else {
                    // 空行是间奏, 清回章节标题 (用户选项 1A，对齐原版行为)
                    clearSinks()
                }
            }
            // 暂停/缓冲: 停在当前行, 等状态变化由 collectLatest 重入
            if (drive.status != Status.PLAY) return
            val next = lrc.timeAfter(index) ?: return
            delay(((next - now) / drive.speed).toLong().coerceAtLeast(1))
        }
    }

    /** 播放态; sticky 事件可能还没发过, 用当前状态兜住 combine 的首值。 */
    private fun statusFlow() = FlowBus.withSticky(EventBus.AUDIO_STATE)
        .filterIsInstance<Int>()
        .onStart { emit(AudioPlayShared.status) }
        .distinctUntilChanged()

    /** 倍速; 变速后到下一行的墙上时间要重算, 所以它是输入而不是每次读全局。 */
    private fun speedFlow() = FlowBus.withSticky(EventBus.AUDIO_SPEED)
        .filterIsInstance<Float>()
        .onStart { emit(AudioPlayShared.playSpeed) }
        .distinctUntilChanged()

    /** 开关; pref 是唯一真源, 设置界面照常直写, 这里靠变更监听跟随。 */
    private fun enabledFlow() = callbackFlow {
        val prefs = PreferenceProviders.get()
        val read = { prefs.getBoolean(PreferKey.publishLyric, false) }
        trySend(read())
        val unregister = prefs.addPreferenceChangeListener { key ->
            if (key == PreferKey.publishLyric) trySend(read())
        }
        awaitClose { unregister() }
    }.distinctUntilChanged()

    private fun publish(line: String) {
        if (lastPublishedText == line) return
        lastPublishedText = line
        sinks.forEach { it.publish(line) }
    }

    private fun clearSinks() {
        if (lastPublishedText != null) {
            lastPublishedText = null
            sinks.forEach { it.clear() }
        }
    }

    private fun clear() {
        lastLrc = null
        lastPublishedIndex = -1
        lastPublishedText = null
        sinks.forEach { it.clear() }
    }
}
