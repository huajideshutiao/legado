package io.legado.app.ui.widget.image

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Picture
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.text.TextPaint
import android.util.AttributeSet
import androidx.appcompat.widget.AppCompatImageView
import androidx.core.content.withStyledAttributes
import androidx.fragment.app.Fragment
import androidx.lifecycle.Lifecycle
import coil3.load as loadCoil
import coil3.request.placeholder
import io.legado.app.R
import io.legado.app.constant.AppPattern
import io.legado.app.help.config.AppConfig
import io.legado.app.lib.theme.accentColor
import io.legado.app.lib.theme.radius
import io.legado.app.model.BookCover
import io.legado.app.model.CoverRatio
import io.legado.app.model.coverConfig
import io.legado.app.utils.textHeight
import io.legado.app.utils.toStringArray

/**
 * 封面
 */
@Suppress("unused")
class CoverImageView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : AppCompatImageView(context, attrs) {

    var coverRatio: CoverRatio = CoverRatio.NOVEL
        set(value) {
            if (field != value) {
                field = value
                requestLayout()
            }
        }

    init {
        // 默认封面是 .9.png,需要 FIT_XY 才能让拉伸区生效;
        // 加载到真实封面后切回 CENTER_CROP (见 glideListener)
        scaleType = ScaleType.FIT_XY
        transitionName = "img_cover"
        contentDescription = context.getString(R.string.img_cover)
        if (isInEditMode) {
            setImageResource(R.drawable.image_cover_default)
        }
        attrs?.let {
            context.withStyledAttributes(it, R.styleable.CoverImageView) {
                coverRatio = when (getInt(R.styleable.CoverImageView_coverRatio, 1)) {
                    2 -> CoverRatio.VIDEO
                    else -> CoverRatio.NOVEL
                }
            }
        }
    }

    private var filletPath = Path()
    private var viewWidth: Float = 0f
    private var viewHeight: Float = 0f
    private var defaultCover = true
    var bitmapPath: String? = null
        private set
    private var name: String? = null
    private var author: String? = null
    private val namePaint by lazy {
        TextPaint().apply {
            typeface = Typeface.DEFAULT_BOLD
            isAntiAlias = true
            textAlign = Paint.Align.CENTER
        }
    }
    private val authorPaint by lazy {
        TextPaint().apply {
            typeface = Typeface.DEFAULT
            isAntiAlias = true
            textAlign = Paint.Align.CENTER
        }
    }

    // 默认封面文字缓存:onDraw 命中时跳过 toStringArray/fontMetrics/N*2 drawText 的 Java 端准备
    private var cachedPicture: Picture? = null
    private var cachedName: String? = null
    private var cachedAuthor: String? = null
    private var cachedWidth: Float = 0f
    private var cachedHeight: Float = 0f
    private var cachedAccent: Int = 0
    private var cachedDrawName: Boolean = false
    private var cachedDrawAuthor: Boolean = false

    override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
        super.onLayout(changed, left, top, right, bottom)
        viewWidth = width.toFloat()
        viewHeight = height.toFloat()
        filletPath.reset()
        if (width > 10 && height > 10) {
            val radius = context.radius.smF
            filletPath.addRoundRect(
                0f,
                0f,
                viewWidth,
                viewHeight,
                radius,
                radius,
                Path.Direction.CW
            )
        }
    }

    override fun onDraw(canvas: Canvas) {
        if (!filletPath.isEmpty) {
            canvas.clipPath(filletPath)
        }
        super.onDraw(canvas)
        if (defaultCover && !isInEditMode) {
            drawNameAuthor(canvas)
        }
    }

    private fun drawTextWithStroke(
        canvas: Canvas,
        text: String,
        x: Float,
        y: Float,
        paint: TextPaint
    ) {
        paint.color = Color.WHITE
        paint.style = Paint.Style.STROKE
        canvas.drawText(text, x, y, paint)
        paint.color = context.accentColor
        paint.style = Paint.Style.FILL
        canvas.drawText(text, x, y, paint)
    }

    private fun drawNameAuthor(canvas: Canvas) {
        if (viewWidth <= 0f || viewHeight <= 0f) return
        val curAccent = context.accentColor
        val curDrawName = BookCover.drawBookName
        val curDrawAuthor = BookCover.drawBookAuthor
        val cached = cachedPicture
        if (cached != null
            && cachedName == name
            && cachedAuthor == author
            && cachedWidth == viewWidth
            && cachedHeight == viewHeight
            && cachedAccent == curAccent
            && cachedDrawName == curDrawName
            && cachedDrawAuthor == curDrawAuthor
        ) {
            canvas.drawPicture(cached)
            return
        }
        val picture = Picture()
        val recordCanvas = picture.beginRecording(viewWidth.toInt(), viewHeight.toInt())
        recordNameAuthor(recordCanvas)
        picture.endRecording()
        canvas.drawPicture(picture)
        cachedPicture = picture
        cachedName = name
        cachedAuthor = author
        cachedWidth = viewWidth
        cachedHeight = viewHeight
        cachedAccent = curAccent
        cachedDrawName = curDrawName
        cachedDrawAuthor = curDrawAuthor
    }

    private fun recordNameAuthor(canvas: Canvas) {
        val nameArr = if (BookCover.drawBookName) name?.toStringArray() else null
        val authorArr = if (BookCover.drawBookAuthor) author?.toStringArray() else null
        if (nameArr.isNullOrEmpty() && authorArr.isNullOrEmpty()) return

        val topMargin = viewHeight * 0.05f
        val bottomMargin = viewHeight * 0.95f

        nameArr?.let { nameList ->
            namePaint.textSize = viewWidth / 6
            namePaint.strokeWidth = namePaint.textSize / 5
            var colX = viewWidth * 0.1f + namePaint.textSize / 2
            var curY = topMargin + namePaint.textHeight
            var colNum = 1

            for (i in nameList.indices) {
                val isLastCharOfName = i == nameList.size - 1
                val isLastSlotInColumn = curY + namePaint.textHeight > bottomMargin

                if (colNum == 3 && isLastSlotInColumn && !isLastCharOfName) {
                    drawTextWithStroke(canvas, "…", colX, curY, namePaint)
                    break
                }

                drawTextWithStroke(canvas, nameList[i], colX, curY, namePaint)

                if (!isLastCharOfName) {
                    if (isLastSlotInColumn) {
                        colNum++
                        colX += namePaint.textSize
                        namePaint.textSize = viewWidth / 10
                        namePaint.strokeWidth = namePaint.textSize / 5

                        val remaining = nameList.size - i - 1
                        val neededHeight = remaining * namePaint.textHeight
                        curY = if (neededHeight < (bottomMargin - topMargin)) {
                            (viewHeight - neededHeight) / 2 + namePaint.textHeight
                        } else {
                            topMargin + namePaint.textHeight
                        }
                    } else {
                        curY += namePaint.textHeight
                    }
                }
            }
        }

        authorArr?.let { authorList ->
            authorPaint.textSize = viewWidth / 10
            authorPaint.strokeWidth = authorPaint.textSize / 5
            val colX = viewWidth * 0.85f
            val neededHeight = authorList.size * authorPaint.textHeight
            var curY = maxOf(viewHeight * 0.95f - neededHeight, viewHeight * 0.2f)

            authorList.forEach { char ->
                curY = maxOf(curY, topMargin + authorPaint.textHeight)
                if (curY > viewHeight * 0.98f) return@forEach
                drawTextWithStroke(canvas, char, colX, curY, authorPaint)
                curY += authorPaint.textHeight
            }
        }
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val wSpec = MeasureSpec.getSize(widthMeasureSpec)
        val hSpec = MeasureSpec.getSize(heightMeasureSpec)
        val wMode = MeasureSpec.getMode(widthMeasureSpec)
        val hMode = MeasureSpec.getMode(heightMeasureSpec)
        if (wMode == MeasureSpec.EXACTLY && hMode != MeasureSpec.EXACTLY && wSpec > 0) {
            val h = wSpec * coverRatio.heightRatio / coverRatio.widthRatio
            super.onMeasure(widthMeasureSpec, MeasureSpec.makeMeasureSpec(h, MeasureSpec.EXACTLY))
        } else if (hMode == MeasureSpec.EXACTLY && wMode != MeasureSpec.EXACTLY && hSpec > 0) {
            val w = hSpec * coverRatio.widthRatio / coverRatio.heightRatio
            super.onMeasure(MeasureSpec.makeMeasureSpec(w, MeasureSpec.EXACTLY), heightMeasureSpec)
        } else {
            super.onMeasure(widthMeasureSpec, heightMeasureSpec)
        }
    }

    /**
     * 默认封面分支:绕过图片库直接 setImageDrawable,
     * 保留 NinePatchDrawable 的 9-patch chunk,配合 FIT_XY 让拉伸区生效。
     */
    private fun showDefaultCover() {
        scaleType = ScaleType.FIT_XY
        setImageDrawable(BookCover.newDefaultDrawable(coverRatio, defaultCoverSeed()))
    }

    // 默认封面按"书名"稳定选图;无书名时回落到 path,再无则随机。
    private fun defaultCoverSeed(): String? {
        return name?.takeIf { it.isNotBlank() } ?: bitmapPath
    }

    fun load(
        path: String? = null,
        name: String? = null,
        author: String? = null,
        loadOnlyWifi: Boolean = false,
        sourceOrigin: String? = null,
        fragment: Fragment? = null,
        lifecycle: Lifecycle? = null,
        inBookshelf: Boolean = false,
        onLoadFinish: (() -> Unit)? = null
    ) {
        this.bitmapPath = path
        this.name = name?.replace(AppPattern.bdRegex, "")?.trim()
        this.author = author?.replace(AppPattern.bdRegex, "")?.trim()
        defaultCover = true
        showDefaultCover()
        invalidate()
        // useDefaultCover 或无 path 时直接停在默认封面,不进 Coil3 -- 保住 9-patch
        if (path.isNullOrBlank() || AppConfig.useDefaultCover) {
            onLoadFinish?.invoke()
            return
        }
        val doLoad = {
            // placeholder 走 setImageDrawable(上面 showDefaultCover),9-patch chunk 不会被光栅化;
            // 加载期间默认图能正确拉伸。error 由 listener.onError 接管(显示默认封面)。
            this@CoverImageView.loadCoil(path) {
                coverConfig(
                    seed = defaultCoverSeed(),
                    ratio = coverRatio,
                    sourceOrigin = sourceOrigin,
                    onLoadFinish = onLoadFinish,
                )
                placeholder(BookCover.newDefaultDrawable(coverRatio, defaultCoverSeed()))
                listener(
                    onSuccess = { _, _ ->
                        defaultCover = false
                        scaleType = ScaleType.CENTER_CROP
                    },
                    onError = { _, _ ->
                        defaultCover = true
                        showDefaultCover()
                    },
                )
            }
            Unit
        }
        // 等待布局完成,确保 Coil3 拿到最新的 cover 宽高
        if (width > 0 && height > 0 && !isLayoutRequested) {
            doLoad()
        } else post(doLoad)
    }
}
