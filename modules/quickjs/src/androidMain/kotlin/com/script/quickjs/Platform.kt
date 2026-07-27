package com.script.quickjs

import android.util.Log

/**
 * Android 平台 actual 实现: 复用 android.util.Log + System.loadLibrary。
 *
 * 不改变 Android 端现有行为, 与改造前完全等价。
 */
actual fun logQuickJsError(tag: String, msg: String, e: Throwable?) {
    Log.e(tag, msg, e)
}

actual fun logQuickJsWarn(tag: String, msg: String, e: Throwable?) {
    Log.w(tag, msg, e)
}

actual fun loadLegadoQuickJsNative() {
    System.loadLibrary("legado_quickjs")
}
