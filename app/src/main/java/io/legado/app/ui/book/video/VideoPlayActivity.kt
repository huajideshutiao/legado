package io.legado.app.ui.book.video

import android.annotation.SuppressLint
import android.content.res.Resources
import android.os.Bundle
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import androidx.activity.viewModels
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackParameters
import androidx.media3.exoplayer.ExoPlayer
import io.legado.app.base.VMBaseActivity
import io.legado.app.constant.Theme
import io.legado.app.databinding.ActivityVideoPlayBinding
import io.legado.app.utils.viewbindingdelegate.viewBinding
import java.util.Locale
import kotlin.math.abs

class VideoPlayActivity(
) : VMBaseActivity<ActivityVideoPlayBinding, VideoViewModel>(toolBarTheme = Theme.Dark) {
    private var player: ExoPlayer? = null
    override val binding by viewBinding(ActivityVideoPlayBinding::inflate)
    override val viewModel by viewModels<VideoViewModel>()
    private var isFullscreen = false
    private var currentSpeed = 1f
    private var originalSpeed = 1f
    private var position: Long = 0
    private val screenWidth = Resources.getSystem().displayMetrics.widthPixels
    private var endX = 0f
    private var isPress = false
    private var isScroll = false


    @SuppressLint("UnsafeOptInUsageError")
    override fun onActivityCreated(savedInstanceState: Bundle?) {
        player = ExoPlayer.Builder(this).build().apply {
            binding.ivPlayer.player = this
            binding.ivPlayer.controllerAutoShow = false
            val mediaItem = MediaItem.fromUri("https://sf1-cdn-tos.huoshanstatic.com/obj/media-fe/xgplayer_doc_video/hls/xgplayer-demo.m3u8")
            setMediaItem(mediaItem)
            prepare()
            playWhenReady = true
        }
        val duration by lazy { player!!.duration }
        val gestureDetector =
            GestureDetector(this, object : GestureDetector.SimpleOnGestureListener() {

                override fun onLongPress(e: MotionEvent) {
                    val player = player ?: return
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
                        val player = player ?: return false
                        isScroll = true
                        val currentPosition = player.currentPosition
                        endX += distanceX
                        position = (currentPosition - (endX / screenWidth) * 120000).toLong()
                            .coerceIn(0, duration)
                        binding.tvVideoSpeed.text = formatDuration(position)
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
                            player?.seekTo(position)
                        }
                        if (isPress) {
                            isPress = false
                            player?.let {
                                it.playbackParameters =
                                    PlaybackParameters(
                                        originalSpeed,
                                        player!!.playbackParameters.pitch
                                    )
                            }
                        }
                        binding.tvVideoSpeed.visibility = View.GONE
                    } else v.performClick()
                }
            }
            true
        }

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

    private fun formatDuration(duration: Long): String {
        val hours = duration / 3600000
        val minutes = (duration % 3600000) / 60000
        val seconds = (duration % 60000) / 1000
        return if (hours > 0) {
            String.format(Locale.ROOT, "%02d:%02d:%02d", hours, minutes, seconds)
        } else {
            String.format(Locale.ROOT, "%02d:%02d", minutes, seconds)
        }

    }


    override fun onDestroy() {
        super.onDestroy()
        player?.release()
    }
}