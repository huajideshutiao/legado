package io.legado.app.help.log

import io.legado.app.napi.OhosNativeBridge

/** 鸿蒙: 直接走 toast napi 桥 (就绪时真实 toast; 未就绪 OhosNativeBridge 内部降级 println)。 */
internal actual fun nativeAppLogToast(message: String) {
    OhosNativeBridge.showToast(message, 2000)
}
