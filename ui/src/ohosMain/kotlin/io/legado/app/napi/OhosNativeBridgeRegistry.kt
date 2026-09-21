package io.legado.app.napi

/**
 * 鸿蒙 napi 桥接注册入口 (KP7+)。
 *
 * 在 [io.legado.app.help.config.registerOhosProviders] 中调用, 初始化 [OhosNativeBridge]
 * (当前为空操作: OhosNativeBridge 是 object, 默认 tsfn 为 null, 走记日志降级)。
 *
 * 真实注入时机: ArkTS EntryAbility.onCreate 调用 `legado.registerToastCallback` /
 * `legado.registerNotificationCallback` (legado_napi.cpp) 后, C++ 侧创建
 * napi_threadsafe_function 并通过 @CName 注入口调用 [OhosNativeBridge.registerToastFn] /
 * [OhosNativeBridge.registerNotificationFn], 此后 tsfn 才真正注入。
 *
 * 模式参考 [io.legado.app.help.config.registerOhosProviders] 中其余 registerOhosXxx 函数。
 */
fun registerOhosNativeBridge() {
    // OhosNativeBridge 下沉到 :foundation 后不再持有 ui 层的 OhosPlatformEventChannel,
    // 此处把统一平台事件通道接回 (ArkTS → Kotlin 事件分发)。
    OhosNativeBridge.registerPlatformEventChannel(OhosNativeBridge.PlatformEventChannel {
        OhosPlatformEventChannel.onEvent(it)
    })
}
