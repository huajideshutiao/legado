package io.legado.app.web

/**
 * [WebServerPlatform] 的鸿蒙实现入口。
 *
 * 继承 [KtorWebServerPlatform] (Ktor server CIO 壳), 仅实现 [serve] 为 no-op
 * (鸿蒙无 Android Service 概念, 对齐 desktop DesktopWebServerPlatform.serve)。
 *
 * 模式参考 [io.legado.app.help.registerNativeFileCacheProvider]。
 */
class NativeWebServerPlatform : KtorWebServerPlatform() {

    override fun serve() {
        // 鸿蒙无 Service 概念, no-op (对齐 desktop DesktopWebServerPlatform.serve)
    }
}

/**
 * 注册 [NativeWebServerPlatform] 到 [WebServerPlatforms] (鸿蒙)。
 *
 * 前置依赖: [io.legado.app.web.utils.registerComposeWebAssetSource] + [registerNativeWebStrings]
 * (KtorWebServerPlatform.startServers 调 WebStringsProviders.get().cannotEmpty;
 * KtorRouting 调 AssetsWeb.getResponse -> WebAssetSources.get().read)。
 *
 * 模式参考 [io.legado.app.help.registerNativeFileCacheProvider]。
 */
fun registerNativeWebServerPlatform() {
    WebServerPlatforms.register(NativeWebServerPlatform())
}
