package io.legado.app.help.notification

/**
 * 进度通知抽象 (shared commonMain)。
 *
 * # 背景
 * 安卓端用 `NotificationCompat.Builder` + `setProgress` / `setLiveProgress` 显示
 * 下载/缓存进度通知, 依赖 `androidx.core.app.NotificationManagerCompat` + `appCtx`。
 * 桌面 JVM 端用 `SystemTray` 显示或 println 到 stdout; iOS/鸿蒙暂留 stub。
 *
 * # 设计
 * - [showProgress] 单次显示/更新一个进度通知 (调用方负责节流)
 * - [cancel] 取消当前通知 (对应 `NotificationManager.cancel`)
 * - 通知 ID 由实现内部管理 (与 app 端 `NotificationId.CacheBookService` 等对应)
 *
 * 模式参考 [io.legado.app.help.config.AppConfigProviders]。
 */
interface NotificationProgress {

    /**
     * 显示/更新进度通知。
     *
     * @param title 通知标题
     * @param content 通知正文
     * @param progress 当前进度 (已完成数量, 0..max)
     * @param max 最大进度 (总数); <=0 视为不确定进度
     */
    fun showProgress(title: String, content: String, progress: Int, max: Int)

    /** 取消当前进度通知。 */
    fun cancel()
}

/**
 * [NotificationProgress] 容器 (provider 注入模式)。
 *
 * 宿主启动早期注册一次 (App.onCreate / desktop main), shared 内通过 [get] 获取。
 * 未注册时调用 [get] 抛 [IllegalStateException]。
 *
 * 安卓端调用 `registerAndroidNotificationProgress(context)` (见 androidMain),
 * 桌面端调用 `registerDesktopNotificationProgress()` (见 jvmMain)。
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
        impl ?: error("NotificationProgresses not registered; call registerAndroidNotificationProgress() or registerDesktopNotificationProgress() first")

    /** 仅测试场景: 清空注册 (生产代码勿调用)。 */
    fun reset() {
        impl = null
    }
}
