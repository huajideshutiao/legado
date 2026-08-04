package io.legado.app.utils

/** composeResources 打进 classpath 的目录前缀 (含模块限定名, 由插件按模块生成)。 */
private const val RESOURCE_PREFIX = "composeResources/legado.shared.generated.resources/files/"

/** 桌面端无 assets 目录, 改从 classpath 读 shared 模块 composeResources (与 web/ 资源同机制)。 */
internal actual fun readSharedResourceBytes(path: String): ByteArray? {
    return try {
        val classLoader = Thread.currentThread().contextClassLoader
            ?: RemoteAssetsUtils::class.java.classLoader
        classLoader?.getResourceAsStream(RESOURCE_PREFIX + path)?.use { it.readBytes() }
    } catch (_: Exception) {
        null
    }
}
