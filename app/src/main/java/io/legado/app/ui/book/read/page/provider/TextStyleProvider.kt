package io.legado.app.ui.book.read.page.provider

import android.graphics.Paint
import android.graphics.Typeface
import android.os.Build
import android.text.StaticLayout
import android.text.TextPaint
import io.legado.app.help.config.AppConfig
import io.legado.app.help.config.ReadBookConfig
import io.legado.app.ui.book.read.page.TITLE_SIZE_EXTRA_SP
import io.legado.app.utils.FileDoc
import io.legado.app.utils.dpToPx
import io.legado.app.utils.isContentScheme
import io.legado.app.utils.openReadPfd
import io.legado.app.utils.spToPx
import io.legado.app.utils.textHeight

/**
 * 文字样式管理，负责 Paint/字体/间距等样式属性的创建与更新
 */
object TextStyleProvider {

    @JvmStatic
    var lineSpacingExtra = 0f
        private set

    @JvmStatic
    var paragraphSpacing = 0
        private set

    @JvmStatic
    var titleTopSpacing = 0
        private set

    @JvmStatic
    var titleBottomSpacing = 0
        private set

    @JvmStatic
    var indentCharWidth = 0f
        private set

    @JvmStatic
    var titlePaintTextHeight = 0f
        private set

    @JvmStatic
    var contentPaintTextHeight = 0f
        private set

    // 下沉 shared commonMain 的测量面只取 descent 1 字段；原 FontMetrics 全字段透传链路废弃。
    @JvmStatic
    var titlePaintDescent = 0f

    @JvmStatic
    var contentPaintDescent = 0f

    @JvmStatic
    var typeface: Typeface? = Typeface.DEFAULT
        private set

    @JvmStatic
    var titlePaint: TextPaint = TextPaint()

    @JvmStatic
    var contentPaint: TextPaint = TextPaint()

    @JvmStatic
    var reviewPaint: TextPaint = TextPaint()

    init {
        upStyle()
    }

    /**
     * 更新样式
     */
    fun upStyle() {
        typeface = getTypeface(ReadBookConfig.textFont)
        getPaints(typeface).let {
            titlePaint = it.first
            contentPaint = it.second
            reviewPaint.color = contentPaint.color
            // 段评气泡偏弱化，不抢正文，统一 60% 透明度
            reviewPaint.alpha = 153
            reviewPaint.textSize = contentPaint.textSize * 0.45f
            reviewPaint.textAlign = Paint.Align.CENTER
            reviewPaint.isAntiAlias = true
            // 气泡填充 + 数字共用 FILL（外圈缓存的是 ALPHA_8，drawBitmap 时按 paint 上色）
            reviewPaint.style = Paint.Style.FILL
        }
        //间距
        lineSpacingExtra = ReadBookConfig.lineSpacingExtra / 10f
        paragraphSpacing = ReadBookConfig.paragraphSpacing
        titleTopSpacing = ReadBookConfig.titleTopSpacing.dpToPx()
        titleBottomSpacing = ReadBookConfig.titleBottomSpacing.dpToPx()
        val bodyIndent = ReadBookConfig.paragraphIndent
        indentCharWidth = if (bodyIndent.isNotEmpty()) {
            var indentWidth = StaticLayout.getDesiredWidth(bodyIndent, contentPaint)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.VANILLA_ICE_CREAM) {
                indentWidth += contentPaint.letterSpacing * contentPaint.textSize
            }
            indentWidth / bodyIndent.length
        } else {
            0f
        }
        titlePaintTextHeight = titlePaint.textHeight
        contentPaintTextHeight = contentPaint.textHeight
        titlePaintDescent = titlePaint.fontMetrics.descent
        contentPaintDescent = contentPaint.fontMetrics.descent
    }

    private fun getTypeface(fontPath: String): Typeface? {
        return kotlin.runCatching {
            when {
                fontPath.isContentScheme() -> {
                    FileDoc.fromFile(fontPath).openReadPfd().getOrThrow()
                        .use {
                            Typeface.Builder(it.fileDescriptor).build()
                        }
                }

                fontPath.isNotEmpty() -> Typeface.createFromFile(fontPath)
                else -> when (AppConfig.systemTypefaces) {
                    1 -> Typeface.SERIF
                    2 -> Typeface.MONOSPACE
                    else -> Typeface.SANS_SERIF
                }
            }
        }.getOrElse {
            ReadBookConfig.textFont = ""
            ReadBookConfig.save()
            Typeface.SANS_SERIF
        } ?: Typeface.DEFAULT
    }

    private fun getPaints(typeface: Typeface?): Pair<TextPaint, TextPaint> {
        // 字体统一处理
        val bold = Typeface.create(typeface, Typeface.BOLD)
        val normal = Typeface.create(typeface, Typeface.NORMAL)
        val (titleFont, textFont) = when (ReadBookConfig.textBold) {
            1 -> {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P)
                    Pair(Typeface.create(typeface, 900, false), bold)
                else
                    Pair(bold, bold)
            }

            2 -> {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P)
                    Pair(normal, Typeface.create(typeface, 300, false))
                else
                    Pair(normal, normal)
            }

            else -> Pair(bold, normal)
        }

        //标题
        val tPaint = TextPaint()
        // 正文标题与页眉/页脚同主题色（tipColor=0 跟随正文色），字号略大（用户需求，
        // 与 shared ReaderDrawStyle.titleStyle 同口径：textSize + titleSize + TITLE_SIZE_EXTRA_SP）
        val tipColor = ReadBookConfig.config.tipColor
        tPaint.color = if (tipColor == 0) ReadBookConfig.textColor else tipColor
        tPaint.letterSpacing = ReadBookConfig.letterSpacing
        tPaint.typeface = titleFont
        tPaint.textSize = with(ReadBookConfig) {
            textSize + titleSize + TITLE_SIZE_EXTRA_SP
        }.toFloat().spToPx()
        tPaint.isAntiAlias = true
        if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.Q && AppConfig.optimizeRender) {
            tPaint.isLinearText = true
        }
        //正文
        val cPaint = TextPaint()
        cPaint.color = ReadBookConfig.textColor
        cPaint.letterSpacing = ReadBookConfig.letterSpacing
        cPaint.typeface = textFont
        cPaint.textSize = ReadBookConfig.textSize.toFloat().spToPx()
        cPaint.isAntiAlias = true
        if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.Q && AppConfig.optimizeRender) {
            cPaint.isLinearText = true
        }
        return Pair(tPaint, cPaint)
    }

}
