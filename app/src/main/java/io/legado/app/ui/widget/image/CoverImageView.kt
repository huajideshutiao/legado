package io.legado.app.ui.widget.image

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.text.TextPaint
import android.util.AttributeSet
import androidx.appcompat.widget.AppCompatImageView
import androidx.core.content.withStyledAttributes
import androidx.fragment.app.Fragment
import androidx.lifecycle.Lifecycle
import com.bumptech.glide.Glide
import com.bumptech.glide.load.DataSource
import com.bumptech.glide.load.engine.GlideException
import com.bumptech.glide.request.RequestListener
import com.bumptech.glide.request.target.Target
import io.legado.app.R
import io.legado.app.constant.AppPattern
import io.legado.app.lib.theme.accentColor
import io.legado.app.model.BookCover
import io.legado.app.utils.dpToPx
import io.legado.app.utils.lifecycle
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

    enum class CoverRatio(val widthRatio: Int, val heightRatio: Int) {
        NOVEL(3, 4),
        VIDEO(16, 9)
    }

    var coverRatio: CoverRatio = CoverRatio.NOVEL
        set(value) {
            if (field != value) {
                field = value
                requestLayout()
            }
        }

    init {
        scaleType = ScaleType.CENTER_CROP
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

    override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
        super.onLayout(changed, left, top, right, bottom)
        viewWidth = width.toFloat()
        viewHeight = height.toFloat()
        filletPath.reset()
        if (width > 10 && height > 10) {
            val radius = 4f.dpToPx()
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

    private val glideListener by lazy {
        object : RequestListener<Drawable> {

            override fun onLoadFailed(
                e: GlideException?,
                model: Any?,
                target: Target<Drawable>,
                isFirstResource: Boolean
            ): Boolean {
                defaultCover = true
                return false
            }

            override fun onResourceReady(
                resource: Drawable,
                model: Any,
                target: Target<Drawable>?,
                dataSource: DataSource,
                isFirstResource: Boolean
            ): Boolean {
                defaultCover = false
                return false
            }

        }
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
        invalidate()
        val requestManager = fragment?.let {
            lifecycle?.let { Glide.with(fragment).lifecycle(it) }
        } ?: Glide.with(context)
        BookCover.load(requestManager, path, loadOnlyWifi, sourceOrigin, inBookshelf, onLoadFinish)
            .addListener(glideListener).placeholder(BookCover.defaultDrawable).into(this)
    }
}
