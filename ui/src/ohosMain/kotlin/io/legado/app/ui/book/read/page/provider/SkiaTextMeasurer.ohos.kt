package io.legado.app.ui.book.read.page.provider

/** 鸿蒙 actual: CMP skiko 把 `FontFamily.Default` 映射到的系统字体别名表。 */
internal actual val readerFontFamilies: Array<String?> = arrayOf(
    "HarmonyOS Sans", "HarmonyOS Sans SC", "Noto Sans CJK SC", "sans-serif"
)

/** 鸿蒙 actual: 缺字回退按中文正文场景匹配 (与绘制侧 SkParagraph 的 locale 语义近似)。 */
internal actual val readerFallbackLocaleTags: Array<String> = arrayOf("zh-Hans", "zh-Hant", "en")
