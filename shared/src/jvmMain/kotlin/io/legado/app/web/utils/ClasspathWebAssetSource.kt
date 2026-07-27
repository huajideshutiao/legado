package io.legado.app.web.utils

/**
 * [WebAssetSource] 的桌面 JVM actual 实现。
 *
 * 用 [ClassLoader.getResourceAsStream] 读 classpath 下 `composeResources/files/web/` 的资源
 * (Compose Multiplatform 插件自动把 `commonMain/composeResources/` 打包到 JVM classpath)。
 * 与 Android AndroidWebAssetSource / iOS 鸿蒙 NativeWebAssetSource 行为一致 (单一数据源)。
 *
 * # 资源不存在处理
 * 资源不存在时返回空数组, 由上层 AssetsWeb 回 500 处理。
 */
class ClasspathWebAssetSource : WebAssetSource {

    override suspend fun read(path: String): ByteArray {
        val classLoader = Thread.currentThread().contextClassLoader ?: WebAssetSource::class.java.classLoader
        return classLoader?.getResourceAsStream("composeResources/files/$path")?.use { it.readBytes() }
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
