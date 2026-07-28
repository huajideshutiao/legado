package io.legado.app.ui.book.manga.render

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.graphics.drawable.Animatable
import android.graphics.drawable.Animatable2
import android.graphics.drawable.AnimatedImageDrawable
import android.graphics.drawable.Drawable
import android.os.Build
import androidx.appcompat.widget.AppCompatImageView
import androidx.vectordrawable.graphics.drawable.Animatable2Compat
import coil3.asDrawable
import coil3.dispose
import coil3.gif.MovieDrawable
import coil3.load
import coil3.request.CachePolicy
import coil3.request.transformations
import coil3.size.Dimension
import coil3.size.Size
import io.legado.app.data.entities.Book
import io.legado.app.data.entities.BookSource
import io.legado.app.help.glide.MangaModel
import io.legado.app.help.glide.progress.ProgressManager
import io.legado.app.ui.book.manga.entities.GrayscaleTransformation

/** 页面单元格加载状态，Compose 覆盖层(loading/进度/重试)依据 */
enum class MangaCellState { LOADING, SUCCESS, ERROR }

/**
 * 漫画图片叶子件(原 MangaVH 的加载部分)：只负责 Coil3 加载、下载进度、
 * GIF 播完翻页；尺寸/占位/重试 UI 全在 Compose 层。
 *
 * 实现 [MangaRenderState.MangaPageRenderer] 以便 shared 版 MangaRenderState
 * 的 GIF 注册表通过平台无关接口持有单元格引用。
 */
class MangaPageImageView(context: Context) : AppCompatImageView(context),
    MangaRenderState.MangaPageRenderer {

    var onStateChange: ((MangaCellState) -> Unit)? = null
    var onProgress: ((String) -> Unit)? = null

    //是否启用“GIF 播放完翻页”（横向翻页 + 设置开启）
    var gifAutoNextEnabled: () -> Boolean = { false }

    //此页是否为当前“停稳的居中页”（即应作为播完翻页的目标页）
    var isArmTarget: () -> Boolean = { false }

    //触发翻到下一页，返回是否真的翻动了；受阻（已到末页/下一章加载中）时返回 false
    var onTurnPage: () -> Boolean = { false }

    private var loadKey: Pair<String, Boolean>? = null
    private var lastBook: Book? = null
    private var lastSource: BookSource? = null

    //本次停留期间是否已触发过翻页，避免无限循环下每播完一轮就重复翻页
    private var mGifTurnConsumed = false

    //已注册结束回调的动图实例，确保每个实例只注册一次（绝不 clearAnimationCallbacks，
    //否则会与平台 AnimatedImageDrawable 已投递的结束回调产生空指针竞态而崩溃）
    private var mGifCallbackTarget: Drawable? = null

    //待忽略的“自触发结束回调”计数：平台 AnimatedImageDrawable.stop() 会主动投递一次
    //onAnimationEnd，我们重播时主动 stop 不应被误判为“自然播完一轮”，故逐一抵消
    private var mPendingSelfEnds = 0

    init {
        adjustViewBounds = true
    }

    /** url/灰度未变则跳过（Compose update 会因任意状态变化重跑） */
    fun loadPageImage(url: String, book: Book?, source: BookSource?, gray: Boolean) {
        book ?: return
        if (loadKey == url to gray) return
        loadKey = url to gray
        lastBook = book
        lastSource = source
        doLoad(url, gray)
    }

    fun retry() {
        val (url, gray) = loadKey ?: return
        doLoad(url, gray)
    }

    /** 单元格回收(LazyColumn 复用/释放)：清请求与进度监听 */
    fun recycle() {
        val activity = context.findActivity()
        if (activity == null || !activity.isDestroyed) {
            dispose()
        }
        (tag as? String)?.let { ProgressManager.removeListener(it) }
        tag = null
        loadKey = null
        mGifCallbackTarget = null
        onStateChange = null
        onProgress = null
    }

    @SuppressLint("CheckResult", "DefaultLocale")
    private fun doLoad(imageUrl: String, gray: Boolean) {
        //Activity 销毁时（如关闭阅读界面）仍可能触发加载，此时跳过
        val activity = context.findActivity()
        if (activity != null && activity.isDestroyed) {
            return
        }
        onStateChange?.invoke(MangaCellState.LOADING)
        ProgressManager.removeListener(imageUrl)
        ProgressManager.addListener(imageUrl) { _, percentage, bytesRead, totalBytes ->
            if (tag == imageUrl) {
                onProgress?.invoke(
                    if (totalBytes > 0) {
                        "$percentage%"
                    } else {
                        val kb = bytesRead / 1024.0
                        if (kb >= 1024) {
                            String.format("%.1fMB", kb / 1024)
                        } else {
                            "${kb.toInt()}KB"
                        }
                    }
                )
            }
        }
        tag = imageUrl
        val book = lastBook ?: return
        val model = MangaModel(imageUrl, book, lastSource)
        val screenWidth = context.resources.displayMetrics.widthPixels
        load(model) {
            // 漫画页磁盘缓存禁用(loadManga 已自管 BookHelp 磁盘缓存)；内存缓存必须保留，
            // 否则 MangaRenderLayer 的 WRITE_ONLY 预加载结果永远不会被页面请求命中。
            memoryCachePolicy(CachePolicy.ENABLED)
            diskCachePolicy(CachePolicy.DISABLED)
            size(Size(Dimension(screenWidth), Dimension.Undefined))
            if (gray) transformations(sharedGrayTransformation)
            listener(
                onError = { _, _ ->
                    if (tag == imageUrl) {
                        onStateChange?.invoke(MangaCellState.ERROR)
                    }
                },
                onSuccess = { _, result ->
                    if (tag == imageUrl) {
                        onStateChange?.invoke(MangaCellState.SUCCESS)
                        val drawable = result.image?.asDrawable(resources)
                        if (drawable != null && isAnimatedDrawable(drawable) && gifAutoNextEnabled()) {
                            //加载完成时：若此页正是当前停稳的居中页（例如直接打开到此页，
                            //或图片在停稳后才加载完），立即从第一帧单次播放并准备翻页；
                            //否则保持无限循环，等真正停稳居中时再装填
                            if (isArmTarget()) armGifAutoNext(drawable) else disarmGifAutoNext(drawable)
                        }
                    }
                },
            )
        }
    }

    private fun Context.findActivity(): Activity? {
        var ctx: Context? = this
        while (ctx is ContextWrapper) {
            if (ctx is Activity) return ctx
            ctx = ctx.baseContext
        }
        return null
    }

    /** 是否为可控制循环的动图（Coil3 MovieDrawable 或平台 AnimatedImageDrawable） */
    private fun isAnimatedDrawable(d: Drawable?): Boolean = when {
        d is MovieDrawable -> true
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.P && d is AnimatedImageDrawable -> true
        else -> false
    }

    /**
     * 为动图实例注册一次“播完一轮”结束回调（同一实例只注册一次）。
     * 关键：**绝不**调用 clearAnimationCallbacks——平台 AnimatedImageDrawable 在
     * 动画结束时会向主线程投递一个遍历回调列表的 Runnable，若期间将列表清空(置 null)，
     * 该 Runnable 执行时会空指针崩溃。我们改为只注册不清除：无限循环时回调本就不触发，
     * 单轮时才触发一次，靠 isArmTarget / mGifTurnConsumed 控制是否真正翻页。
     */
    private fun ensureEndCallback(drawable: Drawable) {
        if (mGifCallbackTarget === drawable) return
        mGifCallbackTarget = drawable
        mPendingSelfEnds = 0
        val imageUrl = tag as? String
        when {
            drawable is MovieDrawable ->
                drawable.registerAnimationCallback(object : Animatable2Compat.AnimationCallback() {
                    override fun onAnimationEnd(d: Drawable) = onAnimEnded(imageUrl)
                })

            Build.VERSION.SDK_INT >= Build.VERSION_CODES.P && drawable is AnimatedImageDrawable ->
                drawable.registerAnimationCallback(object : Animatable2.AnimationCallback() {
                    override fun onAnimationEnd(d: Drawable) = onAnimEnded(imageUrl)
                })
        }
    }

    /** 单轮播放（播完一轮触发结束回调） */
    private fun setPlayOnce(drawable: Drawable) {
        when {
            drawable is MovieDrawable -> drawable.setRepeatCount(0)
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.P && drawable is AnimatedImageDrawable ->
                drawable.repeatCount = 0
        }
    }

    /** 无限循环（结束回调永不触发） */
    private fun setLoopForever(drawable: Drawable) {
        when {
            drawable is MovieDrawable -> drawable.setRepeatCount(MovieDrawable.REPEAT_INFINITE)
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.P && drawable is AnimatedImageDrawable ->
                drawable.repeatCount = AnimatedImageDrawable.REPEAT_INFINITE
        }
    }

    private fun onAnimEnded(imageUrl: String?) {
        //抵消由我们主动 stop() 触发的结束回调（仅平台 AnimatedImageDrawable 的 stop 会回调）
        if (mPendingSelfEnds > 0) {
            mPendingSelfEnds--
            return
        }
        //回调可能在视图已被复用后才到达，用 tag 校验确保仍是同一张图
        if (imageUrl != null && tag != imageUrl) {
            return
        }
        val drawable = drawable
        if (drawable == null || !isAnimatedDrawable(drawable)) {
            return
        }
        //不是当前停稳页（播放途中被滑走），或本次停留已翻过一次：继续无限循环，不再翻页
        if (!isArmTarget() || mGifTurnConsumed) {
            resumeInfiniteLoop(drawable)
            return
        }
        if (onTurnPage()) {
            //已成功翻页：此页即将滑出，恢复无限循环避免停在末帧
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
     * Coil3 MovieDrawable 与平台 AnimatedImageDrawable 均无“重置到首帧”API，
     * 用 stop()+start() 近似（start 会把 loopIteration 归 0 并重设 startTime）。
     * 平台 AnimatedImageDrawable.stop() 会主动投递一次 onAnimationEnd，故先计数抵消。
     */
    private fun restartAnim(drawable: Drawable) {
        when {
            drawable is MovieDrawable -> {
                if (drawable.isRunning) drawable.stop()
                drawable.start()
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
     * 必须重置到第一帧——此页在居中前可能已可见而“偷偷”播放过若干帧甚至播完。
     */
    private fun armGifAutoNext(drawable: Drawable) {
        ensureEndCallback(drawable)
        mGifTurnConsumed = false
        setPlayOnce(drawable)
        restartAnim(drawable)
    }

    /** 取消“播完翻页”：恢复无限循环并继续播放（不清回调，避免与已投递的结束回调竞态） */
    private fun disarmGifAutoNext(drawable: Drawable) {
        setLoopForever(drawable)
        (drawable as? Animatable)?.let { if (!it.isRunning) it.start() }
    }

    /** 此页停稳为当前居中页时调用：从第一帧单次播放并准备翻页 */
    override fun playGifForCurrentPage() {
        if (!gifAutoNextEnabled()) return
        val drawable = drawable
        if (drawable == null || !isAnimatedDrawable(drawable)) return
        armGifAutoNext(drawable)
    }

    /** 此页离开当前居中位置、或关闭 GIF 自动翻页时调用：恢复无限循环 */
    override fun stopGifAutoNext() {
        val drawable = drawable
        if (drawable == null || !isAnimatedDrawable(drawable)) return
        disarmGifAutoNext(drawable)
    }

    companion object {
        //共享实例保证 Coil3 内存缓存键稳定(equals 按类)，与原 adapter 单实例语义一致
        private val sharedGrayTransformation = GrayscaleTransformation()
    }
}
