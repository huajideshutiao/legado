package io.legado.app.ui.book.read.page.provider

/** iOS actual: CMP skiko 把 `FontFamily.Default` 映射到的系统字体别名表。 */
internal actual val readerFontFamilies: Array<String?> = arrayOf(
    ".AppleSystemUIFont", "PingFang SC", "Helvetica Neue", "Helvetica"
)

/** iOS actual: 缺字回退按中文正文场景匹配 (与绘制侧 SkParagraph 的 locale 语义近似)。 */
internal actual val readerFallbackLocaleTags: Array<String> = arrayOf("zh-Hans", "zh-Hant", "en")
