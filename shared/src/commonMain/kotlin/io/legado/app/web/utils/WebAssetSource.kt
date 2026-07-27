package io.legado.app.web.utils

import kotlin.concurrent.Volatile

/**
 * Web 静态资源 (web/index.html 等) 读取抽象 (shared commonMain)。
 *
 * # 单一数据源
 * web 资源唯一数据源在 `shared/src/commonMain/composeResources/files/web/`, 四端 actual
 * (Android / 桌面 JVM / iOS / 鸿蒙) 均通过 composeResources [org.jetbrains.compose.resources.Res.readBytes]
 * 读取, 无任何平台端资源副本 (Android 不再拷贝到 app/assets/web, 桌面不再放 jvmMain/resources/web)。
 *
 * # 下沉 commonMain (原 jvmAndAndroidMain)
 * 返回 [ByteArray] (资源都很小, 几 KB 到几百 KB), 避免 commonMain 无 java.io.InputStream 的问题,
 * 让 iOS/鸿蒙 (nativeMain) 共用同一接口。各端 actual 负责把 composeResources 资源转成 ByteArray。
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
    // 注: registerAndroidWebAssetSource() 现无参 (改用 composeResources, 不再需要 Context)

    /** 仅测试场景: 清空注册。 */
    fun reset() {
        impl = null
    }
}
