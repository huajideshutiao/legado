package io.legado.app.help

import io.legado.app.help.file.AppFilesDirs
import io.legado.app.utils.File
import okio.IOException

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
        // 本 provider 无 LRU: 启动清点里回收整份写入被强杀留下的孤儿临时件 (不回收就永久占空间)
        deleteOrphanAtomicWriteTempFiles(root)
        root
    }

    override fun getAsString(key: String): String? {
        val file = File(resolvePath(key))
        if (!file.exists()) return null
        return file.readBytes().takeIf { it.isNotEmpty() }?.decodeToString()
    }

    override fun put(key: String, value: String) {
        // 整份落盘 (同目录临时件 + 改名就位): 直接覆盖写会在写完之前把条目截断, 并发读方
        // 会把半截内容当成完整值; 落盘失败如实上抛, 调用方按未落盘处理
        val path = resolvePath(key)
        if (!writeTextAtomically(path, value)) {
            throw IOException("写入发现规则缓存失败: $path")
        }
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
