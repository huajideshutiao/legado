package io.legado.app.web.utils

import android.content.Context
import io.legado.app.web.utils.WebStrings
import io.legado.app.web.utils.WebStringsProviders

/**
 * [WebStrings] 的 Android actual 实现。
 *
 * 用 Android [Context.getString] 读 R.string.cannot_empty (resId 由 app 端注入,
 * 避免 shared androidMain 反向依赖 app 模块的 R 资源)。
 *
 * @param context 任意 Context (推荐 appCtx)
 * @param cannotEmptyResId R.string.cannot_empty 的 resId (由 app 端传入)
 */
class AndroidWebStrings(
    private val context: Context,
    private val cannotEmptyResId: Int,
) : WebStrings {

    override val cannotEmpty: String
        get() = context.getString(cannotEmptyResId)
}

/**
 * 安卓宿主启动早期注册 [WebStrings] 的 actual 实现。
 *
 * @param context 任意 Context (推荐 appCtx)
 * @param cannotEmptyResId R.string.cannot_empty 的 resId (由 app 端 App.kt 传入)
 *
 * 模式参考 `registerAndroidServiceLauncher`。
 */
fun registerAndroidWebStrings(context: Context, cannotEmptyResId: Int) {
    WebStringsProviders.register(AndroidWebStrings(context.applicationContext, cannotEmptyResId))
}
