package io.legado.app.help.toast

import io.legado.app.napi.OhosNativeBridge

/**
 * [Toaster] 的鸿蒙 (OHOS) 实现 (KP7+ 已真实化)。
 *
 * # 实现方式: napi_threadsafe_function 桥接
 * 鸿蒙 `@ohos.promptAction.showToast` 仅提供 ArkTS API, 无 NDK C 接口;
 * Kotlin/Native 无法直接调用, 通过 [OhosNativeBridge] 反向 napi 桥接 (Kotlin → ArkTS),
 * 用 threadsafe_function 把 KMP 业务线程的调用调度到 ArkTS 主线程执行。
 *
 * # 调用链
 * `KMP 业务代码` → `Toasters.get().toast(msg)` → [OhosToaster.toast] →
 * [OhosNativeBridge.showToast] → (JSON 序列化) → `toastTsfn(json)` →
 * (legado_napi.cpp threadsafe_function 跨线程调度) →
 * `EntryAbility.ets 回调` → `promptAction.showToast({ message, duration })`
 *
 * # 降级策略
 * [OhosNativeBridge] 未注册 tsfn 时 (当前阶段 cinterop 未接入 / EntryAbility 未调注册)
 * 内部降级为 println 输出到 stdout (鸿蒙 runtime 重定向到 hilog, DevEco Log 可见)。
 *
 * # duration 对齐
 * - [toast] 传 2000ms (对齐 Android Toast.LENGTH_SHORT)
 * - [toastLong] 传 3500ms (对齐 Android Toast.LENGTH_LONG, 鸿蒙无独立 long API)
 *
 * 降级策略对齐 desktop 端 [io.legado.app.help.toast.DesktopToaster] 无 SystemTray 时退化为 println;
 * iOS 端用 NSLog 降级 (鸿蒙无 NSLog 等价物, 用 println 兜底)。
 * 模式参考 `registerAndroidMediaNotificationProvider`。
 */
class OhosToaster : Toaster {

    override fun toast(message: String) {
        OhosNativeBridge.showToast(message, 2000)
    }

    override fun toastLong(message: String) {
        // toastLong 无独立 API, duration 传 3500ms 区分 (对齐 Android Toast.LENGTH_LONG)
        OhosNativeBridge.showToast(message, 3500)
    }
}

/**
 * 鸿蒙宿主启动早期注册 [Toaster] 的实现。
 *
 * 调用时机: 鸿蒙 app 启动早期, 在任何 commonMain 代码调用 `Toasters.get()` 之前。
 * 真实 tsfn 注入由 EntryAbility.onCreate 调 `legado.registerToastCallback` 完成,
 * 此处仅注册 [OhosToaster] (内部走 [OhosNativeBridge], 未注入 tsfn 时降级 println)。
 *
 * 模式参考 `registerAndroidMediaNotificationProvider`。
 */
fun registerOhosToaster() {
    Toasters.register(OhosToaster())
}
