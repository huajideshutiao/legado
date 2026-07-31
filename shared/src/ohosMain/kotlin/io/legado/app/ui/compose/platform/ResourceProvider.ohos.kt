package io.legado.app.ui.compose.platform

import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.painter.Painter
import io.legado.app.ui.compose.theme.LocalAppColors
import org.jetbrains.compose.resources.DrawableResource
import org.jetbrains.compose.resources.painterResource

// ohos actual: rememberPainter 加载 shared/sharedIconResources/drawable/ 下共享 vector XML (与 iOS/jvm 镜像);
// 所有图标均走共享 Compose Resources，未知 key 统一使用 Material Help SVG 兜底

@Composable
actual fun rememberLauncherIconPainters(iconValues: List<String>): List<Painter?> {
    // stub: 鸿蒙端无 AdaptiveIconDrawable 概念
    return emptyList()
}

@Composable
actual fun rememberColor(key: String): Color {
    // 复用 iOS/jvmMain 的 when 分支: Color/LocalAppColors 跨平台一致
    // 对齐 Android values/values-night 资源限定符: dark 主题走 values-night, 否则走 values
    val isDark = LocalAppColors.current.isDark
    return when (key) {
        // = @color/arco_text_1: light #FF212121 / dark #FFF8F8F8
        "primaryText" -> if (isDark) Color(0xFFF8F8F8) else Color(0xFF212121)
        // = @color/arco_text_3: light/dark 均 #FF909090
        "tv_text_summary" -> Color(0xFF909090)
        // = @color/btn_bg: light #100e0e0e / dark #14e0e0e0 (TocScreen 卷名背景)
        "btn_bg" -> if (isDark) Color(0x14E0E0E0) else Color(0x100E0E0E)
        // = @color/arco_fill_3: light #FFE6E6E6 / dark #FF2A2A2A (BookInfoScreen 等次级填充)
        "arco_fill_3" -> if (isDark) Color(0xFF2A2A2A) else Color(0xFFE6E6E6)
        // = @color/bg_divider_line 解引用后: light #FFF3F3F3 / dark #FF424242 (BookInfoScreen 分隔线)
        "bg_divider_line" -> if (isDark) Color(0xFF424242) else Color(0xFFF3F3F3)
        // = @color/divider: #66666666 (values-night 无定义, light/dark 共用)
        "divider" -> Color(0x66666666)
        // Material Design 颜色 (values/colors_material_design.xml, 无 night 变体, light/dark 共用)
        "md_blue_100" -> Color(0xFFBBDEFB)
        "md_blue_A200" -> Color(0xFF448AFF)
        "md_red_100" -> Color(0xFFFFCDD2)
        "md_red_A200" -> Color(0xFFFF5252)
        "md_dark_primary_text" -> Color(0xFFFFFFFF)
        "md_light_secondary" -> Color(0x8A000000)
        else -> Color.Unspecified // 未识别返回 Unspecified, 与 expect KDoc 一致
    }
}
