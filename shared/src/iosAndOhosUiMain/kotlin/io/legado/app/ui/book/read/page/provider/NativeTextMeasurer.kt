package io.legado.app.ui.book.read.page.provider

import org.jetbrains.skia.Font
import org.jetbrains.skia.FontMgr
import org.jetbrains.skia.FontStyle
import org.jetbrains.skia.Typeface
import kotlinx.atomicfu.locks.SynchronizedObject
import kotlinx.atomicfu.locks.synchronized

/**
 * iOS / 鸿蒙共用 [TextMeasurer]: 走 Skia 真实字形度量 (与 desktop SkiaTextMeasurer 同源同口径),
 * 取代等宽近似 [SimpleTextMeasurer]。
 *
 * # 口径 (与 SkiaTextMeasurer / AndroidTextMeasurer 对齐)
 * - 逐字素簇宽度 = 字形 advance + letterSpacingPx (仅 advance > 0 的 code unit),
 *   按 UTF-16 code point 遍历, 代理对低位补 0 — 与 Android getTextWidthsCompat 一致;
 * - [measureWidth] 复用同一套逐字计算求和, 保证 `measureWidth(s) == measureGlyphWidths(s).sum()`
 *   (两端对齐 justifyByLetterSpacing 依赖此自洽性);
 * - descent/ascent 取 Skia FontMetrics, 符号与 Android Paint.fontMetrics 一致;
 * - 主字体缺字 (.notdef) 时按码点走 FontMgr 回退字体取 advance,
 *   对应 Compose SkParagraph 绘制时的 defaultFallback (中日韩正文在主字体缺字场景必须回退)。
 *
 * # 与绘制侧同源
 * 绘制侧 (ReaderDrawStyle 的 Compose FontFamily.Default) 在两端各映射到系统字体
 * (iOS PingFang SC / 鸿蒙 HarmonyOS Sans); 这里按同一份别名表 ([readerFontFamilies]) 向
 * [FontMgr.default] 取, 用户自定义字体 (ReadBookConfig.textFont) 走 makeFromFile 读同一文件。
 *
 * 注册: [registerNativeTextMeasurer] (registerIosProviders / registerOhosProviders 中),
 * 未注册仍回退 SimpleTextMeasurer。
 */
class NativeTextMeasurer(
    override val textSizePx: Float,
    override val letterSpacingPx: Float = 0f,
    typeface: Typeface? = defaultReaderTypeface(),
) : TextMeasurer {

    private val font = Font(typeface, textSizePx).apply {
        // 与 skiko 绘制侧 subpixelPositioning=true 对齐, 否则 advance 被 hinting 取整
        isSubpixel = true
    }

    private val primaryFamily: String? = typeface?.familyName

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
                // commonMain 无 String.codePointAt (internal), 手工拼代理对码点 (与 JVM codePointAt 同值)
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
     * 取不到返回 0, 调用方保留 .notdef 宽度。结果按 (码点, 字号) 缓存。
     */
    private fun fallbackAdvance(codePoint: Int): Float {
        val key = (codePoint.toLong() shl 32) or (textSizePx.toRawBits().toLong() and 0xFFFFFFFFL)
        advanceCache[key]?.let { return it }
        val advance = synchronized(cacheLock) {
            val typeface = runCatching {
                FontMgr.default.matchFamilyStyleCharacter(
                    primaryFamily, FontStyle.NORMAL, localeTags, codePoint
                )
            }.getOrNull()
            if (typeface == null) {
                0f
            } else {
                val fallbackFont = fontCache.getOrPut("${typeface.familyName}@$textSizePx") {
                    Font(typeface, textSizePx).apply { isSubpixel = true }
                }
                val text = codePointToString(codePoint)
                fallbackFont.getWidths(fallbackFont.getStringGlyphs(text)).firstOrNull() ?: 0f
            }
        }
        advanceCache[key] = advance
        return advance
    }

    companion object {

        /** 回退查表结果全局缓存: 度量器按章重建, 缓存跟着重建就白查了。 */
        private val advanceCache = HashMap<Long, Float>()
        private val fontCache = HashMap<String, Font>()
        private val cacheLock = SynchronizedObject()

        /** 码点 → String (commonMain 无 StringBuilder.appendCodePoint, 手工 UTF-16 编码)。 */
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

        /** 回退匹配的 locale 标签 (中文正文场景) — 与绘制侧 SkParagraph 的 locale 语义近似。 */
        private val localeTags: Array<String> = arrayOf("zh-Hans", "zh-Hant", "en")

        /**
         * 与 Compose `FontFamily.Default` 同源的默认字形: CMP skiko 把 Default 映射到
         * 平台字体别名表, 这里按同一份别名表向 [FontMgr.default] 取, 取不到再回退 Skia 默认字体。
         */
        fun defaultReaderTypeface(): Typeface? =
            runCatching { FontMgr.default.matchFamiliesStyle(readerFontFamilies, FontStyle.NORMAL) }
                .getOrNull()

        /**
         * 用户自定义正文字体 (ReadBookConfig.textFont) → Typeface。
         * 走 `FontMgr.default.makeFromFile`, 与绘制侧 Compose `Font(File)` 读同一个文件;
         * 空路径 / 加载失败回落 [defaultReaderTypeface]。
         */
        fun readerTypeface(fontPath: String): Typeface? {
            if (fontPath.isEmpty()) return defaultReaderTypeface()
            return runCatching { FontMgr.default.makeFromFile(fontPath) }.getOrNull()
                ?: defaultReaderTypeface()
        }
    }
}

/** 平台系统字体别名表 (与各端 Compose `FontFamily.Default` 的映射同源, 两端仅此项不同)。 */
internal expect val readerFontFamilies: Array<String?>

/** 注册真实字形度量器 (两端 provider 注册中调用; 未注册回退 SimpleTextMeasurer 等宽近似)。 */
fun registerNativeTextMeasurer() {
    TextMeasurerProviders.register { textSizePx, letterSpacingPx, fontPath ->
        NativeTextMeasurer(
            textSizePx = textSizePx,
            letterSpacingPx = letterSpacingPx,
            typeface = NativeTextMeasurer.readerTypeface(fontPath),
        )
    }
}
