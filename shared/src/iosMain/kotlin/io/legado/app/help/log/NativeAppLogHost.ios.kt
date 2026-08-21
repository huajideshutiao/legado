package io.legado.app.help.log

import io.legado.app.help.toast.Toasters

/** iOS: 复用已注册的 IosToaster (含防重入与 NSLog 兜底); 本 host 注册更早, 未就绪时静默。 */
internal actual fun nativeAppLogToast(message: String) {
    runCatching { Toasters.get().toast(message) }
}
