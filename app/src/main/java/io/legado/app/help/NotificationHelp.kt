package io.legado.app.help

import android.app.Notification
import android.os.Build
import androidx.annotation.ColorInt
import androidx.core.app.NotificationCompat
import io.legado.app.BuildConfig
import io.legado.app.utils.LogUtils
import splitties.systemservices.notificationManager

/**
 * 通知工具: 统一给长耗时前台任务套上 Android 16 "实时更新 (Live Update)" 能力。
 *
 * 对齐 Google 官方 sample (AOSP platform-samples/user-interface/live-updates) 的最小有效集:
 * - manifest 声明 `android.permission.POST_PROMOTED_NOTIFICATIONS` (否则系统不视为可上岛应用,
 *   设置页也不会出现"实况通知"开关项, `setRequestPromotedOngoing` 形同没调)
 * - 调 [NotificationCompat.Builder.setRequestPromotedOngoing] true
 * - 挂 [NotificationCompat.ProgressStyle] 样式 (确定进度时)
 * - 可选挂 [NotificationCompat.Builder.setShortCriticalText] 提供折叠态胶囊文案
 *
 * Google sample 刻意 **不调** `setColorized` / `setColor`, channel importance 也只是 DEFAULT。
 * 故本工具一律不主动染色, 避免把整张通知背景染色这种视觉过重的副作用。
 *
 * androidx.core 1.18.0 字节码已确认 ProgressStyle / setRequestPromotedOngoing /
 * setShortCriticalText 都存在 (1.17.0 引入). API < 36 上述方法均为无副作用 no-op,
 * 但仍按 [Build.VERSION_CODES.BAKLAVA] 显式门控 ProgressStyle 挂载, 确保旧版本只走经典进度条。
 */

/** Android 16 起支持原生实时更新 */
private val liveUpdateSupported: Boolean
    get() = Build.VERSION.SDK_INT >= Build.VERSION_CODES.BAKLAVA

/**
 * 把一个"有明确进度"的前台通知升级为实时更新通知。
 *
 * - 全版本: 设置经典进度条 (也是 API < 36 的唯一渲染来源, 以及不确定态的兜底)。
 * - API 36+: 额外挂载 [NotificationCompat.ProgressStyle], 请求提升为 promotedOngoing,
 *   并设置折叠态状态栏胶囊文案 [shortText]; 系统据此把通知顶置展示, 厂商灵动岛也据此上岛。
 *
 * @param current   已完成数量 (会被 coerce 到 [0, total])
 * @param total     总数量; <= 0 视为不确定进度 (此时不挂 ProgressStyle, 仅经典转圈)
 * @param shortText 折叠态胶囊文案 (越短越好, 如 "5/20"), 仅 API 36+ 生效
 */
fun NotificationCompat.Builder.setLiveProgress(
    current: Int,
    total: Int,
    shortText: String? = null,
): NotificationCompat.Builder {
    val determinate = total > 0 && current >= 0
    val safeCurrent = if (determinate) current.coerceIn(0, total) else 0
    if (liveUpdateSupported) {
        // API 36+: 全程用官方 ProgressStyle, 不确定态走 setProgressIndeterminate(true);
        // 不降级到经典 setProgress —— 后者在 LiveUpdate 胶囊里 HyperOS/灵动岛
        // 渲染不出标准进度态, 会显示成一串怪条 (与 Google sample INITIALIZING 状态一致的写法).
        val style = NotificationCompat.ProgressStyle()
        if (determinate) {
            // 单段 = total 即可表达 current/total; styledByProgress 让系统按进度自动着色。
            style.setStyledByProgress(true)
                .setProgress(safeCurrent)
                .addProgressSegment(NotificationCompat.ProgressStyle.Segment(total))
        } else {
            style.setProgressIndeterminate(true)
        }
        setStyle(style)
    } else {
        // API < 36: 唯一可用的进度渲染。
        setStyle(null)
        setProgress(if (determinate) total else 0, safeCurrent, !determinate)
    }
    if (liveUpdateSupported) {
        // 与 Google 官方 platform-samples/user-interface/live-updates 完全一致的最小信号集:
        // - setRequestPromotedOngoing(true)
        // - setStyle(ProgressStyle)
        // - setOngoing(true) 由调用方负责
        // 官方 sample 不调 setColorized/setColor, 也不要求 channel 解除静音 (importance default 即可)。
        setRequestPromotedOngoing(true)
        shortText?.let { setShortCriticalText(it) }
    }
    return this
}

/**
 * 把一个"无明确进度"的长任务通知 (如 Web 服务运行中) 标记为实时进行中。
 * 仅请求 promotedOngoing + 设置胶囊文案, 不带进度条。API < 36 为 no-op。
 */
@Suppress("unused")
fun NotificationCompat.Builder.setLiveOngoing(
    @ColorInt color: Int? = null,
    shortText: String? = null,
): NotificationCompat.Builder {
    if (liveUpdateSupported) {
        setRequestPromotedOngoing(true)
        shortText?.let { setShortCriticalText(it) }
    }
    return this
}

object NotificationHelp {

    private const val TAG = "NotificationHelp"

    /**
     * 仅 DEBUG 构建下记录三项 LiveUpdate 诊断信号 (与 Google 官方 sample 一致):
     * - canPostPromotedNotifications: 用户是否已在系统设置内允许本应用发布实况通知;
     *   false 时即使 hasPromotableCharacteristics 为 true, 系统也不会上岛。
     *   该值为 false 的常见原因: manifest 未声明 POST_PROMOTED_NOTIFICATIONS, 或
     *   用户在系统通知设置内手动关闭, 或厂商 ROM 暂未实现该开关 UI。
     * - isRequestPromotedOngoing: 本次构建的通知是否调过 setRequestPromotedOngoing(true)。
     * - hasPromotableCharacteristics: 系统综合判定该通知是否具备被提升的资格。
     */
    fun logPromotable(notification: Notification) {
        if (!BuildConfig.DEBUG || !liveUpdateSupported) return
        LogUtils.d(TAG) {
            "canPostPromotedNotifications=${notificationManager.canPostPromotedNotifications()} " +
                "isRequestPromotedOngoing=${NotificationCompat.isRequestPromotedOngoing(notification)} " +
                "hasPromotableCharacteristics=${
                    NotificationCompat.hasPromotableCharacteristics(
                        notification
                    )
                }"
        }
    }
}
