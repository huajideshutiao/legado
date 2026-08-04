package io.legado.app.help.i18n

import io.legado.app.R
import splitties.init.appCtx

/**
 * appString 的安卓映射: key→R.string 后转发 appCtx.getString, 复用现有 strings.xml 多语言资产。
 * shared 侧 appString 走 provider 注册通道(非 expect/actual), 宿主启动时调 registerAndroidAppStringProvider。
 */
private val androidAppStringProvider = AppStringProvider { key, args ->
    val resId = key.resId
    // 无参走非格式化取值, 与原 getString(resId) 行为一致(避免对含 % 的文案强行 format)
    if (args.isEmpty()) {
        appCtx.getString(resId)
    } else {
        appCtx.getString(resId, *args)
    }
}

/** 宿主启动早期注册一次(App.onCreate)。 */
fun registerAndroidAppStringProvider() {
    registerAppStringProvider(androidAppStringProvider)
}

private val AppStringKey.resId: Int
    get() = when (this) {
        AppStringKey.error_get_web_content -> R.string.error_get_web_content
        AppStringKey.chapter_list_empty -> R.string.chapter_list_empty
        AppStringKey.unsupport_archivefile_entry -> R.string.unsupport_archivefile_entry
        AppStringKey.error_decode_bitmap -> R.string.error_decode_bitmap
        AppStringKey.error_image_url_empty -> R.string.error_image_url_empty
        AppStringKey.all_source -> R.string.all_source
        AppStringKey.search -> R.string.search
        AppStringKey.discovery -> R.string.discovery
        AppStringKey.source_tab_info -> R.string.source_tab_info
        AppStringKey.chapter_list -> R.string.chapter_list
        AppStringKey.main_body -> R.string.main_body
        AppStringKey.check_source_config_summary -> R.string.check_source_config_summary
        AppStringKey.data_loading -> R.string.data_loading
        AppStringKey.toc_updateing -> R.string.toc_updateing
        AppStringKey.error_load_toc -> R.string.error_load_toc
        AppStringKey.webdav_application_authorization_error -> R.string.webdav_application_authorization_error
        AppStringKey.replace_rule_invalid -> R.string.replace_rule_invalid
        AppStringKey.read_config -> R.string.read_config
        AppStringKey.theme_mode -> R.string.theme_mode
        AppStringKey.theme_config -> R.string.theme_config
        AppStringKey.cover_config -> R.string.cover_config
        AppStringKey.bookshelf_layout -> R.string.bookshelf_layout
        AppStringKey.thread_count -> R.string.thread_count
        AppStringKey.local_book -> R.string.local_book
        AppStringKey.force_refresh_book -> R.string.force_refresh_book
        AppStringKey.update_toc -> R.string.update_toc
    }
