package io.legado.app.help.i18n

import io.legado.app.help.coroutine.Coroutine
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
 * ([findStringResource] → [getString]), 与 desktop 端 Main.kt 内联注册的 jvmGetString
 * 同构 (runBlocking 桥接 suspend 取值)。
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
    // 后台预热: 冷启动同步段 runBlocking 预读 ~250 条会被 assets IO 阻塞主线程;
    // 预热只是加速 (androidAppString 首次未命中仍同步 runBlocking 兜底读取),
    // 正确性不依赖预热的完成时机
    val keys = AppStringKey.entries.map { it.name } + warmKeys
    Coroutine.async {
        for (key in keys) {
            findStringResource(key)?.let { getString(it) }
        }
        for (key in warmArrayKeys) {
            findStringArrayResource(key)?.let { getStringArray(it) }
        }
    }
}

/** 启动期同步上下文关键 key (AppStringKey 之外)。仅保留首屏/前台/渠道初始化依赖项。 */
private val warmKeys = listOf(
    // ---- 通知渠道 / 前台服务 ----
    "action_download", "read_aloud", "web_service", "cannot_empty",
    // ---- 快捷方式 ----
    "bookshelf", "last_read",
    // ---- 启动协议 / 更新 / 退出 ----
    "privacy_policy", "agree", "refuse", "double_click_exit",
    "is_latest_version", "check_update", "restore", "webdav_after_local_restore_confirm",
    // ---- 核心权限提示与通用对话框 ----
    "notification_permission_rationale", "ignore_battery_permission_rationale",
    "dialog_title", "dialog_setting", "dialog_cancel", "cancel", "ok",
)

/** 同步上下文 string-array key (ReadTipConfig 等)。 */
private val warmArrayKeys = listOf("chinese_mode", "read_tip", "tip_color", "tip_divider_color")
