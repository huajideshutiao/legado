package io.legado.app.help.toast

import kotlin.concurrent.Volatile

/**
 * Toast 提示抽象 (shared commonMain)。
 *
 * # 背景
 * 安卓端 `Context.toastOnUi` 依赖 `android.widget.Toast` + `appCtx`, 不能下沉
 * commonMain。桌面 JVM 端用 `java.awt.SystemTray` 显示通知, iOS 端用 UIAlertController,
 * 鸿蒙端经 napi 桥接 `promptAction.showToast`。
 * 通过本接口在 commonMain 解耦, 各端注册 actual 实现。
 *
 * # 设计
 * - `resId` 用 String 而非 Int: Android 资源 ID 平台特定, commonMain 不可见
 *   安卓端实现可自行从资源名解析, 桌面端直接当字符串显示
 * - 调用线程不限: 安卓实现内部切到主线程, 桌面端在 AWT 事件分发线程显示
 *
 * 模式参考 [io.legado.app.help.config.AppConfigProviders]。
 */
interface Toaster {

    /** 短时间显示 [message]。 */
    fun toast(message: String)

    /** 长时间显示 [message]。 */
    fun toastLong(message: String)
}

/**
 * [Toaster] 容器 (provider 注入模式)。
 *
 * 宿主启动早期注册一次 (App.onCreate / desktop main), shared 内通过 [get] 获取。
 * 未注册时调用 [get] 抛 [IllegalStateException], 帮助早期发现初始化遗漏。
 *
 * 安卓端调用 `registerAndroidToaster(appCtx)` (见 androidMain),
 * 桌面端调用 `registerDesktopToaster()` (见 jvmMain)。
 */
object Toasters {

    @Volatile
    private var impl: Toaster? = null

    /** 宿主启动早期注册一次 (任何 toast 调用之前)。 */
    fun register(impl: Toaster) {
        this.impl = impl
    }

    /** 获取已注册实现, 未注册抛出 IllegalStateException。 */
    fun get(): Toaster =
        impl ?: error("Toasters not registered; call registerAndroidToaster() or registerDesktopToaster() first")

    /** 仅测试场景: 清空注册 (生产代码勿调用)。 */
    fun reset() {
        impl = null
    }
}
