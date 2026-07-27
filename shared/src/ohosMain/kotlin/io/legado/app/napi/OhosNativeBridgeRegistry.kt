package io.legado.app.napi

/**
 * 鸿蒙 napi 桥接注册入口 (KP7+)。
 *
 * 在 [io.legado.app.help.config.registerOhosProviders] 中调用, 初始化 [OhosNativeBridge]
 * (当前为空操作: OhosNativeBridge 是 object, 默认 tsfn 为 null, 走 println 降级)。
 *
 * 真实注入时机: ArkTS EntryAbility.onCreate 调用 `legado.registerToastCallback` /
 * `legado.registerNotificationCallback` (legado_napi.cpp) 后, C++ 侧创建
 * napi_threadsafe_function 并通过 @CName 注入口调用 [OhosNativeBridge.registerToastFn] /
 * [OhosNativeBridge.registerNotificationFn], 此后 tsfn 才真正注入。
 *
 * 模式参考 [io.legado.app.help.config.registerOhosProviders] 中其余 registerOhosXxx 函数。
 */
fun registerOhosNativeBridge() {
    // 当前为空操作: OhosNativeBridge 默认 tsfn 为 null (降级 println)。
    // 等 legado_napi.cpp 实现 registerToastCallback / registerNotificationCallback 后,
    // 由 ArkTS EntryAbility.onCreate 调用 napi 方法注入 tsfn, 无需在此处主动初始化。
    // 保留此函数作为注册序列占位, 便于后续扩展 (如 module 卸载时释放 tsfn 的清理逻辑)。
}
