package io.legado.app.ui.book.read.page.provider

import io.legado.app.ui.book.read.page.provider.SkiaTextMeasurer.Companion.defaultReaderTypeface
import io.legado.app.ui.book.read.page.provider.SkiaTextMeasurer.Companion.readerTypeface
import org.jetbrains.skia.Font
import org.jetbrains.skia.FontMgr
import org.jetbrains.skia.FontStyle
import org.jetbrains.skia.Typeface
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap

/**
 * [TextMeasurer] 桌面实现：走 Skia 真实字形度量（[Font.getWidths] / [Font.metrics]），
 * 取代等宽近似 [SimpleTextMeasurer]。
 *
 * 与 [DesktopTextMeasurer]（Compose `TextMeasurer`）的区别：本类是纯 Skia 对象，
 * 不需要 Composable 上下文，可在 `ReadBookViewModelShared.buildLayout()` 的协程里直接构造。
 *
 * # 与 AndroidTextMeasurer 的口径对齐依据
 *
 * 逐方法对照 `AndroidTextMeasurer` + `TextPaint.getTextWidthsCompat`：
 *
 * - **letterSpacing 如何参与**：Android 的 `letterSpacing` 由 Paint 摊进每个字符的 advance
 *   （每字 +`letterSpacing*textSize`）。API35 起 `getTextWidths` 砍掉了串首/串尾的外侧半格，
 *   `getTextWidthsCompat` 再给首末两个非零宽字符各补回 `letterSpacing*textSize*0.5`——
 *   两条路径最终都等价于「每个非零 advance 字符 + 一个完整 [letterSpacingPx]」。
 *   Skia 的 `Font` 没有 letterSpacing 概念，[getWidths] 只给纯字形 advance，
 *   所以本类按同一口径手工加：`advance > 0` 时 `+letterSpacingPx`，advance==0（组合符 /
 *   代理对低位）保持 0——与 compat 里 `if (widths[i] > 0)` 的守卫一致。
 * - **measureWidth**：Android 是 `measureText + letterSpacing*textSize`（API35 补偿），
 *   数值上等于逐字宽度之和。本类直接复用同一套逐字计算求和，保证
 *   `measureWidth(s) == measureGlyphWidths(s).sum()`——两端对齐（`justifyByLetterSpacing`）
 *   依赖这个自洽性。
 * - **descent**：Android 取 `Paint.fontMetrics.descent`（基线以下为正）；
 *   Skia `FontMetrics.descent` 同样是「distance to reserve below baseline, typically positive」，
 *   符号与量纲一致，直接取用。
 * - **索引口径**：`getTextWidths` 按 UTF-16 code unit 写回，簇内后续 code unit 为 0。
 *   Skia `getStringGlyphs` 是按 **code point** 出 glyph（内部走 `codePointsAsIntArray`），
 *   故本类按 code point 遍历，把 advance 写在代理对高位，低位补 0。
 *
 * 对照验证口径：同一字符串、同一 [Typeface]、同一字号下，本类与 `AndroidTextMeasurer`
 * 的 `measureGlyphWidths` 逐位差应为 0（两边都是「字形 advance + letterSpacingPx」）；
 * 残余差异只可能来自字形整形器不同——Android 走 Minikin 复杂整形（连字 / kerning），
 * 本类走 Skia 未整形 advance。中日韩正文（等宽表意文字，无连字）两者一致；
 * 西文连字场景可能出现亚像素级偏差，属可接受范围。
 *
 * @param textSizePx 字号（px）
 * @param letterSpacingPx 字间距（px，= app 端 `letterSpacing * textSize`）
 * @param typeface 字形来源；默认 [defaultReaderTypeface] 与 desktop 绘制侧
 *   （`PageContentCanvas` 的 Compose `FontFamily.Default`）同源，避免「A 字体度量 / B 字体绘制」。
 *   用户选了自定义字体时由注册处传 [readerTypeface] 的结果（与绘制侧读同一文件）。
 *   注意「同源」只到主字体：主字体缺字时 Compose 的 SkParagraph 会走字体回退（中日韩正文在
 *   Windows 上的 Segoe UI / Arial 里全部缺字），[Font] 却只会给 .notdef 的窄 advance，
 *   所以本类必须自己做同款回退，见 [fallbackAdvance]。
 */
class SkiaTextMeasurer(
    override val textSizePx: Float,
    override val letterSpacingPx: Float = 0f,
    typeface: Typeface? = defaultReaderTypeface(),
) : TextMeasurer {

    private val font = Font(typeface, textSizePx).apply {
        // Compose skiko 的 FontRasterizationSettings 各平台默认 subpixelPositioning=true，
        // 不开则 advance 会被 hinting 取整，与绘制侧对不上。
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

    /**
     * 按 UTF-16 code unit 回调每位宽度（代理对低位回调 0），
     * 保证 [measureGlyphWidths] 与 [measureWidth] 同源同口径。
     *
     * 主字体给出 glyph 0（.notdef）即为缺字，改用回退字体的 advance——与绘制侧一致。
     */
    private inline fun forEachGlyphWidth(text: String, emit: (index: Int, width: Float) -> Unit) {
        if (text.isEmpty()) return
        // Skia 无「写入调用方数组」的 API，glyphs/advances 两个临时数组不可避免；
        // 调用方（SimpleChapterLayout.setTypeText）本就按段落粒度调用，非逐字热路径。
        val glyphs = font.getStringGlyphs(text)
        val advances = font.getWidths(glyphs)
        var codePointIndex = 0
        var i = 0
        while (i < text.length) {
            val c = text[i]
            val isPair = c.isHighSurrogate() && i + 1 < text.length && text[i + 1].isLowSurrogate()
            var advance = advances.getOrElse(codePointIndex) { 0f }
            if (glyphs.getOrElse(codePointIndex) { 0 }.toInt() == 0) {
                val codePoint = if (isPair) text.codePointAt(i) else c.code
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
     * 主字体缺字时按码点取回退字体的 advance（对应 SkParagraph 绘制时的 defaultFallback）。
     * 取不到返回 0，调用方保留 .notdef 宽度。
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
                val text = String(Character.toChars(codePoint))
                fallbackFont.getWidths(fallbackFont.getStringGlyphs(text)).firstOrNull() ?: 0f
            }
        }
        advanceCache[key] = advance
        return advance
    }

    companion object {

        /** 回退查表结果全局缓存：度量器按章重建，缓存跟着重建就白查了。 */
        private val advanceCache = ConcurrentHashMap<Long, Float>()
        private val fontCache = HashMap<String, Font>()
        private val cacheLock = Any()
        private val localeTags: Array<String> =
            arrayOf(Locale.getDefault().toLanguageTag())

        /**
         * 与 Compose `FontFamily.Default` 同源的默认字形：CMP skiko 把 Default 映射到
         * `FontFamily.SansSerif` 的平台别名表（PlatformFont.skiko.kt 的 GenericFontFamiliesMapping），
         * 这里按同一份别名表向 [FontMgr.default] 取，取不到再回退 Skia 默认字体。
         */
        fun defaultReaderTypeface(): Typeface? {
            val os = System.getProperty("os.name").orEmpty().lowercase()
            val families = when {
                os.contains("win") -> arrayOf<String?>("Segoe UI", "Arial")
                os.contains("mac") -> arrayOf<String?>(
                    ".AppleSystemUIFont",
                    "Helvetica Neue",
                    "Helvetica"
                )

                else -> arrayOf<String?>("Noto Sans", "DejaVu Sans", "Arial")
            }
            return runCatching { FontMgr.default.matchFamiliesStyle(families, FontStyle.NORMAL) }
                .getOrNull()
        }

        /**
         * 用户自定义正文字体（`ReadBookConfig.textFont`）→ Typeface。
         * 走 `FontMgr.default.makeFromFile`，与绘制侧 `loadReaderFontFamily`（Compose
         * `Font(File)` 内部同一调用）读同一个文件；空路径 / 加载失败回落 [defaultReaderTypeface]。
         */
        fun readerTypeface(fontPath: String): Typeface? {
            if (fontPath.isEmpty()) return defaultReaderTypeface()
            return runCatching { FontMgr.default.makeFromFile(fontPath) }.getOrNull()
                ?: defaultReaderTypeface()
        }
    }
}
