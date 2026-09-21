package io.legado.app.ui.platform

import android.content.Context

/**
 * [stringRes] 的 Android actual 实现。
 *
 * # 设计要点
 * - holder 定义下沉 :foundation (io.legado.app.ui.platform.AppContextHolder),
 *   app 端注册时传 `appCtx` (见 registerSharedAppContext)
 * - 未注册或 context 为 null 时返回空串, 避免崩溃
 */

actual fun stringRes(resId: Int): String {
    return sharedAppContext?.getString(resId) ?: ""
}
