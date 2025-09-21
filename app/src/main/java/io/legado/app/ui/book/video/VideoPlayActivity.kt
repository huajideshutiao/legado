package io.legado.app.ui.book.video

import android.annotation.SuppressLint
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
import androidx.activity.viewModels
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackParameters
import androidx.media3.exoplayer.ExoPlayer
import io.legado.app.R
import io.legado.app.base.VMBaseActivity
import io.legado.app.constant.Theme
import io.legado.app.databinding.ActivityVideoPlayBinding
import io.legado.app.ui.about.AppLogDialog
import io.legado.app.ui.book.source.edit.BookSourceEditActivity
import io.legado.app.ui.login.SourceLoginActivity
import io.legado.app.utils.StartActivityContract
import io.legado.app.utils.sendToClip
import io.legado.app.utils.showDialogFragment
import io.legado.app.utils.startActivity
import io.legado.app.utils.viewbindingdelegate.viewBinding
import java.util.Locale
import kotlin.math.abs

class VideoPlayActivity(
) : VMBaseActivity<ActivityVideoPlayBinding, VideoViewModel>(toolBarTheme = Theme.Dark) {

    override val binding by viewBinding(ActivityVideoPlayBinding::inflate)
    override val viewModel by viewModels<VideoViewModel>()
    private var isFullscreen = false
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

    private val player by lazy {
        ExoPlayer.Builder(this).build().apply {
            binding.ivPlayer.player = this
            @SuppressLint("UnsafeOptInUsageError")
            binding.ivPlayer.controllerAutoShow = false
            playWhenReady = true
            if (position != 0L) seekTo(position)
        }
    }

    override fun onActivityCreated(savedInstanceState: Bundle?) {
        binding.titleBar.setBackgroundResource(R.color.transparent)
        viewModel.videoTitle.observe(this) {
            binding.titleBar.title = it
        }
        viewModel.videoUrl.observe(this) {
            refreshPlayer(it)
        }
        viewModel.initData(intent)
    }

    fun refreshPlayer(videoUrl: String) {
        player.apply {
            setMediaItem(MediaItem.fromUri(videoUrl))
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
                    binding.tvVideoSpeed.text = "${currentSpeed}x"
                    binding.tvVideoSpeed.visibility = View.VISIBLE
                }

                override fun onScroll(
                    e1: MotionEvent?,
                    e2: MotionEvent,
                    distanceX: Float,
                    distanceY: Float
                ): Boolean {
                    if (abs(distanceX) > abs(distanceY)) {
                        isScroll = true
                        val currentPosition = player.currentPosition
                        endX += distanceX
                        position = (currentPosition - (endX / screenWidth) * 120000).toLong()
                            .coerceIn(0, player.duration )
                        binding.tvVideoSpeed.text = progressTimeFormat.format(position)
                        binding.tvVideoSpeed.visibility = View.VISIBLE
                        return true
                    }
                    return false
                }

                override fun onDown(e: MotionEvent): Boolean = true
            })

        // 设置全屏按钮点击事件
        //binding.fullscreenButton.setOnClickListener {
        //    toggleFullscreen()
        //}

        binding.ivPlayer.setOnTouchListener { v, event ->
            gestureDetector.onTouchEvent(event)
            when (event.action) {
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    if (isScroll || isPress) {
                        if (isScroll) {
                            isScroll = false
                            endX = 0F
                            player.seekTo(position)
                        }
                        if (isPress) {
                            isPress = false
                            player.playbackParameters = PlaybackParameters(
                                originalSpeed,
                                player.playbackParameters.pitch
                            )
                        }
                        binding.tvVideoSpeed.visibility = View.GONE
                    } else v.performClick()
                }
            }
            true
        }

    }

    override fun onCompatCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.audio_play, menu)
        return super.onCompatCreateOptionsMenu(menu)
    }

    override fun onMenuOpened(featureId: Int, menu: Menu): Boolean {
        menu.findItem(R.id.menu_login)?.isVisible =
            !viewModel.bookSource.value?.loginUrl.isNullOrBlank()
        menu.findItem(R.id.menu_wake_lock)?.isVisible = false
//        menu.findItem(R.id.menu_change_source).isVisible = false
        return super.onMenuOpened(featureId, menu)
    }

    override fun onCompatOptionsItemSelected(item: MenuItem): Boolean {
        when (item.itemId) {
            R.id.menu_change_source -> {
            }

            R.id.menu_login -> viewModel.bookSource.value?.let {
                startActivity<SourceLoginActivity> {
                    putExtra("type", "bookSource")
                    putExtra("key", it.bookSourceUrl)
                }
            }

            R.id.menu_copy_audio_url -> viewModel.videoUrl.value?.let { sendToClip(it) }
            R.id.menu_edit_source -> viewModel.bookSource.value?.let {
                sourceEditResult.launch {
                    putExtra("sourceUrl", it.bookSourceUrl)
                }
            }

            R.id.menu_log -> showDialogFragment<AppLogDialog>()
        }
        return super.onCompatOptionsItemSelected(item)
    }

    private fun toggleFullscreen() {
        isFullscreen = !isFullscreen
        if (isFullscreen) {
            // 进入全屏
            window.addFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN)
            window.clearFlags(WindowManager.LayoutParams.FLAG_FORCE_NOT_FULLSCREEN)
            binding.ivPlayer.layoutParams = binding.ivPlayer.layoutParams.apply {
                width = WindowManager.LayoutParams.MATCH_PARENT
                height = WindowManager.LayoutParams.MATCH_PARENT
            }
            //        binding.fullscreenButton.setImageResource(R.drawable.ic_exit_fullscreen)
        } else {
            // 退出全屏
            window.addFlags(WindowManager.LayoutParams.FLAG_FORCE_NOT_FULLSCREEN)
            window.clearFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN)
            binding.ivPlayer.layoutParams = binding.ivPlayer.layoutParams.apply {
                width = WindowManager.LayoutParams.MATCH_PARENT
                height = WindowManager.LayoutParams.WRAP_CONTENT
            }
            //        binding.fullscreenButton.setImageResource(R.drawable.ic_enter_fullscreen)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        viewModel.saveRead()
        player.release()
    }
}