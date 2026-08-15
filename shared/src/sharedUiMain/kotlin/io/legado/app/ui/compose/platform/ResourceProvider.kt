package io.legado.app.ui.compose.platform

import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.painter.Painter
import legado.shared.generated.resources.Res
import legado.shared.generated.resources.ic_material_help
import org.jetbrains.compose.resources.painterResource
import org.jetbrains.compose.resources.stringArrayResource
import org.jetbrains.compose.resources.stringResource

/**
 * 跨平台资源访问器: 字符串/字符串数组/Painter 四端统一走 Compose Resources 按 key 索引的
 * 映射表 (见 ComposeResourceLookup 的 [findStringResource]/[findDrawableResource]);
 * 仅 [rememberColor] 与 [rememberLauncherIconPainters] 是 expect/actual —— 前者无
 * Compose Resources 对应 API, 后者 Android 需处理 AdaptiveIconDrawable。
 * 调用方用 [rememberPainter]/[rememberString]/[rememberColor] 替代
 * painterResource/stringResource/colorResource, 由 B 类 Composable 下沉时统一调用。
 *
 * 支持 key 清单 (来源: app 端 B 类 Composable 实际使用清单) 见 ComposeResourceLookup;
 * 未识别 key 兜底: Painter → 占位图标 `ic_material_help`; String → key 本身;
 * Color → Color.Unspecified (调用方应保证 key 命中, 否则不绘制)。
 *
 * [rememberColor] 的色值定义统一在 ColorPalette.kt (单一数据源): Android 优先读
 * 系统资源 (values-night/动态主题), 资源缺失时回退色板; 其余端直接查色板。
 */
@Composable
fun rememberPainter(key: String): Painter {
    val resource = findDrawableResource(key) ?: Res.drawable.ic_material_help
    return painterResource(resource)
}

/**
 * 取字符串资源; 四端统一走 Compose Resources 按 key 索引的映射表 ([findStringResource])。
 * - formatArgs 为空时不做格式化, 保留原始 %1$d 等占位符
 * - formatArgs 非空时由 Compose Resources 填充, 但它只认索引式 %1$s/%1$d;
 *   无索引的 %s/%d 会原样留下, 故带参调用的文案必须写成索引式
 * - key 缺失返回 key 本身
 */
@Composable
fun rememberString(key: String, vararg formatArgs: Any): String {
    val resource = findStringResource(key) ?: return key
    return if (formatArgs.isEmpty()) stringResource(resource)
    else stringResource(resource, *formatArgs)
}

/**
 * 跨平台 string-array 资源访问; 四端统一走 [findStringArrayResource]。
 * key 缺失返回空 List (调用方按需处理空态)。
 *
 * ## 支持的 key (来源: app 端 B 类 Composable 实际使用清单)
 *
 * ### StringArray key (string-array)
 * - `language` / `language_value`                语言选项 (OtherConfigScreen)
 * - `default_home_page` / `default_home_page_value`  默认主页 (OtherConfigScreen)
 * - `default_app_variant` / `default_app_variant_value`  默认变体 (OtherConfigScreen)
 * - `screen_direction_title` / `screen_direction_value`  屏幕方向 (MoreConfigScreen)
 * - `screen_time_out` / `screen_time_out_value`          屏幕超时 (MoreConfigScreen)
 * - `double_page_title` / `double_page_value`            双页模式 (MoreConfigScreen)
 * - `progress_bar_behavior_title` / `progress_bar_behavior_value`  进度条行为 (MoreConfigScreen)
 * - `read_tip`              tip 信息位名称 (TipConfigScreen: 无/书名/标题/时间/电量/...)
 * - `tip_color`             tip 颜色名称 (TipConfigScreen: 跟随内容/自定义)
 * - `tip_divider_color`     tip 分隔线颜色名称 (TipConfigScreen: 默认/跟随内容/自定义)
 */
@Composable
fun rememberStringArray(key: String): List<String> {
    val resource = findStringArrayResource(key) ?: return emptyList()
    return stringArrayResource(resource)
}

/**
 * 加载 Launcher 图标预览 Painter 列表 (ThemeConfigScreen 换图标用)。
 *
 * 替代 app 端 `LocalContext.current + getIdentifier("mipmap") + getCompatDrawable + toBitmap + BitmapPainter` 链。
 * 各平台 actual 行为:
 * - Android: 按 mipmap 资源名查 id, AdaptiveIconDrawable 转 Bitmap 后包 BitmapPainter
 *   (painterResource 不支持 AdaptiveIconDrawable); 找不到返回 null
 * - 桌面 JVM / iOS: 无 launcher 图标概念, 返回空 List (调用方按需处理空态)
 *
 * @param iconValues mipmap 资源名列表 (如 ["ic_launcher", "ic_launcher_book", ...])
 */
@Composable
expect fun rememberLauncherIconPainters(iconValues: List<String>): List<Painter?>

@Composable
expect fun rememberColor(key: String): Color
