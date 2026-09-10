package io.legado.app.web.utils

/**
 * [WebAssetSource] 的桌面 JVM actual 实现。
 *
 * 用 [ClassLoader.getResourceAsStream] 读 classpath 下 composeResources 的 `files/web/` 资源。
 * 插件产物带模块限定目录名 (legado.shared.generated.resources), 前缀不能省。
 * 与 Android AndroidWebAssetSource / iOS 鸿蒙 NativeWebAssetSource 行为一致 (单一数据源)。
 *
 * # 资源不存在处理
 * 资源不存在时按 [WebAssetSource] 契约抛出异常。
 */
class ClasspathWebAssetSource : WebAssetSource {

    override suspend fun read(path: String): ByteArray {
        val resourcePath = composeResourcePath(path)
        val classLoader = Thread.currentThread().contextClassLoader
            ?: WebAssetSource::class.java.classLoader
            ?: error("No class loader available for resource: $resourcePath")
        return requireNotNull(classLoader.getResourceAsStream(resourcePath)) {
            "Web asset not found: $resourcePath"
        }.use { it.readBytes() }
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
