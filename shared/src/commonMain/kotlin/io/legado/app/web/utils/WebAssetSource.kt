package io.legado.app.web.utils

import io.legado.app.web.utils.WebAssetSources.get
import kotlin.concurrent.Volatile

/**
 * Web 静态资源 (web/index.html 等) 读取抽象 (shared commonMain)。
 *
 * # 单一数据源
 * web 资源唯一数据源在 `shared/src/commonMain/composeResources/files/web/`, 四端 actual
 * Compose Resources 打包到 Android assets / JVM classpath；Native 端通过 `Res.readBytes`
 * 读取同一 commonMain 资源目录，无任何平台端资源副本。
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

/** Compose Resources 在 Android/JVM 打包产物中的公共 classpath/assets 前缀。 */
const val COMPOSE_RESOURCE_FILES_PREFIX =
    "composeResources/legado.shared.generated.resources/files/"

/** 将公共逻辑资源路径转换为 Android/JVM 打包资源路径。 */
fun composeResourcePath(path: String): String = "$COMPOSE_RESOURCE_FILES_PREFIX$path"

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
