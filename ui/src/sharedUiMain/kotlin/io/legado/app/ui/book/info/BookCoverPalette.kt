package io.legado.app.ui.book.info

import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.toPixelMap
import io.legado.app.utils.ColorUtils

/*
 * 详情页封面取色: 顶栏与(书名+字数+最新章节)前景色按其背后的模糊封面背景自适应。
 *
 * 取色不独立发起加载 —— 由 blur 背景加载成功的回调 ([LocalCoverLoaded]) 送来封面原图,
 * 就地采样分区均值, 再按 [BookInfoBgTransformation] 的视觉模型换算元素背后的有效背景:
 * - 整体压暗 argb(50,0,0,0) SRC_ATOP ≈ RGB × [DARKEN]
 * - 竖屏书名块位于 300dp 背景的渐变尾段 (封面残留 alpha ≈ [CONTENT_ALPHA]), 有效背景 =
 *   lerp(页面底色, 封面分区均值, alpha); 横屏左列无渐变, alpha = 1
 * - 顶栏背后: 竖屏 300dp 条 CENTER_CROP 只露出封面中段 (~22%~78%), 取可见段上部;
 *   横屏整高铺满取顶部
 *
 * 回调未发生 (加载失败/E-Ink/未注册 loader) 时 palette 为 null, 调用方回退原有固定色。
 */

/** 封面取色结果。 */
data class BookCoverPalette(
    /** 顶栏 (返回/标题/编辑/分享/更多) 前景 */
    val topBarFg: Color,
    /** 书名前景 (primaryText 昼夜对) */
    val nameFg: Color,
    /** 字数前景 (secondaryText 昼夜对) */
    val wordCountFg: Color,
    /** 最新章节前景 (summaryText 刻意不随昼夜, 两态同值) */
    val lastedFg: Color,
)

/**
 * "blur 背景封面原图就绪"回调: [BookInfoScreen] 顶层 provides (值 = 分区采样),
 * 平台 blur 实现 ([SharedBlurCoverBgCoil] / app 端 BookInfoBlurCoverBg) 加载成功时
 * 调用并送入封面**原图** bitmap (未经模糊/渐变处理)。默认 null, 非详情页宿主不回调。
 */
val LocalCoverLoaded = staticCompositionLocalOf<((ImageBitmap) -> Unit)?> { null }

/**
 * 当前封面取色结果: [BookInfoScreen] 顶层经 [deriveBookCoverPalette] 推导后 provides,
 * 头部/顶栏按 `current?.xxx ?: 固定色` 消费。默认 null (回调未发生: 加载中/失败/E-Ink/
 * devFeat 竖屏), 消费方回退原有固定色。
 */
val LocalBookCoverPalette = staticCompositionLocalOf<BookCoverPalette?> { null }

/** 封面分区原始均值 (已叠压暗, 未混合页面底色)。 */
class CoverRegions internal constructor(internal val topBar: Color, internal val content: Color)

/** BookInfoBgTransformation 的 argb(50,0,0,0) SRC_ATOP 压暗 ≈ RGB × 0.8。 */
private const val DARKEN = 0.8f

/** 竖屏书名块渐变尾段的封面残留 alpha (渐变 stops ~0.80 处)。 */
private const val CONTENT_ALPHA = 0.35f

/** 分区纵向采样范围 (封面高度百分比): 竖屏取 CENTER_CROP 可见中段, 横屏整高可见取顶部/中部。 */
private val PORTRAIT_TOP_BAR_BAND = 0.20f to 0.32f
private val PORTRAIT_CONTENT_BAND = 0.62f to 0.75f
private val LANDSCAPE_TOP_BAR_BAND = 0.05f to 0.20f
private val LANDSCAPE_CONTENT_BAND = 0.35f to 0.60f

// 头部三档文字各映射项目既有 token (AppTheme.kt): 书名块背景只做一次亮暗判定, 三者共用;
// 顶栏沿用 BottomButtons 同款黑白切换强度
private val TOP_BAR_FG_ON_LIGHT_BG = Color(0xDE000000)
private val TOP_BAR_FG_ON_DARK_BG = Color.White
private val NAME_FG_ON_LIGHT_BG = Color(0xFF212121)       // primaryText 亮
private val NAME_FG_ON_DARK_BG = Color(0xFFF8F8F8)        // primaryText 暗
private val WORD_COUNT_FG_ON_LIGHT_BG = Color(0xFF595959) // secondaryText 亮
private val WORD_COUNT_FG_ON_DARK_BG = Color(0xFFCDCDCD)  // secondaryText 暗
private val LASTED_FG = Color(0xFF909090)                 // summaryText (不随昼夜)

/** blur 背景"原图加载完成"回调内调用: 分区均值采样 + 压暗换算 (任意尺寸 bitmap 均可)。 */
fun sampleCoverRegions(bmp: ImageBitmap, land: Boolean): CoverRegions {
    val topBarBand = if (land) LANDSCAPE_TOP_BAR_BAND else PORTRAIT_TOP_BAR_BAND
    val contentBand = if (land) LANDSCAPE_CONTENT_BAND else PORTRAIT_CONTENT_BAND
    val pm = bmp.toPixelMap()
    fun bandAvg(range: Pair<Float, Float>): Color {
        val y0 = (range.first * pm.height).toInt().coerceIn(0, pm.height)
        val y1 = (range.second * pm.height).toInt().coerceIn(y0, pm.height)
        var r = 0f
        var g = 0f
        var b = 0f
        var n = 0
        for (y in y0 until y1) {
            for (x in 0 until pm.width) {
                val c = pm[x, y]
                r += c.red
                g += c.green
                b += c.blue
                n++
            }
        }
        if (n == 0) return Color.Black
        return Color(r / n * DARKEN, g / n * DARKEN, b / n * DARKEN)
    }
    return CoverRegions(bandAvg(topBarBand), bandAvg(contentBand))
}

/** 组合内推导前景色 (依赖页面底色, 主题切换即时生效); 未采样 (回调未发生) 返回 null。 */
fun deriveBookCoverPalette(
    regions: CoverRegions?,
    pageBg: Color,
    isLandscape: Boolean,
): BookCoverPalette? {
    if (regions == null) return null
    val contentAlpha = if (isLandscape) 1f else CONTENT_ALPHA
    // 书名块背景只判一次亮暗, 书名/字数/最新章节共用判定、各映射自己的昼夜 token
    val contentBgLight =
        ColorUtils.isColorLight(lerp(pageBg, regions.content, contentAlpha).toArgb())
    return BookCoverPalette(
        topBarFg = if (ColorUtils.isColorLight(regions.topBar.toArgb())) {
            TOP_BAR_FG_ON_LIGHT_BG
        } else {
            TOP_BAR_FG_ON_DARK_BG
        },
        nameFg = if (contentBgLight) NAME_FG_ON_LIGHT_BG else NAME_FG_ON_DARK_BG,
        wordCountFg = if (contentBgLight) WORD_COUNT_FG_ON_LIGHT_BG else WORD_COUNT_FG_ON_DARK_BG,
        lastedFg = LASTED_FG,
    )
}
