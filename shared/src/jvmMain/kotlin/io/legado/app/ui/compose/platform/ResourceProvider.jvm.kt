@file:OptIn(org.jetbrains.compose.resources.InternalResourceApi::class)

package io.legado.app.ui.compose.platform

import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.painter.Painter
import io.legado.app.ui.compose.theme.LocalAppColors
import kotlinx.coroutines.runBlocking
import org.jetbrains.compose.resources.DrawableResource
import org.jetbrains.compose.resources.getString
import org.jetbrains.compose.resources.painterResource

/**
 * 非 @Composable 字符串获取入口 (供 coroutine / 普通函数 / init 块 / throw 异常等场景使用)。
 *
 * 与 [rememberString] 同源 (Compose Resources 映射表), 但取值 API 是 suspend 而调用点多为同步,
 * 故 runBlocking 桥接; 字符串记录读 classpath 资源且 AsyncCache 命中后不再 IO, 阻塞极短。
 *
 * 占位符由 Compose Resources 的 getString(res, *args) 填充, 与 Composable 侧统一。
 * formatArgs 为 `Any?` (可空), 因错误回调常传 `e.message` (String?), null 输出为 "null"。
 */
fun jvmGetString(key: String, vararg formatArgs: Any?): String {
    val resource = findStringResource(key) ?: return key
    return runBlocking {
        if (formatArgs.isEmpty()) getString(resource)
        else getString(resource, *formatArgs.map { it.toString() }.toTypedArray())
    }
}

@Composable
actual fun rememberColor(key: String): Color {
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
        // = @color/bg_divider_line -> @color/background_menu -> @color/arco_fill_2(light #FFF3F3F3) / @color/md_grey_800(dark #424242)
        // 解引用后实际值: light #FFF3F3F3 / dark #FF424242 (BookInfoScreen 分隔线)
        "bg_divider_line" -> if (isDark) Color(0xFF424242) else Color(0xFFF3F3F3)
        // = @color/divider: #66666666 (values-night 无定义, light/dark 共用)
        "divider" -> Color(0x66666666)
        // = @color/review_voted: #E53935 (values-night 无定义, light/dark 共用; ReviewListDialog 已点赞/点踩高亮)
        "review_voted" -> Color(0xFFE53935)
        // = @color/background_card -> @color/arco_bg_card: light #FFFFFFFF / dark #FF2A2A2A
        "background_card" -> if (isDark) Color(0xFF2A2A2A) else Color(0xFFFFFFFF)
        // = @color/secondaryText -> @color/arco_text_2: light #FF595959 / dark #FFCDCDCD
        "secondaryText" -> if (isDark) Color(0xFFCDCDCD) else Color(0xFF595959)
        // = @color/background -> @color/arco_bg_page(light #FFF8F8F8) / @color/md_grey_900(dark #FF212121)
        "background" -> if (isDark) Color(0xFF212121) else Color(0xFFF8F8F8)
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

@Composable
actual fun rememberLauncherIconPainters(iconValues: List<String>): List<Painter?> {
    return emptyList()
}


