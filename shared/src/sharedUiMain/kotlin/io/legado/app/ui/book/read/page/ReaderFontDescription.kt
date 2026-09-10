package io.legado.app.ui.book.read.page

import androidx.compose.runtime.Immutable
import androidx.compose.ui.text.font.FontFamily

/** 阅读排版与绘制共用的默认字体语义别名。 */
enum class ReaderFontAlias {
    SansSerif,
}

/** 主字体缺字时的回退策略；由各文本栈复用平台原生字体回退能力实现。 */
enum class ReaderGlyphFallback {
    PlatformDefault,
}

/**
 * 阅读字体的跨文本栈解析描述。
 *
 * 此对象只表达稳定身份与策略，不携带无法跨平台共享的 Typeface / FontFamily。Skia 度量和
 * Compose 绘制分别把同一描述映射到平台对象，避免各自推断默认字体、字重和缺字回退。
 */
@Immutable
data class ReaderFontDescription(
    val customPath: String?,
    val weight: Int,
    val defaultAlias: ReaderFontAlias = ReaderFontAlias.SansSerif,
    val glyphFallback: ReaderGlyphFallback = ReaderGlyphFallback.PlatformDefault,
) {
    /**
     * 字体家族加载缓存身份；自定义字体失败后仍按 [defaultAlias] 回退。
     *
     * [weight] 刻意不参与：它由 [androidx.compose.ui.text.TextStyle.fontWeight] 应用，切换粗细
     * 不应重新读取同一个字体文件。描述对象本身仍是 data class，完整相等性继续包含字重。
     */
    val familyIdentity: String
        get() = buildString {
            append(customPath?.let { "file:$it" } ?: "alias:${defaultAlias.name}")
            append(";fallback=")
            append(glyphFallback.name)
        }
}

fun readerFontDescription(fontPath: String, weight: Int): ReaderFontDescription =
    ReaderFontDescription(
        customPath = fontPath.takeUnless { it.isEmpty() },
        weight = weight,
    )

/** Compose 对中性默认别名的明确映射；不再用 null 隐式选择默认字体。 */
internal fun ReaderFontAlias.toComposeFontFamily(): FontFamily = when (this) {
    ReaderFontAlias.SansSerif -> FontFamily.SansSerif
}

/** Compose 管线从统一描述解析；自定义字体失败时按同一默认别名回退。 */
internal fun resolveReaderFontFamily(description: ReaderFontDescription): FontFamily =
    description.customPath?.let(::loadReaderFontFamily)
        ?: description.defaultAlias.toComposeFontFamily()
