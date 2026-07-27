package io.legado.app.ui.platform

import android.content.Context

/**
 * [stringRes] 的 Android actual 实现。
 *
 * # 设计要点
 * - shared androidMain 不依赖 splitties, 通过 holder 模式持有 [Context]
 *   (app 端注册时传 `appCtx`, 见 [registerSharedAppContext])
 * - 注册时机: App.onCreate, 在任何 commonMain 代码调用 [stringRes] 之前
 * - 未注册或 context 为 null 时返回空串, 避免崩溃
 *
 * 模式参考 `Toaster.android.kt` 的 `registerAndroidToaster`。
 */

/** ApplicationContext holder, 由 [registerSharedAppContext] 注入。 */
@Volatile
private var context: Context? = null

/**
 * 安卓宿主启动早期注册 ApplicationContext。
 *
 * 调用时机: App.onCreate, 在任何 commonMain 代码调用 `stringRes(...)` 之前。
 *
 * @param ctx 任意 Context (推荐传 `appCtx`), 内部只用其 applicationContext
 */
fun registerSharedAppContext(ctx: Context) {
    context = ctx.applicationContext
}

actual fun stringRes(resId: Int): String {
    return context?.getString(resId) ?: ""
}
