package io.legado.app.ui.compose.platform

import androidx.compose.ui.graphics.Color

/**
 * 跨平台色板单一数据源。
 *
 * 原实现: Android 走 app/res/values(+values-night)/colors.xml 的 getIdentifier + colorResource,
 * jvm/ios/ohos 各维护一份硬编码 when 表 (三处重复, 且 ios/ohos 还漏了
 * review_voted/background_card/secondaryText/background 等 key)。现统一收拢到本文件:
 * - jvm/ios/ohos 的 [rememberColor] actual 直接查本表, `isDark` 决定 light/dark 分支;
 * - Android 的 actual 仍优先走系统资源 (保留 values-night 自动切换与动态主题能力),
 *   资源缺失 (getIdentifier 返回 0, 如被 lint 清理删除的颜色) 时回退本表,
 *   避免 colorResource(0) 抛 Resources$NotFoundException。
 *
 * 取值来源: app/res/values/colors.xml + values-night/colors.xml 解引用后的最终值
 * (引用链如 bg_divider_line → background_menu → arco_fill_2 已手工解引用)。
 */
internal fun resolvePaletteColor(key: String, isDark: Boolean): Color = when (key) {
    // = @color/arco_text_1: light #FF212121 / dark #FFF8F8F8
    "primaryText" -> if (isDark) Color(0xFFF8F8F8) else Color(0xFF212121)
    // = @color/arco_text_3: light/dark 均 #FF909090
    "tv_text_summary" -> Color(0xFF909090)
    // = @color/btn_bg: light #100e0e0e / dark #14e0e0e0 (TocScreen 卷名背景)
    "btn_bg" -> if (isDark) Color(0x14E0E0E0) else Color(0x100E0E0E)
    // = @color/arco_fill_3: light #FFE6E6E6 / dark #FF2A2A2A (BookInfoScreen 等次级填充)
    "arco_fill_3" -> if (isDark) Color(0xFF2A2A2A) else Color(0xFFE6E6E6)
    // = @color/bg_divider_line → @color/background_menu → @color/arco_fill_2
    // 解引用后实际值: light #FFF3F3F3 / dark #FF424242 (BookInfoScreen 分隔线)
    "bg_divider_line" -> if (isDark) Color(0xFF424242) else Color(0xFFF3F3F3)
    // = @color/divider: #66666666 (values-night 无定义, light/dark 共用)
    "divider" -> Color(0x66666666)
    // = @color/review_voted: #E53935 (values-night 无定义, light/dark 共用; ReviewListDialog 已点赞/点踩高亮)
    "review_voted" -> Color(0xFFE53935)
    // = @color/background_card → @color/arco_bg_card: light #FFFFFFFF / dark #FF2A2A2A
    "background_card" -> if (isDark) Color(0xFF2A2A2A) else Color(0xFFFFFFFF)
    // = @color/secondaryText → @color/arco_text_2: light #FF595959 / dark #FFCDCDCD
    "secondaryText" -> if (isDark) Color(0xFFCDCDCD) else Color(0xFF595959)
    // = @color/background → @color/arco_bg_page (light #FFF8F8F8) / @color/md_grey_900 (dark #FF212121)
    "background" -> if (isDark) Color(0xFF212121) else Color(0xFFF8F8F8)
    // Material Design 颜色 (values/colors_material_design.xml, 无 night 变体, light/dark 共用)
    "md_blue_100" -> Color(0xFFBBDEFB)
    "md_blue_A200" -> Color(0xFF448AFF)
    "md_red_100" -> Color(0xFFFFCDD2)
    "md_red_A200" -> Color(0xFFFF5252)
    "md_dark_primary_text" -> Color(0xFFFFFFFF)
    "md_light_secondary" -> Color(0x8A000000)
    // 未识别返回 Unspecified, 与 expect KDoc 一致 (调用方应保证 key 命中, 否则不绘制)
    else -> Color.Unspecified
}
