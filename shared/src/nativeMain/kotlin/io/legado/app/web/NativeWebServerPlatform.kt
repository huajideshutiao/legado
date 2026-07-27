package io.legado.app.web

/**
 * [WebServerPlatform] 的 iOS / 鸿蒙 (nativeMain) 共用 actual 实现入口。
 *
 * 继承 [KtorWebServerPlatform] (Ktor server CIO 壳), 仅实现 [serve] 为 no-op
 * (iOS/鸿蒙无 Android Service 概念, 对齐 desktop DesktopWebServerPlatform.serve)。
 *
 * # 单一实现 (非 iOS/ohos 各一份)
 * iOS 与鸿蒙端 serve() 逻辑完全相同 (no-op), 按项目约定下沉到 nativeMain 共用,
 * 避免两端复制代码 (参考 NativeFileCacheProvider / NativeSourceCacheProvider 模式)。
 *
 * 模式参考 [io.legado.app.help.registerNativeFileCacheProvider]。
 */
class NativeWebServerPlatform : KtorWebServerPlatform() {

    override fun serve() {
        // iOS / 鸿蒙无 Service 概念, no-op (对齐 desktop DesktopWebServerPlatform.serve)
    }
}

/**
 * 注册 [NativeWebServerPlatform] 到 [WebServerPlatforms] (iOS/鸿蒙共用)。
 *
 * 前置依赖: [registerNativeWebAssetSource] + [registerNativeWebStrings]
 * (KtorWebServerPlatform.startServers 调 WebStringsProviders.get().cannotEmpty;
 * KtorRouting 调 AssetsWeb.getResponse -> WebAssetSources.get().read)。
 *
 * 模式参考 [io.legado.app.help.registerNativeFileCacheProvider]。
 */
fun registerNativeWebServerPlatform() {
    WebServerPlatforms.register(NativeWebServerPlatform())
}
