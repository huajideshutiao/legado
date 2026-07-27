package io.legado.app.model.script

import io.legado.app.help.image.ImageOps

/**
 * Native (iOS/ohos) 端 JS 引擎注册共享入口。
 *
 * 抽自 iosMain RegisterIosJsEngines / ohosMain RegisterOhosJsEngines,
 * 两端仅 imageOps 参数不同 (IosImageOps / OhosImageOps), 其余注册逻辑完全一致。
 *
 * 调用方: registerIosJsEngines(IosImageOps) / registerOhosJsEngines(OhosImageOps)
 */
fun registerNativeJsEngines(imageOps: ImageOps) {
    // 1. 注册 image 实现到 JsBindingInjector (JsBindings 构造时访问, 必须先注册)
    JsBindingInjector.registerImageOps(imageOps)

    // 2. 注册 NativeJsEngine 到 JsEngines 作为 QUICKJS 引擎实现
    // (JS 引擎注册逻辑已下沉到 nativeMain registerNativeJsEngineProvider, iOS/鸿蒙共用)
    registerNativeJsEngineProvider()
}
