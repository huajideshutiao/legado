package io.legado.app.ui.book.read.page.provider

import kotlinx.atomicfu.locks.SynchronizedObject
import kotlinx.atomicfu.locks.synchronized
import org.jetbrains.skia.Font
import org.jetbrains.skia.FontMgr
import org.jetbrains.skia.FontStyle
import org.jetbrains.skia.Typeface

/**
 * desktop / iOS / 鸿蒙 共用的 [TextMeasurer]: 走 Skia 真实字形度量, 取代等宽近似 [SimpleTextMeasurer]。
 *
 * 口径与 app 端 `AndroidTextMeasurer` (`TextPaint.getTextWidthsCompat`) 对齐: 逐字宽度 = 字形
 * advance + [letterSpacingPx] (仅 advance > 0 的 code unit), 按 UTF-16 code unit 写回、代理对
 * 低位补 0; [measureWidth] 复用同一套逐字计算求和, 保证与 [measureGlyphWidths] 自洽 (两端对齐
 * `justifyByLetterSpacing` 依赖此性质)。主字体缺字 (.notdef) 时按码点走 [FontMgr] 回退字体取
 * advance, 对应绘制侧 SkParagraph 的 defaultFallback。
 *
 * 平台差异只有默认字体别名表 [readerFontFamilies] 与回退匹配 locale [readerFallbackLocaleTags]。
 */
class SkiaTextMeasurer(
    override val textSizePx: Float,
    override val letterSpacingPx: Float = 0f,
    typeface: Typeface? = defaultReaderTypeface(),
) : TextMeasurer {

    private val font = Font(typeface, textSizePx).apply {
        // 与 skiko 绘制侧 subpixelPositioning=true 对齐, 否则 advance 被 hinting 取整
        isSubpixel = true
    }

    private val primaryFamily: String? = typeface?.familyName

    /** 回退查表结果: 主字体与字号由实例固定。 */
    private val fallbackAdvances = HashMap<Int, Float>()
    private val fallbackFonts = HashMap<String, Font>()

    // JVM 上 atomicfu 的 SynchronizedObject 是 Any 的 typealias、synchronized 即 kotlin.synchronized
    private val fallbackLock = SynchronizedObject()

    override val descent: Float get() = font.metrics.descent

    override val ascent: Float get() = font.metrics.ascent

    override fun measureGlyphWidths(text: String, widths: FloatArray) {
        forEachGlyphWidth(text) { index, width -> if (index < widths.size) widths[index] = width }
    }

    override fun measureWidth(text: String): Float {
        var sum = 0f
        forEachGlyphWidth(text) { _, width -> sum += width }
        return sum
    }

    /** 按 UTF-16 code unit 回调每位宽度 (代理对低位回调 0), 保证两个 measure 同源同口径。 */
    private inline fun forEachGlyphWidth(text: String, emit: (index: Int, width: Float) -> Unit) {
        if (text.isEmpty()) return
        val glyphs = font.getStringGlyphs(text)
        val advances = font.getWidths(glyphs)
        var codePointIndex = 0
        var i = 0
        while (i < text.length) {
            val c = text[i]
            val isPair = c.isHighSurrogate() && i + 1 < text.length && text[i + 1].isLowSurrogate()
            var advance = advances.getOrElse(codePointIndex) { 0f }
            if (glyphs.getOrElse(codePointIndex) { 0 }.toInt() == 0) {
                // 无 String.codePointAt (JVM 独有), 手工拼代理对码点 (与 JVM codePointAt 同值)
                val codePoint = if (isPair) {
                    ((c.code - 0xD800) shl 10) + text[i + 1].code - 0xDC00 + 0x10000
                } else {
                    c.code
                }
                val fallback = fallbackAdvance(codePoint)
                if (fallback > 0f) advance = fallback
            }
            emit(i, if (advance > 0f) advance + letterSpacingPx else 0f)
            if (isPair) {
                emit(i + 1, 0f)
                i += 2
            } else {
                i++
            }
            codePointIndex++
        }
    }

    /**
     * 主字体缺字时按码点取回退字体的 advance (对应 SkParagraph 绘制时的 defaultFallback)。
     * 取不到返回 0, 调用方保留 .notdef 宽度。
     */
    private fun fallbackAdvance(codePoint: Int): Float = synchronized(fallbackLock) {
        // 键只要码点: 主字体与字号由实例固定
        fallbackAdvances.getOrPut(codePoint) {
            val typeface = runCatching {
                FontMgr.default.matchFamilyStyleCharacter(
                    primaryFamily, FontStyle.NORMAL, readerFallbackLocaleTags, codePoint
                )
            }.getOrNull()
            if (typeface == null) {
                0f
            } else {
                val fallbackFont = fallbackFonts.getOrPut(typeface.familyName) {
                    Font(typeface, textSizePx).apply { isSubpixel = true }
                }
                val text = codePointToString(codePoint)
                fallbackFont.getWidths(fallbackFont.getStringGlyphs(text)).firstOrNull() ?: 0f
            }
        }
    }

    companion object {

        /** 码点 → String (无 StringBuilder.appendCodePoint, 手工 UTF-16 编码)。 */
        private fun codePointToString(codePoint: Int): String = when {
            codePoint in 0x10000..0x10FFFF -> {
                val v = codePoint - 0x10000
                charArrayOf(
                    (0xD800 + (v shr 10)).toChar(),
                    (0xDC00 + (v and 0x3FF)).toChar(),
                ).concatToString()
            }

            else -> codePoint.toChar().toString()
        }

        /**
         * 与 Compose `FontFamily.Default` 同源的默认字形: CMP skiko 把 Default 映射到平台字体别名表,
         * 这里按同一份表 ([readerFontFamilies]) 向 [FontMgr.default] 取; 取不到给 null (Skia 默认字形)。
         */
        fun defaultReaderTypeface(): Typeface? =
            runCatching { FontMgr.default.matchFamiliesStyle(readerFontFamilies, FontStyle.NORMAL) }
                .getOrNull()

        /**
         * 用户自定义正文字体 (`ReadBookConfig.textFont`) → Typeface: 走 `makeFromFile`, 与绘制侧
         * Compose `Font(File)` 读同一个文件; 空路径 / 加载失败回落 [defaultReaderTypeface]。
         */
        fun readerTypeface(fontPath: String): Typeface? {
            if (fontPath.isEmpty()) return defaultReaderTypeface()
            return runCatching { FontMgr.default.makeFromFile(fontPath) }.getOrNull()
                ?: defaultReaderTypeface()
        }
    }
}

/** 平台系统字体别名表 (与各端 Compose `FontFamily.Default` 的映射同源)。 */
internal expect val readerFontFamilies: Array<String?>

/** 缺字回退匹配用的 BCP47 标签 (desktop = 系统 locale, iOS / 鸿蒙 = 中文正文固定表)。 */
internal expect val readerFallbackLocaleTags: Array<String>

/** 注册真实字形度量器 (三端 provider 注册入口; 未注册回退 SimpleTextMeasurer 等宽近似)。 */
fun registerSkiaTextMeasurer() {
    TextMeasurerProviders.register { textSizePx, letterSpacingPx, fontPath ->
        SkiaTextMeasurer(
            textSizePx = textSizePx,
            letterSpacingPx = letterSpacingPx,
            typeface = SkiaTextMeasurer.readerTypeface(fontPath),
        )
    }
}
