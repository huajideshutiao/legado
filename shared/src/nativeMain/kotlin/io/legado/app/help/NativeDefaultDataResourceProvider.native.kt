package io.legado.app.help

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
