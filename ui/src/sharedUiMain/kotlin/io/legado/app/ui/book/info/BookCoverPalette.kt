package io.legado.app.ui.book.info

import androidx.compose.runtime.MutableState
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
 * 取色不另发请求: 平台 slot 加载出封面位图后经 [LocalCoverLoaded] 上报**原图**, 宿主就地分区采样
 * (见 [sampleCoverRegions]), 再由分区均值按 [BookInfoBgTransformation] 的视觉模型换算元素背后的有效背景:
 * - 整体压暗 argb(50,0,0,0) SRC_ATOP ≈ RGB × [DARKEN]
 * - 竖屏书名块位于 300dp 背景的渐变尾段 (封面残留 alpha ≈ [CONTENT_ALPHA]), 有效背景 =
 *   lerp(页面底色, 封面分区均值, alpha); 横屏左列无渐变, alpha = 1
 * - 顶栏背后: 竖屏 300dp 条 CENTER_CROP 只露出封面中段 (~22%~78%), 取可见段上部;
 *   横屏整高铺满取顶部
 *
 * 取色未完成或失败时无采样结果, 头部文字仍走主题色 (对齐原版该三档的 XML 取值);
 * 顶栏在该情形下由 [BookInfoScreen] 单独回退为亮色 (对齐原版顶栏在该背景上的固定亮字)。
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
 * "模糊封面背景是否已绘制"——宿主与绘制方共用的同一份事实。
 *
 * 宿主在 [BookInfoScreen] 顶层 remember (键含承载模糊背景的布局分支, 切分支时重新从 false 起算)
 * 并经本 CompositionLocal 下发; 平台 slot 按自身绘制条件在组合期写回 (画出封面=true, 未画出=false);
 * 顶栏/头部与 slot 读的是同一个值, 不存在"字色已按封面取色、背景却还没画出来"的错配帧。
 *
 * 只由详情页宿主提供 (未提供即用错地方), 非详情页不得读。
 */
val LocalBlurCoverPainted = staticCompositionLocalOf<MutableState<Boolean>> {
    error("LocalBlurCoverPainted not provided, wrap content in BookInfoScreen")
}

/**
 * "封面原图已就绪"回调: 平台 slot 加载出封面位图后调用 (送的就是它刚解出的那张图)。
 *
 * 取色与模糊背景共用同一次加载: 宿主在本回调里就地对分区采样, 不另发图片请求。
 * 只由详情页宿主提供 (未提供即用错地方): 缺它不会报错但取色永不生效, 故未注入即报错。
 */
val LocalCoverLoaded = staticCompositionLocalOf<((ImageBitmap) -> Unit)> {
    error("LocalCoverLoaded not provided, wrap content in BookInfoScreen")
}

/**
 * 当前封面取色结果: [BookInfoScreen] 顶层经 [deriveBookCoverPalette] 推导后 provides; 取色未成
 * 或模糊背景未画出时为 null, 消费方回退各自的主题色。
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

/** 分区均值采样 + 压暗换算 (任意尺寸 bitmap 均可)。 */
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

/** 组合内推导前景色 (依赖页面底色, 主题切换即时生效); 未采样 (取色未完成或失败) 返回 null。 */
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
