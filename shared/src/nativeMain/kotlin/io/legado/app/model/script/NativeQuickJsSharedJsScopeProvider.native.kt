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
import kotlinx.atomicfu.locks.SynchronizedObject
import kotlinx.atomicfu.locks.synchronized
import kotlinx.serialization.decodeFromString
import kotlin.native.concurrent.ThreadLocal
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
 * - 淘汰回收: 被淘汰/版本失效替换的 scope 不立即 close (可能仍在某层 evalJS 栈上, 见
 *   [LruScopeCache] 注释), 挂线程私有待关闭队列, 本线程无任何 eval 在栈上时统一
 *   JS_FreeContext + JS_FreeRuntime 释放 (替代 desktop 端"仅放手等 GC"——K/N 无
 *   PhantomReference 兜底, 必须显式释放, 否则每次 LRU 淘汰都泄漏一个 ctx)。
 */
object NativeQuickJsSharedJsScopeProvider : SharedJsScopeProvider {

    /** 每线程缓存的 ctx 数, 与 app/desktop 端一致。 */
    private const val PER_THREAD_LRU_SIZE = 4

    private class SourceEntry(val sources: List<String>, val version: Long)

    /** 全局源码缓存 (对应 app/desktop bytecodeCache), synchronized(sourceLock) 保护。 */
    private val sourceLock = SynchronizedObject()
    private val sourceCache = HashMap<String, SourceEntry>()
    private var versionSeq = 0L

    /** jsLib URL 下载内容 in-memory 缓存 (对应 app 端 ACache), sourceLock 保护。 */
    private val jsLibContentCache = HashMap<String, String>()

    /** 每线程 ctx 级 LRU 缓存 (见 [LruScopeCache])。 */
    private val threadCache: LruScopeCache? get() = threadCacheHolder

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
        val perThread = threadCache ?: LruScopeCache(PER_THREAD_LRU_SIZE).also { threadCacheHolder = it }
        perThread.flushPendingClose()
        val cached = perThread.get(key)
        if (cached != null && cached.version == sourceEntry.version) {
            return cached.scope
        }
        // 版本失效: 旧 scope 已移出 LRU, 走统一回收 (可能仍在 eval 栈上, 见 LruScopeCache)
        cached?.let { perThread.evict(it) }
        // JsBindings 构造时自动注入 platform/image (与 desktop 手动注入 ScriptBindings 等价)
        val scope = NativeJsEngine.getRuntimeScope(JsBindings().apply {
            dangerousApi = enableDangerousApi
        }) as NativeJsScope
        for (src in sourceEntry.sources) {
            NativeJsEngine.eval(src, scope, coroutineContext)
        }
        perThread.put(key, CtxEntry(scope, sourceEntry.version))
        return scope
    }

    override fun remove(jsLib: String?) {
        if (jsLib.isNullOrBlank()) {
            return
        }
        val key = MD5Utils.md5Encode(jsLib)
        threadCache?.flushPendingClose()
        synchronized(sourceLock) {
            sourceCache.remove(key)
        }
    }

    override fun clearAll() {
        threadCache?.flushPendingClose()
        synchronized(sourceLock) {
            sourceCache.clear()
        }
        // 线程私有 LRU 无法跨线程清理, 依赖版本号 + LRU 淘汰自然回收
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
}

internal class CtxEntry(val scope: NativeJsScope, val version: Long)

/** 每线程 ctx 级 LRU 缓存 (线程私有, 由 @ThreadLocal [threadCacheHolder] 保证), 仅本线程访问无需加锁。 */
private class LruScopeCache(private val maxSize: Int) {
    private val map = LinkedHashMap<String, CtxEntry>()

    /** 淘汰但暂不能 close 的 scope (可能仍在某层 evalJS 栈上), 本线程栈外时统一释放。 */
    private val pendingClose = ArrayList<NativeJsScope>()

    fun get(key: String): CtxEntry? {
        val v = map.remove(key) ?: return null
        map[key] = v
        return v
    }

    fun put(key: String, value: CtxEntry) {
        map.remove(key)
        map[key] = value
        if (map.size > maxSize) {
            map.remove(map.keys.first())?.let { closeOrDefer(it.scope) }
        }
    }

    /** 版本失效替换的旧 scope 也走同一回收路径 (同 key 重入时它可能正在 eval 栈上)。 */
    fun evict(entry: CtxEntry) = closeOrDefer(entry.scope)

    /**
     * 当前线程无任何 eval 在栈上才立即 close; 否则挂起, 等 [flushPendingClose] 在栈外释放。
     *
     * 为什么不能直接 close: evalJS 经 JS 回调 (__nativeDispatch) 可重入 getScope/put,
     * 被淘汰/替换的 scope 可能仍在更外层 eval 栈上, 同步 close 会 use-after-free。
     */
    private fun closeOrDefer(scope: NativeJsScope) {
        if (NativeJsEngine.currentContext() == null) {
            scope.close()
        } else {
            pendingClose.add(scope)
        }
    }

    /**
     * 栈外清扫: threadLocalScope 为空 ⇔ 所有线程均无 eval 在执行 (全局 atomic 单值),
     * 此时本线程淘汰的 scope 必然无人使用, 统一 JS_FreeContext + JS_FreeRuntime 释放。
     * 每次 provider 调用入口执行一次, 保证被淘汰 ctx 最终必被释放 (不再累积泄漏)。
     */
    fun flushPendingClose() {
        if (pendingClose.isEmpty() || NativeJsEngine.currentContext() != null) return
        val list = pendingClose.toTypedArray()
        pendingClose.clear()
        for (scope in list) {
            scope.close()
        }
    }
}

/**
 * 每线程 ctx 级 LRU 缓存持有者。
 *
 * K/N @ThreadLocal 真线程私有 (对齐 app 端 ThreadLocal / desktop ThreadLocal 语义):
 * 每个线程独立 LRU 与待关闭队列, 线程 A 淘汰的 scope 不会被线程 B 使用,
 * "本线程栈外 ⇒ 无人使用" 才成立, 是淘汰安全 close 的前提。
 * (不能用全局 atomic: 跨线程共享时 B 线程可能正持有 A 线程淘汰的 scope,
 * 栈外清扫会形成跨线程 use-after-free。)
 */
@ThreadLocal
private var threadCacheHolder: LruScopeCache? = null
