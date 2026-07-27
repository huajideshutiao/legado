package io.legado.app.ui.compose.platform

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.painter.ColorPainter
import androidx.compose.ui.graphics.painter.Painter
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
import legado.shared.generated.resources.ic_export
import legado.shared.generated.resources.ic_find_replace
import legado.shared.generated.resources.ic_folder
import legado.shared.generated.resources.ic_folder_open
import legado.shared.generated.resources.ic_groups
import legado.shared.generated.resources.ic_help
import legado.shared.generated.resources.ic_history
import legado.shared.generated.resources.ic_image
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
import legado.shared.generated.resources.ic_play_mode_list_end_stop
import legado.shared.generated.resources.ic_play_mode_list_loop
import legado.shared.generated.resources.ic_play_mode_random
import legado.shared.generated.resources.ic_play_mode_single_loop
import legado.shared.generated.resources.ic_praise
import legado.shared.generated.resources.ic_read_aloud
import legado.shared.generated.resources.ic_reduce
import legado.shared.generated.resources.ic_refresh_black_24dp
import legado.shared.generated.resources.ic_review_close
import legado.shared.generated.resources.ic_review_thumb_down
import legado.shared.generated.resources.ic_review_thumb_down_filled
import legado.shared.generated.resources.ic_review_thumb_up
import legado.shared.generated.resources.ic_review_thumb_up_filled
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

// ohos actual: rememberPainter 加载 commonMain/composeResources/drawable/ 下 SVG (与 iOS/jvm 镜像);
// rememberString/rememberStringArray/rememberColor 复用 iOS/jvm 的字面量表 (单一数据源镜像)
// ohosMain 未继承 nonOhosUiMain, material-icons-extended 不可用, 兜底用 ColorPainter (与原 stub 行为一致)

@Composable
actual fun rememberPainter(key: String): Painter {
    // 加载 shared/commonMain/composeResources/drawable/ 下 SVG (与 app 端 R.drawable 视觉对齐, 单一数据源)
    // 与 iOS/jvmMain 镜像: SVG 路径颜色与 app 端 VectorDrawable fillColor/strokeColor 硬编码一致
    val svgRes: DrawableResource? = when (key) {
        // 导航栏图标
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
        // 基础操作图标
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
        // 文件夹/帮助/导入/导出 (BookshelfManageScreen / RemoteBookScreen / ReplaceEditScreen)
        "ic_folder" -> Res.drawable.ic_folder
        "ic_folder_open" -> Res.drawable.ic_folder_open
        "ic_help" -> Res.drawable.ic_help
        "ic_image" -> Res.drawable.ic_image
        "ic_export" -> Res.drawable.ic_export
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
        // TtsControlPanel 下沉带入 (TTS 控制面板)
        "ic_skip_previous" -> Res.drawable.ic_skip_previous
        "ic_skip_next" -> Res.drawable.ic_skip_next
        "ic_pause_24dp" -> Res.drawable.ic_pause_24dp
        "ic_volume_up" -> Res.drawable.ic_volume_up
        "ic_visibility_off" -> Res.drawable.ic_visibility_off
        "ic_time_add_24dp" -> Res.drawable.ic_time_add_24dp
        // TTS 播放模式 (ReadAloud/ReadMenu 播放模式切换)
        "ic_play_mode_list_end_stop" -> Res.drawable.ic_play_mode_list_end_stop
        "ic_play_mode_list_loop" -> Res.drawable.ic_play_mode_list_loop
        "ic_play_mode_random" -> Res.drawable.ic_play_mode_random
        "ic_play_mode_single_loop" -> Res.drawable.ic_play_mode_single_loop
        // ReviewListDialog 下沉带入 (对应 app 端 R.drawable.ic_review_*)
        "ic_review_close" -> Res.drawable.ic_review_close
        "ic_review_thumb_up" -> Res.drawable.ic_review_thumb_up
        "ic_review_thumb_up_filled" -> Res.drawable.ic_review_thumb_up_filled
        "ic_review_thumb_down" -> Res.drawable.ic_review_thumb_down
        "ic_review_thumb_down_filled" -> Res.drawable.ic_review_thumb_down_filled
        else -> null
    }
    if (svgRes != null) {
        return painterResource(svgRes)
    }
    // 兜底: ic_arrow_forward / ic_speed (app 端无对应 drawable xml) 与未识别 key
    // ohosMain 未继承 nonOhosUiMain, material-icons-extended 不可用, 用 ColorPainter 占位
    return ColorPainter(Color.Unspecified)
}

@Composable
actual fun rememberString(key: String, vararg formatArgs: Any): String {
    // 复用 commonMain sharedStringTable (与 iOS/jvm 端一致)
    val raw = sharedStringTable[key] ?: key
    return if (formatArgs.isEmpty()) raw else String.format(raw, *formatArgs)
}

@Composable
actual fun rememberStringArray(key: String): List<String> {
    // 复用 iOS/jvmMain 的 mapOf 字面量 (与 app 端 res/values-zh/strings.xml + res/values/arrays.xml 对齐)
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
            "screen_direction_title" to listOf("跟随系统", "竖向", "横向", "跟随传感器", "反向竖屏"),
            "screen_direction_value" to listOf("0", "1", "2", "3", "4"),
            "screen_time_out" to listOf("默认", "1分钟", "5分钟", "10分钟", "常亮"),
            "screen_time_out_value" to listOf("0", "60", "300", "600", "-1"),
            "double_page_title" to listOf("全局单页", "全局双页", "横屏双页", "平板/横屏双页"),
            "double_page_value" to listOf("0", "1", "2", "3"),
            "progress_bar_behavior_title" to listOf("调整本章页数", "调整章节位置"),
            "progress_bar_behavior_value" to listOf("page", "chapter"),
            // TipConfigScreen 下沉带入 (与 app 端 values-zh/arrays.xml 对齐)
            "read_tip" to listOf(
                "无", "书名", "标题", "时间", "电量", "电量%", "页数",
                "进度(%)", "进度(xx/yyy)", "页数及进度", "时间及电量", "时间及电量%"
            ),
            "tip_color" to listOf("跟随内容", "自定义"),
            "tip_divider_color" to listOf("默认", "跟随内容", "自定义"),
            // OtherConfigScreen / BookshelfManageScreen / ExploreScreen / ThemeConfigScreen 下沉带入
            "book_type" to listOf("文本", "音频", "图片", "文件", "视频", "订阅"),
            "explore_item_style" to listOf("普通", "视频"),
            "icon_names" to listOf("iconMain", "icon1", "icon4", "icon5"),
            "icons" to listOf("ic_launcher", "launcher1", "launcher4", "launcher5"),
            "theme_mode" to listOf("跟随系统", "亮色主题", "暗色主题", "E-Ink(墨水屏)"),
            "theme_mode_v" to listOf("0", "1", "2", "3"),
        )
    }
    return table[key] ?: emptyList() // 未识别返回空 List, 与 expect KDoc 一致
}

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
