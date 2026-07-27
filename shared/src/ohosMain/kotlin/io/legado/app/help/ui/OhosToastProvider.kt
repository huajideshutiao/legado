package io.legado.app.help.ui

import io.legado.app.napi.OhosNativeBridge

/**
 * [ToastProvider] 鸿蒙端实现。
 *
 * 经 [OhosNativeBridge.showToast] tsfn 桥接到 ArkTS promptAction.showToast
 * (KMP 无 ArkTS API 访问能力, 需 tsfn 跨线程 dispatch 到 ArkTS 主线程);
 * 未注册 tsfn 时由 [OhosNativeBridge] 降级 println (兼容 napi 未接入阶段, 保证 JS 调用链不崩)。
 * 在 [registerOhosProviders] 经 [registerOhosToastProvider] 注册到 [ToastProviders]。
 *
 * # 调用链
 * KMP 业务 (JsExtensionsCommon.toast) → [showToast] → [OhosNativeBridge.showToast] →
 * toastTsfn → C++ ohos_toast_dispatch → napi_call_threadsafe_function →
 * ArkTS ToastCallJs → SystemBridgeHandler.handleShowToast → promptAction.showToast。
 */
object OhosToastProviderImpl : ToastProvider {

    override fun showToast(msg: String, long: Boolean) {
        // 对齐 Android Toast 时长: short=2000ms, long=3500ms (与 OhosNativeBridge.ToastPayload durationMs 一致)
        val durationMs = if (long) 3500 else 2000
        OhosNativeBridge.showToast(msg, durationMs)
    }
}

/** 鸿蒙宿主启动早期注册一次, 任何 JsExtensionsCommon.toast 调用之前。 */
fun registerOhosToastProvider() {
    ToastProviders.register(OhosToastProviderImpl)
}
