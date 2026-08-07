package io.legado.app.help.i18n

import io.legado.app.ui.compose.platform.findStringArrayResource
import io.legado.app.ui.compose.platform.findStringResource
import kotlinx.coroutines.runBlocking
import org.jetbrains.compose.resources.getString
import org.jetbrains.compose.resources.getStringArray

/**
 * appString 的安卓实现 (strings.xml 已整体删除, 多语言统一走 shared composeResources)。
 *
 * # 通道演进
 * 原实现 key→R.string 后转发 appCtx.getString, 依赖 app 端 strings.xml 多语言资产;
 * strings.xml 删除后 R.string 不复存在, 改从 shared Compose Resources 映射表取值
 * ([findStringResource] → [getString]), 与 desktop 端 [io.legado.desktop.help.i18n.registerDesktopAppStringProvider]
 * 的 jvmGetString 同构 (runBlocking 桥接 suspend 取值)。
 *
 * # 取值性能
 * Compose Resources 的字符串记录读取带 AsyncCache (按资源路径+偏移缓存), 首次读取后
 * 不再触碰 IO; App.onCreate 调 [warmAppStringCache] 把同步上下文常用 key 集中预读
 * (填热缓存, 启动期一次性 IO), 之后 Service 通知/回调等同步取值零阻塞。
 * 语言切换后 AsyncCache 按 locale 路径天然区分, 自动取新语言, 无需失效处理。
 *
 * # 取值通道全景 (三档)
 * - Composable 上下文: rememberString(key, *args) (ResourceProvider.kt)
 * - suspend 上下文: findStringResource(key) + getString(res) (ComposeResourceLookup.kt)
 * - 同步非协程上下文: 本文件 [androidAppString] (Service 通知/回调/VM toast/dialog 构建/权限 rationale)
 *
 * shared 侧 appString(AppStringKey) 走 provider 注册通道 (非 expect/actual), 宿主启动时
 * 调 [registerAndroidAppStringProvider]; 未注册时 fallback 返回 key 名, 运行期安全不崩。
 */
private val androidAppStringProvider = AppStringProvider { key, args ->
    androidAppString(key.name, *args)
}

/** 宿主启动早期注册一次(App.onCreate, 在 warmAppStringCache 之后)。 */
fun registerAndroidAppStringProvider() {
    registerAppStringProvider(androidAppStringProvider)
}

/**
 * 同步取 key 对应的本地化字符串 (第三档通道)。
 * - key 在 shared 资源表缺失时返回 key 名 (与 rememberString 兜底一致, 运行期可见即查)
 * - 占位符由 Compose Resources 的 getString(res, *args) 填充 (只认索引式 %1$s/%1$d;
 *   无索引 %s 原样保留, 供代码 .replace 消费的白名单 key 不要带参调用)
 * - formatArgs 为 `Any?` (可空), null 输出为 "null" (与 desktop jvmGetString 一致)
 */
fun androidAppString(key: String, vararg formatArgs: Any?): String {
    val resource = findStringResource(key) ?: return key
    return runBlocking {
        if (formatArgs.isEmpty()) getString(resource)
        else getString(resource, *formatArgs.map { it.toString() }.toTypedArray())
    }
}

/** 同步取 key 对应的本地化 string-array; key 缺失返回空列表 (调用方按需处理空态)。 */
fun androidAppStringArray(key: String): List<String> {
    val resource = findStringArrayResource(key) ?: return emptyList()
    return runBlocking { getStringArray(resource) }
}

/**
 * 启动期暖缓存: 集中预读同步上下文 (Service 通知/回调/VM toast/dialog 构建/权限 rationale/
 * 快捷方式等) 用到的全部 key, 填热 Compose Resources AsyncCache, 之后 [androidAppString]
 * 同步取值零 IO。清单 = AppStringKey 全量 + app 端同步上下文 key; 即使漏列也只是
 * 首次取值多一次 assets 读取 (毫秒级), 不影响正确性。
 */
fun warmAppStringCache() {
    val keys = AppStringKey.entries.map { it.name } + warmKeys
    runBlocking {
        for (key in keys) {
            findStringResource(key)?.let { getString(it) }
        }
        for (key in warmArrayKeys) {
            findStringArrayResource(key)?.let { getStringArray(it) }
        }
    }
}

/** 同步上下文额外 key (AppStringKey 之外), 新增同步引用时补入本清单。 */
private val warmKeys = listOf(
    // ---- 通知渠道 / 前台服务 ----
    "action_download", "read_aloud", "web_service", "service_starting",
    "offline_cache", "cancel", "check_book_source", "progress_show",
    "export_wait", "export_book", "export_book_notification_content", "export_success",
    "author_show", "intro_show", "img_cover", "book_intro",
    "tts_init_failed", "update_toc", "network_connection_unavailable",
    "download_start", "cannot_empty",
    // ---- 朗读/音频通知与媒体会话 ----
    "stop", "set_timer", "audio_pause", "playing_timer", "audio_play_t", "audio_play_s",
    "resume", "pause", "pref_media_button_per_next", "pref_media_button_per_next_summary",
    "audio", "read_aloud_pause", "read_aloud_timer", "read_aloud_t", "read_aloud_s",
    "previous_chapter", "next_chapter", "set_charset",
    // ---- 权限 rationale / 对话框 ----
    "read_aloud_read_phone_state_permission_rationale",
    "notification_permission_rationale", "ignore_battery_permission_rationale",
    "get_storage_per", "tip_perm_request_storage", "tip_cannot_jump_setting_page",
    "dialog_title", "dialog_setting", "dialog_cancel",
    // ---- 回调 / VM toast / 平台 provider ----
    "force_refresh_busy", "clear_cache_success", "error_no_source", "error_get_book_info",
    "error_get_chapter_list", "source_auto_changing", "unknown_error", "success", "error",
    "no_book", "no_books_dir", "restore_success", "is_latest_version", "check_update",
    "cronet_enabled", "cronet_download_failed", "error_read_file", "copy_complete",
    "can_not_open", "double_click_exit", "download_and_import_file", "import_select_book",
    "start_read", "simulated_reading", "switch_on", "start_from", "start_chapter",
    "daily_chapters", "btn_default_s", "page_anim", "page_anim_cover", "page_anim_slide",
    "page_anim_simulation", "page_anim_scroll", "page_anim_none", "image_style",
    "use_browser_open", "upload_book_success", "sync_book_progress_success",
    "jump_to_another_app", "confirm", "action_save", "select_folder", "open_fun",
    "upload_url", "sys_folder_picker", "sys_file_picker", "sys_image_picker",
    "app_folder_picker", "app_file_picker", "manual_input", "enter_directory_path",
    "empty_directory_input", "invalid_directory", "path", "select_book_folder",
    "add_to_bookshelf", "chinese_converter", "loading", "help", "share",
    "restore", "webdav_after_local_restore_confirm", "privacy_policy", "agree", "refuse",
    "set_local_password", "set_local_password_summary",
    // ---- 快捷方式 / 书架 ----
    "bookshelf", "last_read", "home", "my", "discovery", "bottom_nav_config", "reset",
    "bookshelf_layout", "group_style", "explore_style", "show_unread",
    "bookshelf_show_group_count", "fixed_width_mode", "view", "column_count",
    "bookshelf_list_show_kind", "bookshelf_list_show_intro", "bookshelf_list_intro_lines",
    "show_last_update_time", "grid_width_dp", "sort", "bookshelf_px_0", "bookshelf_px_1",
    "bookshelf_px_2", "bookshelf_px_3", "bookshelf_px_4", "bookshelf_px_5",
    // ---- 导入/导出对话框 ----
    "import_file_name", "import_book_source", "import_replace_rule", "import_dict_rule",
    "import_tts", "import_txt_toc_rule", "import_theme", "wrong_format",
    "diy_edit_source_group", "diy_edit_source_group_title", "diy_source_group",
    "add_group", "remove_group", "group_name", "keep_original_name", "keep_group",
    "keep_enable", "select_new_source", "select_update_source", "custom_group_summary",
    "export_config", "export_file_name", "export_type", "export_charset",
    "export_no_chapter_name", "select_section_export", "export_all", "custom_export",
    "result_analyzed", "file_contains_number", "export_chapter_index", "error_scope_input",
    "ok", "yes", "no", "search_book_key", "check_source_config", "share_selected_source",
    "clear_webview_data_success", "unsupport_archivefile_entry", "start", "end",
    "file_not_supported", "draw", "sure_del", "no_book_found_bookshelf",
    "confirm_delete_review", "review", "review_post_hint", "reply_review",
    "review_replies_detail_title", "review_replies_section_title", "review_list_section_title",
    "set_book_variable", "set_source_variable", "variable_comment", "open_release_page",
    "delete", "add", "assists_key_config", "create_folder", "folder_chooser",
    "file_chooser", "empty", "default_cover", "night", "day", "change_cover_source",
    "refresh", "bg_image", "welcome", "theme_name", "accent", "background_color",
    "navbar_color", "background_image", "select_image", "background_image_blurring",
    "day_background_too_dark", "night_background_too_light", "customize_day_theme",
    "customize_night_theme", "new_theme", "theme_customize_title", "default_day_theme",
    "default_night_theme", "open_sys_dir_picker_error", "login_header",
    "login_url", "login_ui", "login_check_js", "source_http_header", "name",
    "concurrent_rate", "book_source", "all", "local", "no_group", "update_book_fail",
    "intro_show_null", "verification_code", "more_menu", "disable_source", "delete_source",
    "hide_when_status_bar_show", "show", "hide",
)

/** 同步上下文 string-array key (ReadTipConfig 等)。 */
private val warmArrayKeys = listOf("chinese_mode", "read_tip", "tip_color", "tip_divider_color")
