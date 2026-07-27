package io.legado.app.model.script

import io.legado.app.help.image.IosImageOps

/**
 * iOS 端 JS 引擎注册入口 (薄壳, 委托 nativeMain 共享函数)。
 *
 * 详见 nativeMain [registerNativeJsEngines] 与 [registerNativeJsEngineProvider]。
 */
fun registerIosJsEngines() {
    registerNativeJsEngines(IosImageOps)
}
