package io.legado.app.ui.book.video

import android.annotation.SuppressLint
import android.content.pm.ActivityInfo
import android.content.res.Configuration
import android.content.res.Resources
import android.icu.text.SimpleDateFormat
import android.media.AudioManager
import android.net.Uri
import android.os.Bundle
import androidx.activity.addCallback
import androidx.activity.viewModels
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.net.toUri
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.datasource.ByteArrayDataSource
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.HttpDataSource
import androidx.media3.datasource.TransferListener
import androidx.media3.exoplayer.ExoPlaybackException
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.hls.HlsMediaSource
import androidx.media3.exoplayer.source.UnrecognizedInputFormatException
import androidx.media3.ui.TrackSelectionDialogBuilder
import io.legado.app.R
import io.legado.app.base.BaseComposeActivity
import io.legado.app.constant.AppLog
import io.legado.app.data.entities.BookChapter
import io.legado.app.help.IntentData
import io.legado.app.help.config.AppConfig
import io.legado.app.help.exoplayer.ExoPlayerHelper
import io.legado.app.model.ReadTimeRecorder
import io.legado.app.model.analyzeRule.AnalyzeUrl
import io.legado.app.model.analyzeRule.AnalyzeUrlCore
import io.legado.app.ui.about.AppLogDialog
import io.legado.app.ui.book.bookmark.BookmarkDialog
import io.legado.app.ui.book.info.BookInfoActivity
import io.legado.app.ui.book.read.ReadBookActivity.Companion.RESULT_DELETED
import io.legado.app.ui.compose.dialogs.alert
import io.legado.app.ui.root.AppNavigatorProviders
import io.legado.app.ui.root.AppRoute
import io.legado.app.ui.root.RouteResultPayload
import io.legado.app.ui.root.RouteResults
import io.legado.app.ui.widget.dialog.showBookVariableDialog
import io.legado.app.ui.widget.dialog.showSourceVariableDialog
import io.legado.app.utils.StartActivityContract
import io.legado.app.utils.dpToPx
import io.legado.app.utils.sendToClip
import io.legado.app.utils.showDialogFragment
import io.legado.app.utils.toggleSystemBar
import kotlinx.coroutines.flow.filter
import java.util.Locale
import kotlin.math.abs

@SuppressLint("UnsafeOptInUsageError")
class VideoPlayActivity : BaseComposeActivity() {

    val viewModel by viewModels<VideoViewModel>()

    // ---- Compose 状态 ----
    var titleText by mutableStateOf("")
    var chapters by mutableStateOf<List<BookChapter>>(emptyList())
    var durChapterIndex by mutableIntStateOf(0)
    var inShelf by mutableStateOf(false)
    var isFullScreen by mutableStateOf(false)

    /** 手势反馈文字(原 tv_video_speed), null 时隐藏 */
    var gestureText by mutableStateOf<String?>(null)

    /** 控制层显隐(原 exo controller 显隐) */
    var controlsVisible by mutableStateOf(false)

    /** 锁定态：旁路全部手势并隐藏控制层，仅留解锁钮；不持久化，退出重置 */
    var isLocked by mutableStateOf(false)

    // 控制层回显状态, uiListener 从 Player 回喂
    var isPlaying by mutableStateOf(false)
        private set
    var playWhenReady by mutableStateOf(false)
        private set
    var playbackState by mutableIntStateOf(Player.STATE_IDLE)
        private set
    var playbackSpeed by mutableFloatStateOf(1f)
        private set

    /** 强制分辨率钮回显文字, null 时用 R.string.resolution */
    var resolutionText by mutableStateOf<String?>(null)
        private set

    /** 当前播放器, AndroidView update 中直挂渲染面 */
    var exoPlayer by mutableStateOf<ExoPlayer?>(null)
        private set
    private val player get() = exoPlayer

    private val progressTimeFormat by lazy {
        SimpleDateFormat("mm:ss", Locale.getDefault())
    }

    // 本 Activity 只会被独立启动(startActivityForBook/BookInfoActivity), 不在 Main 路由栈内,
    // 故书籍详情走 Activity 结果回调而非 navigator
    private val bookInfoResult =
        registerForActivityResult(StartActivityContract(BookInfoActivity::class.java)) { result ->
            when (result.resultCode) {
                RESULT_DELETED -> {
                    setResult(RESULT_DELETED)
                    super.finish()
                }

                RESULT_OK -> {
                    setResult(RESULT_OK)
                    inShelf = true
                }
            }
        }

    private enum class GestureMode { NONE, PROGRESS, BRIGHTNESS, VOLUME }

    /** 手势逻辑(原 VideoGestureListener 逐项等价), 由 Compose pointerInput 驱动 */
    inner class VideoGestureHandler {
        private var originalSpeed = 1f
        var speedBoosted = false
            private set
        private var position = 0L
        private val screenWidth get() = Resources.getSystem().displayMetrics.widthPixels
        private val screenHeight = 350.dpToPx()
        private var gestureMode = GestureMode.NONE
        private var startX = 0f
        private var startY = 0f
        private val deadZoneSize by lazy { 15F.dpToPx() }
        private val audioManager by lazy { getSystemService(AUDIO_SERVICE) as AudioManager }
        private var currentVolume = 0
        private var currentBrightness = 0f
        private val maxVolume by lazy { audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC) }
        private var lastScrollTime = 0L
        private val scrollThrottleInterval = 32L //ms

        fun onDoubleTap() {
            player?.let { if (it.isPlaying) it.pause() else it.play() }
        }

        fun onSingleTap() {
            controlsVisible = !controlsVisible
        }

        fun onDown(x: Float, y: Float) {
            startX = x
            startY = y
        }

        fun onLongPress() {
            player?.let { p ->
                originalSpeed = p.playbackParameters.speed
                speedBoosted = true
                val targetSpeed = originalSpeed * 2f
                p.playbackParameters =
                    PlaybackParameters(targetSpeed, p.playbackParameters.pitch)
                gestureText = String.format(
                    Locale.getDefault(), "%.1fX", targetSpeed
                )
            }
        }

        fun onScroll(x: Float, y: Float) {
            val currentTime = System.currentTimeMillis()
            if (currentTime - lastScrollTime < scrollThrottleInterval) {
                return
            }
            lastScrollTime = currentTime
            if (gestureMode == GestureMode.NONE) {
                val deltaX = abs(x - startX)
                val deltaY = abs(y - startY)

                if (deltaX < deadZoneSize && deltaY < deadZoneSize) return

                gestureMode = when {
                    deltaX > deltaY -> GestureMode.PROGRESS
                    startX < screenWidth / 2 -> {
                        currentBrightness = if (window.attributes.screenBrightness <= 0f) 0f
                        else window.attributes.screenBrightness
                        GestureMode.BRIGHTNESS
                    }

                    else -> {
                        currentVolume = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC)
                        GestureMode.VOLUME
                    }
                }
            }
            when (gestureMode) {
                GestureMode.PROGRESS -> {
                    player?.let { p ->
                        position =
                            (p.currentPosition + (x - startX) / screenWidth * 180000).toLong()
                                .coerceIn(0, p.duration)
                        gestureText = String.format(
                            "%s/%s",
                            progressTimeFormat.format(position),
                            progressTimeFormat.format(p.duration)
                        )
                    }
                }

                GestureMode.BRIGHTNESS -> {
                    val deltaBrightness =
                        (currentBrightness + (startY - y) / screenHeight).coerceIn(0f, 1f)
                    window.attributes = window.attributes.apply {
                        screenBrightness = deltaBrightness
                    }
                    if (deltaBrightness == 0f || deltaBrightness == 1f) {
                        startY = y
                        currentBrightness = deltaBrightness
                    }
                    gestureText = String.format(
                        Locale.getDefault(), "亮度: %d%%", (deltaBrightness * 100).toInt()
                    )
                }

                GestureMode.VOLUME -> {
                    val deltaVolume =
                        (currentVolume + (startY - y) / screenHeight * maxVolume).toInt()
                            .coerceIn(0, maxVolume)
                    if (deltaVolume == 0 || deltaVolume == maxVolume) {
                        startY = y
                        currentVolume = deltaVolume
                    }
                    audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, deltaVolume, 0)
                    gestureText = String.format(
                        Locale.getDefault(), "音量: %d%%", deltaVolume * 100 / maxVolume
                    )
                }

                GestureMode.NONE -> {}
            }
        }

        fun onUp() {
            if (speedBoosted) {
                player?.let { p ->
                    p.playbackParameters =
                        PlaybackParameters(originalSpeed, p.playbackParameters.pitch)
                }
                speedBoosted = false
            }
            when (gestureMode) {
                GestureMode.PROGRESS -> {
                    player?.seekTo(position)
                    player?.play()
                }

                else -> {}
            }
            gestureMode = GestureMode.NONE
            gestureText = null
        }
    }

    val gestureHandler = VideoGestureHandler()

    /** 回喂控制层 Compose 状态, 播放器换代时随迁 */
    private val uiListener = object : Player.Listener {
        override fun onIsPlayingChanged(value: Boolean) {
            isPlaying = value
        }

        override fun onPlayWhenReadyChanged(value: Boolean, reason: Int) {
            playWhenReady = value
        }

        override fun onPlaybackStateChanged(state: Int) {
            playbackState = state
        }

        override fun onPlaybackParametersChanged(parameters: PlaybackParameters) {
            playbackSpeed = parameters.speed
        }
    }

    @Composable
    override fun Content() {
        // 监听书源编辑路由回传结果 (原 sourceEditResult RESULT_OK)
        LaunchedEffect(Unit) {
            AppNavigatorProviders.getOrNull()?.results
                ?.filter { it.key == RouteResults.BOOK_SOURCE_EDIT }
                ?.collect { result ->
                    if (result.payload is RouteResultPayload.BookSourceEdit) {
                        viewModel.upSource()
                    }
                }
        }
        VideoPlayScreen(this)
    }

    override fun onActivityCreated(savedInstanceState: Bundle?) {
        viewModel.initData(intent)
        viewModel.videoUrl.observe(this) {
            if (it != null) refreshPlayer(it)
            updateResolutionText()
        }
        viewModel.resolutions.observe(this) { updateResolutionText() }
        viewModel.chapterListData.observe(this) {
            titleText = viewModel.curBook?.name ?: ""
            chapters = it
            durChapterIndex = viewModel.curBook?.durChapterIndex ?: 0
            inShelf = viewModel.inBookshelf
        }
        onBackPressedDispatcher.addCallback(this) {
            when {
                resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE -> {
                    requestedOrientation =
                        ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
                }

                isFullScreen -> applyFullScreen(false)
                else -> finish()
            }
        }
    }

    private fun setCurrentPlayer(p: ExoPlayer) {
        exoPlayer?.removeListener(uiListener)
        p.addListener(uiListener)
        exoPlayer = p
        isPlaying = p.isPlaying
        playWhenReady = p.playWhenReady
        playbackState = p.playbackState
        playbackSpeed = p.playbackParameters.speed
    }

    private fun setPlayerMediaSource(
        p: ExoPlayer,
        analyzeUrl: AnalyzeUrlCore
    ) {
        if (analyzeUrl.url.startsWith("http")) {
            p.setMediaItem(
                ExoPlayerHelper.createMediaItem(
                    analyzeUrl.url, analyzeUrl.headerMap
                )
            )
        } else {
            val fakeUrl = analyzeUrl.headerMap["Referer"]
            val dataSourceFactory = DataSource.Factory {
                object : DataSource {
                    private val httpDataSource =
                        ExoPlayerHelper.okhttpDataFactory.createDataSource()
                    private val byteArrayDataSource =
                        ByteArrayDataSource(analyzeUrl.url.toByteArray(Charsets.UTF_8))
                    private var isMemoryData = false
                    private val dataSource get() = if (isMemoryData) byteArrayDataSource else httpDataSource

                    override fun addTransferListener(transferListener: TransferListener) {
                        httpDataSource.addTransferListener(transferListener)
                        byteArrayDataSource.addTransferListener(transferListener)
                    }

                    override fun open(dataSpec: DataSpec): Long {
                        isMemoryData = dataSpec.uri == fakeUrl?.toUri()
                        return dataSource.open(dataSpec)
                    }

                    override fun read(buffer: ByteArray, offset: Int, length: Int): Int =
                        dataSource.read(buffer, offset, length)

                    override fun getUri(): Uri? = dataSource.uri
                    override fun close() = dataSource.close()
                }
            }
            p.setMediaSource(
                HlsMediaSource.Factory(dataSourceFactory).createMediaSource(
                    MediaItem.Builder().setUri(fakeUrl).setMimeType(MimeTypes.APPLICATION_M3U8)
                        .build()
                )
            )
        }
    }

    private fun refreshPlayer(analyzeUrl: AnalyzeUrlCore) {
        val p = player ?: ExoPlayerHelper.createHttpExoPlayer(this).apply {
            addListener(object : Player.Listener {
                override fun onPlaybackStateChanged(playbackState: Int) {
                    if (playbackState == Player.STATE_ENDED) {
                        viewModel.moveToNextChapter()
                    }
                }

                override fun onPlayerError(error: PlaybackException) {
                    val retried = viewModel.sharedVM.retryOnPlayError()
                    if (!retried && error is ExoPlaybackException && error.type == ExoPlaybackException.TYPE_SOURCE) {
                        val msg = when (error.sourceException) {
                            is UnrecognizedInputFormatException -> "不是视频链接"
                            is HttpDataSource.InvalidResponseCodeException -> "视频地址不可用"
                            else -> "视频播放出错"
                        }
                        AppLog.put(msg, error, true)
                    }
                }
            })
            setCurrentPlayer(this)
        }
        setPlayerMediaSource(p, analyzeUrl)
        p.apply {
            if (viewModel.position != 0L) {
                seekTo(viewModel.position)
            }
            prepare()
            play()
        }
    }

    // ---- 控制层动作 ----

    /** 播放/暂停钮(原 exo_play_pause): 播完态回起点重播 */
    fun playButton() {
        val p = player ?: return
        when {
            p.isPlaying -> p.pause()
            p.playbackState == Player.STATE_ENDED -> {
                p.seekToDefaultPosition()
                p.play()
            }

            else -> p.play()
        }
    }

    fun setPlaySpeed(speed: Float) {
        player?.let { p ->
            p.playbackParameters = PlaybackParameters(speed, p.playbackParameters.pitch)
        }
    }

    fun seekTo(positionMs: Long) {
        player?.seekTo(positionMs)
    }

    /** 后退钮(原 exo controller rewind): 按播放器 seekBackIncrement(默认5s)后退 */
    fun seekBack() {
        player?.seekBack()
    }

    /** 前进钮(原 exo controller ffwd): 按播放器 seekForwardIncrement(默认15s)前进 */
    fun seekForward() {
        player?.seekForward()
    }

    /** 上一集钮(原 exo_prev): 切到选集列表前一集 */
    fun playPrevChapter() {
        viewModel.moveToPrevChapter()
        durChapterIndex = viewModel.curBook?.durChapterIndex ?: 0
    }

    /** 下一集钮(原 exo_next): 切到选集列表后一集 */
    fun playNextChapter() {
        viewModel.moveToNextChapter()
        durChapterIndex = viewModel.curBook?.durChapterIndex ?: 0
    }

    /** 全屏钮(原 setFullscreenButtonClickListener): 横竖屏互切 */
    fun toggleOrientationFullscreen() {
        requestedOrientation =
            if (resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE) {
                ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
            } else {
                ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
            }
    }

    // ---- 菜单动作(原 onCompatOptionsItemSelected) ----

    fun refreshChapter() {
        player?.pause()
        viewModel.refreshChapter()
    }

    fun toggleShelf() {
        if (viewModel.inBookshelf) {
            if (AppConfig.bookInfoDeleteAlert) {
                alert(
                    titleResource = R.string.draw, messageResource = R.string.sure_del
                ) {
                    yesButton {
                        viewModel.delBook {
                            setResult(RESULT_DELETED)
                            finish()
                        }
                    }
                    noButton()
                }
            } else {
                viewModel.delBook {
                    setResult(RESULT_DELETED)
                    finish()
                }
            }
        } else {
            viewModel.addToBookshelf {
                setResult(RESULT_OK)
                inShelf = true
            }
        }
    }

    fun toggleFullScreen() = applyFullScreen(!isFullScreen)

    fun showLogin() {
        viewModel.curBookSource?.let {
            IntentData.book = viewModel.curBook
            IntentData.put(
                "nowChapter",
                viewModel.chapterListData.value?.get(viewModel.curBook!!.durChapterIndex)
            )
            it.showLoginDialog()
        }
    }

    fun copyPlayUrl() {
        viewModel.videoUrl.value?.let { sendToClip(it.url) }
    }

    fun showSourceVariable() {
        viewModel.curBookSource?.showSourceVariableDialog(this)
    }

    fun showBookVariable() {
        viewModel.curBook!!.showBookVariableDialog(this, viewModel.curBookSource)
    }

    fun editSource() {
        viewModel.curBookSource?.let {
            AppNavigatorProviders.getOrNull()?.push(
                AppRoute.BookSourceEdit(it.bookSourceUrl),
                resultKey = RouteResults.BOOK_SOURCE_EDIT,
            )
        }
    }

    fun openReview() = viewModel.openCommentDialog(this)

    fun showAppLog() = showDialogFragment<AppLogDialog>()

    fun addBookmark() {
        val book = viewModel.curBook ?: return
        val pos = player?.currentPosition ?: 0L
        val dur = player?.duration?.takeIf { it > 0 } ?: 0L
        val chapters = viewModel.chapterListData.value
        val chapter = chapters?.getOrNull(book.durChapterIndex)
        val bookmark = viewModel.sharedVM.createBookmark(
            positionMs = pos,
            durationMs = dur,
            bookName = book.name,
            chapterIndex = book.durChapterIndex,
            chapterName = chapter?.title ?: book.durChapterTitle ?: "",
        ) ?: return
        showDialogFragment(BookmarkDialog(bookmark))
    }

    fun onTitleClick() {
        bookInfoResult.launch {
            IntentData.book = viewModel.curBook
            IntentData.chapterList = viewModel.chapterListData.value
            player?.pause()
        }
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        when (newConfig.orientation) {
            Configuration.ORIENTATION_LANDSCAPE -> applyFullScreen(true)
            Configuration.ORIENTATION_PORTRAIT -> applyFullScreen(false)
            else -> {}
        }
        super.onConfigurationChanged(newConfig)
    }

    private fun applyFullScreen(isFull: Boolean) {
        toggleSystemBar(!isFull)
        isFullScreen = isFull
    }

    private fun updateResolutionText() {
        val resolutions = viewModel.resolutions.value
        resolutionText = if (resolutions != null && resolutions.size > 1) {
            resolutions.getOrNull(viewModel.currentResolutionIndex)?.name
        } else {
            null
        }
    }

    fun showResolutionDialog() {
        val resolutions = viewModel.resolutions.value

        if (resolutions.isNullOrEmpty() || resolutions.size <= 1) {
            player?.let { p ->
                TrackSelectionDialogBuilder(
                    this, getString(R.string.resolution), p, C.TRACK_TYPE_VIDEO
                ).build().show()
            }
            return
        }

        val names = resolutions.map { it.name }.toTypedArray()
        alert(titleResource = R.string.resolution) {
            singleChoiceItems(names, viewModel.currentResolutionIndex) { dialog, which ->
                controlsVisible = false
                dialog.dismiss()
                if (which != viewModel.currentResolutionIndex) {
                    switchResolution(which)
                }
            }
        }
    }

    fun switchResolution(index: Int) {
        val source = viewModel.videoSource.value ?: return
        val resolution = source.getResolution(index) ?: return
        val seekPosition = if (player == null) 0L else player!!.currentPosition + 800L
        viewModel.currentResolutionIndex = index
        updateResolutionText()

        val analyzeUrl = AnalyzeUrl(
            rawUrl = resolution.url,
            source = viewModel.curBookSource,
            headerMapF = source.headers
        )
        val newPlayer = ExoPlayerHelper.createHttpExoPlayer(this)
        setPlayerMediaSource(newPlayer, analyzeUrl)
        newPlayer.seekTo(seekPosition)
        newPlayer.addListener(object : Player.Listener,
            androidx.lifecycle.DefaultLifecycleObserver {
            init {
                lifecycle.addObserver(this)
            }

            override fun onPlaybackStateChanged(playbackState: Int) {
                if (playbackState == Player.STATE_READY) {
                    val oldPlayer = player
                    setCurrentPlayer(newPlayer)
                    if (oldPlayer?.isPlaying ?: false) newPlayer.play()
                    oldPlayer?.stop()
                    oldPlayer?.release()
                    newPlayer.removeListener(this)
                    lifecycle.removeObserver(this)
                }
            }

            override fun onDestroy(owner: androidx.lifecycle.LifecycleOwner) {
                if (exoPlayer != newPlayer) {
                    newPlayer.release()
                }
            }
        })
        newPlayer.prepare()
    }

    override fun onResume() {
        super.onResume()
        ReadTimeRecorder.start(ReadTimeRecorder.Source.VIDEO, viewModel.curBook?.name ?: "")
    }

    override fun onPause() {
        super.onPause()
        ReadTimeRecorder.end(ReadTimeRecorder.Source.VIDEO)
        val currentPlayer = player
        val duration = currentPlayer?.duration ?: 0L
        val position = currentPlayer?.currentPosition ?: 0L
        viewModel.sharedVM.saveVideoProgressOnExit(position, duration)
        viewModel.saveRead(
            if (duration > 0 && position > duration - 1000) -1L
            else position
        )
        if (viewModel.inBookshelf) {
            viewModel.curBook?.let { viewModel.uploadProgress(it) }
        }
    }

    override fun onDestroy() {
        if (!isChangingConfigurations) {
            ReadTimeRecorder.endImmediately(ReadTimeRecorder.Source.VIDEO)
        }
        player?.release()
        super.onDestroy()
    }

    fun openChapter(bookChapter: BookChapter) {
        viewModel.loadChapter(bookChapter.index)
        durChapterIndex = viewModel.curBook!!.durChapterIndex
    }
}
