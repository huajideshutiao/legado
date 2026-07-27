@file:OptIn(org.jetbrains.compose.resources.InternalResourceApi::class)

package io.legado.app.ui.compose.platform

// 桌面端 SVG 化下沉后, 仅保留以下 Material Icons 作为兜底/无对应 drawable 的图标:
// - Icons.Filled.Help: 未识别 key 的占位图标
// - Icons.AutoMirrored.Filled.ArrowForward: ic_arrow_forward (app 端无对应 drawable xml, 桌面端独有)
// - Icons.Filled.Speed: ic_speed (app 端无对应 drawable xml, 桌面端独有)
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

/**
 * 桌面 JVM actual: 优先加载 shared/commonMain/composeResources/drawable/ 下 SVG (与 app 端 R.drawable 视觉对齐)
 * + 字面量 Map<String, String> / Map<String, Color>。
 *
 * 不依赖 classpath 资源文件, 桌面端零额外配置 (无需 shared/src/jvmMain/resources/)。
 * SVG 化下沉后, 仅 ic_arrow_forward / ic_speed (app 端无对应 drawable xml) 与未识别 key 走 Material Icons
 * (material-icons-extended, shared/build.gradle:167 显式声明) 兜底; 未识别的 Painter key 返回 Icons.Filled.Help 占位,
 * 未识别的 String key 返回 key 本身。
 *
 * 字符串字面量与 app 端 strings.xml 默认中文值保持一致 (zh-CN),
 * 桌面端不做 i18n (后续若需要可改为 Properties 资源 + Locale 切换)。
 *
 * Color key 字面量与 app 端 res/values/colors.xml + values-night/colors.xml 对齐:
 * - primaryText (= arco_text_1): light #FF212121 / dark #FFF8F8F8
 * - tv_text_summary (= arco_text_3): light/dark 均 #FF909090
 * 桌面端按 [LocalAppColors.current.isDark] 选择 light/dark 分支, 与 Android 端
 * values/values-night 资源限定符行为对齐 (桌面 isDark 由 DesktopThemeStoreProvider
 * 切换, 不依赖系统夜间模式)。
 */
@Composable
actual fun rememberPainter(key: String): Painter {
    // 优先加载下沉到 shared/commonMain/composeResources/drawable/ 的 SVG 图标
    // (与 app 端 R.drawable 视觉对齐, 单一数据源, 桌面端不再用 Material Icons 替代)
    // SVG 路径颜色与 app 端 VectorDrawable fillColor/strokeColor 硬编码一致 (选中蓝 #2f45a6 / 描边等)
    // 原 fillColor 为白/@color 引用 + tint 的 drawable, SVG 统一用 #000000, 由 Compose 通过 tint 着色 (与 ic_arrow_back 风格一致)
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
        // TtsControlPanel 下沉带入 (桌面端 TTS 控制面板)
        "ic_skip_previous" -> Res.drawable.ic_skip_previous
        "ic_skip_next" -> Res.drawable.ic_skip_next
        "ic_pause_24dp" -> Res.drawable.ic_pause_24dp
        "ic_volume_up" -> Res.drawable.ic_volume_up
        "ic_visibility_off" -> Res.drawable.ic_visibility_off
        "ic_time_add_24dp" -> Res.drawable.ic_time_add_24dp
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
    // 兜底: 仅有以下 key 无 app 端 drawable xml 对应, 仍走 Material Icons
    val imageVector = when (key) {
        "ic_arrow_forward" -> Icons.AutoMirrored.Filled.ArrowForward // 段落前进 (app 端无 drawable, 区分于章节 SkipNext, 方向敏感图标已迁移到 AutoMirrored 包)
        "ic_speed" -> Icons.Filled.Speed // 语速调节 (TTS 播放速度滑杆, app 端无 drawable)
        else -> Icons.Filled.Help // 占位图标, 暴露未识别 key 便于调试
    }
    return rememberVectorPainter(imageVector)
}

/**
 * 桌面 JVM 共享字符串表 ([rememberString] 与 [jvmGetString] 共用, 单一数据源, 避免维护两份字面量)。
 *
 * 收录所有 i18n key, 值与 app 端 values-zh/strings.xml 默认中文对齐 (zh-CN), 桌面端不做 i18n。
 * 未识别的 key 返回 key 本身。
 */
val jvmStringTable: Map<String, String> = mapOf(
            "ok" to "确定",
            "cancel" to "取消",
            // 退出未保存确认对话框 (与 app 端 values-zh/strings.xml 对齐)
            "exit" to "退出",
            "exit_no_save" to "尚未保存，是否继续编辑",
            "yes" to "是",
            "no" to "否",
            "reduce" to "减少",
            "plus" to "增加",
            "clear" to "清除",
            "more_menu" to "更多",
            "empty" to "空空如也",
            "revert_selection" to "反选",
            // 书源管理 Screen 用到的 key (与 app 端 values-zh-rCN/strings.xml 对齐)
            "book_source" to "书源",
            "search_book_source" to "搜索书源",
            "sort" to "排序",
            "sort_desc" to "降序",
            "sort_manual" to "手动排序",
            "sort_auto" to "自动排序",
            "sort_by_name" to "按名称",
            "sort_by_url" to "按链接",
            "sort_by_lastUpdateTime" to "按更新时间",
            "sort_by_respondTime" to "按响应时间",
            "is_enabled" to "已启用",
            "menu_action_group" to "分组",
            "group_manage" to "分组管理",
            "enabled" to "启用",
            "disabled" to "禁用",
            "need_login" to "需登录",
            "no_group" to "未分组",
            "error_load_toc" to "加载目录失败", // BookInfoScreen toc 加载失败提示 (与 app 端 values-zh/strings.xml 对齐)
            "enabled_explore" to "启用发现",
            "disabled_explore" to "禁用发现",
            "add_book_source" to "添加书源",
            "import_local" to "本地导入",
            "import_on_line" to "网络导入",
            "group_sources_by_domain" to "按域名分组",
            "help" to "帮助",
            "edit" to "编辑",
            "to_top" to "置顶",
            "to_bottom" to "置底",
            "login" to "登录",
            // SourceLoginDialog 下沉带入 (与 app 端 values-zh/strings.xml 对齐)
            "login_source" to "登录 %s", // rememberString formatArgs 填 bookSourceName
            "login_url" to "登录 URL(loginUrl)",
            "login_header" to "登录请求头",
            "show_login_header" to "查看登录请求头",
            "del_login_header" to "删除登录请求头",
            "login_header_tip" to "请求头", // 输入框 label (避免与按钮文案 "cookie"=清除 Cookie 冲突)
            "cookie_input" to "Cookie", // SourceLoginDialog Cookie 输入框 label (与 "cookie"=清除 Cookie 区分)
            "search" to "搜索",
            "debug" to "调试",
            "delete" to "删除",
            "enable_explore" to "启用发现",
            "disable_explore" to "禁用发现",
            // ImportListScaffold 用到的 key (与 app 端 values-zh/strings.xml 对齐)
            "open" to "打开",
            "select_all_count" to "全选（%1\$d/%2\$d）",
            "select_cancel_count" to "取消全选（%1\$d/%2\$d）",
            // MoreConfigScreen 用到的 key (与 app 端 values-zh/strings.xml 对齐)
            "more_config" to "更多配置",
            "screen_direction" to "屏幕方向",
            "keep_light" to "屏幕超时",
            "pt_hide_status_bar" to "隐藏状态栏",
            "pt_hide_navigation_bar" to "隐藏导航栏",
            "double_page_horizontal" to "平板/横屏双页",
            "progress_bar_behavior" to "进度条行为",
            "use_zh_layout" to "使用自定义中文分行",
            "text_full_justify" to "文字两端对齐",
            "text_bottom_justify" to "文字底部对齐",
            "mouse_wheel_page" to "鼠标滚轮翻页",
            "volume_key_page" to "音量键翻页",
            "volume_key_page_on_play" to "朗读时音量键翻页",
            "page_touch_slop_title" to "滑动翻页阈值",
            "auto_change_source" to "自动换源",
            "preview_image_by_click" to "点击预览图片",
            "click_regional_config" to "点击区域设置",
            "custom_page_key" to "自定义翻页按键",
            "show_read_title_addition" to "展示顶部工具栏附加区域",
            // ReadAloudConfigScreen 用到的 key (与 app 端 values-zh/strings.xml 对齐)
            "read_aloud_config" to "朗读配置",
            "aloud_config" to "朗读设置",
            "ignore_audio_focus_title" to "忽略音频焦点",
            "ignore_audio_focus_summary" to "允许与其他应用同时播放音频",
            "pause_read_aloud_while_phone_calls_title" to "来电期间暂停朗读",
            "pause_read_aloud_while_phone_calls_summary" to "在通话期间暂停朗读，需要读取手机状态权限",
            "read_aloud_wake_lock" to "朗读服务唤醒锁",
            "read_aloud_wake_lock_summary" to "开启朗读的时候启用唤醒锁,有些手机开启唤醒锁会被杀后台",
            "system_media_control_compatibility_change" to "系统媒体控件兼容性更改",
            "system_media_control_compatibility_change_summary" to "当锁屏不显示系统媒体控件时可以尝试开启，比如oneui7.0或vivo等",
            "pref_media_button_per_next" to "媒体按钮•上一首|下一首",
            "pref_media_button_per_next_summary" to "上一段|下一段/上一章|下一章",
            "read_aloud_by_page" to "按页朗读",
            "read_aloud_by_page_summary" to "及时翻页，翻页时会停顿一下",
            "stream_read_aloud_audio" to "流式播放音频",
            "stream_read_aloud_audio_summary" to "即边下边播，网络不好时播放会断断续续，仅TTS源有效",
            "speak_engine" to "朗读引擎",
            "system_tts" to "系统 TTS", // 系统默认 TTS 引擎名称 (ReadAloudConfig / TtsControlPanel)
            "sys_tts_config" to "系统 TTS 设置",
            "sys_tts_config_summary" to "打开系统 TTS 设置界面",
            // TocScreen 下沉带入 (与 app 端 values-zh/strings.xml 对齐)
            "chapter_list" to "目录",
            "bookmark" to "书签",
            "export" to "导出",
            "export_md" to "导出(MD)",
            "txt_toc_rule" to "TXT 目录规则",
            "split_long_chapter" to "拆分超长章节",
            "reverse_toc" to "反转目录",
            "use_replace" to "使用替换",
            "load_word_count" to "加载字数",
            "log" to "日志",
            "go_to_top" to "滚动到顶部",
            "go_to_bottom" to "滚动到底部",
            // TipConfigScreen 下沉带入 (与 app 端 values-zh/strings.xml 对齐)
            "tip_config" to "提示信息配置", // 桌面端 TipConfigScreen 包装 title (页眉页脚提示)
            "body_title" to "正文标题",
            "title_left" to "靠左",
            "title_center" to "居中",
            "title_hide" to "隐藏",
            "title_font_size" to "字号",
            "title_margin_top" to "上边距",
            "title_margin_bottom" to "下边距",
            "header" to "页眉",
            "footer" to "页脚",
            // 原 XML 用 CDATA 转义 &, 实际字符串为 "页眉&页脚"
            "header_footer" to "页眉&页脚",
            "show_hide" to "显示/隐藏",
            "left" to "左",
            "middle" to "中",
            "right" to "右",
            "text_color" to "文字颜色",
            "tip_divider_color" to "分隔线颜色",
            "show" to "显示",
            "hide" to "隐藏",
            "hide_when_status_bar_show" to "状态栏显示时隐藏",
            // PaddingConfigScreen 下沉带入 (与 app 端 values-zh/strings.xml 对齐)
            "padding_config" to "边距配置",
            "main_body" to "正文",
            "showLine" to "显示分隔线",
            "padding_top" to "上边距",
            "padding_bottom" to "下边距",
            "padding_left" to "左边距",
            "padding_right" to "右边距",
            // EffectiveReplacesScreen 下沉带入 (与 app 端 values-zh/strings.xml 对齐)
            "effective_replaces" to "起效的替换",
            "add" to "添加",
            "close" to "关闭",
            "source_filter_rule_manage" to "管理全部",
            // FontSelectDialog 下沉带入 (与 app 端 values-zh/strings.xml 对齐)
            "select_font" to "选择字体",
            "default_font" to "默认字体",
            "other_folder" to "其它目录",
            "system_typeface" to "系统内置字体样式",
            // BookshelfComposablesShared 下沉带入 (与 app 端 values-zh/strings.xml 对齐)
            "bookshelf" to "书架",
            "bookshelf_empty" to "书架还空着，先去搜索书籍或从发现里添加吧！",
            "book_local" to "添加本地",
            "add_url" to "添加网址",
            "add_book_url" to "添加书籍网址",
            "force_refresh_book" to "强制更新书籍",
            "select_file" to "选择文件",
            "add_remote_book" to "远程书籍",
            // ReplaceEditScreen / ReplaceRuleListScreen 下沉带入 (与 app 端 values-zh/strings.xml 对齐)
            "replace_rule_edit" to "替换规则编辑",
            "action_save" to "保存",
            "copy_rule" to "拷贝规则",
            "paste_rule" to "粘贴规则",
            "replace_rule_summary" to "替换规则名称",
            "replace_rule" to "替换规则",
            "replace_to" to "替换为",
            "replace_scope" to "替换范围，选填书名或者书源 URL",
            "replace_exclude_scope" to "排除范围，选填书名或者书源 URL",
            "replace_rule_title" to "替换净化",
            "replace_purify" to "替换净化",
            "replace_purify_search" to "替换净化-搜索",
            "replace_enable_default_s" to "新加入书架的书是否启用替换净化",
            "replace_enable_default_t" to "默认启用替换净化",
            "replace" to "替换",
            "scope_title" to "作用于标题",
            "scope_content" to "作用于正文",
            "use_regex" to "使用正则表达式",
            "add_replace_rule" to "新建替换",
            "sure_del" to "是否确认删除？",
            "draw" to "提醒",
            "timeout_millisecond" to "超时毫秒数",
            "respondTime" to "响应时间：%1\$d ms",
            "rule_subscription" to "规则订阅",
            // RSS 源入口 (桌面端独有, MyConfigScreen showRssEntry=true 时渲染)
            "rss_sources" to "RSS",
            "dict_rule" to "字典规则",
            "enable_selection" to "启用所选",
            "disable_selection" to "禁用所选",
            "selection_to_top" to "置顶所选",
            "selection_to_bottom" to "置底所选",
            "export_selection" to "导出所选",
            // BookInfoScreen / BookSourceEditScreen 下沉带入 (与 app 端 values-zh/strings.xml 对齐)
            "book_info" to "书籍信息",
            "book_info_delete_alert_summary" to "删除书籍前弹出确认对话框",
            "book_info_delete_alert_title" to "删除书籍时提醒",
            "book_info_horizontal_layout" to "书籍详情横向布局",
            "book_info_horizontal_layout_summary" to "书籍详情页将封面与信息并排显示",
            "book_intro" to "内容简介",
            "book_name" to "书名",
            "book_source_manage" to "书源管理",
            "book_source_manage_desc" to "新建、导入、编辑或管理书源",
            "book_tree_uri_t" to "书籍保存位置",
            "book_type" to "类型：",
            "bookshelf_cover_height" to "列表模式下封面高度",
            "bookshelf_layout" to "书架布局",
            "bookshelf_management" to "书籍管理",
            "author" to "作者",
            "cookie" to "清除 Cookie",
            "copy_book_url" to "拷贝书籍 URL",
            "copy_source" to "拷贝源",
            "copy_toc_url" to "拷贝目录 URL",
            "debug_source" to "调试源",
            "delete_source" to "删除源",
            "disable_source" to "禁用源",
            "edit_book_source" to "编辑书源",
            "edit_source" to "编辑源",
            "paste_source" to "粘贴源",
            "like_source" to "赞",
            "not_like_source" to "踩",
            "review" to "评论",
            "share" to "分享",
            "str_share" to "字符串分享",
            "in_favorites" to "已收藏",
            "out_favorites" to "未收藏",
            "favorite" to "收藏",
            "intro_show_null" to "简介：暂无简介",
            "add_to_bookshelf" to "放入书架",
            "remove_from_bookshelf" to "删除书籍",
            // ChangeSourceScreen / SearchScreen / SearchContentScreen 下沉带入 (与 app 端 values-zh/strings.xml 对齐)
            "all_source" to "全部书源",
            "groups_or_source" to "多分组/书源",
            "precision_search" to "精准搜索",
            "screen" to "筛选",
            "search_book_key" to "搜索书名、作者",
            "search_content" to "全文搜索",
            "search_content_size" to "搜索结果",
            "search_layout" to "搜索布局",
            "searchHistory" to "搜索历史",
            "source_filter_rule" to "屏蔽规则",
            "start" to "开始",
            "stop" to "停止",
            "refresh" to "刷新",
            "next_chapter" to "下一章",
            "previous_chapter" to "上一章",
            "read_dur_progress" to "最近：%s",
            "read_record" to "阅读记录",
            "reading" to "阅读",
            // ReadRecordScreen 下沉带入 (与 app 端 values-zh/strings.xml 对齐)
            "read_record_today_total" to "当日 %1\$s / 总 %2\$s",
            "reading_time_sort" to "阅读时长排序",
            "last_read_time_sort" to "阅读时间排序",
            "enable_record" to "开启记录",
            "delete_all" to "删除所有",
            "sure_del_any" to "是否确认删除 %s？",
            "today_read_time" to "今日",
            "month_read_time" to "本月",
            "read_book_count" to "已读书籍数",
            "week_read_time" to "本周",
            "all_read_time" to "总阅读时间",
            "avg_book_read_time" to "均读时长",
            "read_heatmap_title" to "阅读热力图",
            "prev_month" to "上个月",
            "next_month" to "下个月",
            "month_label_format" to "%1\$d 年 %2\$d 月",
            "loading" to "加载中…",
            // CodeDialog 下沉带入 (对齐 app 端 CodeDialog 硬编码标题 "code view")
            "code_view" to "code view",
            "success" to "成功",
            "switchLayout" to "切换布局",
            // BookshelfManageScreen / ImportBookScreen / RemoteBookScreen 下沉带入 (与 app 端 values-zh/strings.xml 对齐)
            "action_download" to "下载",
            "allow_update" to "允许更新",
            "download_to_local" to "下载到本地",
            "empty_msg_import_book" to "点击右上角文件夹图标，选择文件夹",
            "export_all_use_book_source" to "导出所有书的书源",
            "export_config" to "导出文件设置",
            "export_folder" to "导出文件夹",
            "export_to_web_dav" to "导出到 WebDav",
            "custom_export_section" to "自定义Epub导出章节",
            "filter_book_type" to "按类型筛选",
            "import_file_name" to "导入文件名",
            "local_book" to "本地书籍",
            "menu_download_after" to "下载之后章节",
            "menu_download_all" to "下载全部章节",
            "move_to_group" to "移入分组",
            "pre_download" to "预下载",
            "remote_book" to "远程书籍",
            "scan_folder" to "智能扫描",
            "select_folder" to "选择文件夹",
            "sub_dir" to "子文件夹",
            "group" to "分组",
            // BackupConfigScreen / OtherConfigScreen / AboutScreen 下沉带入 (与 app 端 values-zh/strings.xml 对齐)
            "about" to "关于",
            "app_name" to "阅读",
            "version" to "版本", // 关于页版本号标题 (AboutScreen)
            "backup_config" to "备份设置",
            "backup" to "备份",
            "backup_path" to "备份路径",
            "backup_restore" to "备份与恢复",
            "backup_summary" to "本地和 WebDav 一起备份",
            "auto_check_new_backup_s" to "打开软件时检查是否有新备份，有新备份时提示是否更新",
            "auto_check_new_backup_t" to "自动检查新备份",
            "only_latest_backup_s" to "本地备份仅保留最新备份文件",
            "only_latest_backup_t" to "仅保留最新备份",
            "restore" to "恢复",
            "restore_ignore" to "恢复忽略列表",
            "restore_ignore_summary" to "恢复时忽略一些内容不恢复，方便不同手机配置不同",
            "restore_summary" to "优先从 WebDav 恢复，长按从本地恢复",
            "select_restore_file" to "选择恢复文件",
            "set_local_password" to "设置本地密码",
            "set_local_password_summary" to "本地密码用来对备份的敏感信息加密和解密,如需在不同设备之间同步,本地密码需一致.",
            "web_dav_account" to "WebDav 账号",
            "web_dav_pw" to "WebDav 密码",
            "web_dav_set" to "WebDav 设置",
            "web_dav_set_import_old" to "WebDav 设置/导入旧版本数据",
            "web_dav_url" to "WebDav 服务器地址",
            "webdav_device_name" to "设备名称",
            "web_port_title" to "Web 端口",
            "web_service" to "Web 服务",
            "web_service_wake_lock" to "WebService唤醒锁",
            "web_service_wake_lock_summary" to "开启web服务的时候启用唤醒锁,有些手机开启唤醒锁会被杀后台",
            "user_agent" to "用户代理",
            "threads_num_title" to "更新和搜索线程数（太多会卡顿）",
            "bitmap_cache_size" to "图片绘制缓存",
            "clear_cache" to "清理缓存",
            "clear_cache_summary" to "清除已下载书籍和字体缓存",
            "clear_webview_data" to "清除 WebView 数据",
            "clear_webview_data_summary" to "清除内置浏览器所有数据",
            "shrink_database" to "压缩数据库",
            "shrink_database_summary" to "减小数据库文件的大小",
            "auto_check_update" to "自动检查更新",
            "check_update" to "检查更新",
            "update_to_variant_title" to "检查更新查找版本",
            "update_to_variant_summary" to "检查更新时查找其他签名版本",
            "pref_cronet_summary" to "使用 Cronet 网络组件",
            "server_config" to "服务器配置",
            "direct_link_upload_rule" to "直链上传规则",
            "direct_link_upload_rule_summary" to "用于导出书源书单时生成直链 URL",
            "check_source_config" to "校验设置",
            "contributors" to "开发人员",
            "contributors_summary" to "gedoor、Invinciblelee 和 Xwite 等，详情请在 GitHub 中查看",
            "join_telegram_group" to "加入 Telegram 群",
            "update_log" to "更新日志",
            "crash_log" to "崩溃日志",
            "save_log" to "保存日志",
            "create_heap_dump" to "创建堆转储",
            "record_debug_log" to "记录调试日志",
            "record_heap_dump_s" to "当应用发生OOM崩溃时保存堆转储",
            "record_heap_dump_t" to "记录堆转储",
            "record_log" to "记录日志",
            "privacy_policy" to "用户隐私与协议",
            "license" to "开源许可",
            "disclaimer" to "免责声明",
            "other" to "其它",
            "other_setting" to "其它设置",
            // ThemeConfigScreen / WelcomeConfigScreen / CoverConfigScreen 下沉带入 (与 app 端 values-zh/strings.xml 对齐)
            "theme_config" to "主题设置",
            "welcome_config" to "欢迎页设置",
            "background_image" to "背景图片",
            "change_icon" to "切换图标",
            "change_icon_summary" to "切换软件显示在桌面的图标",
            "cover_config" to "封面设置",
            "cover_config_summary" to "默认封面样式",
            "cover_show_author" to "显示作者",
            "cover_show_author_summary" to "封面上显示作者",
            "cover_show_name" to "显示书名",
            "cover_show_name_summary" to "封面上显示书名",
            "customize_day_theme" to "自定义白天主题",
            "customize_night_theme" to "自定义夜间主题",
            "dark_theme" to "深色模式",
            "default_cover" to "默认封面",
            "default_home_page" to "默认主页",
            "font_scale" to "字体大小",
            "only_wifi" to "仅 WiFi",
            "only_wifi_summary" to "仅在 WiFi 下加载网络封面",
            "show_default_book_icon" to "显示默认书籍图标",
            "use_default_cover" to "总是使用默认封面",
            "use_default_cover_s" to "总是显示默认封面（不显示网络封面）",
            "bottom_nav_config" to "底栏设置",
            "bottom_nav_config_summary" to "调整底栏高度、图标大小与文本显示方式",
            "theme_list" to "主题列表",
            "theme_list_summary" to "使用、保存、导入或分享主题",
            // ThemeCustomizeDialog / ThemeListDialog 下沉带入 (与 app 端 values-zh/strings.xml 对齐)
            "accent" to "强调色",
            "background_color" to "背景色",
            "navbar_color" to "底部操作栏颜色",
            "theme_name" to "主题名称",
            "new_theme" to "新建",
            "theme_customize_title" to "编辑主题",
            "select_image" to "选择图片",
            "default_cover_count" to "已选 %1\$d 张",
            "background_image_blurring" to "背景图片虚化",
            "day_background_too_dark" to "白天背景不能太暗",
            "night_background_too_light" to "夜间背景不能太亮",
            "theme_mode" to "主题模式",
            "theme_setting" to "界面设置",
            "theme_setting_s" to "与界面/颜色相关的一些设置",
            "welcome_show_time" to "欢迎页显示时间",
            "welcome_style" to "启动界面样式",
            "welcome_style_summary" to "启动界面图片和是否显示文字等",
            "welcome_text" to "阅读|享受美好时光",
            "enable_welcome" to "启用欢迎页",
            "enable_welcome_summary" to "启动时是否显示欢迎页",
            "show_welcome_text" to "显示文字",
            "show_icon" to "显示图标",
            "day" to "白天",
            "night" to "夜间",
            "source_edit_text_max_line" to "源编辑框最大行数",
            "auto_indent" to "自动缩进",
            // ReadMenu / ReadAloudConfigScreen 下沉带入 (与 app 端 values-zh/strings.xml 对齐)
            "ps_auto_refresh" to "打开软件时自动更新书籍",
            "pt_auto_refresh" to "自动刷新",
            "click_book_open_read" to "点击视频书籍直接播放",
            "click_book_open_read_summary" to "在搜索、发现中点击视频书籍直接打开播放页，跳过详情",
            "media_button_on_exit_title" to "全程响应耳机按键",
            "media_button_on_exit_summary" to "即使退出软件也响应耳机按键",
            "read_aloud_by_media_button_title" to "耳机按键启动朗读",
            "read_aloud_by_media_button_summary" to "通过耳机按键来启动朗读",
            "auto_next_page" to "自动翻页",
            "auto_next_page_stop" to "停止自动翻页",
            // HomeScreen / MainBottomBar / MyConfigScreen / ExploreScreen 下沉带入 (与 app 端 values-zh/strings.xml 对齐)
            "bottom_line" to "我是有底线的",
            "discovery" to "发现",
            "explore_cols" to "列数",
            "explore_empty" to "当前没有发现源！",
            "explore_style" to "样式",
            "home" to "主页",
            "home_manage" to "管理组件",
            "home_tab_empty" to "此分组无组件，点击右上角+添加",
            "home_tab_manage" to "管理分组",
            "my" to "我的",
            "language" to "语言",
            "add_to_text_context_menu_s" to "长按文字在操作菜单中显示阅读",
            "add_to_text_context_menu_t" to "文字操作显示搜索",
            "show_add_to_shelf_alert_summary" to "阅读未放入书架的书籍在返回时提示放入书架",
            "show_add_to_shelf_alert_title" to "返回时提示放入书架",
            "set_book_variable" to "设置书籍变量",
            "set_source_variable" to "设置源变量",
            "sync_book_progress_s" to "进入退出阅读界面时同步阅读进度",
            "sync_book_progress_t" to "同步阅读进度",
            "sync_book_progress_plus_s" to "重新进入页面（息屏、后台返回等）或者网络变为可用时同步云端进度，同步新进度会询问",
            "sync_book_progress_plus_t" to "同步增强",
            "upload_to_remote" to "上传 WebDav",
            "sort_by_size" to "大小排序",
            "sort_by_time" to "时间排序",
            // 补全: SelectAction / 批量操作菜单文本 key (与 app 端 values-zh/strings.xml 对齐)
            "add_group" to "添加到分组", // 书籍/书源批量操作菜单: 添加到分组 (app 端 BookSourceActivity 用)
            "remove_group" to "从分组移除", // 书籍/书源批量操作菜单: 从分组移除 (app 端 BookSourceActivity 用)
            "add_to_group" to "加入分组", // 书籍批量操作菜单: 加入分组 (app 端 BookshelfManageActivity 用, 与 add_group 区分)
            "share_selection" to "分享所选", // 批量操作菜单: 分享所选 (旧 key, 桌面端未直接使用)
            "share_selected_source" to "分享选中源", // 书源批量操作菜单: 分享选中源 (app 端 BookSourceActivity 用)
            "check_select_source" to "校验选中书源", // 书源管理批量操作: 校验选中书源
            "export_all" to "导出全部", // 书源/书单管理: 导出全部
            "disable_update" to "禁止更新", // 书籍批量操作菜单: 禁止更新
            "export_bookshelf" to "导出书架", // 书架管理: 导出书架
            "change_source_batch" to "批量改源", // 书籍批量操作菜单: 批量改源
            "check_selected_interval" to "检查选中区间", // 书源管理批量操作: 检查选中区间
            "refresh_list" to "刷新列表", // 通用列表刷新菜单
            "checkAuthor" to "校验作者", // 书源管理批量操作: 校验作者
            "sure_clear_search_history" to "是否确认清空搜索历史？", // 搜索历史清空确认对话框
            "non_null_name_url" to "书源 URL 与名称不能为空", // 书源编辑校验提示
            "wrong_format" to "格式错误", // 通用格式校验提示
            // 桌面端 AlertDialog (替换原 javax.swing.JOptionPane) 用到的 key
            "debug_error" to "调试错误", // 调试页错误弹窗标题 (BookSourceDebugScreen.startSearch 书源未获取到)
            "no_source_found" to "未获取到书源", // 调试页错误弹窗正文
            "select_explore" to "选择发现", // 调试页发现分类选择弹窗提示
            "select_explore_kind" to "选择发现分类", // 调试页发现分类选择弹窗标题
            "no_source_code" to "暂无源码", // 调试页源码查看弹窗空源码占位
            "save_failed" to "保存失败", // 书源编辑保存校验失败标题 / catch fallback 正文
            "save_source_error" to "保存书源错误", // 书源编辑保存异常弹窗标题
            "clipboard_empty" to "剪贴板为空", // 书源编辑粘贴源剪贴板空提示
            "paste_source_error" to "粘贴书源错误", // 书源编辑粘贴源异常弹窗标题
            "enable_dangerous_api" to "启用危险 API", // 启用危险 API 确认弹窗标题
            "enable_dangerous_api_warn" to "启用危险 API 后, 书源可执行任意 JS 代码, 存在安全风险。确认启用?", // 启用危险 API 确认弹窗正文
            // 桌面端硬编码中文替换补全 key (与 app 端 values-zh/strings.xml 对齐)
            // 通用类
            "dialog_title" to "提示",
            "not_set" to "未设置",
            "zero_disable_suffix" to "(0=禁用)",
            "settings_not_implemented" to "设置 (待实现)",
            "retry" to "重试",
            // 书源类
            "new_book_source" to "新建书源",
            "select_book_source_json" to "选择书源 JSON 文件",
            "input_book_source_url" to "请输入书源订阅 URL",
            "net_import_book_source" to "网络导入书源",
            "export_book_source_json" to "导出书源 JSON",
            "json_file_filter" to "JSON 文件 (*.json)",
            "sure_del_source" to "确认删除该书源？",
            "image_style_default" to "默认",
            "image_style_full" to "满版",
            "image_style_text" to "文字环绕",
            "image_style_single" to "单张",
            // 换源/搜索类
            "searched_count_progress" to "已搜索 %1\$d 条 / 第 %2\$d / 共 %3\$d 个源 (%4\$s)",
            "sure_del_explore_favorite" to "确认删除该发现收藏？",
            "explore_category_error" to "发现分类错误",
            "bottom_reached" to "到底了",
            "loading_error" to "加载错误",
            "loading_error_click_detail" to "加载错误 点击查看详情",
            // 替换规则类 (桌面端 ReplaceRuleScreen AlertDialog / FileDialog 标题)
            "replace_rule_net_import_title" to "网络导入替换规则",
            "replace_rule_input_url" to "请输入替换规则订阅 URL",
            "replace_rule_select_json_file" to "选择替换规则 JSON 文件",
            "replace_rule_save_json_file" to "保存替换规则 JSON 文件",
            // 发现类 (ExploreShowScreen footer 空结果占位)
            "explore_show_empty" to "空",
            // TTS/朗读类
            "previous_segment" to "上一段",
            "next_segment" to "下一段",
            "play" to "播放",
            "pause" to "暂停",
            "speech_rate" to "语速",
            "reading_not_started" to "朗读未开始",
            "reading_progress_format" to "正在朗读 %1\$d/? 章 第 %2\$d 段",
            "paused" to "已暂停",
            "stopped" to "已停止",
            "reading_completed" to "朗读完成",
            "reading_error" to "朗读出错",
            // 书籍编辑/导入类
            "select_cover_image" to "选择封面图片",
            "input_new_cover_url" to "请输入新的封面 URL",
            "change_cover_source" to "更换封面源",
            "select_import_book_dir" to "选择导入书籍目录",
            "filename_import_js_title" to "文件名导入 js",
            "filename_import_js_summary" to "使用js处理文件名变量src，将书名作者分别赋值到变量name author",
            "image_label_with_src" to "图片: %s",
            // Remote/WebDav 类
            "web_dav_config_not_implemented" to "WebDav 服务器配置功能暂未在桌面端实现",
            "web_dav_help_not_implemented" to "WebDav 帮助暂未在桌面端实现",
            "log_view_not_implemented" to "日志查看功能暂未在桌面端实现",
            "pending_operation" to "待操作",
            "test_connection" to "测试连接",
            "connecting" to "测试连接中...",
            "connection_success" to "连接成功",
            "connection_no_account" to "未连接: 账号或密码为空",
            "connection_failed" to "连接失败: %s",
            // WebDav 服务类型标签 (WebDavConfigScreen 测试连接成功状态文本)
            "web_dav_jianGuoYun" to "坚果云",
            "web_dav_other" to "其他 WebDav",
            "backup_success_uploaded" to "备份成功 (已上传到 WebDav)",
            "backup_failed" to "备份失败: %s",
            "local_backup_success" to "本地备份成功 (未上传到 WebDav)",
            "local_backup_failed" to "本地备份失败: %s",
            "web_dav_no_backup" to "WebDav 无备份文件",
            "restore_success" to "恢复成功 (已从云端拉取并恢复)",
            "restore_failed" to "恢复失败: %s",
            // 欢迎页类
            "seconds_unit" to "秒",
            // NumberPickerDialog 用到的 key (与 app 端 values-zh/strings.xml 对齐)
            "btn_default_s" to "默认",
            "page_touch_slop_dialog_title" to "滑动翻页阈值（0 = 系统默认值）",
            // BookSourceDebugScreen 源码查看对话框标题 (与 app 端 values-zh/strings.xml 对齐)
            "search_src" to "搜索页源码",
            "book_src" to "详情页源码",
            "toc_src" to "目录页源码",
            "content_src" to "正文页源码",
            "review_src" to "段评页源码",
            // 分组管理对话框 (与 app 端 values-zh/strings.xml 对齐)
            "group_name" to "分组名",
            // BookSourceEditScreen 表单 label (与 app 端 values-zh/strings.xml 对齐)
            "source_url" to "书源 URL",
            "source_name" to "书源名称",
            "source_group" to "书源分组",
            "comment" to "书源注释",
            "login_check_js" to "登录检测 JS",
            "book_url_pattern" to "详情页 URL 正则",
            "source_http_header" to "请求头",
            "concurrent_rate" to "并发率",
            "r_search_url" to "搜索 URL",
            "check_key_word" to "校验关键字",
            "r_book_list" to "列表规则",
            "r_book_name" to "书名规则",
            "r_author" to "作者规则",
            "rule_book_kind" to "分类规则",
            "rule_word_count" to "字数规则",
            "rule_last_chapter" to "最新章节规则",
            "rule_book_intro" to "简介规则",
            "rule_cover_url" to "封面规则",
            "r_book_url" to "详情页 URL 规则",
            "rule_has_more" to "下一页规则",
            "r_find_url" to "发现 URL",
            "rule_book_info_init" to "初始化规则",
            "rule_toc_url" to "目录 URL 规则",
            "rule_can_re_name" to "可重命名规则",
            "download_url_rule" to "下载 URL 规则",
            "pre_update_js" to "更新前 JS",
            "rule_chapter_name" to "章节名规则",
            "rule_chapter_url" to "章节 URL 规则",
            "rule_is_volume" to "卷规则",
            "rule_update_time" to "更新时间规则",
            "rule_is_vip" to "VIP 规则",
            "rule_is_pay" to "付费规则",
            "rule_next_toc_url" to "下一页规则",
            "rule_book_content" to "正文规则",
            "rule_should_override_url_loading" to "URL 拦截规则",
            "rule_web_js" to "WebView JS",
            "rule_source_regex" to "源码正则",
            "rule_replace_regex" to "替换规则",
            "rule_image_style" to "图片样式",
            "rule_pay_action" to "付费动作",
            // 桌面端独有 key (app 端 strings.xml 未定义)
            "cover_decode_js" to "封面解析 JS",
            "variable_comment" to "变量注释",
            "js_lib" to "JS 库",
            "sub_content_rule" to "副正文规则",
            "image_decode" to "图片解码",
            "music_cover_rule" to "音乐封面规则",
            "review_count_rule" to "段评数量规则",
            "total_review_count_rule" to "总段评数规则",
            "review_url_rule" to "段评 URL 规则",
            "review_list_rule" to "段评列表规则",
            "review_id_rule" to "段评 ID 规则",
            "avatar_rule" to "头像规则",
            "nickname_rule" to "昵称规则",
            "content_rule" to "内容规则",
            "post_time_rule" to "发布时间规则",
            "extra_rule" to "额外规则",
            "images_rule" to "图片规则",
            "vote_up_count_rule" to "点赞数规则",
            "vote_up_selected_rule" to "已点赞规则",
            "vote_down_selected_rule" to "已踩规则",
            "reply_count_rule" to "回复数规则",
            "reply_list_url" to "回复列表 URL",
            "vote_up_rule" to "点赞规则",
            "vote_down_rule" to "踩规则",
            "reply_rule" to "回复规则",
            // 书源编辑表单补充 (login_url/login_ui 已有不同值, 用独立 key 避免冲突)
            "source_login_url" to "登录 URL",
            "source_login_ui" to "登录界面",
            "delete_rule" to "删除规则",
            // 阅读配置面板 (ReadMenu 配置项 label)
            "read_config" to "阅读配置",
            "text_size" to "字号",
            "line_size" to "行距",
            "paragraph_size" to "段距",
            "background" to "背景",
            "page_turn" to "翻页",
            "page_anim_none" to "无动画",
            "page_anim_cover" to "覆盖",
            "page_anim_slide" to "滑动",
            // 搜索/文本工具栏/时间格式化 (与 app 端 values-zh/strings.xml 对齐)
            "search_result_empty" to "搜索结果为空",
            "search_result_empty_close_precision" to "%s 分组搜索结果为空，是否关闭精准搜索？",
            "search_result_empty_switch_all" to "%s 分组搜索结果为空，是否切换到全部分组？",
            "copy" to "复制",
            "cut" to "剪切",
            "paste" to "粘贴",
            "select_all" to "全选",
            "time_format_minutes" to "0 分钟",
            "time_format_hours_minutes" to "%1\$d 小时 %2\$d 分钟",
            "time_format_hours" to "%d 小时",
            "time_format_minutes_only" to "%d 分钟",
            "time_format_seconds" to "%d 秒",
            // 书源编辑/调试菜单项 (与 app 端 values-zh/strings.xml 对齐)
            "book_source_tutorial" to "书源教程",
            "js_tutorial" to "JS 教程",
            "regex_tutorial" to "正则教程",
            "debug_search_hint" to "调试搜索>>输入关键字，如：",
            "debug_explore_hint" to "调试发现>>输入发现URL，如：",
            "debug_book_info_hint" to "调试详情页>>输入详情页URL，如：",
            "debug_toc_hint" to "调试目录页>>输入目录页URL，如：",
            "debug_content_hint" to "调试正文页>>输入正文页URL，如：",
            "system" to "系统",
            // ===== P0 Dialog 下沉新增 i18n key (16 个 Dialog 用) =====
            // TextDialog
            "text_too_large" to "数据太大，无法全部显示…",
            // AppLogDialog
            "log_level_all" to "全部",
            "log_level_error" to "错误",
            "log_level_warn" to "警告",
            "log_level_info" to "信息",
            "log_level_debug" to "调试",
            "log_stack_trace" to "堆栈信息",
            // BookmarkDialog
            "bookmark_content" to "内容",
            "bookmark_note" to "备注内容",
            // SearchScopeDialog
            "search_scope" to "搜索范围",
            // VariableDialog
            "source_variable_tab" to "源变量",
            "book_variable_tab" to "书籍变量",
            "variable_key" to "变量名",
            "variable_value" to "变量值",
            "variable_edit_title" to "编辑变量",
            "variable_empty" to "暂无变量",
            // GroupEditDialog
            "group_edit" to "编辑分组",
            "group_add" to "添加分组",
            "allow_drop_down_refresh" to "允许下拉刷新",
            "book_sort_default" to "默认",
            "book_sort_reading_time" to "按阅读时间",
            "book_sort_update_time" to "按更新时间",
            "book_sort_name" to "按书名",
            "book_sort_manual" to "手动排序",
            "book_sort_comprehensive" to "综合排序",
            "book_sort_author" to "按作者",
            // TxtTocRuleEditDialog
            "txt_toc_rule_edit_name" to "名称",
            "txt_toc_rule_edit_rule" to "规则",
            "txt_toc_rule_edit_example" to "示例",
            "txt_toc_rule_edit_name_required" to "名称不能为空",
            "txt_toc_rule_edit_regex_error" to "正则语法错误或不支持(txt)",
            "txt_toc_rule_edit_copy_success" to "已复制",
            // DictRuleEditDialog
            "dict_rule_edit_name" to "名称",
            "dict_rule_edit_url_rule" to "URL 规则",
            "dict_rule_edit_show_rule" to "显示规则",
            "dict_rule_edit_copy_success" to "已复制",
            // SourceFilterEditDialog
            "source_filter_rule_edit_name" to "规则名",
            "source_filter_rule_edit_pattern" to "规则",
            "source_filter_rule_edit_invalid_pattern" to "正则无效或为空",
            "source_filter_rule_edit_title_add" to "添加屏蔽规则",
            "source_filter_rule_edit_title_edit" to "屏蔽规则",
            // ContentEditDialog
            "content_edit_reset" to "重置",
            "content_edit_copy_all" to "复制全部",
            "content_edit_copy_success" to "已复制",
            // ClickActionDialog
            "click_regional_config" to "点击区域设置",
            "select_action" to "选择动作",
            "menu" to "菜单",
            "next_page" to "下一页",
            "prev_page" to "上一页",
            "read_aloud_prev_paragraph" to "朗读上一段",
            "read_aloud_next_paragraph" to "朗读下一段",
            "bookmark_add" to "添加书签",
            "replace_state_change" to "替换状态切换",
            "read_aloud_pause_resume" to "朗读暂停/继续",
            // PageKeyDialog
            "prev_page_key" to "上一页按键",
            "next_page_key" to "下一页按键",
            "reset" to "重置",
            // SpeakEngineDialog
            "system_default" to "系统默认",
            // ReadAloudDialog
            "prev_sentence" to "上一句",
            "next_sentence" to "下一句",
            "audio_play" to "播放",
            "set_timer" to "设定时间",
            "timer_m" to "%d 分钟",
            "read_aloud_speed" to "语速",
            "flow_sys" to "跟随系统",
            "tts_speech_reduce" to "语速减",
            "tts_speech_add" to "语速加",
            "main_menu" to "主菜单",
            "to_backstage" to "转到后台",
            // SourcePickerDialog
            "select_book_source" to "选择书源",
            "change_source_delay" to "换源延迟",
            // P0: TTS onError 错误回调 (DesktopHttpTtsPlayer, 非 @Composable 上下文用 jvmGetString)
            "tts_error_prepare_no_url" to "prepare 失败: 未设置 URL",
            "tts_error_prepare_exception" to "prepare 异常: %s",
            "tts_error_resume_failed" to "恢复播放失败: %s",
            "tts_error_play_exception" to "播放异常: %s",
            "tts_error_http_connect_failed" to "HTTP 连接失败: %s",
            "tts_error_input_stream_null" to "InputStream 为 null",
            "tts_error_unsupported_audio_format" to "不支持的音频格式 (桌面端仅支持 WAV/PCM/AU/AIFF; MP3 需引入 MP3 SPI): %s",
            "tts_error_read_stream_failed" to "读取音频流失败: %s",
            "tts_error_audio_line_unavailable" to "音频输出线不可用: %s",
            "tts_error_open_line_failed" to "打开音频输出线失败: %s",
            "tts_error_source_line_not_ready" to "播放失败: SourceDataLine 未就绪",
            "tts_error_audio_stream_not_ready" to "播放失败: AudioInputStream 未就绪",
            "tts_error_start_failed" to "启动播放失败: %s",
            "tts_error_read_stream_interrupted" to "读取音频流中断: %s",
            "tts_error_write_line_failed" to "写入音频线失败: %s",
            // P1: 调试日志 (BookSourceDebugScreen logs.add, 非 @Composable 上下文用 jvmGetString)
            "debug_explore_error" to "获取发现出错\n%s",
            "debug_explore_json_error" to "获取发现出错 JSON 数据错误\n%s",
            "debug_source_not_found" to "ERROR:未找到书源 %s",
            // HttpTts 导入 (ReadAloudConfigScreen 接入 DesktopImportDialog, 与 ReplaceRuleScreen
            // 的 replace_rule_net_import_title/replace_rule_input_url/replace_rule_select_json_file
            // 命名风格对齐; import_tts 与 app 端 values/strings.xml line 701 一致)
            "import_tts" to "导入 TTS",
            "http_tts_net_import_title" to "网络导入 TTS",
            "http_tts_input_url" to "请输入 TTS 订阅 URL",
            "http_tts_select_json_file" to "选择 TTS JSON 文件",
            // 桌面端硬编码中文 i18n 补全 key
            "back" to "返回",
            "change_source" to "换源",
            "cover" to "封面",
            "no_lyrics" to "暂无歌词",
            "speed" to "倍速",
            "play_mode" to "播放模式",
            "log_saved" to "日志已保存",
            "save_log_failed" to "保存日志失败",
            "heap_dump_saved" to "堆转储已保存",
            "save_heap_dump_failed" to "保存堆转储失败",
            "found_new_version" to "发现新版本",
            "download_now" to "前往下载",
            "net_import_theme" to "网络导入主题",
            "input_theme_url" to "请输入主题订阅 URL",
            "import_theme_success" to "导入成功, 共 %d 个主题",
            "clipboard_empty_or_invalid" to "剪贴板为空或格式不对",
            "format_invalid_add_failed" to "格式不对,添加失败",
            "copied_to_clipboard" to "已复制到剪贴板",
            "copy_failed" to "复制失败",
            "load_chapter_content_failed" to "加载章节内容失败",
            "text_action" to "文字操作",
            "loading_chapter_content" to "正在加载章节内容...",
            "address_copied" to "已复制地址",
            "copy_address" to "复制地址",
            "open_failed" to "打开失败: %s",
            "open_in_browser" to "浏览器打开",
            "restore_from_local_prompt" to "将从本地备份恢复。",
            "clear_cache_success" to "清缓存成功",
            "export_bookmark_json" to "导出书签 JSON",
            "export_success" to "导出成功",
            "export_bookmark_md" to "导出书签 Markdown",
            "no_book" to "没有书籍",
            "resolution" to "分辨率",
            "reload" to "重新加载",
            // ReviewListDialog 下沉带入 (与 app 端 values-zh/strings.xml 对齐)
            "review_post_hint" to "想说点什么？",
            "reply_review" to "回复",
            "review_replies_detail_title" to "评论详情",
            "review_replies_section_title" to "全部回复 · %1\$d",
            "review_list_section_title" to "全部评论 · %1\$s",
            "review_sort_hot" to "最热",
            "review_sort_latest" to "最新",
            "review_expand" to "展开",
            "review_collapse" to "收起",
            "confirm_delete_review" to "确定删除这条评论？",
            "vote_up" to "点赞",
            "vote_down" to "点踩",
            "review_replies_count" to "全部 %1\$d 条回复 >"
        )

/**
 * @Composable 字符串获取入口 (UI 层用)。
 *
 * 查 [jvmStringTable], 未识别的 key 返回 key 本身。
 * formatArgs 为空时保留原始占位符; 非空时用 [String.format] 填充 (与 Android resources.getString 对齐)。
 */
@Composable
actual fun rememberString(key: String, vararg formatArgs: Any): String {
    val raw = jvmStringTable[key] ?: key
    return if (formatArgs.isEmpty()) raw else String.format(raw, *formatArgs)
}

/**
 * 非 @Composable 字符串获取入口 (供 coroutine / 普通函数 / init 块 / throw 异常等场景使用)。
 *
 * 与 [rememberString] 共用 [jvmStringTable], 单一数据源, 避免维护两份字面量表。
 * formatArgs 为 `Any?` (可空), 因错误回调常传 `e.message` (String?), null 会被 String.format 输出为 "null",
 * 与 Kotlin 字符串模板 `${e.message}` 行为一致。
 */
fun jvmGetString(key: String, vararg formatArgs: Any?): String {
    val raw = jvmStringTable[key] ?: key
    return if (formatArgs.isEmpty()) raw else String.format(raw, *formatArgs)
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
actual fun rememberStringArray(key: String): List<String> {
    // 字面量与 app 端 res/values-zh/strings.xml + res/values/arrays.xml 对齐 (zh-CN 默认中文)
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
 * 桌面 JVM actual: 无 launcher 图标概念, 返回空 List。
 *
 * 桌面端不展示 AdaptiveIconDrawable 预览, iconListPreference 的图标 widget 会因
 * icons.getOrNull(index) == null 而不渲染图标 (与未配置图标行为一致)。
 */
@Composable
actual fun rememberLauncherIconPainters(iconValues: List<String>): List<Painter?> {
    return emptyList()
}


