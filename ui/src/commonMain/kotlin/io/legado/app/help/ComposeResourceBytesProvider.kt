package io.legado.app.help

import kotlinx.coroutines.runBlocking
import legado.ui.generated.resources.Res

/**
 * [PackagedResourceBytesProvider] 的界面侧实现 (四端共用, 与 [ComposeResourceDefaultDataProvider]
 * 同款)。
 *
 * 经 composeResources 生成的 `Res.readBytes` 取数: 打包前缀由资源生成器写进 `Res`
 * (形如 `composeResources/legado.ui.generated.resources/`), 源码侧不持有该前缀,
 * 模块切分/改名后无需改动任何读取点。`files` 目录前缀在这里补上, 数据层只描述
 * "相对 composeResources files 目录"的路径。
 *
 * [readBytes] 为同步接口, [runBlocking] 包装 (调用方均在 IO 派发路径, 即 TVBox 引导脚本读取链)。
 */
class ComposeResourceBytesProvider : PackagedResourceBytesProvider {

    override fun readBytes(path: String): ByteArray =
        runBlocking { Res.readBytes("files/$path") }
}

/**
 * 宿主启动早期注册一次 (任何 [readHostAsset] 调用之前)。
 *
 * 调用点: App.onCreate / DesktopCore.registerEarlyProviders (桌面与无头共用)。
 *
 * 模式参考 [registerComposeDefaultDataResourceProvider]。
 */
fun registerComposeResourceBytesProvider() {
    PackagedResourceBytesProviders.register(ComposeResourceBytesProvider())
}
