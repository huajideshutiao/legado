package io.legado.app.constant

import kotlin.concurrent.Volatile

/**
 * androidId 由宿主启动时注入(App.onCreate → registerAndroidJsEngines)。
 * shared 不触平台 API；未注入时返回 "null"，与原 Settings 缺省语义一致。
 */
object AndroidIdHolder {
    @Volatile
    var value: String = "null"
}

val AppConst.androidId: String get() = AndroidIdHolder.value
