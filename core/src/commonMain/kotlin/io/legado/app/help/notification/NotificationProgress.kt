package io.legado.app.help.notification

import kotlin.concurrent.Volatile

/**
 * 进度通知抽象 (shared commonMain)。
 *
 * # 背景
 * 安卓端用 `NotificationCompat.Builder` + `setProgress` / `setLiveProgress` 显示
 * 下载/缓存进度通知, 依赖 `androidx.core.app.NotificationManagerCompat` + `appCtx`。
 * 桌面 JVM 端用 `SystemTray` 显示或退化为 AppLog 记账; iOS 端用 UNUserNotificationCenter
 * 本地通知, 鸿蒙端经 napi 桥接 `notificationManager`。
 *
 * # 设计
 * - [showProgress] 单次显示/更新一个进度通知 (调用方负责节流)
 * - [cancel] 取消指定通知 (对应 `NotificationManager.cancel(id)`)
 * - 通知标识由调用方传入 ([io.legado.app.constant.NotificationId] 常量), 同一标识共用一条通知;
 *   实现不得自持业务标识 (同一接口并存多条业务进度时才能各占一条、互不覆盖/互不取消)
 *
 * 模式参考 [io.legado.app.help.config.AppConfigProviders]。
 */
interface NotificationProgress {

    /**
     * 显示/更新进度通知。
     *
     * @param id 通知标识 ([io.legado.app.constant.NotificationId] 常量)
     * @param title 通知标题
     * @param content 通知正文
     * @param progress 当前进度 (已完成数量, 0..max)
     * @param max 最大进度 (总数); <=0 视为不确定进度
     */
    fun showProgress(id: Int, title: String, content: String, progress: Int, max: Int)

    /** 取消 [id] 标识的进度通知。 */
    fun cancel(id: Int)
}

/**
 * [NotificationProgress] 容器 (provider 注入模式)。
 *
 * 宿主启动早期注册一次 (desktop main / iOS·鸿蒙宿主启动), shared 内通过 [get] 获取。
 * 未注册时调用 [get] 抛 [IllegalStateException]。
 *
 * 桌面端调用 `registerDesktopNotificationProgress()` (见 jvmMain), iOS 端调用
 * `registerIosNotificationProgress()` / 鸿蒙端调用 `registerOhosNotificationProgress()`。
 * 安卓端不注册本 provider: 更新进度通知由 `AndroidUpdateBookCallback` 直连
 * `NotificationManagerCompat` 走前台服务。
 */
object NotificationProgresses {

    @Volatile
    private var impl: NotificationProgress? = null

    /** 宿主启动早期注册一次 (任何进度通知调用之前)。 */
    fun register(impl: NotificationProgress) {
        this.impl = impl
    }

    /** 获取已注册实现, 未注册抛出 IllegalStateException。 */
    fun get(): NotificationProgress =
        impl ?: error("NotificationProgresses not registered; call the host's register*NotificationProgress() first")

    /** 仅测试场景: 清空注册 (生产代码勿调用)。 */
    fun reset() {
        impl = null
    }
}
