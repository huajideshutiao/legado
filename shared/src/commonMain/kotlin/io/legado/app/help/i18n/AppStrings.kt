package io.legado.app.help.i18n

import kotlin.concurrent.Volatile

/**
 * 非 UI 层字符串通道 (KJ3 方案 b)。model/help 的异常/报错文案统一走 appString 取本地化字符串,
 * key 与 R.string 资源名一一对应; 本文件零 Android 依赖, 已下沉 commonMain。
 *
 * 取值走「provider 注册」而非 expect/actual: appString 的平台差异只在「谁提供 key→本地化串的映射」
 * (安卓=R.string+appCtx.getString), 取值逻辑本身无平台 API 差异, 是依赖注入而非编译期分叉。
 * 故 shared 只留注入点, 宿主启动时 registerAppStringProvider 注册映射(见 app 侧 AppStringsAndroid.kt);
 * 未注册时 fallback 返回 key 名, 运行期安全不崩。新平台(iOS/鸿蒙)注册各自 provider 即可, 无需补 actual。
 */
@Suppress("EnumEntryName")
enum class AppStringKey {
    // webBook/fileBook 抓书异常
    error_get_web_content,
    chapter_list_empty,
    unsupport_archivefile_entry,

    // ImageProvider 图片解码
    error_decode_bitmap,
    error_image_url_empty,

    // SearchScope 显示 "全部书源"
    all_source,

    // CheckSource 校验配置摘要
    search,
    discovery,
    source_tab_info,
    chapter_list,
    main_body,
    check_source_config_summary,

    // AudioPlay 加载占位标题
    data_loading,

    // AppWebDav 授权失败
    webdav_application_authorization_error,

    // ReplaceRule 校验失败
    replace_rule_invalid,

    // BackupConfig 备份忽略项标题 (与 app 端 R.string 一一对应)
    read_config,
    theme_mode,
    theme_config,
    cover_config,
    bookshelf_layout,
    thread_count,
    local_book,
}

/**
 * key→本地化字符串的映射提供者, 由宿主平台注册。args 对应资源里的 %s/%1$s 等格式化参数。
 */
fun interface AppStringProvider {
    fun getString(key: AppStringKey, args: Array<out Any?>): String
}

@Volatile
private var appStringProvider: AppStringProvider? = null

/**
 * 宿主启动早期注册一次(任何 appString 调用之前)。
 */
fun registerAppStringProvider(provider: AppStringProvider) {
    appStringProvider = provider
}

/**
 * 取 key 对应的本地化字符串, args 为格式化参数(对应资源里的 %s/%1$s 等)。
 * provider 未注册时 fallback 返回 key 名(仅可能出现在无宿主的测试/工具场景)。
 */
fun appString(key: AppStringKey, vararg args: Any?): String {
    return appStringProvider?.getString(key, args) ?: key.name
}
