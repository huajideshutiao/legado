package io.legado.desktop.js

import androidx.collection.LruCache
import com.script.quickjs.QuickJsEngine
import com.script.quickjs.ScriptBindings
import io.legado.app.exception.NoStackTraceException
import io.legado.app.help.http.OkHttpClientProviders
import io.legado.app.help.http.newCallStrResponse
import io.legado.app.model.SharedJsScope
import io.legado.app.model.script.JsBindingInjector
import io.legado.app.model.script.JsScope
import io.legado.app.model.script.SharedJsScopeProvider
import io.legado.app.model.script.quickjs.QuickJsJsScope
import io.legado.app.utils.KS_JSON
import io.legado.app.utils.MD5Utils
import io.legado.app.utils.isAbsUrl
import io.legado.app.utils.isJsonObject
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.decodeFromString
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import kotlin.coroutines.CoroutineContext

/**
 * 桌面端 [SharedJsScopeProvider] 简化实现。
 *
 * # 与 app 端 [io.legado.app.model.script.quickjs.QuickJsSharedJsScopeProvider] 区别
 * - app 端用 `ACache.get(cacheFolder)` (Android 文件缓存) 持久化 jsLib URL 下载内容;
 *   桌面端无 ACache, 改用 in-memory Map 缓存 (进程退出即丢, 重启重新下载)
 * - app 端 cacheFolder 走 `appCtx.cacheDir` (Android Context);
 *   桌面端无 appCtx, 直接 in-memory 不需要 cacheFolder
 * - 三层缓存结构 (bytecodeCache + ThreadLocal LRU + versionSeq) 与 app 端完全一致,
 *   保持 quickjs 共享 scope 的核心语义: jsLib 编译一次为 bytecode, 跨线程/跨实例复用
 *
 * # 注册时机
 * 由 [registerDesktopJsEngines] 在桌面端 main 入口早期注册, 任何 shared 中
 * `SharedJsScope.getScope(...)` 调用之前 (BaseSource.evalJS / AnalyzeRule 等 JS eval 入口)。
 *
 * # 未注册影响
 * SharedJsScope.provider() 会抛 IllegalStateException "SharedJsScopeProviders 未注册",
 * 被 runCatching 吞掉, 表现为书源 jsLib 规则失效 (source.lib 注入失败, JS 调用 jsLib
 * 函数报 ReferenceError)。
 *
 * # JSON Map jsLib 处理
 * 与 app 端对齐: jsLib 是 `{"name1": "url1", "name2": "url2"}` 形式时, 按 url 下载每个
 * js 文件后编译; 不同点是用 in-memory Map 替代 ACache 缓存下载内容, 进程退出即丢。
 */
object DesktopQuickJsSharedJsScopeProvider : SharedJsScopeProvider {

    /** 每线程缓存的 ctx 数, 与 app 端一致。 */
    private const val PER_THREAD_LRU_SIZE = 4

    /** jsLib URL 下载内容 in-memory 缓存 (替代 app 端 ACache)。 */
    private val jsLibContentCache = ConcurrentHashMap<String, String>()

    private class BytecodeEntry(
        val bytecodes: List<ByteArray>,
        val version: Long,
    )

    private class CtxEntry(val ctx: com.script.quickjs.QuickJsContext, val version: Long)

    private val bytecodeCache = ConcurrentHashMap<String, BytecodeEntry>()
    private val versionSeq = AtomicLong(0)

    private val threadCache = ThreadLocal.withInitial {
        LruCache<String, CtxEntry>(PER_THREAD_LRU_SIZE)
    }

    override fun getScope(
        jsLib: String?,
        enableDangerousApi: Boolean,
        coroutineContext: CoroutineContext?,
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
                // 与 app 端一致: jsLib 顶层代码也能读 platform/image (与 JsBindings 注入一致)
                this["platform"] = JsBindingInjector.platform
                this["image"] = JsBindingInjector.image
            }
        )
        for (bc in bytecodeEntry.bytecodes) {
            QuickJsEngine.evalBytecode(bc, scope, coroutineContext)
        }
        // LRU 淘汰仅放手强引用, 不显式 close (与 app 端一致, 旧 ctx 由 GC + PhantomReference 兜底)
        perThread.put(key, CtxEntry(scope, bytecodeEntry.version))
        return QuickJsJsScope(scope)
    }

    override fun remove(jsLib: String?) {
        if (jsLib.isNullOrBlank()) {
            return
        }
        val key = MD5Utils.md5Encode(jsLib)
        bytecodeCache.remove(key)
    }

    override fun clearAll() {
        bytecodeCache.clear()
        // ThreadLocal threadCache 无法跨线程清理, 依赖版本号 + LRU 淘汰自然回收
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
     * - JSON Map 形式 `{"name1": "url1"}`: 按 url 下载 js 文件 (in-memory 缓存) 后分别编译
     * - 普通 JS 字符串: 直接编译为单个 bytecode
     */
    private fun compileJsLib(jsLib: String): List<ByteArray> {
        if (jsLib.isJsonObject()) {
            val jsMap: Map<String, String> = KS_JSON.decodeFromString(jsLib)
            val out = ArrayList<ByteArray>(jsMap.size)
            jsMap.values.forEach { value ->
                if (value.isAbsUrl()) {
                    val fileName = MD5Utils.md5Encode(value)
                    // in-memory 缓存优先, 未命中走 HTTP 下载
                    var js = jsLibContentCache[fileName]
                    if (js == null) {
                        js = runBlocking {
                            OkHttpClientProviders.get().okHttpClient.newCallStrResponse {
                                url(value)
                            }.body
                        }
                        if (js != null) {
                            jsLibContentCache[fileName] = js
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
