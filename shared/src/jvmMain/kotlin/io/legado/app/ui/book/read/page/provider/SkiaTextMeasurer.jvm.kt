package io.legado.app.ui.book.read.page.provider

import java.util.Locale

/**
 * 桌面 actual: CMP skiko 把 `FontFamily.Default` 映射到的 `FontFamily.SansSerif` 平台别名表
 * (PlatformFont.skiko.kt 的 GenericFontFamiliesMapping)。
 */
internal actual val readerFontFamilies: Array<String?> = run {
    val os = System.getProperty("os.name").orEmpty().lowercase()
    when {
        os.contains("win") -> arrayOf<String?>("Segoe UI", "Arial")
        os.contains("mac") -> arrayOf<String?>(".AppleSystemUIFont", "Helvetica Neue", "Helvetica")
        else -> arrayOf<String?>("Noto Sans", "DejaVu Sans", "Arial")
    }
}

/** 桌面 actual: 回退匹配跟随系统 locale。 */
internal actual val readerFallbackLocaleTags: Array<String> =
    arrayOf(Locale.getDefault().toLanguageTag())
