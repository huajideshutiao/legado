package io.legado.app.help.media

import io.legado.app.help.tts.OhosReadAloudHost
import io.legado.app.napi.OhosNativeBridge
import io.legado.app.utils.KS_JSON
import kotlinx.serialization.Serializable

/**
 * 鸿蒙端系统媒体控制: 经 NAPI 桥与 ArkTS 的 AVSession (播控中心 / 锁屏卡片 / 长时任务) 收发。
 *
 * 归属判定与取值都在 [SystemMediaControl], 这里只做 JSON 编解码。
 */
object OhosMediaNotificationController : NowPlayingSink, OhosNativeBridge.MediaEventListener {

    // ===== NowPlayingSink =====

    override fun sync(info: NowPlayingInfo) {
        send(
            MediaCommand(
                action = "syncNowPlaying",
                title = info.title,
                artist = info.bookName,
                album = info.author,
                duration = info.durationMs,
                position = info.positionMs,
                speed = info.playbackRate,
                isPlaying = info.isPlaying,
                isPaused = info.isPaused,
                coverUrl = info.coverUrl?.takeIf { it.isNotBlank() },
            )
        )
    }

    override fun clear() = send(MediaCommand(action = "clearNowPlaying"))

    /** 鸿蒙音频焦点由 AVPlayer 按 StreamUsage 自动申请/归还, 无需应用侧介入。 */
    override fun setAudioFocus(active: Boolean, exclusive: Boolean) = Unit

    // ===== 播控中心指令 =====

    override fun onMediaEvent(eventJson: String) {
        val payload = runCatching {
            KS_JSON.decodeFromString(SessionCommandPayload.serializer(), eventJson)
        }.getOrNull() ?: return
        if (payload.event != "onCommand") return
        val command = when (payload.command) {
            "play" -> RemoteMediaCommand.Play
            "pause" -> RemoteMediaCommand.Pause
            "stop" -> RemoteMediaCommand.Stop
            "next" -> RemoteMediaCommand.Next
            "previous" -> RemoteMediaCommand.Previous
            "seek" -> RemoteMediaCommand.Seek
            else -> return
        }
        SystemMediaControl.onRemoteCommand(command, payload.position ?: 0L)
    }

    private fun send(cmd: MediaCommand) =
        OhosNativeBridge.sendMediaCommand(KS_JSON.encodeToString(MediaCommand.serializer(), cmd))

    @Serializable
    private data class MediaCommand(
        val action: String,
        val title: String? = null,
        val artist: String? = null,
        val album: String? = null,
        val duration: Long? = null,
        val position: Long? = null,
        val speed: Float? = null,
        val isPlaying: Boolean? = null,
        val isPaused: Boolean? = null,
        val coverUrl: String? = null,
    )

    @Serializable
    private data class SessionCommandPayload(
        val event: String,
        val command: String? = null,
        val position: Long? = null,
    )
}

/** 注册鸿蒙端系统媒体控制 (播控事件监听 + 卡片写入端 + 朗读宿主)。 */
fun registerOhosMediaNotificationController() {
    OhosNativeBridge.setMediaEventListener(
        OhosNativeBridge.PLAYER_ID_SESSION,
        OhosMediaNotificationController
    )
    SystemMediaControl.registerSink(OhosMediaNotificationController)
    SystemMediaControl.registerReadAloudHost(OhosReadAloudHost)
}
