package io.legado.app.ui.about

import io.legado.app.help.crash.CrashMarkerProviders
import io.legado.app.ui.compose.platform.syncGetString
import io.legado.app.ui.root.AppNavigatorProviders
import io.legado.app.ui.root.AppOverlay

/** 崩溃提示 Overlay 的 key (内容见 [CrashNoticeDialog])。 */
internal const val CRASH_NOTICE_OVERLAY_KEY = "crash_notice"

/** 崩溃提示正文 (原版 `MainActivity.notifyAppCrash` 的硬编码文案, 四端逐字一致)。 */
private const val CRASH_NOTICE_MESSAGE = "检测到阅读发生了崩溃，是否打开崩溃日志以便报告问题？"

/**
 * 崩溃后重启提示 (对照原版 `MainActivity.notifyAppCrash`, 四端共用)。
 *
 * 原版行为逐字对应:
 * - 读 `LocalConfig.appCrash` (写方是各端崩溃处理器) 与 DEBUG 判定, 任一不满足直接返回
 * - 命中则**先清标记**, 再弹确认框: 标题 `R.string.draw`(提醒)、正文为原版硬编码文案,
 *   "是" 打开崩溃日志列表, "否" 仅关闭
 *
 * 非挂起、展示即返回、不等待关闭: 原版 `alert(...)` 同样是展示即返回, 启动序列随后继续。
 * 调用点位置见各端入口 (原版排在隐私协议/版本更新之后、备份同步之前)。
 *
 * 未注册 navigator 时直接返回: 本函数可能在首帧组合前后被调用, 拿不到导航栈就没有可展示的
 * 位置; 标记已在上面消费, 与原版"提示过就不再提示"一致。
 *
 * @param isDebug 当前是否 DEBUG 构建 (原版 `BuildConfig.DEBUG`; 桌面取 `legado.debug` 属性,
 *   iOS/鸿蒙无构建类型感知传 false, 与各自启动序列其余 DEBUG 门同一口径)
 */
fun notifyAppCrash(isDebug: Boolean) {
    if (isDebug) return
    if (!CrashMarkerProviders.get().consumePending()) return
    val navigator = AppNavigatorProviders.getOrNull() ?: return
    navigator.showOverlay(
        AppOverlay.Dialog(CRASH_NOTICE_OVERLAY_KEY, payload = syncGetString("draw"))
    )
}

/** 崩溃提示正文 (供 Overlay 宿主渲染, 与 [notifyAppCrash] 同一份字面量)。 */
internal fun crashNoticeMessage(): String = CRASH_NOTICE_MESSAGE
