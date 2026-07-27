package io.legado.app.model.script

/**
 * nativeMain JS 引擎注册公共逻辑 (iOS/鸿蒙共用)。
 *
 * 原 iosMain [registerIosJsEngines] / ohosMain [registerOhosJsEngines] 中
 * `JsEngines.registerProvider` 部分逻辑完全一致 (注册 [NativeJsEngine] 作为 QUICKJS 引擎),
 * 下沉到 nativeMain 共用, ios/ohos 入口函数保留 (各自注册平台 ImageOps 后调用本函数)。
 *
 * # 调用时机
 * 必须在任何 `JsEngine.eval` / `JsBindings()` 构造之前调用一次, 否则:
 * - 未注册 [JsEngines] provider → JsEngines.get() 抛 IllegalStateException("JsEngineProvider 未注册")
 *
 * # 与平台入口函数的关系
 * ```
 * // iosMain RegisterIosJsEngines.kt
 * fun registerIosJsEngines() {
 *     JsBindingInjector.registerImageOps(IosImageOps)  // 平台特有
 *     registerNativeJsEngineProvider()                 // nativeMain 公共
 * }
 *
 * // ohosMain RegisterOhosJsEngines.kt
 * fun registerOhosJsEngines() {
 *     JsBindingInjector.registerImageOps(OhosImageOps) // 平台特有
 *     registerNativeJsEngineProvider()                  // nativeMain 公共
 * }
 * ```
 */
fun registerNativeJsEngineProvider() {
    // 注册 NativeJsEngine 到 JsEngines 作为 QUICKJS 引擎实现
    // (JsEngines.type 硬编码 QUICKJS, rhino 已弃用, 切换入口已撤)
    JsEngines.registerProvider { type ->
        when (type) {
            JsEngineType.QUICKJS -> NativeJsEngine
            else -> error("rhino 已弃用,JsEngines.type 固定 QUICKJS,不应到达 type=$type")
        }
    }
}
