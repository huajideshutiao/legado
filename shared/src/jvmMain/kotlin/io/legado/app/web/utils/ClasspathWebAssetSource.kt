package io.legado.app.web.utils

/** composeResources 打进 classpath 的目录前缀 (含模块限定名, 由插件按模块生成)。 */
private const val RESOURCE_PREFIX = "composeResources/legado.shared.generated.resources/files/"

/**
 * [WebAssetSource] 的桌面 JVM actual 实现。
 *
 * 用 [ClassLoader.getResourceAsStream] 读 classpath 下 composeResources 的 `files/web/` 资源。
 * 插件产物带模块限定目录名 (legado.shared.generated.resources), 前缀不能省。
 * 与 Android AndroidWebAssetSource / iOS 鸿蒙 NativeWebAssetSource 行为一致 (单一数据源)。
 *
 * # 资源不存在处理
 * 资源不存在时返回空数组, 由上层 AssetsWeb 回 500 处理。
 */
class ClasspathWebAssetSource : WebAssetSource {

    override suspend fun read(path: String): ByteArray {
        val classLoader = Thread.currentThread().contextClassLoader ?: WebAssetSource::class.java.classLoader
        return classLoader?.getResourceAsStream("$RESOURCE_PREFIX$path")?.use { it.readBytes() }
            ?: ByteArray(0)
    }
}

/**
 * 桌面宿主启动早期注册 [WebAssetSource] 的 actual 实现。
 *
 * 模式参考 `registerDesktopServiceLauncher`。
 */
fun registerDesktopWebAssetSource() {
    WebAssetSources.register(ClasspathWebAssetSource())
}
