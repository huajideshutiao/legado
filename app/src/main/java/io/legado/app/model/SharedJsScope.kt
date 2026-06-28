package io.legado.app.model

import androidx.collection.LruCache
import com.google.gson.reflect.TypeToken
import com.script.quickjs.QuickJsContext
import com.script.quickjs.QuickJsEngine
import com.script.quickjs.ScriptBindings
import io.legado.app.exception.NoStackTraceException
import io.legado.app.help.http.newCallStrResponse
import io.legado.app.help.http.okHttpClient
import io.legado.app.model.SharedJsScope.PER_THREAD_LRU_SIZE
import io.legado.app.model.SharedJsScope.bytecodeCache
import io.legado.app.model.SharedJsScope.getScope
import io.legado.app.model.SharedJsScope.remove
import io.legado.app.model.SharedJsScope.threadCache
import io.legado.app.utils.ACache
import io.legado.app.utils.GSON
import io.legado.app.utils.MD5Utils
import io.legado.app.utils.isAbsUrl
import io.legado.app.utils.isJsonObject
import kotlinx.coroutines.runBlocking
import splitties.init.appCtx
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import kotlin.coroutines.CoroutineContext

/**
 * JS 共享作用域缓存。
 *
 * QuickJS 的 JSContext 不可跨线程共享 (rhino 通过 `sealObject()` + 线程私有
 * `Context.enter()` 实现共享 scope, quickjs 没有等价机制), 故采用:
 *
 * 1. 全局 [bytecodeCache]: jsLib 编译一次为 bytecode, 跨实例可复用。
 *    [QuickJsEngine.compile] 内部 synchronized, 编译过程线程安全。
 * 2. 每线程独占 [threadCache]: LRU<jsLib key -> ctx>。同线程同 jsLib 重复访问
 *    无开销; 不同线程访问同一 jsLib 各自构建 ctx, 无 native 竞争。
 * 3. [remove] 走版本号失效: 删除 bytecode entry, 下次 [getScope] 重建并分配
 *    新版本; 各线程的旧 ctx 在版本号校验时被替换, 由 GC + PhantomReference
 *    ([com.script.quickjs.NativeCleanup]) 异步释放 native 资源。
 *
 * 性能: 首次访问每线程多一次 ctx 构建 (~5-15ms, bytecode 已缓存, 主要是 native
 * runtime/ctx 创建 + bootstrap bytecode eval + jsLib bytecode eval); 同线程重复
 * 访问 0 额外开销, 完全消除并发 evalJS 在共享 ctx 上撕裂 native 状态的风险
 * (历史上 BookChapterList.mapAsync 并发触发 SIGSEGV)。
 *
 * 内存: 单 ctx 估 ~250KB, 每线程上限 [PER_THREAD_LRU_SIZE], 协程池 (Dispatchers.IO
 * 默认 64) 满载估 ~64MB 上限, 典型场景远低于此 (单次书架刷新通常只用 8-16 个工作线程)。
 */
object SharedJsScope {

    /**
     * 每线程缓存的 ctx 数。
     *
     * 实际场景: 单线程通常串行处理 1-2 个 source, LRU=4 已覆盖
     * "当前 source + 上一个 source"模式。提升此值会按线程数线性增加峰值内存。
     */
    private const val PER_THREAD_LRU_SIZE = 4

    private val cacheFolder = File(appCtx.cacheDir, "shareJs")
    private val aCache = ACache.get(cacheFolder)

    private class BytecodeEntry(
        val bytecodes: List<ByteArray>,
        val version: Long
    )

    private class CtxEntry(val ctx: QuickJsContext, val version: Long)

    private val bytecodeCache = ConcurrentHashMap<String, BytecodeEntry>()
    private val versionSeq = AtomicLong(0)

    private val threadCache = ThreadLocal.withInitial {
        LruCache<String, CtxEntry>(PER_THREAD_LRU_SIZE)
    }

    fun getScope(
        jsLib: String?,
        enableDangerousApi: Boolean,
        coroutineContext: CoroutineContext?
    ): QuickJsContext? {
        if (jsLib.isNullOrBlank()) {
            return null
        }
        val key = MD5Utils.md5Encode(jsLib)
        val bytecodeEntry = getOrCreateBytecodeEntry(key, jsLib)
        val perThread = threadCache.get()
        val cached = perThread.get(key)
        if (cached != null && cached.version == bytecodeEntry.version) {
            return cached.ctx
        }
        val scope = QuickJsEngine.getRuntimeScope(
            ScriptBindings().apply {
                this.dangerousApi = enableDangerousApi
            }
        )
        for (bc in bytecodeEntry.bytecodes) {
            QuickJsEngine.evalBytecode(bc, scope, coroutineContext)
        }
        // LRU 淘汰仅放手强引用, 不显式 close (旧 ctx 可能仍被另一处 evalJS 持栈,
        // native 资源由 GC + PhantomReference 兜底释放)。
        perThread.put(key, CtxEntry(scope, bytecodeEntry.version))
        return scope
    }

    /**
     * 删除 jsLib 的 bytecode 缓存条目。各线程 LRU 中的 stale ctx 通过版本号在
     * 下次 [getScope] 时被替换。
     *
     * 不能在此处同步 close 任何 ctx: 老 ctx 可能仍被某条 evalJS 持栈强引用,
     * 同步释放会与正在执行的 native 调用形成 use-after-free。
     */
    fun remove(jsLib: String?) {
        if (jsLib.isNullOrBlank()) {
            return
        }
        val key = MD5Utils.md5Encode(jsLib)
        bytecodeCache.remove(key)
    }

    private fun getOrCreateBytecodeEntry(key: String, jsLib: String): BytecodeEntry {
        bytecodeCache[key]?.let { return it }
        return bytecodeCache.computeIfAbsent(key) {
            BytecodeEntry(compileJsLib(jsLib), versionSeq.incrementAndGet())
        }
    }

    private fun compileJsLib(jsLib: String): List<ByteArray> {
        if (jsLib.isJsonObject()) {
            val jsMap: Map<String, String> = GSON.fromJson(
                jsLib,
                TypeToken.getParameterized(
                    Map::class.java,
                    String::class.java,
                    String::class.java
                ).type
            )
            val out = ArrayList<ByteArray>(jsMap.size)
            jsMap.values.forEach { value ->
                if (value.isAbsUrl()) {
                    val fileName = MD5Utils.md5Encode(value)
                    var js = aCache.getAsString(fileName)
                    if (js == null) {
                        js = runBlocking {
                            okHttpClient.newCallStrResponse {
                                url(value)
                            }.body
                        }
                        if (js != null) {
                            aCache.put(fileName, js)
                        } else {
                            throw NoStackTraceException("下载jsLib-${value}失败")
                        }
                    }
                    out.add(QuickJsEngine.compile(js).bytecode)
                }
            }
            return out
        }
        return listOf(QuickJsEngine.compile(jsLib).bytecode)
    }

}
