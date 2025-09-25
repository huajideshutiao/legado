package io.legado.app.ui.book.video

import android.annotation.SuppressLint
import android.content.res.Configuration
import android.content.res.Resources
import android.icu.text.SimpleDateFormat
import android.os.Build
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
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.datasource.ResolvingDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.recyclerview.widget.GridLayoutManager
import io.legado.app.R
import io.legado.app.base.VMBaseActivity
import io.legado.app.data.entities.BookChapter
import io.legado.app.databinding.ActivityVideoPlayBinding
import io.legado.app.ui.about.AppLogDialog
import io.legado.app.ui.book.source.edit.BookSourceEditActivity
import io.legado.app.ui.book.toc.ChapterListAdapter
import io.legado.app.ui.login.SourceLoginActivity
import io.legado.app.utils.StartActivityContract
import io.legado.app.utils.sendToClip
import io.legado.app.utils.showDialogFragment
import io.legado.app.utils.startActivity
import io.legado.app.utils.toggleSystemBar
import io.legado.app.utils.viewbindingdelegate.viewBinding
import kotlinx.coroutines.launch
import java.util.Locale
import kotlin.math.abs

class VideoPlayActivity(
) : VMBaseActivity<ActivityVideoPlayBinding, VideoViewModel>(), ChapterListAdapter.Callback {

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
    private val progressTimeFormat by lazy {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            SimpleDateFormat("mm:ss", Locale.getDefault())
        } else {
            java.text.SimpleDateFormat("mm:ss", Locale.getDefault())
        }
    }

    @delegate:SuppressLint("UnsafeOptInUsageError")
    private val player by lazy {
        val baseDataSourceFactory = DefaultDataSource.Factory(
            this,
            DefaultHttpDataSource.Factory()
        )
        ExoPlayer.Builder(this).setMediaSourceFactory(
            DefaultMediaSourceFactory(this).apply {
                setDataSourceFactory(
                    ResolvingDataSource.Factory(baseDataSourceFactory) { dataSpec ->
                        dataSpec.withRequestHeaders(viewModel.videoUrl.value?.headerMap ?: mapOf())
                    })
            }
        ).build().apply {
            binding.ivPlayer.player = this
            binding.ivPlayer.controllerAutoShow = false
            addListener(object : Player.Listener {
                override fun onPlaybackStateChanged(playbackState: Int) {
                    super.onPlaybackStateChanged(playbackState)
                    if (playbackState == Player.STATE_ENDED&& viewModel.chapterList.value!!.size!=viewModel.book.durChapterIndex+1) {
                        openChapter(viewModel.chapterList.value!![viewModel.book.durChapterIndex+1])
                    }
                }
            })
        }
    }

    override fun onActivityCreated(savedInstanceState: Bundle?) {
        viewModel.initData(intent)
        viewModel.bookTitle.observe(this) {
            binding.titleBar.title = it
        }
        viewModel.videoUrl.observe(this) {
            refreshPlayer(it.url)
        }
        viewModel.chapterList.observe(this) {
            if (it.size > 1) {
                (binding.ivPlayer.layoutParams as? ConstraintLayout.LayoutParams)?.apply {
                    dimensionRatio = "h,16:9"
//                    matchConstraintMaxHeight = screenWidth
                }
//                binding.ivPlayer.maxHeight = screenWidth
                binding.recyclerView.layoutManager = GridLayoutManager(this, 3)
                binding.recyclerView.adapter = adapter
                adapter.setItems(it)
                binding.recyclerView.scrollToPosition(viewModel.book.durChapterIndex)
                adapter.upDisplayTitles(viewModel.book.durChapterIndex)
            } else {
                (binding.ivPlayer.layoutParams as? ConstraintLayout.LayoutParams)?.apply {
                    bottomToBottom = ConstraintLayout.LayoutParams.PARENT_ID
                }
                binding.recyclerView.visibility = View.GONE
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
        player.apply {
            setMediaItem(MediaItem.fromUri(videoUrl))
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
        binding.ivPlayer.setOnTouchListener { v, event ->
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

    override fun onMenuOpened(featureId: Int, menu: Menu): Boolean {
        menu.findItem(R.id.menu_login)?.isVisible =
            !viewModel.bookSource?.loginUrl.isNullOrBlank()
        return super.onMenuOpened(featureId, menu)
    }

    override fun onCompatOptionsItemSelected(item: MenuItem): Boolean {
        when (item.itemId) {
//            R.id.menu_change_source -> {
//            }
            R.id.menu_full_screen -> toggleFullScreen()
            R.id.menu_login -> viewModel.bookSource?.let {
                startActivity<SourceLoginActivity> {
                    putExtra("type", "bookSource")
                    putExtra("key", it.bookSourceUrl)
                }
            }
            R.id.menu_copy_audio_url -> viewModel.videoUrl.value?.let { sendToClip(it.url) }
            R.id.menu_edit_source -> viewModel.bookSource?.let {
                sourceEditResult.launch {
                    putExtra("sourceUrl", it.bookSourceUrl)
                }
            }
            R.id.menu_log -> showDialogFragment<AppLogDialog>()
        }
        return super.onCompatOptionsItemSelected(item)
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        when (newConfig.orientation) {
            Configuration.ORIENTATION_LANDSCAPE -> isFullScreen = false
            Configuration.ORIENTATION_PORTRAIT -> isFullScreen = true
        }
        toggleFullScreen()
        super.onConfigurationChanged(newConfig)
    }

    private fun toggleFullScreen() {
        isFullScreen = !isFullScreen
        toggleSystemBar(!isFullScreen)
        if (isFullScreen) {
            supportActionBar?.hide()
            binding.ivPlayer.layoutParams.apply {
                height = WindowManager.LayoutParams.MATCH_PARENT
            }
            binding.recyclerView.isVisible = false
        } else {
            supportActionBar?.show()
            binding.ivPlayer.layoutParams = binding.ivPlayer.layoutParams.apply {
                height = WindowManager.LayoutParams.WRAP_CONTENT
            }
            binding.recyclerView.isVisible = true
        }
    }

    override fun onDestroy() {
        viewModel.saveRead(if(player.currentPosition==player.duration)0L else player.currentPosition)
        player.release()
//        setResult(RESULT_OK
//            , intent.apply {
//
//        }
//        )
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