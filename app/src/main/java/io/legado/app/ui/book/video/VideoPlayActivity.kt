package io.legado.app.ui.book.video

import android.annotation.SuppressLint
import android.content.res.Configuration
import android.content.res.Resources
import android.icu.text.SimpleDateFormat
import android.os.Bundle
import android.view.GestureDetector
import android.view.Menu
import android.view.MenuItem
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import androidx.activity.addCallback
import androidx.activity.viewModels
import androidx.constraintlayout.widget.ConstraintLayout
import androidx.core.view.isVisible
import androidx.lifecycle.lifecycleScope
import androidx.media3.common.C
import androidx.media3.common.PlaybackException
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.HttpDataSource
import androidx.media3.datasource.ResolvingDataSource
import androidx.media3.exoplayer.ExoPlaybackException
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.source.UnrecognizedInputFormatException
import androidx.media3.ui.TrackSelectionDialogBuilder
import androidx.recyclerview.widget.GridLayoutManager
import io.legado.app.R
import io.legado.app.base.VMBaseActivity
import io.legado.app.constant.BookType
import io.legado.app.data.GlobalVars
import io.legado.app.data.entities.BookChapter
import io.legado.app.databinding.ActivityVideoPlayBinding
import io.legado.app.help.book.isNotShelf
import io.legado.app.help.book.removeType
import io.legado.app.help.config.LocalConfig
import io.legado.app.lib.dialogs.alert
import io.legado.app.ui.about.AppLogDialog
import io.legado.app.ui.book.info.BookInfoActivity
import io.legado.app.ui.book.read.ReadBookActivity.Companion.RESULT_DELETED
import io.legado.app.ui.book.source.edit.BookSourceEditActivity
import io.legado.app.ui.book.toc.ChapterListAdapter
import io.legado.app.ui.login.SourceLoginActivity
import io.legado.app.utils.StartActivityContract
import io.legado.app.utils.sendToClip
import io.legado.app.utils.showDialogFragment
import io.legado.app.utils.startActivity
import io.legado.app.utils.toastOnUi
import io.legado.app.utils.toggleSystemBar
import io.legado.app.utils.viewbindingdelegate.viewBinding
import kotlinx.coroutines.launch
import java.util.Locale
import kotlin.math.abs
// Android 核心包
import android.net.Uri

// Media3 基础和通用包
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes

// Media3 数据源 (DataSource) 相关包
import androidx.media3.datasource.ByteArrayDataSource
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.datasource.TransferListener

// Media3 HLS (m3u8) 专属解析包
import androidx.media3.exoplayer.hls.HlsMediaSource

class VideoPlayActivity(
) : VMBaseActivity<ActivityVideoPlayBinding, VideoViewModel>(),
    ChapterListAdapter.Callback {

    override val binding by viewBinding(ActivityVideoPlayBinding::inflate)
    override val viewModel by viewModels<VideoViewModel>()
    private val adapter by lazy { ChapterListAdapter(this, this) }
    private var isFullScreen = false
    private var currentSpeed = 1f
    private var originalSpeed = 1f
    private var position = 0L
    private val screenWidth = Resources.getSystem().displayMetrics.widthPixels
    private var endX = 0f
    private var isPress = false
    private var isScroll = false

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
                    viewModel.book.removeType(BookType.notShelf)
                }
            }
        }
    private val progressTimeFormat by lazy {
        SimpleDateFormat("mm:ss", Locale.getDefault())
    }

    @delegate:SuppressLint("UnsafeOptInUsageError")
    private val player by lazy {
        ExoPlayer.Builder(this)
            .setMediaSourceFactory(
                DefaultMediaSourceFactory(this).apply {
                    setDataSourceFactory(
                        ResolvingDataSource.Factory(
                            DefaultDataSource.Factory(
                                this@VideoPlayActivity,
                                DefaultHttpDataSource.Factory()
                            )
                        ) { dataSpec ->
                            dataSpec.withRequestHeaders(
                                viewModel.videoUrl.value?.headerMap ?: mapOf()
                            )
                        })
                }
            ).build().apply {
                binding.ivPlayer.player = this
                binding.ivPlayer.controllerAutoShow = false
                addListener(object : Player.Listener {
                    override fun onPlaybackStateChanged(playbackState: Int) {
                        super.onPlaybackStateChanged(playbackState)
                        if (playbackState == Player.STATE_ENDED && viewModel.chapterList.value!!.size != viewModel.book.durChapterIndex + 1) {
                            openChapter(viewModel.chapterList.value!![viewModel.book.durChapterIndex + 1])
                        }
                    }

                    override fun onPlayerError(error: PlaybackException) {
                        if (error is ExoPlaybackException) {
                            this@VideoPlayActivity.toastOnUi(
                                when (error.sourceException) {
                                    is UnrecognizedInputFormatException -> "不是视频链接"
                                    is HttpDataSource.InvalidResponseCodeException -> "视频地址不可用"
                                    else -> "视频播放出错"
                                }
                            )
                        }
                        super.onPlayerError(error)
                    }
                })
            }
    }

    override fun onActivityCreated(savedInstanceState: Bundle?) {
        viewModel.initData()
        viewModel.bookTitle.observe(this) {
            binding.titleBar.title = it
        }
        viewModel.videoUrl.observe(this) {
            refreshPlayer(it.url)
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
                this,
                getString(R.string.resolution),
                player,
                C.TRACK_TYPE_VIDEO
            ).build().show()
        }
        binding.ivPlayer.setFullscreenButtonClickListener { isFullScreenRequested ->
            if (isFullScreen != isFullScreenRequested) {
                toggleFullScreen()
            }
        }
        onBackPressedDispatcher.addCallback(this) {
            if (isFullScreen) {
                toggleFullScreen()
                return@addCallback
            }
            finish()
        }

    }

    @SuppressLint("SetTextI18n")
    private fun refreshPlayer(videoUrl: String) {
        if (videoUrl.startsWith("http")) {
    player.setMediaItem(MediaItem.fromUri(videoUrl))
} else {
    // 1. 创建一个自定义的假 URI 代表你的内存 m3u8
    val customM3u8Uri = Uri.parse("custom://memory.m3u8")
    val m3u8Bytes = videoUrl.toByteArray(Charsets.UTF_8)

    // 2. 告诉 ExoPlayer 这是一个 HLS 流 (APPLICATION_M3U8)
    val mediaItem = MediaItem.Builder()
        .setUri(customM3u8Uri)
        .setMimeType(MimeTypes.APPLICATION_M3U8) 
        .build()

    // 3. 构建一个智能的 DataSource Factory
    val dataSourceFactory = DataSource.Factory {
        object : DataSource {
            // 底层使用 HttpDataSource 来下载真正的视频切片
            private val httpDataSource = DefaultHttpDataSource.Factory().createDataSource()
            // 使用 ByteArrayDataSource 来返回内存中的 m3u8 文本
            private val byteArrayDataSource = ByteArrayDataSource(m3u8Bytes)
            
            private var isMemoryData = false

            override fun addTransferListener(transferListener: TransferListener) {
                httpDataSource.addTransferListener(transferListener)
                byteArrayDataSource.addTransferListener(transferListener)
            }

            override fun open(dataSpec: DataSpec): Long {
                // 如果是请求我们自定义的假 URI，就走内存
                return if (dataSpec.uri == customM3u8Uri) {
                    isMemoryData = true
                    byteArrayDataSource.open(dataSpec)
                } else {
                    // 否则（例如请求 .ts 切片），走网络下载
                    isMemoryData = false
                    httpDataSource.open(dataSpec)
                }
            }

            override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
                return if (isMemoryData) {
                    byteArrayDataSource.read(buffer, offset, length)
                } else {
                    httpDataSource.read(buffer, offset, length)
                }
            }

            override fun getUri(): Uri? {
                return if (isMemoryData) byteArrayDataSource.uri else httpDataSource.uri
            }

            override fun close() {
                if (isMemoryData) {
                    byteArrayDataSource.close()
                } else {
                    httpDataSource.close()
                }
            }
        }
    }

    // 4. 使用专门处理 HLS 的 HlsMediaSource
    val mediaSource = HlsMediaSource.Factory(dataSourceFactory)
        .createMediaSource(mediaItem)

    player.setMediaSource(mediaSource)
}
        player.apply {
            if (viewModel.position != 0L) seekTo(viewModel.position)
            play()
            prepare()
        }
        val gestureDetector =
            GestureDetector(this, object : GestureDetector.SimpleOnGestureListener() {
                override fun onLongPress(e: MotionEvent) {
                    isPress = true
                    originalSpeed = player.playbackParameters.speed
                    currentSpeed = originalSpeed * 2f
                    player.playbackParameters =
                        PlaybackParameters(currentSpeed, player.playbackParameters.pitch)
                    binding.tvVideoSpeed.text = "${currentSpeed}X"
                    binding.tvVideoSpeed.visibility = View.VISIBLE
                }

                override fun onDoubleTap(e: MotionEvent): Boolean {
                    if (player.isPlaying) player.pause() else player.play()
                    return true
                }

                override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
                    binding.ivPlayer.performClick()
                    return true
                }


                override fun onScroll(
                    e1: MotionEvent?,
                    e2: MotionEvent,
                    distanceX: Float,
                    distanceY: Float
                ): Boolean {
                    if (abs(distanceX) > abs(distanceY)) {
                        isScroll = true
                        player.pause()
                        endX += distanceX
                        position = (player.currentPosition - (endX / screenWidth) * 180000).toLong()
                            .coerceIn(0, player.duration)
                        binding.tvVideoSpeed.text =
                            progressTimeFormat.format(position) + "/" + progressTimeFormat.format(
                                player.duration
                            )
                        binding.tvVideoSpeed.visibility = View.VISIBLE
                        return true
                    }
                    return false
                }
            })
        @SuppressLint("ClickableViewAccessibility")
        binding.ivPlayer.setOnTouchListener { _, event ->
            gestureDetector.onTouchEvent(event)
            when (event.action) {
                MotionEvent.ACTION_UP -> {
                    if (isScroll) {
                        isScroll = false
                        endX = 0F
                        player.seekTo(position)
                        player.play()
                    }
                    if (isPress) {
                        isPress = false
                        player.playbackParameters = PlaybackParameters(
                            originalSpeed,
                            player.playbackParameters.pitch
                        )
                    }
                    binding.tvVideoSpeed.visibility = View.GONE
                }
            }
            true
        }

    }

    override fun onCompatCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.video_play, menu)
        return super.onCompatCreateOptionsMenu(menu)
    }

    override fun onPrepareOptionsMenu(menu: Menu): Boolean {
        menu.findItem(R.id.menu_login)?.isVisible =
            !viewModel.bookSource?.loginUrl.isNullOrBlank()
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
//            R.id.menu_change_source -> {
//            }
            R.id.menu_refresh -> viewModel.initChapter(viewModel.chapterList.value?.get(viewModel.book.durChapterIndex)!!)
            R.id.menu_shelf -> {
                if (!viewModel.book.isNotShelf) {
                    if (LocalConfig.bookInfoDeleteAlert) {
                        alert(
                            titleResource = R.string.draw,
                            messageResource = R.string.sure_del
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

            R.id.menu_full_screen -> toggleFullScreen()
            R.id.menu_resolution -> {
                TrackSelectionDialogBuilder(
                    this,
                    getString(R.string.resolution),
                    player,
                    C.TRACK_TYPE_VIDEO
                ).build().show()
            }

            R.id.menu_login -> viewModel.bookSource?.let {
                GlobalVars.nowSource = it
                GlobalVars.nowBook = viewModel.book
                GlobalVars.nowChapter =
                    viewModel.chapterList.value?.get(viewModel.book.durChapterIndex)
                startActivity<SourceLoginActivity> {}
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
        // 根据屏幕方向更新全屏状态
        when (newConfig.orientation) {
            Configuration.ORIENTATION_LANDSCAPE -> {
                // 横屏时进入全屏
                if (!isFullScreen) {
                    isFullScreen = true
                    enterFullScreen()
                }
            }

            Configuration.ORIENTATION_PORTRAIT -> {
                // 竖屏时退出全屏
                if (isFullScreen) {
                    isFullScreen = false
                    exitFullScreen()
                }
            }
        }
        super.onConfigurationChanged(newConfig)
    }

    private fun toggleFullScreen() {
        if (isFullScreen) {
            exitFullScreen()
        } else {
            enterFullScreen()
        }
    }

    /**
     * 进入全屏模式
     */
    private fun enterFullScreen() {
        isFullScreen = true
        toggleSystemBar(false) // 隐藏系统栏
        supportActionBar?.hide()
        binding.titleBar.isVisible = false
        binding.ivPlayer.layoutParams.height = WindowManager.LayoutParams.MATCH_PARENT
        binding.recyclerView.isVisible = false
    }

    /**
     * 退出全屏模式
     */
    private fun exitFullScreen() {
        isFullScreen = false
        toggleSystemBar(true) // 显示系统栏
        supportActionBar?.show()
        binding.titleBar.isVisible = true
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
        viewModel.saveRead(if (player.currentPosition > player.duration - 1000) 0L else player.currentPosition)
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
