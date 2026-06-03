package io.legado.app.ui.book.manga.recyclerview

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.graphics.drawable.Animatable2
import android.graphics.drawable.AnimatedImageDrawable
import android.graphics.drawable.Drawable
import android.os.Build
import android.view.Gravity
import android.view.ViewGroup
import android.widget.Button
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.ProgressBar
import android.widget.TextView
import androidx.appcompat.widget.AppCompatImageView
import androidx.core.view.isGone
import androidx.core.view.isVisible
import androidx.core.view.updateLayoutParams
import androidx.recyclerview.widget.RecyclerView
import androidx.vectordrawable.graphics.drawable.Animatable2Compat
import androidx.viewbinding.ViewBinding
import com.bumptech.glide.Glide
import com.bumptech.glide.load.DataSource
import com.bumptech.glide.load.Transformation
import com.bumptech.glide.load.engine.DiskCacheStrategy
import com.bumptech.glide.load.engine.GlideException
import com.bumptech.glide.load.resource.gif.GifDrawable
import com.bumptech.glide.request.RequestListener
import com.bumptech.glide.request.target.Target
import com.bumptech.glide.request.target.Target.SIZE_ORIGINAL
import io.legado.app.data.entities.Book
import io.legado.app.data.entities.BookSource
import io.legado.app.help.glide.MangaModel
import io.legado.app.help.glide.progress.ProgressManager

open class MangaVH<VB : ViewBinding>(val binding: VB, private val context: Context) :
    RecyclerView.ViewHolder(binding.root) {

    protected lateinit var mLoading: ProgressBar
    protected lateinit var mImage: AppCompatImageView
    protected lateinit var mProgress: TextView
    protected lateinit var mFlProgress: FrameLayout
    protected var mRetry: Button? = null
    private val minHeight = context.resources.displayMetrics.heightPixels * 2 / 3

    //是否启用“GIF 播放完翻页”（横向翻页 + 设置开启）
    private var mGifAutoNextEnabled: () -> Boolean = { false }

    //GIF 播完时，此页是否应触发翻页（即此页是否为当前居中页）
    private var mShouldTurnOnGifEnd: () -> Boolean = { false }

    //触发翻页的动作
    private var mOnTurnPage: () -> Unit = {}

    fun initComponent(
        loading: ProgressBar,
        image: AppCompatImageView,
        progress: TextView,
        button: Button? = null,
        flProgress: FrameLayout,
    ) {
        mLoading = loading
        mImage = image
        mRetry = button
        mProgress = progress
        mFlProgress = flProgress
    }

    @SuppressLint("CheckResult", "DefaultLocale")
    fun loadImageWithRetry(
        imageUrl: String,
        book: Book,
        bookSource: BookSource?,
        isHorizontal: Boolean,
        isLastImage: Boolean,
        transformation: Transformation<Bitmap>?,
        gifAutoNextEnabled: () -> Boolean = { false },
        shouldTurnOnGifEnd: () -> Boolean = { false },
        onTurnPage: () -> Unit = {},
    ) {
        mGifAutoNextEnabled = gifAutoNextEnabled
        mShouldTurnOnGifEnd = shouldTurnOnGifEnd
        mOnTurnPage = onTurnPage
        mFlProgress.isVisible = true
        mLoading.isVisible = true
        mRetry?.isGone = true
        mProgress.isVisible = true
        ProgressManager.removeListener(imageUrl)
        ProgressManager.addListener(imageUrl) { _, percentage, bytesRead, totalBytes ->
            if (mImage.tag == imageUrl) {
                @SuppressLint("SetTextI18n")
                mProgress.text = if (totalBytes > 0) {
                    "$percentage%"
                } else {
                    val kb = bytesRead / 1024.0
                    if (kb >= 1024) {
                        String.format("%.1fMB", kb / 1024)
                    } else {
                        "${kb.toInt()}KB"
                    }
                }
            }
        }
        mImage.tag = imageUrl
        Glide.with(context)
            .load(MangaModel(imageUrl, book, bookSource))
            .override(context.resources.displayMetrics.widthPixels, SIZE_ORIGINAL)
            .diskCacheStrategy(DiskCacheStrategy.NONE)
            .apply { transformation?.let { transform(it) } }
            .listener(object : RequestListener<Drawable> {
                override fun onLoadFailed(
                    e: GlideException?,
                    p1: Any,
                    p2: Target<Drawable>,
                    isFirstResource: Boolean
                ): Boolean {
                    if (mImage.tag == imageUrl) {
                        mFlProgress.isVisible = true
                        mLoading.isGone = true
                        mRetry?.isVisible = true
                        mProgress.isGone = true
                        itemView.updateLayoutParams<ViewGroup.LayoutParams> {
                            height = ViewGroup.LayoutParams.MATCH_PARENT
                        }
                    }
                    return false
                }

                override fun onResourceReady(
                    p0: Drawable,
                    model: Any,
                    p2: Target<Drawable>,
                    dataSource: DataSource,
                    isFirstResource: Boolean
                ): Boolean {
                    if (mImage.tag == imageUrl) {
                        mFlProgress.isGone = true
                        if (isHorizontal) {
                            itemView.updateLayoutParams<ViewGroup.LayoutParams> {
                                height = ViewGroup.LayoutParams.MATCH_PARENT
                            }
                            itemView.minimumHeight = 0
                            mImage.updateLayoutParams<FrameLayout.LayoutParams> {
                                height = ViewGroup.LayoutParams.MATCH_PARENT
                                gravity = Gravity.CENTER
                            }
                            mImage.scaleType = ImageView.ScaleType.FIT_CENTER
                        } else {
                            itemView.updateLayoutParams<ViewGroup.LayoutParams> {
                                height = ViewGroup.LayoutParams.WRAP_CONTENT
                            }
                            itemView.minimumHeight = if (isLastImage) minHeight else 0
                            mImage.updateLayoutParams<FrameLayout.LayoutParams> {
                                gravity = Gravity.NO_GRAVITY
                                height =
                                    if (isLastImage) ViewGroup.LayoutParams.WRAP_CONTENT else ViewGroup.LayoutParams.MATCH_PARENT
                            }
                            mImage.scaleType = ImageView.ScaleType.FIT_XY
                        }
                        if (isAnimatedDrawable(p0) && mGifAutoNextEnabled()) {
                            //加载完成即强制单次播放（动图默认无限循环，必须显式覆盖），
                            //并注册结束回调；随后 Glide 会自动 start，播完一轮即触发。
                            installEndCallback(p0)
                        }
                    }
                    return false
                }
            })
            .into(mImage)
    }

    /** 是否为可控制循环的动图（Glide 的 GifDrawable 或平台 AnimatedImageDrawable） */
    private fun isAnimatedDrawable(d: Drawable?): Boolean = when {
        d is GifDrawable -> true
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.P && d is AnimatedImageDrawable -> true
        else -> false
    }

    /**
     * 给动图设为单次播放并注册“播完”回调。
     * 动图自身常被设置为无限循环，这里强制覆盖为只播一次，
     * 否则结束回调永远不会触发。Glide 5 在 API 28+ 会把 GIF 解码成
     * 系统的 AnimatedImageDrawable，需用其自有 API 处理。
     */
    private fun installEndCallback(drawable: Drawable) {
        val imageUrl = mImage.tag as? String
        when {
            drawable is GifDrawable -> {
                drawable.setLoopCount(1)
                drawable.clearAnimationCallbacks()
                drawable.registerAnimationCallback(object : Animatable2Compat.AnimationCallback() {
                    override fun onAnimationEnd(d: Drawable?) = onAnimEnded(imageUrl)
                })
            }

            Build.VERSION.SDK_INT >= Build.VERSION_CODES.P && drawable is AnimatedImageDrawable -> {
                drawable.repeatCount = 0
                drawable.clearAnimationCallbacks()
                drawable.registerAnimationCallback(object : Animatable2.AnimationCallback() {
                    override fun onAnimationEnd(d: Drawable?) = onAnimEnded(imageUrl)
                })
            }
        }
    }

    private fun onAnimEnded(imageUrl: String?) {
        if (imageUrl != null && mImage.tag != imageUrl) {
            return
        }
        if (mShouldTurnOnGifEnd()) {
            mOnTurnPage()
        }
    }

    /** 从头重新播放当前动图 */
    private fun restartAnim(drawable: Drawable) {
        when {
            drawable is GifDrawable -> {
                if (drawable.isRunning) drawable.stop()
                drawable.startFromFirstFrame()
            }

            Build.VERSION.SDK_INT >= Build.VERSION_CODES.P && drawable is AnimatedImageDrawable -> {
                if (drawable.isRunning) drawable.stop()
                drawable.start()
            }
        }
    }

    /**
     * 设置变化时，对当前已加载的动图立即应用最新状态：
     * 开启则单次播放，关闭则恢复无限循环。
     */
    fun applyGifAutoNext() {
        val drawable = mImage.drawable
        if (!isAnimatedDrawable(drawable) || drawable == null) return
        if (mGifAutoNextEnabled()) {
            installEndCallback(drawable)
        } else {
            when {
                drawable is GifDrawable -> {
                    drawable.clearAnimationCallbacks()
                    drawable.setLoopCount(GifDrawable.LOOP_FOREVER)
                }

                Build.VERSION.SDK_INT >= Build.VERSION_CODES.P && drawable is AnimatedImageDrawable -> {
                    drawable.clearAnimationCallbacks()
                    drawable.repeatCount = AnimatedImageDrawable.REPEAT_INFINITE
                }
            }
        }
        restartAnim(drawable)
    }
}
