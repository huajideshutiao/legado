package io.legado.desktop.audio

import io.legado.app.help.media.NowPlayingLyricSink
import io.legado.app.help.media.SystemMediaControl
import io.legado.app.model.AudioPlayCommanders
import io.legado.app.model.AudioPlayShared
import io.legado.app.model.analyzeRule.AnalyzeUrlFactories
import io.legado.app.model.audio.AudioPlayAnalyzeRuleFactory
import io.legado.app.model.audio.AudioPlaySession
import io.legado.app.model.audio.LyricPublisher
import io.legado.app.model.audio.NowPlayingSessionHost
import io.legado.app.ui.compose.platform.jvmGetString
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.currentCoroutineContext

/**
 * 桌面端 AudioPlay 宿主 (对应 app 端 AudioPlayService 的平台部分)。
 *
 * 会话状态机在 commonMain [AudioPlaySession] (四端共用), 播控卡片同步在
 * [NowPlayingSessionHost] (三端共用), 本类只做 mpv 引擎接入 (直链解析 + setSource/prepare)。
 *
 * # 不实现 (与 app 端 AudioPlayService 的差异)
 * MediaSession / Notification / WakeLock / AudioFocus 是 Android 专属, 桌面端无对应概念
 * (托盘/任务栏/悬停卡片由 DesktopMediaTray / DesktopTaskbarMedia 承载, 读会话寿命与 AUDIO_STATE)。
 *
 * 注册时机: desktop Main.kt, 在所有依赖 provider (AppDbProviders / OkHttpClientProviders /
 * JsEngines / SourceHelpAccessors / registerDesktopWebBookProviders) 注册之后 ——
 * 章节加载经 WebBook.getContentAwait 间接访问 appDb + webBook 编排层 + JS 引擎。
 */
class DesktopAudioPlayProvider : NowPlayingSessionHost() {

    override val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private val player = DesktopAudioPlayer()

    override val controller = DesktopAudioPlayController(player)

    override val analyzeRuleFactory: AudioPlayAnalyzeRuleFactory
        get() = DesktopAudioPlayAnalyzeRuleFactory

    override var pendingTimerMinute: Int = 0

    /**
     * 直链解析 → mpv setSource + prepare。
     *
     * 必须经 AnalyzeUrl 才能拆掉 legado 的 `url,{options}` 后缀并拿到 UA/Referer/Cookie,
     * 裸喂 mpv 会 MPV_ERROR_LOADING_FAILED(-13)。经工厂创建以获得 desktop 平台子类
     * (DesktopAnalyzeUrl) 的完整 JS 扩展面, url 内 `<js>` 才能调 java.createSymmetricCrypto 等。
     */
    override suspend fun startPlayback(url: String, positionMs: Int) {
        val (mediaUrl, headers) = AnalyzeUrlFactories.create(
            rawUrl = url,
            source = AudioPlayShared.bookSource,
            ruleData = AudioPlayShared.book,
            chapter = AudioPlayShared.durChapter,
            coroutineContext = currentCoroutineContext(),
        ).resolveMedia()
        controller.setSource(mediaUrl, headers, positionMs.toLong())
        controller.prepare()
    }

    /** mpv 的报错文案走桌面本地化字符串。 */
    override fun playerErrorMessage(error: Throwable): String =
        jvmGetString("desktop_audio_play_error", error.message ?: "")

    /** SMTC 首次播放时激活 (幂等)。 */
    override fun onSessionStart() {
        DesktopSmtc.init()
    }

    override fun onSessionEnd() {
        // mpv 实例贵, 会话结束只停不 release: 下一轮 setSource + prepare 复用同一实例
        player.stop()
        SystemMediaControl.releaseAudio()
    }
}

/**
 * 桌面端注册 AudioPlay 平台 provider (对应 app 端 registerAndroidAudioPlayProviders)。
 *
 * 调用时机: desktop Main.kt, 在 registerDesktopWebBookProviders() /
 * registerDesktopJsEngines() 之后。
 */
fun registerDesktopAudioPlayProviders() {
    AudioPlayCommanders.register(DesktopAudioPlayProvider().session)
    LyricPublisher.register(NowPlayingLyricSink)
}
