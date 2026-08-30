package io.legado.app.help.media

import io.legado.app.constant.PreferKey
import io.legado.app.constant.Status
import io.legado.app.help.config.PreferenceProviders
import io.legado.app.help.media.SystemMediaControl.owner
import io.legado.app.help.media.SystemMediaControl.syncAudio
import io.legado.app.help.media.SystemMediaControl.syncReadAloud
import io.legado.app.help.media.SystemMediaControl.togglePlayPause
import io.legado.app.model.ActiveReadBookRegistry
import io.legado.app.model.AudioPlayCommanders
import io.legado.app.model.AudioPlayShared
import kotlin.concurrent.Volatile

/** 系统播控卡片要展示的一帧快照。 */
data class NowPlayingInfo(
    val title: String,
    val bookName: String,
    val author: String,
    val coverUrl: String?,
    val isPlaying: Boolean,
    val isPaused: Boolean,
    val positionMs: Long,
    val durationMs: Long,
    val playbackRate: Float,
    /** true = 有声书, false = 朗读 (朗读没有时长与倍速概念, 平台据此决定是否写进度/倍速)。 */
    val isAudioBook: Boolean,
)

/**
 * 平台播控卡片写入端 (iOS NowPlayingInfoCenter / 鸿蒙 AVSession)。
 *
 * 只负责"把这一帧画出去", 归属判定与取值都在 [SystemMediaControl]。
 */
interface NowPlayingSink {

    /** 刷新卡片。 */
    fun sync(info: NowPlayingInfo)

    /** 撤掉卡片并释放会话。 */
    fun clear()

    /**
     * 音频焦点: [active] 为 true 时独占播放 (打断其他 App), false 时归还。
     *
     * @param exclusive false 表示用户开了 `ignoreAudioFocus`, 与其他 App 混音
     */
    fun setAudioFocus(active: Boolean, exclusive: Boolean)
}

/** 系统播控指令 (锁屏 / 播控中心 / 耳机线控 / 车机)。 */
enum class RemoteMediaCommand { Play, Pause, TogglePlayPause, Next, Previous, Stop, Seek }

/**
 * 朗读宿主的播控抽象 (iOS `IosReadAloudHost` / 鸿蒙 `OhosReadAloudHost` 各注册一份)。
 */
interface ReadAloudRemoteHost {
    val isRun: Boolean
    val isPause: Boolean
    fun pause()
    fun resume()
    fun stop()
    fun prevParagraph()
    fun nextParagraph()
    fun prevChapter()
    fun nextChapter()
}

/**
 * 系统媒体控制的跨平台中枢: 播控卡片取值 + 远程指令归属判定。
 *
 * Android 端有声书与朗读各持一个 MediaSession, 卡片互不干扰; iOS 的 NowPlaying 与鸿蒙的
 * AVSession 都是每 App 一份, 只有一张卡片, 因此要判定"当前谁是媒体主"([owner])。
 */
object SystemMediaControl {

    /** 当前占着播控卡片的一方。 */
    private enum class Owner { None, Audio, ReadAloud }

    /**
     * 媒体主。
     *
     * 不能从 [AudioPlayShared.status] 反推: `skipTo` / `prev` / `next` 切章都先走 `stopPlay`,
     * status 会眨眼变 STOP, 那一瞬间的按键会被误路由到朗读。也不能只看 commander 的
     * `isServiceRunning` (`stopPlay` 不清它), 否则停播后的有声书会一直截走朗读的指令。
     * 用"谁最后同步了卡片"作判据: [syncAudio] / [syncReadAloud] 就是"我要占卡片"的声明。
     */
    @Volatile
    private var owner = Owner.None

    @Volatile
    private var sink: NowPlayingSink? = null

    @Volatile
    private var readAloudHost: ReadAloudRemoteHost? = null

    /** 最近一次有声书倍速 (朗读退场后把卡片交还有声书时补上)。 */
    @Volatile
    private var lastAudioRate: Float = 1f

    /** 宿主启动早期注册平台卡片实现。 */
    fun registerSink(sink: NowPlayingSink) {
        this.sink = sink
    }

    /** 宿主启动早期注册朗读宿主。 */
    fun registerReadAloudHost(host: ReadAloudRemoteHost) {
        this.readAloudHost = host
    }

    /** 有声书是否在出声 (含拉流窗口), 决定朗读能否抢走卡片。 */
    private val audioSounding: Boolean
        get() = AudioPlayShared.status == Status.PLAY || AudioPlayShared.status == Status.LOADING

    // ===== 播控卡片 =====

    /**
     * 同步有声书卡片 (含 `stopPlay` 后的停止态: 服务还活着, 对照原版只更新 playbackState 不撤会话)。
     *
     * @param positionMs 拖动进度时传目标位置 (引擎 seek 异步, 当前位置还没跟上)
     * @param coverUrl   `onLoadCover` 拿到的封面 (书源 musicCover 规则结果), 缺省回退 durCoverUrl
     */
    fun syncAudio(positionMs: Long? = null, playbackRate: Float = 1f, coverUrl: String? = null) {
        lastAudioRate = playbackRate
        owner = Owner.Audio
        val status = AudioPlayShared.status
        val isPlaying = status == Status.PLAY
        val book = AudioPlayShared.book
        sink?.sync(
            NowPlayingInfo(
                title = AudioPlayShared.durChapter?.title ?: "",
                bookName = book?.name ?: "",
                author = book?.author ?: "",
                coverUrl = coverUrl ?: AudioPlayShared.durCoverUrl ?: book?.getDisplayCover(),
                isPlaying = isPlaying,
                isPaused = status == Status.PAUSE || status == Status.LOADING,
                positionMs = positionMs ?: AudioPlayShared.durChapterPos.toLong(),
                durationMs = AudioPlayShared.durAudioSize.toLong(),
                playbackRate = playbackRate,
                isAudioBook = true,
            )
        )
        // 对照 app 端 AudioFocusController 的调用点: 只有 play 申请、pause 归还;
        // LOADING (拉流) 与 STOP (stopPlay / 切章) 保持当前焦点不动
        when (status) {
            Status.PLAY -> sink?.setAudioFocus(true, exclusive = !ignoreAudioFocus)
            Status.PAUSE -> sink?.setAudioFocus(false, exclusive = !ignoreAudioFocus)
            else -> Unit
        }
    }

    /** 同步朗读卡片 (有声书正在出声时不抢卡片, 对照原版的音频优先)。 */
    fun syncReadAloud(isPlaying: Boolean) {
        if (owner == Owner.Audio && audioSounding) return
        owner = Owner.ReadAloud
        val readBook = ActiveReadBookRegistry.current
        val book = readBook?.bookValue
        sink?.sync(
            NowPlayingInfo(
                title = readBook?.chapterListValue
                    ?.getOrNull(readBook.durChapterIndexValue)?.title ?: "",
                bookName = book?.name ?: "",
                author = book?.author ?: "",
                coverUrl = book?.getDisplayCover(),
                isPlaying = isPlaying,
                isPaused = !isPlaying,
                positionMs = 0L,
                durationMs = 0L,
                playbackRate = 1f,
                isAudioBook = false,
            )
        )
        sink?.setAudioFocus(isPlaying, exclusive = !ignoreAudioFocus)
    }

    /**
     * 有声书退场 (对照原版 `onDestroy` 的 `mediaSessionCompat.release()`)。
     *
     * 朗读还在播就把卡片交还给它: 只有一张卡片, 否则会留着有声书的标题。
     */
    fun releaseAudio() {
        if (owner != Owner.Audio) return
        val host = readAloudHost
        if (host?.isRun == true) {
            owner = Owner.ReadAloud
            syncReadAloud(isPlaying = !host.isPause)
            return
        }
        detach()
    }

    /** 朗读退场; 有声书服务还活着就把卡片交还给它。 */
    fun releaseReadAloud() {
        if (owner != Owner.ReadAloud) return
        if (AudioPlayCommanders.getOrNull()?.isServiceRunning == true) {
            owner = Owner.Audio
            syncAudio(playbackRate = lastAudioRate)
            return
        }
        detach()
    }

    private fun detach() {
        owner = Owner.None
        sink?.setAudioFocus(active = false, exclusive = !ignoreAudioFocus)
        sink?.clear()
    }

    // ===== 远程指令 =====

    /**
     * 派发系统播控指令。
     *
     * @param positionMs [RemoteMediaCommand.Seek] 的目标位置
     */
    fun onRemoteCommand(command: RemoteMediaCommand, positionMs: Long = 0L) {
        when (command) {
            RemoteMediaCommand.Play -> if (!isPlaying) togglePlayPause()
            RemoteMediaCommand.Pause -> if (isPlaying) togglePlayPause()
            RemoteMediaCommand.TogglePlayPause -> togglePlayPause()

            // 卡片是谁的按键就给谁, 对应原版 KEYCODE_MEDIA_NEXT / PREVIOUS / STOP 的有声书优先
            RemoteMediaCommand.Next -> when (owner) {
                Owner.Audio -> AudioPlayShared.next()
                Owner.ReadAloud -> readAloudHost?.let {
                    if (mediaButtonPerNext) it.nextChapter() else it.nextParagraph()
                }

                Owner.None -> Unit
            }

            RemoteMediaCommand.Previous -> when (owner) {
                Owner.Audio -> AudioPlayShared.prev()
                Owner.ReadAloud -> readAloudHost?.let {
                    if (mediaButtonPerNext) it.prevChapter() else it.prevParagraph()
                }

                Owner.None -> Unit
            }

            RemoteMediaCommand.Stop -> when (owner) {
                Owner.Audio -> AudioPlayShared.stop()
                Owner.ReadAloud -> readAloudHost?.stop()
                Owner.None -> Unit
            }

            RemoteMediaCommand.Seek -> if (owner == Owner.Audio) {
                AudioPlayShared.adjustProgress(positionMs.toInt())
            }
        }
    }

    /**
     * 播放/暂停切换, 对照原版 `MediaButtonReceiver.readAloud`: 朗读优先, 且连带切换有声书。
     *
     * 原版还有"两者都没在播 → 打开上次读的书开始朗读"的分支; iOS / 鸿蒙没有播控卡片就收不到
     * 系统指令, 该分支不可达, 故不下沉。
     */
    private fun togglePlayPause() {
        val host = readAloudHost
        when {
            host?.isRun == true -> if (host.isPause) {
                host.resume()
                if (audioRunning) AudioPlayShared.resume()
            } else {
                host.pause()
                if (audioRunning) AudioPlayShared.pause()
            }

            owner == Owner.Audio -> when (AudioPlayShared.status) {
                Status.PLAY -> AudioPlayShared.pause()
                Status.PAUSE -> AudioPlayShared.resume()
                // 停止态 (stopPlay / 切章): 对照原版通知 action 的 else 分支重新拉流
                else -> AudioPlayShared.loadOrUpPlayUrl()
            }

            else -> Unit
        }
    }

    /** 当前是否有声音在放 (朗读优先, 与 [togglePlayPause] 同口径)。 */
    private val isPlaying: Boolean
        get() = readAloudHost?.takeIf { it.isRun }?.let { !it.isPause }
            ?: (AudioPlayShared.status == Status.PLAY)

    /** 有声书 commander 是否还活着 (连带播控用, 停播状态下调用也安全)。 */
    private val audioRunning: Boolean
        get() = AudioPlayCommanders.getOrNull()?.isServiceRunning == true

    private val mediaButtonPerNext: Boolean
        get() = readPref("mediaButtonPerNext")

    /** 开启后不抢音频焦点 (对照 app 端 `AudioFocusController` 的 `ignoreAudioFocus` 短路)。 */
    private val ignoreAudioFocus: Boolean
        get() = readPref(PreferKey.ignoreAudioFocus)

    private fun readPref(key: String): Boolean =
        runCatching { PreferenceProviders.get().getBoolean(key, false) }.getOrDefault(false)
}
