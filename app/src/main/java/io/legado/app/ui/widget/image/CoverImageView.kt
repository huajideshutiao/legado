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
import androidx.fragment.app.Fragment
import androidx.lifecycle.Lifecycle
import com.bumptech.glide.Glide
import com.bumptech.glide.load.DataSource
import com.bumptech.glide.load.engine.GlideException
import com.bumptech.glide.request.RequestListener
import com.bumptech.glide.request.target.Target
import io.legado.app.constant.AppPattern
import io.legado.app.lib.theme.accentColor
import io.legado.app.model.BookCover
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
    private var filletPath = Path()
    private var viewWidth: Float = 0f
    private var viewHeight: Float = 0f
    private var defaultCover = true
    var bitmapPath: String? = null
        private set
    private var name: String? = null
    private var author: String? = null
    private var nameHeight = 0f
    private var authorHeight = 0f
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
            filletPath.addRoundRect(0f, 0f, viewWidth, viewHeight, 10f, 10f, Path.Direction.CW)
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
        val leftMargin = viewWidth * 0.1f

        nameArr?.let { nameList ->
            namePaint.textSize = viewWidth / 6
            namePaint.strokeWidth = namePaint.textSize / 5
            var colX = leftMargin + namePaint.textSize / 2
            var curY = topMargin + namePaint.textHeight
            var colNum = 1

            for (i in nameList.indices) {
                val isLastCharOfName = i == nameList.size - 1
                val nextY = curY + namePaint.textHeight
                val isLastSlotInColumn = nextY > bottomMargin

                if (colNum == 3 && isLastSlotInColumn && !isLastCharOfName) {
                    drawTextWithStroke(canvas, "…", colX, curY, namePaint)
                    break
                }

                drawTextWithStroke(canvas, nameList[i], colX, curY, namePaint)

                if (!isLastCharOfName) {
                    if (isLastSlotInColumn) {
                        colNum++
                        if (colNum > 3) break

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
                        curY = maxOf(curY, topMargin + namePaint.textHeight)
                    } else {
                        curY = nextY
                    }
                }
            }
        }

        authorArr?.let { authorList ->
            authorPaint.textSize = viewWidth / 10
            authorPaint.strokeWidth = authorPaint.textSize / 5
            val colX = viewWidth * 0.85f
            val neededHeight = authorList.size * authorPaint.textHeight
            var curY = viewHeight * 0.95f - neededHeight
            curY = maxOf(curY, viewHeight * 0.2f)

            authorList.forEach { char ->
                if (curY < topMargin + authorPaint.textHeight) {
                    curY = topMargin + authorPaint.textHeight
                }
                if (curY > viewHeight * 0.98f) return@forEach

                drawTextWithStroke(canvas, char, colX, curY, authorPaint)
                curY += authorPaint.textHeight
            }
        }
    }

    fun setHeight(height: Int) {
        val width = height * 5 / 7
        minimumWidth = width
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
        val requestManager =
            if (fragment != null && lifecycle != null) Glide.with(fragment).lifecycle(lifecycle)
            else Glide.with(context)
        BookCover.load(requestManager, path, loadOnlyWifi, sourceOrigin, inBookshelf, onLoadFinish)
            .addListener(glideListener).placeholder(BookCover.defaultDrawable).into(this)
    }
}
