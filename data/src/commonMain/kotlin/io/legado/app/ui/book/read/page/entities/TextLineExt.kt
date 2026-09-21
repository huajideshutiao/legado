package io.legado.app.ui.book.read.page.entities

/**
 * TextLine 行几何扩展（由 app 端 `render/TextLineRender.kt` 的 upTopBottom 迁移而来）。
 *
 * 原实现直接读 android 静态 `ChapterProvider.paddingTop`，下沉 commonMain 后无法依赖
 * app 侧单例，改为显式传参（app 端调用方传 `ChapterProvider.paddingTop`），保持纯 Kotlin。
 */
fun TextLine.upTopBottom(durY: Float, textHeight: Float, descent: Float, paddingTop: Float) {
    lineTop = paddingTop + durY
    lineBottom = lineTop + textHeight
    lineBase = lineBottom - descent
}
