package io.legado.app.ui.book.manga.recyclerview

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.graphics.Bitmap
import android.graphics.drawable.Animatable
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

    //此页是否为当前“停稳的居中页”（即应作为播完翻页的目标页）。
    //仅在 RecyclerView 滚动停止(IDLE)且此页居中时为真，避免预布局/滑动途中误判。
    private var mIsArmTarget: () -> Boolean = { false }

    //触发翻到下一页，返回是否真的翻动了；受阻（已到末页/下一章加载中）时返回 false。
    private var mOnTurnPage: () -> Boolean = { false }

    //本次停留期间是否已触发过翻页，避免无限循环下每播完一轮就重复翻页
    private var mGifTurnConsumed = false

    //已注册结束回调的动图实例，确保每个实例只注册一次（绝不 clearAnimationCallbacks，
    //否则会与平台 AnimatedImageDrawable 已投递的结束回调产生空指针竞态而崩溃）
    private var mGifCallbackTarget: Drawable? = null

    //待忽略的“自触发结束回调”计数：平台 AnimatedImageDrawable.stop() 会主动投递一次
    //onAnimationEnd，我们重播时主动 stop 不应被误判为“自然播完一轮”，故逐一抵消。
    private var mPendingSelfEnds = 0

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
        isArmTarget: () -> Boolean = { false },
        onTurnPage: () -> Boolean = { false },
    ) {
        //Activity 销毁时（如关闭阅读界面）仍可能因布局/滚动触发 onBindViewHolder，
        //此时 Glide.with(activity) 会抛 "destroyed activity" 异常，直接跳过加载
        val activity = context.findActivity()
        if (activity != null && activity.isDestroyed) {
            return
        }
        mGifAutoNextEnabled = gifAutoNextEnabled
        mIsArmTarget = isArmTarget
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
                            //加载完成时：若此页正是当前停稳的居中页（例如直接打开到此页，
                            //或图片在停稳后才加载完），立即从第一帧单次播放并准备翻页；
                            //否则保持无限循环——因 LayoutManager 预布局，相邻页虽未居中也已
                            //贴附可见而在“偷偷”播放，必须等其真正停稳居中时（由 Activity 在
                            //滚动停止后调用 playGifForCurrentPage）才装填，否则翻到时已停在末帧。
                            if (mIsArmTarget()) armGifAutoNext(p0) else disarmGifAutoNext(p0)
                        }
                    }
                    return false
                }
            })
            .into(mImage)
    }

    private fun Context.findActivity(): Activity? {
        var ctx: Context? = this
        while (ctx is ContextWrapper) {
            if (ctx is Activity) return ctx
            ctx = ctx.baseContext
        }
        return null
    }

    /** 是否为可控制循环的动图（Glide 的 GifDrawable 或平台 AnimatedImageDrawable） */
    private fun isAnimatedDrawable(d: Drawable?): Boolean = when {
        d is GifDrawable -> true
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.P && d is AnimatedImageDrawable -> true
        else -> false
    }

    /**
     * 为动图实例注册一次“播完一轮”结束回调（同一实例只注册一次）。
     * 关键：**绝不**调用 clearAnimationCallbacks——平台 AnimatedImageDrawable 在
     * 动画结束时会向主线程投递一个遍历回调列表的 Runnable，若期间将列表清空(置 null)，
     * 该 Runnable 执行时会空指针崩溃。我们改为只注册不清除：无限循环时回调本就不触发，
     * 单轮时才触发一次，靠 mIsArmTarget / mGifTurnConsumed 控制是否真正翻页。
     * Glide 5 在 API 28+ 仍把 GIF 解码为自有 GifDrawable，仅动画 WebP/AVIF 为平台 AnimatedImageDrawable。
     */
    private fun ensureEndCallback(drawable: Drawable) {
        if (mGifCallbackTarget === drawable) return
        mGifCallbackTarget = drawable
        mPendingSelfEnds = 0
        val imageUrl = mImage.tag as? String
        when {
            drawable is GifDrawable ->
                drawable.registerAnimationCallback(object : Animatable2Compat.AnimationCallback() {
                    override fun onAnimationEnd(d: Drawable?) = onAnimEnded(imageUrl)
                })

            Build.VERSION.SDK_INT >= Build.VERSION_CODES.P && drawable is AnimatedImageDrawable ->
                drawable.registerAnimationCallback(object : Animatable2.AnimationCallback() {
                    override fun onAnimationEnd(d: Drawable?) = onAnimEnded(imageUrl)
                })
        }
    }

    /** 单轮播放（播完一轮触发结束回调） */
    private fun setPlayOnce(drawable: Drawable) {
        when {
            drawable is GifDrawable -> drawable.setLoopCount(1)
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.P && drawable is AnimatedImageDrawable ->
                drawable.repeatCount = 0
        }
    }

    /** 无限循环（结束回调永不触发） */
    private fun setLoopForever(drawable: Drawable) {
        when {
            drawable is GifDrawable -> drawable.setLoopCount(GifDrawable.LOOP_FOREVER)
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.P && drawable is AnimatedImageDrawable ->
                drawable.repeatCount = AnimatedImageDrawable.REPEAT_INFINITE
        }
    }

    private fun onAnimEnded(imageUrl: String?) {
        //抵消由我们主动 stop() 触发的结束回调（仅平台 AnimatedImageDrawable 的 stop 会回调），
        //否则重播时的 stop 会被误判为“播完一轮”而连环翻页。
        if (mPendingSelfEnds > 0) {
            mPendingSelfEnds--
            return
        }
        //回调可能在视图已被复用后才到达，用 tag 校验确保仍是同一张图
        if (imageUrl != null && mImage.tag != imageUrl) {
            return
        }
        val drawable = mImage.drawable
        if (drawable == null || !isAnimatedDrawable(drawable)) {
            return
        }
        //不是当前停稳页（播放途中被滑走），或本次停留已翻过一次：继续无限循环，不再翻页
        if (!mIsArmTarget() || mGifTurnConsumed) {
            resumeInfiniteLoop(drawable)
            return
        }
        if (mOnTurnPage()) {
            //已成功翻页：此页即将滑出，恢复无限循环避免停在末帧；
            //停稳到下一页后由 Activity 装填下一页的 GIF。
            mGifTurnConsumed = true
            resumeInfiniteLoop(drawable)
        } else {
            //翻页受阻（已是最后一页/下一章仍在加载）：保持单轮、从第一帧重播，下一轮再试
            restartAnim(drawable)
        }
    }

    /** 恢复无限循环并从第一帧重播（不动回调列表，可安全在结束回调内调用） */
    private fun resumeInfiniteLoop(drawable: Drawable) {
        setLoopForever(drawable)
        restartAnim(drawable)
    }

    /**
     * 从第一帧重新播放当前动图。
     * 平台 AnimatedImageDrawable 无“重置到首帧”API，只能 stop()+start()；而 stop() 会
     * 主动投递一次 onAnimationEnd，故先对 mPendingSelfEnds 计数，供 onAnimEnded 抵消。
     */
    private fun restartAnim(drawable: Drawable) {
        when {
            drawable is GifDrawable -> {
                //Glide 的 GifDrawable.stop() 不会回调结束，无需计数
                if (drawable.isRunning) drawable.stop()
                drawable.startFromFirstFrame()
            }

            Build.VERSION.SDK_INT >= Build.VERSION_CODES.P && drawable is AnimatedImageDrawable -> {
                if (drawable.isRunning) {
                    mPendingSelfEnds++
                    drawable.stop()
                }
                drawable.start()
            }
        }
    }

    /**
     * 装填“播完一轮即翻页”：注册结束回调并强制从第一帧重新播放。
     * 必须重置到第一帧——因 LayoutManager 预布局，此页在居中前可能已贴附可见
     * 而“偷偷”播放过若干帧甚至播完，不重置就会从中途或末帧开始。
     * 播完一轮后在 onAnimEnded 里决定：成功翻页则恢复无限循环随页滑出，
     * 受阻则继续从第一帧循环等待下一轮重试。
     */
    private fun armGifAutoNext(drawable: Drawable) {
        ensureEndCallback(drawable)
        mGifTurnConsumed = false
        setPlayOnce(drawable)
        restartAnim(drawable)
    }

    /** 取消“播完翻页”：恢复无限循环并继续播放（不清回调，避免与已投递的结束回调竞态）。 */
    private fun disarmGifAutoNext(drawable: Drawable) {
        setLoopForever(drawable)
        //GifDrawable 与 AnimatedImageDrawable 均实现 Animatable
        (drawable as? Animatable)?.let { if (!it.isRunning) it.start() }
    }

    /**
     * 此页停稳为当前居中页时由 Activity 调用：从第一帧单次播放并准备翻页。
     * 仅在开启 GIF 自动翻页且当前为动图时生效。
     */
    fun playGifForCurrentPage() {
        if (!mGifAutoNextEnabled()) return
        val drawable = mImage.drawable
        if (drawable == null || !isAnimatedDrawable(drawable)) return
        armGifAutoNext(drawable)
    }

    /**
     * 此页离开当前居中位置、或关闭 GIF 自动翻页时由 Activity 调用：
     * 恢复无限循环并继续播放，避免停在末帧或触发误翻页。
     */
    fun stopGifAutoNext() {
        val drawable = mImage.drawable
        if (drawable == null || !isAnimatedDrawable(drawable)) return
        disarmGifAutoNext(drawable)
    }
}
