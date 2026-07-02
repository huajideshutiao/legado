package io.legado.app.model.script.quickjs

import androidx.collection.LruCache
import com.google.gson.reflect.TypeToken
import com.script.quickjs.QuickJsContext
import com.script.quickjs.QuickJsEngine
import com.script.quickjs.ScriptBindings
import io.legado.app.exception.NoStackTraceException
import io.legado.app.help.http.newCallStrResponse
import io.legado.app.help.http.okHttpClient
import io.legado.app.model.script.JsScope
import io.legado.app.model.script.SharedJsScopeProvider
import io.legado.app.model.script.quickjs.QuickJsSharedJsScopeProvider.bytecodeCache
import io.legado.app.model.script.quickjs.QuickJsSharedJsScopeProvider.getScope
import io.legado.app.model.script.quickjs.QuickJsSharedJsScopeProvider.remove
import io.legado.app.model.script.quickjs.QuickJsSharedJsScopeProvider.threadCache
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
 * quickjs 版 SharedJsScope provider。
 *
 * 从原 `app/model/SharedJsScope.kt` 搬迁的 quickjs 逻辑，返回 [JsScope]（包装 [QuickJsJsScope]）。
 *
 * 三层缓存（对齐原实现）：
 * 1. 全局 [bytecodeCache]: jsLib 编译一次为 bytecode，跨实例可复用（QuickJsEngine.compile 内部 synchronized）
 * 2. 每线程独占 [threadCache]: LRU<jsLib key -> ctx>，同线程同 jsLib 重复访问无开销
 * 3. [remove] 走版本号失效：删除 bytecode entry，下次 getScope 重建并分配新版本
 *
 * QuickJsContext 不可跨线程共享（rhino 通过 sealObject + Context.enter 实现共享 scope，
 * quickjs 没有等价机制），故采用 ThreadLocal LRU。
 */
object QuickJsSharedJsScopeProvider : SharedJsScopeProvider {

    /**
     * 每线程缓存的 ctx 数。
     *
     * 实际场景：单线程通常串行处理 1-2 个 source，LRU=4 已覆盖"当前 source + 上一个 source"模式。
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

    override fun getScope(
        jsLib: String?,
        enableDangerousApi: Boolean,
        coroutineContext: CoroutineContext?
    ): JsScope? {
        if (jsLib.isNullOrBlank()) {
            return null
        }
        val key = MD5Utils.md5Encode(jsLib)
        val bytecodeEntry = getOrCreateBytecodeEntry(key, jsLib)
        val perThread = threadCache.get()
        val cached = perThread.get(key)
        if (cached != null && cached.version == bytecodeEntry.version) {
            return QuickJsJsScope(cached.ctx)
        }
        val scope = QuickJsEngine.getRuntimeScope(
            ScriptBindings().apply {
                this.dangerousApi = enableDangerousApi
            }
        )
        for (bc in bytecodeEntry.bytecodes) {
            QuickJsEngine.evalBytecode(bc, scope, coroutineContext)
        }
        // LRU 淘汰仅放手强引用，不显式 close（旧 ctx 可能仍被另一处 evalJS 持栈，
        // native 资源由 GC + PhantomReference 兜底释放）。
        perThread.put(key, CtxEntry(scope, bytecodeEntry.version))
        return QuickJsJsScope(scope)
    }

    /**
     * 删除 jsLib 的 bytecode 缓存条目。各线程 LRU 中的 stale ctx 通过版本号在
     * 下次 [getScope] 时被替换。
     *
     * 不能在此处同步 close 任何 ctx：老 ctx 可能仍被某条 evalJS 持栈强引用，
     * 同步释放会与正在执行的 native 调用形成 use-after-free。
     */
    override fun remove(jsLib: String?) {
        if (jsLib.isNullOrBlank()) {
            return
        }
        val key = MD5Utils.md5Encode(jsLib)
        bytecodeCache.remove(key)
    }

    override fun clearAll() {
        bytecodeCache.clear()
        // ThreadLocal 的 threadCache 无法跨线程清理，依赖版本号 + LRU 淘汰自然回收
        // 切换引擎时 clearAll 主要清 bytecodeCache，stale ctx 由 GC 兜底
    }

    private fun getOrCreateBytecodeEntry(key: String, jsLib: String): BytecodeEntry {
        bytecodeCache[key]?.let { return it }
        return bytecodeCache.computeIfAbsent(key) {
            BytecodeEntry(compileJsLib(jsLib), versionSeq.incrementAndGet())
        }
    }

    /**
     * 编译 jsLib 为 bytecode 列表。
     *
     * 支持 JSON Map 形式 jsLib：`{"name1": "url1", "name2": "url2"}`，
     * 按 url 下载每个 js 文件（ACache 缓存）后编译。
     * 普通 JS 字符串直接编译为单个 bytecode。
     */
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
