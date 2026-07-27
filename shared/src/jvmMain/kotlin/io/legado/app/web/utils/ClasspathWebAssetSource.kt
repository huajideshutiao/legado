package io.legado.app.web.utils

/**
 * [WebAssetSource] 的桌面 JVM actual 实现。
 *
 * 用 [ClassLoader.getResourceAsStream] 读 classpath 下的 `web/` 静态资源,
 * 资源本体存 `shared/src/jvmMain/resources/web/` (jvmMain resources 经 shared jar 暴露)。
 * 返回 ByteArray (原 InputStream, 下沉 commonMain 后接口统一)。
 *
 * # 与 Android 端的差异
 * Android 端用 [android.content.res.AssetManager.open] 读 `app/src/main/assets/web/` 副本
 * (KMP commonMain resources 不自动路由到 Android assets); 桌面端用 ClassLoader 读 classpath,
 * 资源经 shared jvmMain resources → shared jar 根 → 桌面 classpath。两端资源内容一致。
 *
 * # 资源不存在处理
 * [ClassLoader.getResourceAsStream] 资源不存在时返回 null, 这里包装为 [java.io.IOException]
 * 让调用方走异常分支回 500, 与 Android 端 AssetManager.open 抛 IOException 的行为对齐。
 */
class ClasspathWebAssetSource : WebAssetSource {

    override suspend fun read(path: String): ByteArray {
        // ClassLoader 资源路径以 / 开头表示从 classpath 根查找, 去掉前导 / (与 Android assets 一致)
        val resourcePath = path.removePrefix("/")
        return ClasspathWebAssetSource::class.java.classLoader
            ?.getResourceAsStream(resourcePath)?.use { it.readBytes() }
            ?: throw java.io.IOException("Web resource not found: $resourcePath")
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
