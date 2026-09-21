package io.legado.app.ui.book.read.page

/**
 * 阅读页字重口径的唯一来源：把用户配置 `ReadBookConfig.textBold`（0 正常 / 1 粗体 / 2 细体）
 * 映射成 100..900 的 CSS 字重值。
 *
 * 排版度量侧（`TextMeasurerProviders.createOrNull` 的 `weight` 参数）与绘制侧
 * （`ReaderDrawStyle` 的 `FontWeight`）必须调本对象取同一个值：两侧字重不同则
 * 「A 字重量度 / B 字形绘制」，粗体比常规宽，行尾会溢出可视区并被负字距兜底压回。
 *
 * 标题与正文各用一档（0 档标题粗、正文常规，与原版 `TextStyleProvider.getPaints` 一致）。
 */
object ReaderFontWeights {

    /** 章节标题字重。 */
    fun title(textBold: Int): Int = when (textBold) {
        1 -> 900
        2 -> 400
        else -> 700
    }

    /** 正文（含页眉页脚提示）字重。 */
    fun content(textBold: Int): Int = when (textBold) {
        1 -> 700
        2 -> 300
        else -> 400
    }
}
