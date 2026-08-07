package io.legado.app.web.utils

import io.legado.app.web.utils.WebStrings
import io.legado.app.web.utils.WebStringsProviders

/**
 * [WebStrings] 的 Android actual 实现。
 *
 * strings.xml 删除后没有 R.string.cannot_empty 可注入 resId, 改为 app 端直接注入
 * 已取好的本地化字符串 (app 端经 androidAppString("cannot_empty") 取 shared
 * composeResources 文案后传入, 避免 shared androidMain 反向依赖 app 模块的 R 资源)。
 */
class AndroidWebStrings(
    override val cannotEmpty: String,
) : WebStrings

/**
 * 安卓宿主启动早期注册 [WebStrings] 的 actual 实现。
 *
 * @param cannotEmpty 已本地化的「不能为空」文案 (由 app 端 App.kt 传入)
 *
 * 模式参考 `registerAndroidServiceLauncher`。
 */
fun registerAndroidWebStrings(cannotEmpty: String) {
    WebStringsProviders.register(AndroidWebStrings(cannotEmpty))
}
