package io.legado.app.model.script

/**
 * nativeMain JS 引擎注册公共逻辑 (iOS/鸿蒙共用)。
 *
 * 原 iosMain / ohosMain 各自入口中 `JsEngines.registerProvider` 部分逻辑完全一致
 * (注册 [NativeJsEngine] 作为 QUICKJS 引擎), 下沉到 nativeMain 共用。
 *
 * # 调用时机
 * 必须在任何 `JsEngine.eval` / `JsBindings()` 构造之前调用一次, 否则:
 * - 未注册 [JsEngines] provider → JsEngines.get() 抛 IllegalStateException("JsEngineProvider 未注册")
 *
 * # 与平台入口的关系
 * 两端入口已合并为 nativeMain 的 [registerNativeJsEngines] (平台 ImageOps 当参数传入),
 * 各端 provider 注册序列直接调 `registerNativeJsEngines(IosImageOps / OhosImageOps)`。
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
