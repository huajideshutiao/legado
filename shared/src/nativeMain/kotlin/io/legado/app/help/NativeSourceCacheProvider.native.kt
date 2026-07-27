package io.legado.app.help

import io.legado.app.help.file.AppFilesDirs
import io.legado.app.utils.File

/**
 * nativeMain: [SourceCacheProvider] 的 iOS / 鸿蒙 两端共用真实实现
 * (基于 kotlin.io.File + AppFilesDirs.filesDir)。
 *
 * 两端实现逻辑一致, 仅文件 API 不同 (iOS 原用 NSFileManager + NSData, 鸿蒙用 kotlin.io.File),
 * 统一用 [kotlin.io.File] (Kotlin/Native iOS/ohos target 标准库均支持, 基于 POSIX fs,
 * 行为与 JVM java.io.File 等价), 下沉到 nativeMain 共用。
 *
 * # 背景
 * app 端 [CacheManager] 持久化到 appDb.cacheDao (Room) + 内存 LruCache, 依赖 Android
 * Room + appCtx, 无法跨平台。desktop 端简化为 in-memory Map (进程退出即丢失)。
 * iOS/鸿蒙端用 kotlin.io.File 把缓存落到 `{AppFilesDirs.filesDir}/cache_manager/` 目录下
 * (与各端 BookStorage 用 `{filesDir}/book_cache/` 同源), 进程重启后缓存仍在,
 * 让 BaseSource.getLoginHeader/putLoginHeader/getLoginInfo/putLoginInfo/setVariable/
 * getVariable 等持久层调用在 iOS/鸿蒙端可用。
 *
 * # 设计
 * - 持久层 (get/put/delete): 每个 key 一个文件, 落在
 *   `{AppFilesDirs.filesDir}/cache_manager/` 目录下
 * - 内存层 (getFromMemory/putMemory/deleteMemory): in-memory HashMap (对应
 *   app 端 CacheManager.putMemory 的 LruCache 内存缓存语义, 进程退出即丢失,
 *   与 desktop DesktopSourceCacheProvider 一致)
 * - asBinding: 返回自身 (与 desktop 一致, 供 JS bindings["cache"] 绑定调用)
 *
 * # 文件名转义
 * key 可能含 `:`/`/` 等非 POSIX 文件名字符 (如 `loginHeader_http://...`),
 * 用 [escapeKey] 把非 `[a-zA-Z0-9._-]` 字符替换为 `_`, 保证文件名合法。
 * 不同 key 转义后理论上可能冲突 (极低概率), stub 可接受; 后续若引入 Base64url
 * 转义可彻底消除冲突。
 *
 * # 与 desktop [io.legado.desktop.help.source.DesktopSourceCacheProvider] 区别
 * - desktop 持久层也用 in-memory Map (进程退出丢失), native 持久层用文件 (重启仍在)
 * - 内存层两端一致 (均 in-memory Map)
 *
 * 注册入口 (iOS/鸿蒙共用): [registerNativeSourceCacheProvider]
 *
 * 前置依赖: 各端 [registerIosAppFilesDir] / [registerOhosAppFilesDir] 需先注册
 * (本文件持久化目录从 [AppFilesDirs.get].filesDir 派生)。
 *
 * 模式参考 [io.legado.app.help.book.BookStorage] / desktop `DesktopSourceCacheProvider` /
 * app 端 `JsEnginesAndroid` 中的 SourceCacheProvider 桥接。
 */
class NativeSourceCacheProvider : SourceCacheProvider {

    /**
     * 持久化根目录: `{AppFilesDirs.filesDir}/cache_manager/`。
     *
     * 启动时确保目录存在 (mkdirs 失败静默, 后续真实 I/O 时再报错更易定位;
     * 与各端 BookStorage saveText 父目录创建行为对齐)。
     */
    private val cacheRoot: String = run {
        val filesDir = AppFilesDirs.get().filesDir
        val root = if (filesDir.endsWith("/")) "${filesDir}cache_manager" else "$filesDir/cache_manager"
        runCatching { File(root).mkdirs() }
        root
    }

    /**
     * 内存层缓存 (对应 app 端 CacheManager.putMemory 的 LruCache 语义)。
     *
     * Kotlin/Native iOS/鸿蒙 target 无 java.util.concurrent.ConcurrentHashMap, 用普通 HashMap;
     * stub 场景下调用方多为单协程串行访问 (与 [io.legado.app.utils.concurrent.newConcurrentSet]
     * ios/ohos actual 实现策略一致), 若后续高频并发需用 kotlinx.atomicfu 加锁。
     */
    private val memoryStore: HashMap<String, Any> = HashMap()

    // ---- 持久层 (文件) ----

    override fun get(key: String): String? {
        val file = File(resolvePath(key))
        if (!file.exists()) return null
        val bytes = file.readBytes()
        if (bytes.isEmpty()) return null
        return bytes.decodeToString()
    }

    override fun put(key: String, value: String) {
        val file = File(resolvePath(key))
        val parent = file.parentFile
        // 父目录不存在则递归创建 (与各端 BookStorage.saveText 行为对齐)
        if (parent != null && !parent.exists()) {
            parent.mkdirs()
        }
        // writeText 默认 UTF-8 (与 encodeToByteArray() 行为等价)
        file.writeText(value)
    }

    override fun delete(key: String) {
        val file = File(resolvePath(key))
        if (file.exists()) {
            file.delete()
        }
    }

    // ---- 内存层 (in-memory Map) ----

    override fun getFromMemory(key: String): Any? = memoryStore[key]

    override fun putMemory(key: String, value: Any) {
        memoryStore[key] = value
    }

    override fun deleteMemory(key: String) {
        memoryStore.remove(key)
    }

    // ---- JS 绑定 ----

    /**
     * 返回自身供 JS bindings["cache"] 绑定 (与 desktop DesktopSourceCacheProvider.asBinding 一致)。
     *
     * app 端 [CacheManager] 是 @JsApi 注解对象, JS 调用走 BaseSourceJsDispatcher 分派表;
     * iOS/鸿蒙/desktop 端无 @JsApi 注解处理, asBinding 返回自身让 JS 调用经反射 fallback
     * 调用 SourceCacheProvider 接口方法 (调用方 runCatching 兜底, 行为可控)。
     */
    override fun asBinding(): Any = this

    // ---- 工具 ----

    /**
     * 解析 key 对应的缓存文件路径: `{cacheRoot}/{escapedKey}`。
     */
    private fun resolvePath(key: String): String {
        return "$cacheRoot/${escapeKey(key)}"
    }

    /**
     * 转义 key 为合法 POSIX 文件名: 非 `[a-zA-Z0-9._-]` 字符替换为 `_`。
     *
     * - 保留字母数字/点/下划线/连字符 (常见 key 前缀如 `loginHeader_`/`userInfo_`/`sourceVariable_`/`v_` 可读)
     * - `:`/`/`/`?` 等 POSIX 非法字符替换为 `_` (避免 File 路径解析失败)
     * - 极低概率冲突 (不同 key 转义后相同), stub 可接受
     */
    private fun escapeKey(key: String): String {
        val sb = StringBuilder(key.length)
        for (c in key) {
            sb.append(
                if (c.isLetterOrDigit() || c == '.' || c == '-' || c == '_') c else '_'
            )
        }
        return sb.toString()
    }
}

/**
 * 注册 [NativeSourceCacheProvider] 到 [SourceCacheProviders] (iOS/鸿蒙共用)。
 *
 * 前置依赖: [AppFilesDirs] 已注册 (持久化目录从 AppFilesDirs.get().filesDir 派生)。
 */
fun registerNativeSourceCacheProvider() {
    SourceCacheProviders.impl = NativeSourceCacheProvider()
}
