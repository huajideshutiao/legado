package io.legado.app.model.script

import io.legado.app.exception.NoStackTraceException
import io.legado.app.help.http.OkHttpClientProviders
import io.legado.app.help.http.newCallStrResponse
import io.legado.app.ui.compose.platform.sharedStringTable
import io.legado.app.utils.KS_JSON
import io.legado.app.utils.MD5Utils
import io.legado.app.utils.formatNative
import io.legado.app.utils.isAbsUrl
import io.legado.app.utils.isJsonObject
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.decodeFromString
import kotlin.coroutines.CoroutineContext

/**
 * native 端 (iOS/鸿蒙) [SharedJsScopeProvider] 实现, 语义对照 app 端 QuickJsSharedJsScopeProvider /
 * desktop DesktopQuickJsSharedJsScopeProvider 的三层缓存 (bytecodeCache + ThreadLocal LRU + versionSeq)。
 *
 * # 与 app/desktop 端差异
 * - native 无 bytecode ([NativeJsCompiledScript.bytecode] 恒 null), "bytecode 层"退化为
 *   jsLib 源码列表缓存 (URL 形式 jsLib 下载后缓存源码), ctx 级缓存 (每线程 LruCache(4)) 语义不变;
 * - jsLib URL 下载内容用 in-memory Map 缓存 (对应 app 端 ACache / desktop in-memory Map);
 * - K/N 无 ConcurrentHashMap/AtomicLong, 用 synchronized 块 + 普通 Long 计数替代
 *   (与 nativeMain NativeLocalBookLocator 的并发策略一致);
 * - LRU 淘汰/版本失效替换时与 desktop 一致仅放手强引用不显式 close (老 ctx 可能仍被
 *   evalJS 持栈), native 无 GC/PhantomReference 兜底, 被淘汰 ctx 内存泄漏 (已知取舍)。
 */
object NativeQuickJsSharedJsScopeProvider : SharedJsScopeProvider {

    /** 每线程缓存的 ctx 数, 与 app/desktop 端一致。 */
    private const val PER_THREAD_LRU_SIZE = 4

    private class SourceEntry(val sources: List<String>, val version: Long)

    private class CtxEntry(val scope: NativeJsScope, val version: Long)

    /** 全局源码缓存 (对应 app/desktop bytecodeCache), synchronized(sourceLock) 保护。 */
    private val sourceLock = Any()
    private val sourceCache = HashMap<String, SourceEntry>()
    private var versionSeq = 0L

    /** jsLib URL 下载内容 in-memory 缓存 (对应 app 端 ACache), sourceLock 保护。 */
    private val jsLibContentCache = HashMap<String, String>()

    /** 每线程 ctx 级 LRU 缓存 (ThreadLocal 线程独占, 与 NativeJsEngine.threadLocalScope 同机制)。 */
    private val threadCache: ThreadLocal<LruScopeCache?> = ThreadLocal()

    override fun getScope(
        jsLib: String?,
        enableDangerousApi: Boolean,
        coroutineContext: CoroutineContext?,
    ): JsScope? {
        if (jsLib.isNullOrBlank()) {
            return null
        }
        val key = MD5Utils.md5Encode(jsLib)
        val sourceEntry = getOrCreateSourceEntry(key, jsLib)
        val perThread = threadCache.value
            ?: LruScopeCache(PER_THREAD_LRU_SIZE).also { threadCache.value = it }
        val cached = perThread.get(key)
        if (cached != null && cached.version == sourceEntry.version) {
            return cached.scope
        }
        // JsBindings 构造时自动注入 platform/image (与 desktop 手动注入 ScriptBindings 等价)
        val scope = NativeJsEngine.getRuntimeScope(JsBindings().apply {
            dangerousApi = enableDangerousApi
        }) as NativeJsScope
        for (src in sourceEntry.sources) {
            NativeJsEngine.eval(src, scope, coroutineContext)
        }
        // 淘汰/替换仅放手强引用, 不显式 close (老 ctx 可能仍被某条 evalJS 持栈强引用)
        perThread.put(key, CtxEntry(scope, sourceEntry.version))
        return scope
    }

    override fun remove(jsLib: String?) {
        if (jsLib.isNullOrBlank()) {
            return
        }
        val key = MD5Utils.md5Encode(jsLib)
        synchronized(sourceLock) {
            sourceCache.remove(key)
        }
    }

    override fun clearAll() {
        synchronized(sourceLock) {
            sourceCache.clear()
        }
        // ThreadLocal threadCache 无法跨线程清理, 依赖版本号 + LRU 淘汰自然回收
    }

    private fun getOrCreateSourceEntry(key: String, jsLib: String): SourceEntry {
        synchronized(sourceLock) {
            sourceCache[key]?.let { return it }
        }
        // 下载/解析在锁外执行 (可能有网络 IO), 完成后二次检查写入
        val sources = resolveJsLibSources(jsLib)
        return synchronized(sourceLock) {
            sourceCache[key] ?: SourceEntry(sources, ++versionSeq).also { sourceCache[key] = it }
        }
    }

    /**
     * 解析 jsLib 为源码列表。
     *
     * - JSON Map 形式 `{"name1": "url1"}`: 按 url 下载每个 js 文件 (in-memory 缓存)
     * - 普通 JS 字符串: 直接作为单个源码
     */
    private fun resolveJsLibSources(jsLib: String): List<String> {
        if (jsLib.isJsonObject()) {
            val jsMap: Map<String, String> = KS_JSON.decodeFromString(jsLib)
            val out = ArrayList<String>(jsMap.size)
            jsMap.values.forEach { value ->
                if (value.isAbsUrl()) {
                    val fileName = MD5Utils.md5Encode(value)
                    val cached = synchronized(sourceLock) { jsLibContentCache[fileName] }
                    val js = cached ?: runBlocking {
                        OkHttpClientProviders.get().okHttpClient.newCallStrResponse {
                            url(value)
                        }.body
                    } ?: throw NoStackTraceException(
                        (sharedStringTable["download_jslib_failed"] ?: "下载jsLib-%s失败")
                            .formatNative(value)
                    )
                    if (cached == null) {
                        synchronized(sourceLock) { jsLibContentCache[fileName] = js }
                    }
                    out.add(js)
                }
            }
            return out
        }
        return listOf(jsLib)
    }

    /** 极简 LRU (访问序), 仅本线程访问无需加锁; 淘汰仅移除引用不 close。 */
    private class LruScopeCache(private val maxSize: Int) {
        private val map = LinkedHashMap<String, CtxEntry>()

        fun get(key: String): CtxEntry? {
            val v = map.remove(key) ?: return null
            map[key] = v
            return v
        }

        fun put(key: String, value: CtxEntry) {
            map.remove(key)
            map[key] = value
            if (map.size > maxSize) {
                map.remove(map.keys.first())
            }
        }
    }
}
