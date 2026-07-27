package io.legado.app.ui.book.read.page.provider

/**
 * 排版测量面收口：把 TextChapterLayout/ZhLayout 消费的平台测量原语抽象出来。
 * 已下沉 shared commonMain（跨端共用）；安卓实现 AndroidTextMeasurer 留在 app。
 */
interface TextMeasurer {

    /**
     * 逐字素簇宽度写入 [widths]（口径 = TextPaint.getTextWidthsCompat，含 API35 letterSpacing 首末补偿）。
     * 热路径：[widths] 由调用方复用，实现不得新分配。
     */
    fun measureGlyphWidths(text: String, widths: FloatArray)

    /** 整串期望宽度（口径 = measureText + API35 letterSpacing*textSize 补偿）。 */
    fun measureWidth(text: String): Float

    /** letterSpacing*textSize（px），断行宽度余量用。 */
    val letterSpacingPx: Float

    /**
     * textSize（px）：消除 [android.text.TextPaint.textSize] 直接访问，统一从测量面取值。
     * 用于 justifyByLetterSpacing 把 px 偏移折算成 letterSpacing 单位（d / textSize）。
     */
    val textSizePx: Float

    /**
     * descent（px）：消除 [android.graphics.Paint.FontMetrics] 类型依赖，仅取下行度量 1 字段。
     * 原链路用 FontMetrics 全字段透传，实际只消费 [descent]——下沉为 Float 让 TextChapterLayout
     * 不再持 android.graphics 类型。
     */
    val descent: Float
}
