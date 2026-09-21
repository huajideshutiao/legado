package io.legado.app.help

import io.legado.app.help.file.AppFilesDirs
import io.legado.app.utils.File

/**
 * nativeMain: [ExploreKindsCacheProvider] 的 iOS/鸿蒙共用文件缓存实现
 * (对照 app 端 ACache.get("explore"), 落 `{AppFilesDirs.cacheDir}/explore/`)。
 *
 * key 为 md5 hex (BookSource.exploreKindsJson 生成), 本身即合法文件名;
 * [escapeKey] 兜底转义防御异常输入。模式与 [NativeSourceCacheProvider] 一致。
 */
class NativeExploreKindsCacheProvider : ExploreKindsCacheProvider {

    private val cacheRoot: String = run {
        val cacheDir = AppFilesDirs.get().cacheDir
        val root = if (cacheDir.endsWith("/")) "${cacheDir}explore" else "$cacheDir/explore"
        runCatching { File(root).mkdirs() }
        root
    }

    override fun getAsString(key: String): String? {
        val file = File(resolvePath(key))
        if (!file.exists()) return null
        return file.readBytes().takeIf { it.isNotEmpty() }?.decodeToString()
    }

    override fun put(key: String, value: String) {
        val file = File(resolvePath(key))
        file.parentFile?.takeIf { !it.exists() }?.mkdirs()
        file.writeText(value)
    }

    override fun remove(key: String) {
        val file = File(resolvePath(key))
        if (file.exists()) file.delete()
    }

    private fun resolvePath(key: String): String = "$cacheRoot/${escapeKey(key)}"

    private fun escapeKey(key: String): String = buildString(key.length) {
        for (c in key) append(if (c.isLetterOrDigit() || c == '.' || c == '-' || c == '_') c else '_')
    }
}

/**
 * 注册 [NativeExploreKindsCacheProvider] (iOS/鸿蒙共用, 在 AppFilesDirs 之后)。
 * 未注册时 `@js:` 发现规则解析结果不落盘, 每次冷启动重新执行 JS。
 */
fun registerNativeExploreKindsCacheProvider() {
    ExploreKindsCacheProviders.impl = NativeExploreKindsCacheProvider()
}
