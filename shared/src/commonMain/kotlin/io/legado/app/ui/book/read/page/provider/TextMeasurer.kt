package io.legado.app.ui.book.read.page.provider

import kotlin.concurrent.Volatile

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

    /**
     * ascent（px，基线以上为负）：配合 [descent] 还原真实字体高度
     * （`descent - ascent` = app 端 `paint.textHeight`，排版行盒高度与行距基准用）。
     */
    val ascent: Float
}

/**
 * 平台真实字形度量注册处：未注册时排版回退等宽近似 [SimpleTextMeasurer]。
 *
 * desktop 在 `Main.kt` 注册 `SkiaTextMeasurer`；iOS / 鸿蒙暂未注册（走 fallback）。
 */
object TextMeasurerProviders {

    @Volatile
    private var factory: TextMeasurerFactory? = null

    /** 宿主启动早期注册一次（任何章节排版之前）。 */
    fun register(factory: TextMeasurerFactory) {
        this.factory = factory
    }

    /** 未注册返回 null，由调用方回退 [SimpleTextMeasurer]。 */
    fun createOrNull(textSizePx: Float, letterSpacingPx: Float, fontPath: String): TextMeasurer? =
        factory?.invoke(textSizePx, letterSpacingPx, fontPath)
}

/**
 * 度量器工厂：[fontPath] 为 `ReadBookConfig.textFont`（自定义字体文件绝对路径，空 = 默认字体）。
 * 实现必须与绘制侧 `loadReaderFontFamily` 读同一个文件、失败时回落同一个默认字体，
 * 否则「A 字体度量 / B 字体绘制」会让正文错位。
 */
typealias TextMeasurerFactory =
        (textSizePx: Float, letterSpacingPx: Float, fontPath: String) -> TextMeasurer
