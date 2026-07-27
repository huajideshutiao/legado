package io.legado.app.web.utils

/**
 * Web 静态资源 (web/index.html 等) 读取抽象 (shared commonMain)。
 *
 * # 背景
 * 原 app 端 [AssetsWeb] 直接用 `appCtx.assets.open(path)` (Android AssetManager) 读
 * `app/src/main/assets/web/` 下的静态资源; 桌面端无 AssetManager, 改从 classpath 读
 * `shared/jvmMain/resources/web/`。iOS/鸿蒙端用 composeResources 读
 * `commonMain/composeResources/files/web/`。读源是平台特殊行为, 抽象至此接口。
 *
 * # 下沉 commonMain (原 jvmAndAndroidMain)
 * 返回 [ByteArray] (资源都很小, 几 KB 到几百 KB), 避免 commonMain 无 java.io.InputStream 的问题,
 * 让 iOS/鸿蒙 (nativeMain) 共用同一接口。各端 actual 负责把原生资源源转成 ByteArray。
 */
interface WebAssetSource {

    /**
     * 读 web 资源字节。路径已含 rootPath 前缀 (如 "web/index.html"), 由 [AssetsWeb] 拼接。
     * @throws Exception 资源不存在或读取失败
     */
    suspend fun read(path: String): ByteArray
}

/**
 * [WebAssetSource] 容器 (provider 注入模式)。
 *
 * 宿主启动早期注册一次 (App.onCreate / desktop main / iOS registerIosProviders / 鸿蒙 registerOhosProviders),
 * shared 内通过 [get] 获取。未注册时调用 [get] 抛 [IllegalStateException]。
 *
 * 模式参考 [io.legado.app.help.service.ServiceLaunchers]。
 */
object WebAssetSources {

    @Volatile
    private var impl: WebAssetSource? = null

    /** 宿主启动早期注册一次 (任何 AssetsWeb 调用之前)。 */
    fun register(impl: WebAssetSource) {
        this.impl = impl
    }

    /** 获取已注册实现, 未注册抛出 IllegalStateException。 */
    fun get(): WebAssetSource =
        impl ?: error("WebAssetSources not registered; call registerAndroidWebAssetSource() / registerDesktopWebAssetSource() / registerNativeWebAssetSource() first")

    /** 仅测试场景: 清空注册。 */
    fun reset() {
        impl = null
    }
}
