package io.legado.app.model.script.quickjs

import com.script.quickjs.QuickJsContext
import com.script.quickjs.QuickJsEngine
import com.script.quickjs.ScriptBindings
import io.legado.app.help.CommonLruCache
import io.legado.app.model.script.JsBindingInjector
import io.legado.app.model.script.JsScope
import io.legado.app.model.script.SharedJsScopeProvider
import io.legado.app.utils.KS_JSON
import io.legado.app.utils.MD5Utils
import io.legado.app.utils.isAbsUrl
import io.legado.app.utils.isJsonObject
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import kotlin.coroutines.CoroutineContext

/**
 * quickjs 版 [SharedJsScopeProvider] 公共核心 (Android 与桌面 JVM 共用)。
 *
 * # 下沉位置
 * 原先 app 的 `QuickJsSharedJsScopeProvider` 与 desktop 的
 * `DesktopQuickJsSharedJsScopeProvider` 整体重复 (getScope/remove/clearAll/
 * getOrCreateBytecodeEntry/compileJsLib 与三层缓存结构相同), 收敛到本类;
 * 差异 (jsLib 下载内容的缓存后端 / 下载用 OkHttpClient 单例 / 下载失败文案 /
 * eval 报错包装) 经下方抽象钩子留给两端各自的薄适配。
 *
 * # 三层缓存 (对齐原实现)
 * 1. 全局 bytecodeCache: jsLib 编译一次为 bytecode, 跨实例可复用
 *    (QuickJsEngine.compile 内部 synchronized)
 * 2. 每线程独占 threadCache: LRU<jsLib key -> ctx>, 同线程同 jsLib 重复访问无开销
 *    (QuickJsContext 不可跨线程共享 —— rhino 经 sealObject + Context.enter 实现
 *    共享 scope, quickjs 没有等价机制)
 * 3. remove 走版本号失效: 删除 bytecode entry, 下次 getScope 重建并分配新版本
 *
 * # LRU 实现说明
 * per-thread LRU 用 commonMain 的 [CommonLruCache] (LinkedHashMap LRU +
 * synchronized, 与 androidx.collection.LruCache 语义一致), jvmAndAndroidMain
 * 不引入 androidx.collection 依赖。
 */
abstract class QuickJsSharedJsScopeBase : SharedJsScopeProvider {

    /**
     * 单个 jsLib 的编译产物。
     * label/source 供报错定位 (桌面端 eval 包装钩子用, app 端默认不使用)。
     */
    protected class JsLibBytecode(
        val bytecode: ByteArray,
        val label: String,
        val source: String,
    )

    private class BytecodeEntry(
        val bytecodes: List<JsLibBytecode>,
        val version: Long,
    )

    private class CtxEntry(val ctx: QuickJsContext, val version: Long)

    private companion object {

        /**
         * 每线程缓存的 ctx 数: 单线程通常串行处理 1-2 个 source,
         * LRU=4 已覆盖「当前 source + 上一个 source」模式。
         */
        const val PER_THREAD_LRU_SIZE = 4
    }

    private val bytecodeCache = ConcurrentHashMap<String, BytecodeEntry>()
    private val versionSeq = AtomicLong(0)

    private val threadCache = ThreadLocal.withInitial {
        CommonLruCache<String, CtxEntry>(PER_THREAD_LRU_SIZE)
    }

    // ==================== 平台适配点 (各端薄适配实现) ====================

    /** 取 jsLib URL 下载内容的缓存 (app: ACache 文件持久化 / 桌面: 内存 Map)。 */
    protected abstract fun cachedJsLibContent(name: String): String?

    /** 写 jsLib URL 下载内容缓存。 */
    protected abstract fun storeJsLibContent(name: String, content: String)

    /** 下载 jsLib 文本 (两端用各自的 OkHttpClient 单例; 返回 null 表示失败)。 */
    protected abstract fun downloadJsLibContent(url: String): String?

    /** 下载失败抛的异常 (app: 中文硬编码 / 桌面: i18n 文案)。 */
    protected abstract fun jsLibDownloadFailedException(url: String): Exception

    /**
     * 执行一段 jsLib bytecode; 默认直接 eval。
     * 桌面端复写此钩子, 把 ScriptException 包装成带 jsLib 定位信息的报错。
     */
    protected open fun evalJsLibBytecode(
        entry: JsLibBytecode,
        scope: QuickJsContext,
        coroutineContext: CoroutineContext?,
    ) {
        QuickJsEngine.evalBytecode(entry.bytecode, scope, coroutineContext)
    }

    // ==================== 核心实现 ====================

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
        // withInitial 保证 get() 非空 (K2 对 ThreadLocal.get() 判为可空, 显式断言)
        val perThread = threadCache.get()!!
        val cached = perThread.get(key)
        if (cached != null && cached.version == bytecodeEntry.version) {
            return QuickJsJsScope(cached.ctx)
        }
        val scope = QuickJsEngine.getRuntimeScope(
            ScriptBindings().apply {
                this.dangerousApi = enableDangerousApi
                // jsLib 顶层代码也能读 platform/image (与 JsBindings 注入一致)
                this["platform"] = JsBindingInjector.platform
                this["image"] = JsBindingInjector.image
            }
        )
        for (bc in bytecodeEntry.bytecodes) {
            evalJsLibBytecode(bc, scope, coroutineContext)
        }
        // LRU 淘汰仅放手强引用, 不显式 close (旧 ctx 可能仍被另一处 evalJS 持栈,
        // native 资源由 GC + PhantomReference 兜底释放)
        perThread.put(key, CtxEntry(scope, bytecodeEntry.version))
        return QuickJsJsScope(scope)
    }

    /**
     * 删除 jsLib 的 bytecode 缓存条目。各线程 LRU 中的 stale ctx 通过版本号在
     * 下次 getScope 时被替换。
     *
     * 不能在此处同步 close 任何 ctx: 老 ctx 可能仍被某条 evalJS 持栈强引用,
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
        // ThreadLocal 的 threadCache 无法跨线程清理, 依赖版本号 + LRU 淘汰自然回收;
        // 切换引擎时 clearAll 主要清 bytecodeCache, stale ctx 由 GC 兜底
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
     * - JSON Map 形式 `{"name1": "url1"}`: 按 url 下载每个 js 文件 (经缓存钩子) 后分别编译
     * - 普通 JS 字符串: 直接编译为单个 bytecode
     */
    private fun compileJsLib(jsLib: String): List<JsLibBytecode> {
        if (jsLib.isJsonObject()) {
            val jsMap: Map<String, String> = KS_JSON.decodeFromString(jsLib)
            val out = ArrayList<JsLibBytecode>(jsMap.size)
            jsMap.forEach { (name, value) ->
                if (value.isAbsUrl()) {
                    val fileName = MD5Utils.md5Encode(value)
                    var js = cachedJsLibContent(fileName)
                    if (js == null) {
                        js = downloadJsLibContent(value)
                        if (js != null) {
                            storeJsLibContent(fileName, js)
                        } else {
                            throw jsLibDownloadFailedException(value)
                        }
                    }
                    out.add(JsLibBytecode(QuickJsEngine.compile(js).bytecode, "$name<$value>", js))
                }
            }
            return out
        }
        return listOf(JsLibBytecode(QuickJsEngine.compile(jsLib).bytecode, "inline-jsLib", jsLib))
    }
}
