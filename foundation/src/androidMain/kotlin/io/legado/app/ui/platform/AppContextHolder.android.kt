package io.legado.app.ui.platform

import android.content.Context

/**
 * Android ApplicationContext holder (从原 :shared 的 StringRes.android.kt 下沉)。
 *
 * 切分后 :foundation 的 androidMain 需要 Context (网络可用性检测等), 而原 holder 是
 * :shared 的 internal, 跨模块不可见, 故下沉为公开 API; 包名保持 io.legado.app.ui.platform,
 * app 端 App.onCreate 的注册调用与其余引用处无需改 import。
 */

/** ApplicationContext holder, 由 [registerSharedAppContext] 注入; 各模块 androidMain 内共用。 */
@Volatile
var sharedAppContext: Context? = null

/**
 * 安卓宿主启动早期注册 ApplicationContext。
 *
 * 调用时机: App.onCreate, 在任何 commonMain 代码调用 `stringRes(...)` 之前。
 *
 * @param ctx 任意 Context (推荐传 `appCtx`), 内部只用其 applicationContext
 */
fun registerSharedAppContext(ctx: Context) {
    sharedAppContext = ctx.applicationContext
}
