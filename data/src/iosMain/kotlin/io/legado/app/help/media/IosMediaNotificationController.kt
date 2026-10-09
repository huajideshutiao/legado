@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package io.legado.app.help.media

import io.legado.app.constant.AppLog
import io.legado.app.help.coroutine.IoDispatcher
import io.legado.app.help.http.KmpRequestBuilder
import io.legado.app.help.http.OkHttpClientProviders
import io.legado.app.help.tts.IosReadAloudHost
import kotlinx.cinterop.alloc
import kotlinx.cinterop.ObjCObjectVar
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.usePinned
import kotlinx.cinterop.value
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import platform.AVFAudio.AVAudioSession
import platform.AVFAudio.AVAudioSessionCategoryOptionMixWithOthers
import platform.AVFAudio.AVAudioSessionCategoryPlayback
import platform.AVFAudio.AVAudioSessionInterruptionNotification
import platform.AVFAudio.AVAudioSessionInterruptionOptionKey
import platform.AVFAudio.AVAudioSessionInterruptionOptionShouldResume
import platform.AVFAudio.AVAudioSessionInterruptionTypeBegan
import platform.AVFAudio.AVAudioSessionInterruptionTypeEnded
import platform.AVFAudio.AVAudioSessionInterruptionTypeKey
import platform.AVFAudio.AVAudioSessionRouteChangeNotification
import platform.AVFAudio.AVAudioSessionRouteChangeReasonKey
import platform.AVFAudio.AVAudioSessionRouteChangeReasonOldDeviceUnavailable
import platform.AVFAudio.AVAudioSessionSetActiveOptionNotifyOthersOnDeactivation
import platform.AVFAudio.setActive
import platform.Foundation.NSData
import platform.Foundation.NSError
import platform.Foundation.NSNotification
import platform.Foundation.NSNotificationCenter
import platform.Foundation.NSNumber
import platform.Foundation.NSOperationQueue
import platform.Foundation.create
import platform.MediaPlayer.MPChangePlaybackPositionCommandEvent
import platform.MediaPlayer.MPMediaItemArtwork
import platform.MediaPlayer.MPMediaItemPropertyAlbumTitle
import platform.MediaPlayer.MPMediaItemPropertyArtist
import platform.MediaPlayer.MPMediaItemPropertyArtwork
import platform.MediaPlayer.MPMediaItemPropertyPlaybackDuration
import platform.MediaPlayer.MPMediaItemPropertyTitle
import platform.MediaPlayer.MPNowPlayingInfoCenter
import platform.MediaPlayer.MPNowPlayingInfoPropertyDefaultPlaybackRate
import platform.MediaPlayer.MPNowPlayingInfoPropertyElapsedPlaybackTime
import platform.MediaPlayer.MPNowPlayingInfoPropertyPlaybackRate
import platform.MediaPlayer.MPRemoteCommand
import platform.MediaPlayer.MPRemoteCommandCenter
import platform.MediaPlayer.MPRemoteCommandHandlerStatusSuccess
import platform.UIKit.UIImage

/**
 * iOS 端系统媒体控制 (NowPlaying 卡片 / RemoteCommandCenter / AVAudioSession)。
 *
 * 归属判定与取值都在 [SystemMediaControl], 这里只做平台三件事: 音频会话开关、远程指令转发、
 * 卡片写入 (封面按 URL 去重加载)。MediaPlayer 与 AVAudioSession 都要求主线程, 而调用方分散在
 * 朗读 (Dispatchers.Default) 与音频 (Dispatchers.Main) 两条协程上, 故一切经 [scope] 串到主线程。
 */
object IosMediaNotificationController : NowPlayingSink {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    /** 以下状态只在 [scope] (主线程) 上读写, 无需同步。 */
    private var commandsRegistered = false
    private var focusActive = false
    private var focusExclusive = true
    private var lastCoverUrl: String? = null
    private var artwork: MPMediaItemArtwork? = null

    /** 最近一次写出的卡片字段, 供封面到手后补写 (读回系统的星投影 Map 无法再 put)。 */
    private var lastInfo: MutableMap<Any?, Any?>? = null

    // ===== NowPlayingSink =====

    override fun sync(info: NowPlayingInfo) {
        scope.launch {
            ensureRemoteCommands()
            val map = mutableMapOf<Any?, Any?>()
            if (info.title.isNotEmpty()) map[MPMediaItemPropertyTitle] = info.title
            if (info.bookName.isNotEmpty()) map[MPMediaItemPropertyArtist] = info.bookName
            if (info.author.isNotEmpty()) map[MPMediaItemPropertyAlbumTitle] = info.author
            if (info.durationMs > 0) {
                map[MPMediaItemPropertyPlaybackDuration] = info.durationMs / 1000.0
            }
            map[MPNowPlayingInfoPropertyElapsedPlaybackTime] =
                info.positionMs.coerceAtLeast(0L) / 1000.0
            map[MPNowPlayingInfoPropertyPlaybackRate] =
                if (info.isPlaying) info.playbackRate.toDouble() else 0.0
            map[MPNowPlayingInfoPropertyDefaultPlaybackRate] = 1.0
            artwork?.let { map[MPMediaItemPropertyArtwork] = it }
            lastInfo = map
            MPNowPlayingInfoCenter.defaultCenter().nowPlayingInfo = map
            upCover(info.coverUrl)
        }
    }

    override fun clear() {
        scope.launch {
            lastCoverUrl = null
            artwork = null
            lastInfo = null
            MPNowPlayingInfoCenter.defaultCenter().nowPlayingInfo = null
        }
    }

    override fun setAudioFocus(active: Boolean, exclusive: Boolean) {
        scope.launch { applyAudioSession(active, exclusive) }
    }

    // ===== 音频会话 =====

    /**
     * 开关播放类别的音频会话, 对应 app 端 `AudioFocusController` 的 request / abandon。
     *
     * 只在真正开播时激活: 启动即激活会掐掉用户正在放的音乐。[exclusive] 为 false 时叠加
     * MixWithOthers, 即用户开了 `ignoreAudioFocus`, 与其他 App 混音不抢。
     */
    @OptIn(kotlinx.cinterop.BetaInteropApi::class)
    private fun applyAudioSession(active: Boolean, exclusive: Boolean) {
        if (active == focusActive && exclusive == focusExclusive) return
        val session = AVAudioSession.sharedInstance()
        memScoped {
            val err = alloc<ObjCObjectVar<NSError?>>()
            if (active) {
                val options = if (exclusive) 0uL else AVAudioSessionCategoryOptionMixWithOthers
                if (!session.setCategory(
                        AVAudioSessionCategoryPlayback,
                        withOptions = options,
                        error = err.ptr
                    )
                ) {
                    AppLog.put("iOS 音频会话设置类别失败: ${err.value?.localizedDescription}")
                    return
                }
                if (!session.setActive(true, error = err.ptr)) {
                    AppLog.put("iOS 音频会话激活失败: ${err.value?.localizedDescription}")
                    return
                }
            } else {
                // NotifyOthersOnDeactivation: 通知其他 App 恢复播放
                session.setActive(
                    false,
                    withOptions = AVAudioSessionSetActiveOptionNotifyOthersOnDeactivation,
                    error = err.ptr
                )
            }
        }
        focusActive = active
        focusExclusive = exclusive
    }

    // ===== 远程指令 =====

    /** 首次展示卡片时注册, 之后复用 (主线程串行, 不会重复挂 target)。 */
    private fun ensureRemoteCommands() {
        if (commandsRegistered) return
        commandsRegistered = true
        val center = MPRemoteCommandCenter.sharedCommandCenter()
        center.playCommand.bind(RemoteMediaCommand.Play)
        center.pauseCommand.bind(RemoteMediaCommand.Pause)
        center.togglePlayPauseCommand.bind(RemoteMediaCommand.TogglePlayPause)
        center.nextTrackCommand.bind(RemoteMediaCommand.Next)
        center.previousTrackCommand.bind(RemoteMediaCommand.Previous)
        center.stopCommand.bind(RemoteMediaCommand.Stop)
        center.changePlaybackPositionCommand.also { command ->
            command.enabled = true
            command.addTargetWithHandler { event ->
                val second = (event as? MPChangePlaybackPositionCommandEvent)?.positionTime ?: 0.0
                SystemMediaControl.onRemoteCommand(
                    RemoteMediaCommand.Seek,
                    (second * 1000.0).toLong()
                )
                MPRemoteCommandHandlerStatusSuccess
            }
        }
    }

    private fun MPRemoteCommand.bind(command: RemoteMediaCommand) {
        enabled = true
        addTargetWithHandler {
            SystemMediaControl.onRemoteCommand(command)
            MPRemoteCommandHandlerStatusSuccess
        }
    }

    // ===== 卡片封面 =====

    /** 封面按 URL 去重 (对照 app 端 `AudioPlayService.loadCover` 的 lastCoverUrl 短路)。 */
    private fun upCover(url: String?) {
        if (url.isNullOrBlank() || url == lastCoverUrl) return
        lastCoverUrl = url
        if (!url.startsWith("http", ignoreCase = true)) {
            applyCover(UIImage.imageWithContentsOfFile(url))
            return
        }
        scope.launch {
            val bytes = runCatching { withContext(IoDispatcher) { fetch(url) } }
                .onFailure { AppLog.put("播控卡片封面加载失败: $url", it) }
                .getOrNull()
            if (bytes == null || bytes.isEmpty()) return@launch
            applyCover(UIImage(data = bytes.toNSData()))
        }
    }

    /** 封面到手后只补 artwork 字段, 其余保持当前卡片状态。 */
    private fun applyCover(image: UIImage?) {
        image ?: return
        val art = MPMediaItemArtwork(image = image)
        artwork = art
        val current = lastInfo ?: return
        current[MPMediaItemPropertyArtwork] = art
        MPNowPlayingInfoCenter.defaultCenter().nowPlayingInfo = current
    }

    private fun fetch(url: String): ByteArray {
        val request = KmpRequestBuilder().url(url).get().build()
        val response = OkHttpClientProviders.get().okHttpClient.newCall(request).execute()
        try {
            check(response.isSuccessful) { "HTTP ${response.code}: $url" }
            return response.body.bytes()
        } finally {
            response.close()
        }
    }

    /** ByteArray → NSData (`NSData.create` 只收 CPointer, 需先 pin)。 */
    @OptIn(kotlinx.cinterop.BetaInteropApi::class)
    private fun ByteArray.toNSData(): NSData = usePinned { pinned ->
        NSData.create(bytes = pinned.addressOf(0), length = size.toULong())
    }
}

/**
 * 注册 iOS 端系统媒体控制 (卡片写入端 + 朗读宿主 + 音频会话事件源)。
 *
 * 这里不激活音频会话: 会话在真正开播时才激活, 见 [NowPlayingSink.setAudioFocus]。
 */
fun registerIosMediaNotificationController() {
    SystemMediaControl.registerSink(IosMediaNotificationController)
    SystemMediaControl.registerReadAloudHost(IosReadAloudHost)
    registerIosAudioSessionObservers()
    registerIosCallStateObserver()
}

/**
 * iOS 音频焦点与音频路由事件源 (对照 app 端 `AudioFocusController` 的系统回调与
 * `BecomingNoisyReceiver`)。
 *
 * 只挂一次观测者, 全程在册:
 * - `AVAudioSessionInterruptionNotification`: Began → [AudioFocusChange.LossTransient],
 *   Ended 且带 `ShouldResume` → [AudioFocusChange.Gain]; Ended 不带 `ShouldResume` 不派发
 *   (系统明确说"不要自己恢复", 此时应维持暂停)。
 *   iOS 无 `AUDIOFOCUS_LOSS` (永久丢失) 与 `CAN_DUCK` 等价枚举, 故这两档不会到达。
 * - `AVAudioSessionRouteChangeNotification`: 旧设备不可用 (拔耳机/蓝牙断开) →
 *   [SystemMediaControl.onAudioBecomingNoisy]。
 *
 * 通知的 `userInfo` 是 NSDictionary, 数值经 Kotlin/ObjC 桥接后可能是 NSNumber 或
 * 装箱过的数值类型, 故用 [intValue] 两种形态都取。
 */
private fun registerIosAudioSessionObservers() {
    val center = NSNotificationCenter.defaultCenter
    center.addObserverForName(
        AVAudioSessionInterruptionNotification,
        `object` = null,
        queue = NSOperationQueue.mainQueue,
    ) { notification ->
        val info = notification ?: return@addObserverForName
        val type = info.intValue(AVAudioSessionInterruptionTypeKey) ?: return@addObserverForName
        when (type.toULong()) {
            AVAudioSessionInterruptionTypeBegan ->
                SystemMediaControl.onAudioFocusChange(AudioFocusChange.LossTransient)

            AVAudioSessionInterruptionTypeEnded -> {
                val options = info.intValue(AVAudioSessionInterruptionOptionKey)
                    ?.toULong() ?: 0uL
                if (options and AVAudioSessionInterruptionOptionShouldResume != 0uL) {
                    SystemMediaControl.onAudioFocusChange(AudioFocusChange.Gain)
                }
            }
        }
    }
    center.addObserverForName(
        AVAudioSessionRouteChangeNotification,
        `object` = null,
        queue = NSOperationQueue.mainQueue,
    ) { notification ->
        val info = notification ?: return@addObserverForName
        val reason = info.intValue(AVAudioSessionRouteChangeReasonKey)
            ?: return@addObserverForName
        if (reason.toULong() == AVAudioSessionRouteChangeReasonOldDeviceUnavailable) {
            SystemMediaControl.onAudioBecomingNoisy()
        }
    }
}

/** 从通知 userInfo 取数值 (NSNumber 与 Kotlin 装箱数值两种形态都接)。 */
private fun NSNotification.intValue(key: String?): Int? = when (val value = key?.let { userInfo?.get(it) }) {
    is NSNumber -> value.intValue
    is Int -> value
    is Long -> value.toInt()
    else -> null
}
