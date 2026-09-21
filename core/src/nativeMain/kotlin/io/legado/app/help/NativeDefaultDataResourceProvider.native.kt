package io.legado.app.help

import io.legado.app.help.i18n.AppStringProvider
import io.legado.app.help.i18n.registerAppStringProvider
import io.legado.app.ui.compose.platform.syncGetString
import kotlinx.coroutines.runBlocking
import legado.shared.generated.resources.Res
import org.jetbrains.compose.resources.ExperimentalResourceApi

/**
 * native (iOS/鸿蒙) [DefaultDataResourceProvider]: 用 composeResources [Res.readBytes]
 * 读 `commonMain/composeResources/files/defaultData/` 下的默认数据 JSON
 * (与 [io.legado.app.web.utils.NativeWebAssetSource] 同一取数路径)。
 *
 * 注: 默认数据 JSON 唯一数据源即 `commonMain/composeResources/files/defaultData/`,
 * app 端从打进 assets 的同一目录读, desktop 端从 classpath 读, 三端同源无需同步。
 * readResource 为同步接口, runBlocking 包装 (调用方均在后台惰性初始化路径)。
 */
class NativeDefaultDataResourceProvider : DefaultDataResourceProvider {

    @OptIn(ExperimentalResourceApi::class)
    override fun readResource(name: String): String =
        runBlocking { Res.readBytes("files/defaultData/$name") }.decodeToString()
}

/** iOS/鸿蒙宿主启动早期注册一次 (任何 DefaultDataShared 属性访问之前)。 */
fun registerNativeDefaultDataResourceProvider() {
    DefaultDataResourceProviders.register(NativeDefaultDataResourceProvider())
}

/**
 * appString 的 iOS/鸿蒙实现 (nativeMain 中间源集共用, 两端注册同一份), 同文件承载
 * composeResources 同步读取的宿主注册。key 名 → [syncGetString] 查 strings.xml,
 * 与 app 端 `AppStringsAndroid` / desktop 端 Main.kt 内联注册 (jvmGetString 查表) 同构。
 * 修复背景: 此前两端从未注册 AppStringProvider, `appString` fallback 返回 key 名,
 * 运行期可见为 "no_prev_page" 之类的原始 key (翻页边界提示弹成模态弹窗)。
 */
private val nativeAppStringProvider = AppStringProvider { key, args ->
    syncGetString(key.name, *args)
}

/** iOS/鸿蒙宿主启动早期注册一次 (任何 commonMain 调用 appString(...) 之前)。 */
fun registerNativeAppStringProvider() {
    registerAppStringProvider(nativeAppStringProvider)
}
