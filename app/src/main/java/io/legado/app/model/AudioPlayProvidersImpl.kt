package io.legado.app.model

import android.content.Intent
import io.legado.app.App
import io.legado.app.constant.IntentAction
import io.legado.app.model.audio.LyricPublisher
import io.legado.app.model.audio.LyricSink
import io.legado.app.service.AudioPlayService
import io.legado.app.utils.startService

/**
 * AudioPlay 平台 provider 的 Android 实现。
 *
 * 包含:
 * - [AudioPlayProvidersImpl]: 实现 [AudioPlayCommander], 内部走
 *   `appCtx.startService<AudioPlayService>` + IntentAction + extras,
 *   与原 app 端 `AudioPlay.sendAction` 完全等价
 * - [MediaMetadataLyricSink]: 车载/锁屏 now-playing metadata 的歌词 sink
 *
 * 注册时机: App.onCreate, 经 [registerAndroidAudioPlayProviders]。
 *
 * 模式参考 `WebBookProvidersImpl` / `registerAndroidWebBookProviders`。
 */
object AudioPlayProvidersImpl : AudioPlayCommander {

    // ---------- AudioPlayCommander ----------

    override val isServiceRunning: Boolean
        get() = AudioPlayService.isRun

    override var pendingTimerMinute: Int
        get() = AudioPlayService.pendingTimerMinute
        set(value) {
            AudioPlayService.pendingTimerMinute = value
        }

    override val positionMs: Int
        get() = AudioPlayService.positionMs

    override fun play() = sendAction(IntentAction.play, requireRunning = false)

    override fun playNew() = sendAction(IntentAction.playNew, requireRunning = false)

    override fun stop() = sendAction(IntentAction.stop)

    override fun stopPlay() = sendAction(IntentAction.stopPlay)

    override fun pause() = sendAction(IntentAction.pause)

    override fun resume() = sendAction(IntentAction.resume)

    override fun adjustSpeed(adjust: Float) =
        sendAction(IntentAction.adjustSpeed) { putExtra("adjust", adjust) }

    override fun adjustProgress(position: Int) =
        sendAction(IntentAction.adjustProgress) { putExtra("position", position) }

    override fun setTimer(minute: Int) =
        sendAction(IntentAction.setTimer) { putExtra("minute", minute) }

    override fun addTimer() = sendAction(IntentAction.addTimer, requireRunning = false)

    override fun loadPlayUrl() = sendAction(IntentAction.loadPlayUrl, requireRunning = false)

    /**
     * 向 [AudioPlayService] 发送命令 (原 app 端 `AudioPlay.sendAction` 等价实现)。
     *
     * @param requireRunning 仅在服务运行中才派发(默认 true)。
     *                       播放/加载类命令需要设置为 false 来启动服务。
     * @param extras Intent extra 写入块 (putExtra("adjust", ...) 等)
     */
    private inline fun sendAction(
        action: String,
        requireRunning: Boolean = true,
        extras: Intent.() -> Unit = {}
    ) {
        if (requireRunning && !AudioPlayService.isRun) return
        App.instance.startService<AudioPlayService> {
            this.action = action
            extras()
        }
    }
}

/**
 * 车载/锁屏 sink: 歌词行顶掉 now-playing 标题。
 *
 * AVRCP 的文本属性只有曲名/歌手/专辑, 没有歌词字段 —— 车机屏幕与蓝牙那行字来自 MediaSession
 * metadata 的 TITLE, Android Auto 读的也是同一份, 所以"车载歌词"就是让歌词占用标题位。
 */
private object MediaMetadataLyricSink : LyricSink {

    override fun publish(line: String) = AudioPlayService.publishLyricLine(line)

    override fun clear() = AudioPlayService.publishLyricLine(null)
}

/**
 * 安卓宿主启动早期注册 AudioPlay 平台 provider。
 *
 * 调用时机: App.onCreate, 在 `registerAndroidWebBookProviders()` 之后
 * (AudioPlay 依赖 AppDbProviders 已注册, 虽然 AudioPlayCommander/BookBridge 不直接用 appDb,
 * 但 AudioPlayShared 内部通过 AppDbProviders.get().bookChapterDao 访问 DB)。
 *
 * 模式参考 `registerAndroidWebBookProviders` / `registerAndroidServiceLauncher`。
 */
fun registerAndroidAudioPlayProviders() {
    AudioPlayCommanders.register(AudioPlayProvidersImpl)
    LyricPublisher.register(MediaMetadataLyricSink)
}
