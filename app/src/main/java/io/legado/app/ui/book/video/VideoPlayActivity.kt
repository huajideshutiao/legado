package io.legado.app.ui.book.video

import android.annotation.SuppressLint
import android.content.pm.ActivityInfo
import android.content.res.Configuration
import android.content.res.Resources
import android.icu.text.SimpleDateFormat
import android.media.AudioManager
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.GestureDetector
import android.view.Menu
import android.view.MenuItem
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import androidx.activity.addCallback
import androidx.activity.viewModels
import androidx.constraintlayout.widget.ConstraintLayout
import androidx.core.net.toUri
import androidx.core.view.isVisible
import androidx.lifecycle.lifecycleScope
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
import androidx.media3.exoplayer.hls.HlsMediaSource
import androidx.media3.exoplayer.source.UnrecognizedInputFormatException
import androidx.media3.ui.TrackSelectionDialogBuilder
import androidx.recyclerview.widget.GridLayoutManager
import io.legado.app.R
import io.legado.app.base.VMBaseActivity
import io.legado.app.constant.AppLog
import io.legado.app.data.GlobalVars
import io.legado.app.data.entities.BookChapter
import io.legado.app.databinding.ActivityVideoPlayBinding
import io.legado.app.help.book.isNotShelf
import io.legado.app.help.config.LocalConfig
import io.legado.app.help.exoplayer.ExoPlayerHelper
import io.legado.app.lib.dialogs.alert
import io.legado.app.model.analyzeRule.AnalyzeUrl
import io.legado.app.ui.about.AppLogDialog
import io.legado.app.ui.book.info.BookInfoActivity
import io.legado.app.ui.book.read.ReadBookActivity.Companion.RESULT_DELETED
import io.legado.app.ui.book.source.edit.BookSourceEditActivity
import io.legado.app.ui.book.toc.ChapterListAdapter
import io.legado.app.utils.StartActivityContract
import io.legado.app.utils.dpToPx
import io.legado.app.utils.sendToClip
import io.legado.app.utils.showDialogFragment
import io.legado.app.utils.toggleSystemBar
import io.legado.app.utils.viewbindingdelegate.viewBinding
import kotlinx.coroutines.launch
import java.util.Locale
import kotlin.math.abs

@SuppressLint("UnsafeOptInUsageError")
class VideoPlayActivity(
) : VMBaseActivity<ActivityVideoPlayBinding, VideoViewModel>(), ChapterListAdapter.Callback {

    override val binding by viewBinding(ActivityVideoPlayBinding::inflate)
    override val viewModel by viewModels<VideoViewModel>()
    private val adapter by lazy { ChapterListAdapter(this, this) }
    private var isFullScreen = false
    private var originalSpeed = 1f
    private var position = 0L
    private var screenWidth = Resources.getSystem().displayMetrics.widthPixels

    private val sourceEditResult =
        registerForActivityResult(StartActivityContract(BookSourceEditActivity::class.java)) {
            if (it.resultCode == RESULT_OK) {
                viewModel.upSource()
            }
        }
    private val bookInfoResult =
        registerForActivityResult(StartActivityContract(BookInfoActivity::class.java)) { result ->
            when (result.resultCode) {
                RESULT_DELETED -> {
                    setResult(RESULT_DELETED)
                    super.finish()
                }

                RESULT_OK -> {
                    setResult(RESULT_OK)
                    val item = binding.titleBar.menu.findItem(R.id.menu_shelf)
                    item.setIcon(R.drawable.ic_star)
                    item.setTitle(R.string.in_favorites)
                }
            }
        }
    private val progressTimeFormat by lazy {
        SimpleDateFormat("mm:ss", Locale.getDefault())
    }

    private val player by lazy {
        ExoPlayerHelper.createHttpExoPlayer(this).apply {
            addListener(object : Player.Listener {
                override fun onPlaybackStateChanged(playbackState: Int) {
                    super.onPlaybackStateChanged(playbackState)
                    if (playbackState == Player.STATE_ENDED && viewModel.chapterList.value!!.size != viewModel.book.durChapterIndex + 1) {
                        openChapter(viewModel.chapterList.value!![viewModel.book.durChapterIndex + 1])
                    }
                }

                override fun onPlayerError(error: PlaybackException) {
                    if (error is ExoPlaybackException && error.type == ExoPlaybackException.TYPE_SOURCE) {
                        val msg = when (error.sourceException) {
                            is UnrecognizedInputFormatException -> "不是视频链接"
                            is HttpDataSource.InvalidResponseCodeException -> "视频地址不可用"
                            else -> "视频播放出错"
                        }
                        AppLog.put(msg, error, true)
                    }
                    super.onPlayerError(error)
                }
            })
            binding.ivPlayer.player = this
        }
    }

    private enum class GestureMode { NONE, PROGRESS, BRIGHTNESS, VOLUME }

    private inner class VideoGestureListener : GestureDetector.SimpleOnGestureListener() {
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

        override fun onDoubleTap(e: MotionEvent): Boolean {
            if (player.isPlaying) player.pause() else player.play()
            return true
        }

        override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
            binding.ivPlayer.performClick()
            return true
        }

        override fun onDown(e: MotionEvent): Boolean {
            startX = e.x
            startY = e.y
            return true
        }

        override fun onLongPress(e: MotionEvent) {
            originalSpeed = player.playbackParameters.speed
            player.playbackParameters =
                PlaybackParameters(originalSpeed * 2f, player.playbackParameters.pitch)
            binding.tvVideoSpeed.text = String.format(
                Locale.getDefault(), "%.1fX", originalSpeed * 2f
            )
            binding.tvVideoSpeed.visibility = View.VISIBLE
        }

        override fun onScroll(
            e1: MotionEvent?, e2: MotionEvent, distanceX: Float, distanceY: Float
        ): Boolean {
            e1 ?: return false
            val currentTime = System.currentTimeMillis()
            if (currentTime - lastScrollTime < scrollThrottleInterval) {
                return true
            }
            lastScrollTime = currentTime
            when (gestureMode) {
                GestureMode.NONE -> {
                    val deltaX = abs(e2.x - startX)
                    val deltaY = abs(e2.y - startY)

                    if (deltaX < deadZoneSize && deltaY < deadZoneSize) return false

                    gestureMode = when {
                        deltaX > deltaY -> GestureMode.PROGRESS
                        e1.x < screenWidth / 2 -> {
                            currentBrightness = if (window.attributes.screenBrightness <= 0f) 0f
                            else window.attributes.screenBrightness
                            GestureMode.BRIGHTNESS
                        }

                        else -> {
                            currentVolume = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC)
                            GestureMode.VOLUME
                        }
                    }
                    Handler(Looper.getMainLooper()).postDelayed({
                        binding.tvVideoSpeed.visibility = View.VISIBLE
                    }, 50)
                }

                GestureMode.PROGRESS -> {
                    position =
                        (player.currentPosition + (e2.x - startX) / screenWidth * 180000).toLong()
                            .coerceIn(0, player.duration)
                    binding.tvVideoSpeed.text = String.format(
                        "%s/%s",
                        progressTimeFormat.format(position),
                        progressTimeFormat.format(player.duration)
                    )
                }

                GestureMode.BRIGHTNESS -> {
                    val deltaBrightness =
                        (currentBrightness + (startY - e2.y) / screenHeight).coerceIn(0f, 1f)
                    window.attributes = window.attributes.apply {
                        screenBrightness = deltaBrightness
                    }
                    if (deltaBrightness == 0f) {
                        startY = e2.y
                        currentBrightness = 0f
                    }
                    if (deltaBrightness == 1f) {
                        startY = e2.y
                        currentBrightness = 1f
                    }
                    binding.tvVideoSpeed.text = String.format(
                        Locale.getDefault(), "亮度: %d%%", (deltaBrightness * 100).toInt()
                    )
                }

                GestureMode.VOLUME -> {
                    val deltaVolume =
                        (currentVolume + (startY - e2.y) / screenHeight * maxVolume).toInt()
                            .coerceIn(0, maxVolume)
                    if (deltaVolume == 0) {
                        startY = e2.y
                        currentVolume = 0
                    }
                    if (deltaVolume == maxVolume) {
                        startY = e2.y
                        currentVolume = maxVolume
                    }
                    audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, deltaVolume, 0)
                    binding.tvVideoSpeed.text = String.format(
                        Locale.getDefault(), "音量: %d%%", deltaVolume * 100 / maxVolume
                    )
                }
            }
            return true
        }

        fun onUp() {
            when (gestureMode) {
                GestureMode.PROGRESS -> {
                    player.seekTo(position)
                    player.play()
                }

                GestureMode.NONE -> {
                    if (originalSpeed != player.playbackParameters.speed) player.playbackParameters =
                        PlaybackParameters(
                            originalSpeed, player.playbackParameters.pitch
                        )
                }

                else -> {}
            }
            gestureMode = GestureMode.NONE
            binding.tvVideoSpeed.visibility = View.GONE
        }
    }

    private val gestureListener by lazy { VideoGestureListener() }
    private val gestureDetector by lazy {
        GestureDetector(this, gestureListener)
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onActivityCreated(savedInstanceState: Bundle?) {
        viewModel.initData()
        binding.titleBar.title = viewModel.bookTitle
        viewModel.videoUrl.observe(this) {
            refreshPlayer(it)
        }
        viewModel.chapterList.observe(this) {
            if (it.size > 1) {
                binding.recyclerView.layoutManager = GridLayoutManager(this, 3)
                binding.recyclerView.adapter = adapter
                adapter.setItems(it)
                binding.recyclerView.scrollToPosition(viewModel.book.durChapterIndex)
                adapter.upDisplayTitles(viewModel.book.durChapterIndex)
            }
            showChapterList(it)
        }
        binding.titleBar.toolbar.setOnClickListener {
            bookInfoResult.launch {
                player.pause()
            }
        }
        binding.ivPlayer.findViewById<View>(R.id.tv_force_resolution)?.setOnClickListener {
            TrackSelectionDialogBuilder(
                this, getString(R.string.resolution), player, C.TRACK_TYPE_VIDEO
            ).build().show()
        }
        binding.ivPlayer.setFullscreenButtonClickListener { isFullScreenRequested ->
            requestedOrientation = if (isFullScreenRequested) {
                ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
            } else {
                ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
            }
        }
        binding.ivPlayer.setOnTouchListener { _, event ->
            gestureDetector.onTouchEvent(event)
            if (event.action == MotionEvent.ACTION_UP) {
                gestureListener.onUp()
            }
            true
        }
        onBackPressedDispatcher.addCallback(this) {
            when {
                resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE ->{
                    //传入横屏状态
                    binding.ivPlayer.setFullscreenButtonState(false)
                    requestedOrientation =
                        ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
                }

                isFullScreen -> exitFullScreen()
                else -> finish()
            }
        }

    }

    @SuppressLint("SetTextI18n")
    private fun refreshPlayer(analyzeUrl: AnalyzeUrl) {
        if (analyzeUrl.url.startsWith("http")) {
            player.setMediaItem(
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
            player.setMediaSource(
                HlsMediaSource.Factory(dataSourceFactory).createMediaSource(
                    MediaItem.Builder().setUri(fakeUrl).setMimeType(MimeTypes.APPLICATION_M3U8)
                        .build()
                )
            )
        }
        player.apply {
            if (viewModel.position != 0L) seekTo(viewModel.position)
            play()
            prepare()
        }
    }


    override fun onCompatCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.video_play, menu)
        return super.onCompatCreateOptionsMenu(menu)
    }

    override fun onPrepareOptionsMenu(menu: Menu): Boolean {
        menu.findItem(R.id.menu_login)?.isVisible = !viewModel.bookSource?.loginUrl.isNullOrBlank()
        menu.findItem(R.id.menu_shelf).apply {
            if (!viewModel.book.isNotShelf) {
                setIcon(R.drawable.ic_star)
                setTitle(R.string.in_favorites)
            } else {
                setIcon(R.drawable.ic_star_border)
                setTitle(R.string.out_favorites)
            }
        }
        return super.onPrepareOptionsMenu(menu)
    }

    override fun onCompatOptionsItemSelected(item: MenuItem): Boolean {
        when (item.itemId) {
            R.id.menu_refresh -> {
                player.pause()
                viewModel.initChapter(viewModel.chapterList.value?.get(viewModel.book.durChapterIndex)!!)
            }

            R.id.menu_shelf -> {
                if (!viewModel.book.isNotShelf) {
                    if (LocalConfig.bookInfoDeleteAlert) {
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
                        item.setIcon(R.drawable.ic_star)
                        item.setTitle(R.string.in_favorites)
                    }
                }
            }

            R.id.menu_full_screen -> if (isFullScreen) {
                exitFullScreen()
            } else {
                enterFullScreen()
            }

            R.id.menu_login -> viewModel.bookSource?.let {
                GlobalVars.nowBook = viewModel.book
                GlobalVars.nowChapter =
                    viewModel.chapterList.value?.get(viewModel.book.durChapterIndex)
                it.showLoginDialog(this)
            }

            R.id.menu_copy_audio_url -> viewModel.videoUrl.value?.let { sendToClip(it.url) }
            R.id.menu_edit_source -> viewModel.bookSource?.let {
                GlobalVars.nowSource = it
                sourceEditResult.launch {}
            }

            R.id.menu_log -> showDialogFragment<AppLogDialog>()
        }
        return super.onCompatOptionsItemSelected(item)
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        when (newConfig.orientation) {
            Configuration.ORIENTATION_LANDSCAPE -> enterFullScreen()
            Configuration.ORIENTATION_PORTRAIT -> exitFullScreen()
            else -> {}
        }
        screenWidth = Resources.getSystem().displayMetrics.widthPixels
        super.onConfigurationChanged(newConfig)
    }

    private fun enterFullScreen() {
        isFullScreen = true
        toggleSystemBar(false)
        supportActionBar?.hide()
        binding.ivPlayer.layoutParams.height = WindowManager.LayoutParams.MATCH_PARENT
        binding.recyclerView.isVisible = false
    }

    private fun exitFullScreen() {
        isFullScreen = false
        toggleSystemBar(true)
        supportActionBar?.show()
        binding.ivPlayer.layoutParams.height = 0
        viewModel.chapterList.value?.let { showChapterList(it) }
    }

    private fun showChapterList(list: List<BookChapter>) {
        if (list.size > 1) {
            binding.recyclerView.isVisible = true
            (binding.ivPlayer.layoutParams as ConstraintLayout.LayoutParams).apply {
                dimensionRatio = "h,16:9"
                bottomToTop = binding.recyclerView.id
            }
        } else {
            binding.recyclerView.isVisible = false
            (binding.ivPlayer.layoutParams as ConstraintLayout.LayoutParams).apply {
                dimensionRatio = ""
            }
        }
    }

    override fun onDestroy() {
        viewModel.saveRead(
            if (player.currentPosition > player.duration - 1000) 0L
            else player.currentPosition
        )
        player.release()
        super.onDestroy()
    }

    override val scope = lifecycleScope
    override val book by lazy { viewModel.book }
    override val isLocalBook = false
    override fun openChapter(bookChapter: BookChapter) {
        lifecycleScope.launch {
            val tmp = viewModel.book.durChapterIndex
            viewModel.changeChapter(bookChapter)
            adapter.notifyItemChanged(tmp)
            adapter.notifyItemChanged(bookChapter.index)
        }
    }

    override fun durChapterIndex(): Int = viewModel.book.durChapterIndex
    override fun onListChanged() {}
}
