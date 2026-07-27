@file:OptIn(org.jetbrains.compose.resources.InternalResourceApi::class)

package io.legado.app.ui.compose.platform

// iOS 端 SVG 化下沉后, 仅保留以下 Material Icons 作为兜底/无对应 drawable 的图标 (与 jvmMain 镜像):
// - Icons.Filled.Help: 未识别 key 的占位图标
// - Icons.AutoMirrored.Filled.ArrowForward: ic_arrow_forward (app 端无对应 drawable xml)
// - Icons.Filled.Speed: ic_speed (app 端无对应 drawable xml)
// 其余 key 全部下沉到 shared/commonMain/composeResources/drawable/ 的 SVG (与 app 端 R.drawable 视觉对齐)
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.Help
import androidx.compose.material.icons.filled.Speed
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import io.legado.app.ui.compose.theme.LocalAppColors
import legado.shared.generated.resources.Res
import legado.shared.generated.resources.ic_add
import legado.shared.generated.resources.ic_arrow_back
import legado.shared.generated.resources.ic_arrow_down
import legado.shared.generated.resources.ic_arrow_drop_down
import legado.shared.generated.resources.ic_arrow_drop_up
import legado.shared.generated.resources.ic_arrow_right
import legado.shared.generated.resources.ic_auto_page
import legado.shared.generated.resources.ic_auto_page_stop
import legado.shared.generated.resources.ic_author
import legado.shared.generated.resources.ic_baseline_close
import legado.shared.generated.resources.ic_baseline_sort_24
import legado.shared.generated.resources.ic_book_has
import legado.shared.generated.resources.ic_book_last
import legado.shared.generated.resources.ic_bookmark
import legado.shared.generated.resources.ic_bottom_books_e
import legado.shared.generated.resources.ic_bottom_books_s
import legado.shared.generated.resources.ic_bottom_explore_e
import legado.shared.generated.resources.ic_bottom_explore_s
import legado.shared.generated.resources.ic_bottom_home_e
import legado.shared.generated.resources.ic_bottom_home_s
import legado.shared.generated.resources.ic_bottom_person_e
import legado.shared.generated.resources.ic_bottom_person_s
import legado.shared.generated.resources.ic_brightness
import legado.shared.generated.resources.ic_bug_report
import legado.shared.generated.resources.ic_cfg_about
import legado.shared.generated.resources.ic_cfg_backup
import legado.shared.generated.resources.ic_cfg_other
import legado.shared.generated.resources.ic_cfg_replace
import legado.shared.generated.resources.ic_cfg_source
import legado.shared.generated.resources.ic_cfg_theme
import legado.shared.generated.resources.ic_cfg_web
import legado.shared.generated.resources.ic_check
import legado.shared.generated.resources.ic_clear_all
import legado.shared.generated.resources.ic_download_line
import legado.shared.generated.resources.ic_edit
import legado.shared.generated.resources.ic_exchange
import legado.shared.generated.resources.ic_expand_less
import legado.shared.generated.resources.ic_expand_more
import legado.shared.generated.resources.ic_find_replace
import legado.shared.generated.resources.ic_folder
import legado.shared.generated.resources.ic_folder_open
import legado.shared.generated.resources.ic_groups
import legado.shared.generated.resources.ic_help
import legado.shared.generated.resources.ic_history
import legado.shared.generated.resources.ic_import
import legado.shared.generated.resources.ic_interface_setting
import legado.shared.generated.resources.ic_layout_list
import legado.shared.generated.resources.ic_layout_video
import legado.shared.generated.resources.ic_lock_outline
import legado.shared.generated.resources.ic_menu
import legado.shared.generated.resources.ic_more_vert
import legado.shared.generated.resources.ic_outline_cloud_24
import legado.shared.generated.resources.ic_pause_24dp
import legado.shared.generated.resources.ic_play_24dp
import legado.shared.generated.resources.ic_praise
import legado.shared.generated.resources.ic_read_aloud
import legado.shared.generated.resources.ic_reduce
import legado.shared.generated.resources.ic_refresh_black_24dp
import legado.shared.generated.resources.ic_save
import legado.shared.generated.resources.ic_search
import legado.shared.generated.resources.ic_settings
import legado.shared.generated.resources.ic_share
import legado.shared.generated.resources.ic_skip_next
import legado.shared.generated.resources.ic_skip_previous
import legado.shared.generated.resources.ic_sort
import legado.shared.generated.resources.ic_star
import legado.shared.generated.resources.ic_star_border
import legado.shared.generated.resources.ic_stop_black_24dp
import legado.shared.generated.resources.ic_time_add_24dp
import legado.shared.generated.resources.ic_toc
import legado.shared.generated.resources.ic_translate
import legado.shared.generated.resources.ic_visibility_off
import legado.shared.generated.resources.ic_volume_up
import legado.shared.generated.resources.ic_web_outline
import legado.shared.generated.resources.outline_filter_alt_24
import org.jetbrains.compose.resources.DrawableResource
import org.jetbrains.compose.resources.painterResource
import platform.Foundation.NSBundle
import platform.Foundation.NSString

/**
 * iOS actual: 复用 jvmMain 的字面量映射策略, 保持跨平台一致。
 *
 * ## 实现说明
 *
 * - rememberString: 用 NSBundle + Localizable.strings 真实化实现 (iOS 独有)。
 *   资源文件: shared/src/iosMain/resources/Localizable.strings
 *   (KMP 默认把 src/iosMain/resources/* 打入 framework bundle 根目录)。
 *   `NSBundle.mainBundle.localizedStringForKey(key, "", null)` 取字符串;
 *   未识别 key (返回空串) 回退到 key 本身, 与 jvmMain/Android 行为一致。
 *   formatArgs 非空时用 `NSString.stringWithFormat` 格式化, 并把 Java Formatter 的
 *   `%s` / `%N$s` 替换为 iOS 的 `%@` / `%N$@` (NSString.stringWithFormat 不支持 %s
 *   解析 Kotlin String, 会按 C 字符串处理导致乱码); `%d` / `%N$d` 两者一致无需替换。
 * - rememberPainter: 加载 shared/commonMain/composeResources/drawable/ 下 SVG
 *   (与 app 端 R.drawable 视觉对齐, 单一数据源, iOS 端不再用 Material Icons 替代)。
 *   与 jvmMain 镜像: 仅 ic_arrow_forward / ic_speed (app 端无对应 drawable xml) 与
 *   未识别 key 走 Material Icons 兜底 (material-icons-extended 已在 sharedUiMain 引入,
 *   iosMain 继承); Compose Multiplatform 提供 painterResource, 无需 UIImage 平台转换。
 *   未识别 key 返回 Icons.Filled.Help 占位, 与 jvmMain 行为一致。
 * - rememberColor: 复用 jvmMain 的 when 分支 (Color/LocalAppColors 跨平台一致),
 *   按 LocalAppColors.current.isDark 分 light/dark, 与 Android values/values-night
 *   资源限定符行为对齐。
 * - rememberStringArray: 复用 jvmMain 的 mapOf 字面量 (与 app 端
 *   res/values-zh/strings.xml + res/values/arrays.xml 默认中文值对齐)。
 * - rememberLauncherIconPainters: 返回 emptyList() (iOS 无 launcher 图标概念)。
 *
 * 字符串/数组字面量与 app 端 strings.xml/arrays.xml 默认中文值保持一致 (zh-CN),
 * iOS 端不做 i18n (后续若需要可改用 .lproj 目录 + 系统语言切换)。
 *
 * ## 后续 TODO
 *
 * - TODO(KP4): Localizable.strings 当前直接放在 bundle 根目录, iOS 不会按系统语言
 *   切换 (始终走根目录文件 = 简体中文)。如需 i18n, 移动到
 *   `zh-Hans.lproj/Localizable.strings` 并补 `Base.lproj/Localizable.strings`。
 * - TODO(KP4): NSBundle.mainBundle 是 App bundle, KMP framework 资源实际在 framework
 *   bundle。当 Legado iOS App 与 KMP framework 是同一 bundle 时 (Compose iOS App
 *   通常如此) 可工作; 若 framework 单独打包需改用 NSBundle.bundleForClass。
 */
@Composable
actual fun rememberPainter(key: String): Painter {
    // 优先加载下沉到 shared/commonMain/composeResources/drawable/ 的 SVG 图标
    // (与 app 端 R.drawable 视觉对齐, 单一数据源, iOS 端不再用 Material Icons 替代)
    // 与 jvmMain 镜像: SVG 路径颜色与 app 端 VectorDrawable fillColor/strokeColor 硬编码一致
    // 原 fillColor 为白/@color 引用 + tint 的 drawable, SVG 统一用 #000000, 由 Compose 通过 tint 着色
    val svgRes: DrawableResource? = when (key) {
        // 导航栏图标 (原有 SVG)
        "ic_arrow_back" -> Res.drawable.ic_arrow_back
        "ic_menu" -> Res.drawable.ic_menu
        "ic_bottom_home_s" -> Res.drawable.ic_bottom_home_s
        "ic_bottom_home_e" -> Res.drawable.ic_bottom_home_e
        "ic_bottom_books_s" -> Res.drawable.ic_bottom_books_s
        "ic_bottom_books_e" -> Res.drawable.ic_bottom_books_e
        "ic_bottom_explore_s" -> Res.drawable.ic_bottom_explore_s
        "ic_bottom_explore_e" -> Res.drawable.ic_bottom_explore_e
        "ic_bottom_person_s" -> Res.drawable.ic_bottom_person_s
        "ic_bottom_person_e" -> Res.drawable.ic_bottom_person_e
        // 基础操作图标 (本次新增 SVG)
        "ic_search" -> Res.drawable.ic_search
        "ic_more_vert" -> Res.drawable.ic_more_vert
        "ic_baseline_close" -> Res.drawable.ic_baseline_close
        "ic_clear_all" -> Res.drawable.ic_clear_all
        "ic_check" -> Res.drawable.ic_check
        "ic_add" -> Res.drawable.ic_add
        "ic_reduce" -> Res.drawable.ic_reduce
        "ic_sort" -> Res.drawable.ic_sort
        "ic_baseline_sort_24" -> Res.drawable.ic_baseline_sort_24
        "ic_groups" -> Res.drawable.ic_groups
        "ic_edit" -> Res.drawable.ic_edit
        // TocScreen 下沉带入 (TocScreen / TocDrawerContent 用到)
        "ic_lock_outline" -> Res.drawable.ic_lock_outline
        "ic_expand_more" -> Res.drawable.ic_expand_more
        "ic_expand_less" -> Res.drawable.ic_expand_less
        "ic_outline_cloud_24" -> Res.drawable.ic_outline_cloud_24
        "ic_arrow_drop_up" -> Res.drawable.ic_arrow_drop_up
        "ic_arrow_drop_down" -> Res.drawable.ic_arrow_drop_down
        "ic_arrow_down" -> Res.drawable.ic_arrow_down
        "ic_arrow_right" -> Res.drawable.ic_arrow_right
        // BookshelfComposablesShared 下沉带入 (书架列表行图标)
        "ic_author" -> Res.drawable.ic_author
        "ic_history" -> Res.drawable.ic_history
        "ic_book_last" -> Res.drawable.ic_book_last
        "ic_book_has" -> Res.drawable.ic_book_has
        "ic_bookmark" -> Res.drawable.ic_bookmark
        // 文件夹/帮助/导入 (BookshelfManageScreen / RemoteBookScreen / ReplaceEditScreen)
        "ic_folder" -> Res.drawable.ic_folder
        "ic_folder_open" -> Res.drawable.ic_folder_open
        "ic_help" -> Res.drawable.ic_help
        "ic_import" -> Res.drawable.ic_import
        // 书架/发现布局切换 (BookshelfScreen / ExploreScreen)
        "ic_layout_list" -> Res.drawable.ic_layout_list
        "ic_layout_video" -> Res.drawable.ic_layout_video
        // 播放/停止/刷新/筛选 (SearchScreen / ChangeSourceScreen)
        "ic_play_24dp" -> Res.drawable.ic_play_24dp
        "ic_stop_black_24dp" -> Res.drawable.ic_stop_black_24dp
        "ic_refresh_black_24dp" -> Res.drawable.ic_refresh_black_24dp
        "outline_filter_alt_24" -> Res.drawable.outline_filter_alt_24
        // Bug 反馈 (AboutScreen)
        "ic_bug_report" -> Res.drawable.ic_bug_report
        // 配置入口图标 (MyConfigScreen)
        "ic_cfg_about" -> Res.drawable.ic_cfg_about
        "ic_cfg_backup" -> Res.drawable.ic_cfg_backup
        "ic_cfg_other" -> Res.drawable.ic_cfg_other
        "ic_cfg_replace" -> Res.drawable.ic_cfg_replace
        "ic_cfg_source" -> Res.drawable.ic_cfg_source
        "ic_cfg_theme" -> Res.drawable.ic_cfg_theme
        "ic_cfg_web" -> Res.drawable.ic_cfg_web
        // 书信息 (BookInfoScreen)
        "ic_praise" -> Res.drawable.ic_praise
        "ic_save" -> Res.drawable.ic_save
        "ic_share" -> Res.drawable.ic_share
        "ic_star" -> Res.drawable.ic_star
        "ic_star_border" -> Res.drawable.ic_star_border
        "ic_translate" -> Res.drawable.ic_translate
        "ic_web_outline" -> Res.drawable.ic_web_outline
        // ReadMenu 下沉带入 (阅读页菜单图标, 对应 app 端 R.drawable.ic_*)
        "ic_exchange" -> Res.drawable.ic_exchange
        "ic_download_line" -> Res.drawable.ic_download_line
        "ic_auto_page" -> Res.drawable.ic_auto_page
        "ic_auto_page_stop" -> Res.drawable.ic_auto_page_stop
        "ic_find_replace" -> Res.drawable.ic_find_replace
        "ic_brightness" -> Res.drawable.ic_brightness
        "ic_toc" -> Res.drawable.ic_toc
        "ic_read_aloud" -> Res.drawable.ic_read_aloud
        "ic_interface_setting" -> Res.drawable.ic_interface_setting
        "ic_settings" -> Res.drawable.ic_settings
        // TtsControlPanel 下沉带入 (iOS 端 TTS 控制面板)
        "ic_skip_previous" -> Res.drawable.ic_skip_previous
        "ic_skip_next" -> Res.drawable.ic_skip_next
        "ic_pause_24dp" -> Res.drawable.ic_pause_24dp
        "ic_volume_up" -> Res.drawable.ic_volume_up
        "ic_visibility_off" -> Res.drawable.ic_visibility_off
        "ic_time_add_24dp" -> Res.drawable.ic_time_add_24dp
        else -> null
    }
    if (svgRes != null) {
        return painterResource(svgRes)
    }
    // 兜底: 仅有以下 key 无 app 端 drawable xml 对应, 仍走 Material Icons (与 jvmMain 一致)
    val imageVector = when (key) {
        "ic_arrow_forward" -> Icons.AutoMirrored.Filled.ArrowForward // 段落前进 (app 端无 drawable, 区分于章节 SkipNext, 方向敏感图标已迁移到 AutoMirrored 包)
        "ic_speed" -> Icons.Filled.Speed // 语速调节 (TTS 播放速度滑杆, app 端无 drawable)
        else -> Icons.Filled.Help // 占位图标, 暴露未识别 key 便于调试
    }
    return rememberVectorPainter(imageVector)
}

@Composable
actual fun rememberString(key: String, vararg formatArgs: Any): String {
    // NSBundle.mainBundle.localizedStringForKey: 第二参数 value="" 表示未找到时返回空串,
    // 第三参数 table=null 表示用默认表名 Localizable.strings
    val raw = NSBundle.mainBundle.localizedStringForKey(key, "", null)
    // 未识别 key 回退到 key 本身 (与 jvmMain table[key] ?: key 行为对齐)
    val resolved = if (raw.isEmpty()) key else raw
    // formatArgs 为空时直接返回原始字符串 (保留 %1$d 等占位符, 与 Android stringResource(id) 一致);
    // 非空时用 NSString.stringWithFormat 填充占位符 (与 Android resources.getString(id, *args) 对齐)
    if (formatArgs.isEmpty()) return resolved
    // NSString.stringWithFormat 与 Java Formatter 占位符差异:
    // - %d / %N$d (整数): 两者格式一致, 无需转换
    // - %s / %N$s (字符串): iOS 用 %@ / %N$@, 需替换 (否则 %s 在 iOS 会按 C 字符串解析导致乱码)
    val iosFormat = resolved.replace(Regex("%(\\d+\\$)?s"), "%$1@")
    return NSString.stringWithFormat(iosFormat, *formatArgs)
}

@Composable
actual fun rememberStringArray(key: String): List<String> {
    // 复用 jvmMain 的 mapOf 字面量 (与 app 端 res/values-zh/strings.xml + res/values/arrays.xml 对齐)
    val table = remember {
        mapOf(
            // arrays.xml 中 language 数组无 @string 引用, 直接取字面量
            "language" to listOf("Auto", "Simplified_Chinese", "Traditional_Chinese", "English"),
            "language_value" to listOf("auto", "zh", "tw", "en"),
            // default_home_page 数组 item 引用 @string/home|bookshelf|discovery|my, 取 zh 翻译
            "default_home_page" to listOf("主页", "书架", "发现", "我的"),
            "default_home_page_value" to listOf("home", "bookshelf", "explore", "my"),
            // default_app_variant 数组 item 引用 @string/default_version|official_version|beta_release_version|beta_releaseA_version, 取 zh 翻译
            "default_app_variant" to listOf("当前", "正式版", "测试版", "共存版"),
            "default_app_variant_value" to listOf(
                "default_version",
                "official_version",
                "beta_release_version",
                "beta_releaseA_version",
            ),
            // MoreConfigScreen 用到的数组 (与 app 端 res/values/arrays.xml + res/values-zh/arrays.xml 对齐)
            // screen_direction_title 数组 item 引用 @string/screen_unspecified|screen_portrait|screen_landscape|screen_sensor|screen_portrait_reversed, 取 zh 翻译
            "screen_direction_title" to listOf("跟随系统", "竖向", "横向", "跟随传感器", "反向竖屏"),
            "screen_direction_value" to listOf("0", "1", "2", "3", "4"),
            // screen_time_out 数组直接取字面量 (values-zh/arrays.xml 有翻译)
            "screen_time_out" to listOf("默认", "1分钟", "5分钟", "10分钟", "常亮"),
            "screen_time_out_value" to listOf("0", "60", "300", "600", "-1"),
            // double_page_title 数组直接取字面量 (values-zh/arrays.xml 有翻译)
            "double_page_title" to listOf("全局单页", "全局双页", "横屏双页", "平板/横屏双页"),
            "double_page_value" to listOf("0", "1", "2", "3"),
            // progress_bar_behavior_title 数组 item 引用 @string/adjust_chapter_page|adjust_chapter_index, 取 zh 翻译
            "progress_bar_behavior_title" to listOf("调整本章页数", "调整章节位置"),
            "progress_bar_behavior_value" to listOf("page", "chapter"),
            // TipConfigScreen 下沉带入 (与 app 端 values-zh/arrays.xml 对齐)
            // read_tip 数组直接取字面量 (values-zh/arrays.xml 有翻译)
            "read_tip" to listOf(
                "无", "书名", "标题", "时间", "电量", "电量%", "页数",
                "进度(%)", "进度(xx/yyy)", "页数及进度", "时间及电量", "时间及电量%"
            ),
            // tip_color 数组直接取字面量 (values-zh/arrays.xml 有翻译)
            "tip_color" to listOf("跟随内容", "自定义"),
            // tip_divider_color 数组直接取字面量 (values-zh/arrays.xml 有翻译)
            "tip_divider_color" to listOf("默认", "跟随内容", "自定义"),
            // OtherConfigScreen / BookshelfManageScreen / ExploreScreen / ThemeConfigScreen 下沉带入
            // book_type 数组直接取字面量 (values-zh/arrays.xml 有翻译)
            "book_type" to listOf("文本", "音频", "图片", "文件", "视频", "订阅"),
            // explore_item_style 数组 item 引用 @string/explore_style_normal|@string/explore_style_video, 取 zh 翻译
            "explore_item_style" to listOf("普通", "视频"),
            // icon_names 数组直接取字面量 (ThemeConfigScreen 换图标名称, values-zh/arrays.xml)
            "icon_names" to listOf("iconMain", "icon1", "icon4", "icon5"),
            // icons 数组直接取字面量 (ThemeConfigScreen mipmap 资源名, values/arrays.xml)
            "icons" to listOf("ic_launcher", "launcher1", "launcher4", "launcher5"),
            // theme_mode 数组直接取字面量 (values-zh/arrays.xml 有翻译)
            "theme_mode" to listOf("跟随系统", "亮色主题", "暗色主题", "E-Ink(墨水屏)"),
            // theme_mode_v 数组直接取字面量 (values/arrays.xml, 与 theme_mode 对应的 value)
            "theme_mode_v" to listOf("0", "1", "2", "3"),
        )
    }
    return table[key] ?: emptyList() // 未识别返回空 List, 与 expect KDoc 一致
}

/**
 * iOS actual: 返回空 List (iOS 无 launcher 图标概念)。
 *
 * iOS 端不展示 AdaptiveIconDrawable 预览, iconListPreference 的图标 widget 会因
 * icons.getOrNull(index) == null 而不渲染图标 (与未配置图标行为一致)。
 */
@Composable
actual fun rememberLauncherIconPainters(iconValues: List<String>): List<Painter?> {
    return emptyList()
}

@Composable
actual fun rememberColor(key: String): Color {
    // 复用 jvmMain 的 when 分支: Color/LocalAppColors 跨平台一致
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
