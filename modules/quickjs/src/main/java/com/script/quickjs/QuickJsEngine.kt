package com.script.quickjs

import com.script.quickjs.QuickJsEngine.cacheBootstrapGlobals
import com.script.quickjs.QuickJsEngine.cacheBootstrapHelpers
import com.script.quickjs.QuickJsEngine.cleanupBindings
import com.script.quickjs.QuickJsEngine.compilerCtx
import com.script.quickjs.QuickJsEngine.createNativeCtx
import com.script.quickjs.QuickJsEngine.enterBindings
import com.script.quickjs.QuickJsEngine.eval
import com.script.quickjs.QuickJsEngine.evalBytecode
import com.script.quickjs.QuickJsEngine.evalInSubScope
import com.script.quickjs.QuickJsEngine.exitBindings
import com.script.quickjs.QuickJsEngine.getRuntimeScope
import com.script.quickjs.QuickJsEngine.injectBindings
import com.script.quickjs.QuickJsEngine.wrapJsForEval
import kotlin.coroutines.CoroutineContext

/**
 * QuickJS 脚本引擎,对应 Rhino 的 [com.script.rhino.RhinoScriptEngine]。
 *
 * 单例 object。每个 [getRuntimeScope] 创建独立的 native JSRuntime + JSContext +
 * [QuickJsContext], 用于实现 SharedJsScope 的作用域隔离。实例创建后立即 evaluate
 * [JsBootstrap.code] (优先用预编译 bytecode,避免每次重新解析) 注入
 * Packages/JavaImporter/JavaAdapter, 并通过 [QuickJsNative.nativeDefineBinding]
 * 注册 10 个 binding 供 JS 调用 Kotlin。
 *
 * 架构 A (自封装 native QuickJS):
 * - 不再依赖 quickjs-kt 的 QuickJs (Phase 5 已移除)
 * - 所有 JS 操作通过 [QuickJsNative] 调用 native API
 * - binding 回调由 native 层统一分发到 [BindingHandler.call]
 *
 * 同步桥接: 业务层 [eval] 是同步签名, native nativeEval 本身是同步 JNI 调用,
 * 无需 runBlocking。但 [QuickJsContext.threadLocalContext] 仍需在 eval 前设置,
 * 让 binding handler 能访问到当前 ctx。
 *
 * 安全名单: 通过 [JsSecurityPolicy] + [JavaObjectBridge] 实现,
 * 由 [ScriptBindings.dangerousApi] 控制, 通过 [QuickJsNative.nativeSetDangerousApi]
 * 同步到 native ctx opaque (供 exotic trap 读取)。
 *
 * 性能优化:
 * - bootstrap 预编译为 bytecode,避免每次 [getRuntimeScope] 重复解析
 * - dangerousApi 仅在变化时同步到 native ctx opaque (减少 JNI 调用)
 * - [injectBindings] 返回注入键, [cleanupBindings] 可清理,实现子 scope 隔离
 */
@Suppress("MemberVisibilityCanBePrivate")
object QuickJsEngine {

    /**
     * bootstrap 预编译 bytecode,懒加载。
     *
     * 解析 JS 是较慢的操作(bootstrap ~6KB,需要词法+语法分析+字节码生成)。
     * 缓存 bytecode 后,每次 [createNativeCtx] 只需 nativeEvalBytecode,跳过解析步骤,
     * 显著降低 [getRuntimeScope] 的初始化开销。
     *
     * 注意: bytecode 在临时 ctx 上编译, 在目标 ctx 上执行。
     * QuickJS bytecode 跨实例兼容 (格式标准), 只要 quickjs-ng 版本一致即可。
     */
    @Volatile
    private var bootstrapBytecode: ByteArray? = null

    /**
     * 用于编译 bootstrap bytecode 的临时 ctx, 编译后复用避免重复创建。
     * 编译不需要 bootstrap, 只需要空的 native ctx。
     */
    @Volatile
    private var compilerCtx: Long = 0L

    // ============ public API (保持与旧版兼容) ============

    /**
     * 执行 JS(最常用入口)。
     *
     * 对应 RhinoScriptEngine.eval(js, bindingsConfig)。
     * 内部构建 bindings 并创建新 scope 执行,执行后立即 close 释放 native 资源
     * (JSRuntime/JSContext/Java 句柄),避免大量一次性 eval 导致 native 内存泄漏。
     *
     * 注意: 此方法每次都创建新 scope,适合一次性 JS 执行。
     * 高频复用场景(如 BaseSource.evalJS)应使用 SharedJsScope 缓存 scope,
     * 配合 [injectBindings] + [cleanupBindings] 实现子 scope 隔离。
     *
     * 返回值说明: eval 期间创建的 Java 对象在 close 前已由 native 层 toJavaObject
     * 解包为原始 Java 对象引用,close 只释放句柄映射,不影响已返回的 Java 对象。
     */
    fun eval(js: String, bindingsConfig: ScriptBindings.() -> Unit = {}): Any? {
        if (js.isBlank()) return null
        val bindings = ScriptBindings().apply(bindingsConfig)
        val ctx = getRuntimeScope(bindings)
        return try {
            eval(js, ctx, null)
        } finally {
            ctx.close()
        }
    }

    /**
     * 执行 JS,使用预构建的 [ScriptBindings]。
     *
     * 对应 RhinoScriptEngine.eval(js, bindings: ScriptBindings)。
     * 用于 BookExtensions/RegexExtensions/FileBook 等直接传入 bindings 的场景。
     * 执行后立即 close 释放 native 资源,避免内存泄漏。
     */
    fun eval(js: String, bindings: ScriptBindings): Any? {
        if (js.isBlank()) return null
        val ctx = getRuntimeScope(bindings)
        return try {
            eval(js, ctx, null)
        } finally {
            ctx.close()
        }
    }

    /**
     * 在指定 scope 上执行 JS。
     *
     * 对应 RhinoScriptEngine.eval(reader, scope, coroutineContext)。
     * 用于 SharedJsScope 复用作用域、BaseSource.evalJS 注入 sharedScope。
     *
     * @param scope [getRuntimeScope] 返回的 context,或 SharedJsScope 缓存的 context
     * @param coroutineContext 外层协程上下文,传递给 [QuickJsContext] 以支持 ensureActive
     */
    fun eval(js: String, scope: QuickJsContext, coroutineContext: CoroutineContext?): Any? {
        if (js.isBlank()) return null
        return withEvalContext(scope, coroutineContext) {
            try {
                syncDangerousApiIfNeeded(scope)
                val result = QuickJsNative.nativeEval(scope.ctxPtr, js)
                unwrapReturnValue(result)
            } catch (e: JsNativeException) {
                throw ScriptException(e.message)
            }
        }
    }

    /**
     * 在指定 scope 上执行 JS,并注入 bindings 变量。
     *
     * 用于 AnalyzeRule/BaseSource 复用 SharedJsScope 缓存的 scope:
     * 变量注入到全局作用域(覆盖同名变量),eval 后由调用方决定是否调用 [cleanupBindings]。
     *
     * 对应 rhino 的 `bindings.prototype = sharedScope` + `eval(js, bindings)`。
     * quickjs 没有 prototype 继承,改为直接在 sharedScope 上注入变量。
     *
     * 注意: 此方法不做清理,调用方如需子 scope 隔离,应使用 [injectBindings] + [eval] + [cleanupBindings]。
     */
    fun eval(
        js: String,
        scope: QuickJsContext,
        bindings: ScriptBindings,
        coroutineContext: CoroutineContext?
    ): Any? {
        // injectBindings 内部会同步 dangerousApi (以 bindings 为准,支持不同书源切换)
        // 并返回注入的键列表 (此处忽略返回值,如需子 scope 隔离应显式调 injectBindings + eval + cleanupBindings)
        injectBindings(scope, bindings)
        return eval(js, scope, coroutineContext)
    }

    /**
     * 执行编译后的 bytecode。
     *
     * 用于 [CompiledScript.eval],避免重复解析 JS 源码。
     */
    fun evalBytecode(
        bytecode: ByteArray,
        scope: QuickJsContext,
        coroutineContext: CoroutineContext?
    ): Any? {
        return withEvalContext(scope, coroutineContext) {
            try {
                syncDangerousApiIfNeeded(scope)
                val result = QuickJsNative.nativeEvalBytecode(scope.ctxPtr, bytecode)
                unwrapReturnValue(result)
            } catch (e: JsNativeException) {
                throw ScriptException(e.message)
            }
        }
    }

    /**
     * 在 SharedJsScope 的 topScope 上执行 bytecode, bindings 压入 `__bindingsStack__` 子 scope 栈,
     * 由 [wrapJsForEval] 的 `with(__currentBindings())` 让 user JS 走栈顶命中, 不污染 topScope
     * (对齐 rhino `childScope.prototype = topScope` 语义)。
     *
     * jsLib 里 topScope 自由函数看不到 bindings; 共享函数写 jsLib, 业务变量走参数。
     */
    fun evalInSubScope(
        compiled: CompiledScript,
        scope: QuickJsContext,
        bindings: ScriptBindings,
        coroutineContext: CoroutineContext?
    ): Any? {
        scope.dangerousApi = bindings.dangerousApi
        // nativeCallJsHandle 的 fromJavaObject 绕过 isObjectVisible, 先在 Kotlin 侧过滤
        val kvs = buildBindingKvs(bindings)
        val previousThreadContext = QuickJsContext.threadLocalContext.get()
        QuickJsContext.threadLocalContext.set(scope)
        try {
            syncDangerousApiIfNeeded(scope)
            enterBindings(scope, kvs)
            try {
                return evalBytecode(compiled.bytecode, scope, coroutineContext)
            } finally {
                exitBindings(scope)
            }
        } finally {
            QuickJsContext.threadLocalContext.set(previousThreadContext)
        }
    }

    /**
     * 把 bindings 铺成 `[k1,v1,k2,v2,...]` 传给 `__enterBindings`。
     * 非法变量名 / 不可见 Java 对象跳过 (基本类型/null 保留)。
     */
    private fun buildBindingKvs(bindings: ScriptBindings): Array<Any?> {
        val dangerousApi = bindings.dangerousApi
        val out = ArrayList<Any?>(bindings.size * 2)
        for ((key, value) in bindings) {
            if (!isValidVarName(key)) continue
            if (value != null && value !is String && value !is Boolean && value !is Number) {
                if (!JsSecurityPolicy.isObjectVisible(value, dangerousApi)) continue
            }
            out.add(key)
            out.add(value)
        }
        return out.toTypedArray()
    }

    private fun enterBindings(scope: QuickJsContext, kvs: Array<Any?>) {
        // 懒加载: 单次 eval 路径不进入 evalInSubScope, 此处首次访问时才抓取 helper 句柄,
        // 避免 createNativeCtx 阶段对所有 ctx 都跑一遍 (省 4 次 JNI/ctx)。
        if (!scope.bootstrapHelpersCached) {
            cacheBootstrapHelpers(scope)
            scope.bootstrapHelpersCached = true
        }
        val handle = scope.enterBindingsHandle
        if (handle == 0L) return
        QuickJsNative.nativeCallJsHandle(handle, kvs)
    }

    private fun exitBindings(scope: QuickJsContext) {
        // enterBindings 已触发 helper 抓取 (同 scope 内), 此处直接读, 不再判标志。
        val handle = scope.exitBindingsHandle
        if (handle == 0L) return
        QuickJsNative.nativeCallJsHandle(handle, EMPTY_ARGS)
    }

    /** [exitBindings] / [cleanupBindings] 空参调用复用. */
    private val EMPTY_ARGS: Array<Any?> = emptyArray()

    /**
     * 注入 bindings 变量到已有 scope 的全局作用域,返回注入的键列表(用于后续清理)。
     *
     * 用于复用 SharedJsScope 缓存的 scope:每次 evalJS 前注入最新变量。
     * 变量覆盖同名全局变量(对应 rhino 的子 scope 写入)。
     *
     * @return 注入成功的键列表,供 [cleanupBindings] 使用以实现子 scope 隔离
     */
    fun injectBindings(scope: QuickJsContext, bindings: ScriptBindings): List<String> {
        val injectedKeys = mutableListOf<String>()
        val previousThreadContext = QuickJsContext.threadLocalContext.get()
        QuickJsContext.threadLocalContext.set(scope)
        try {
            // 同步 dangerousApi (仅变化时调用 nativeSetDangerousApi)
            scope.dangerousApi = bindings.dangerousApi
            syncDangerousApiIfNeeded(scope)
            val globalHandle = fetchGlobalHandle(scope.ctxPtr)
            try {
                for ((key, value) in bindings) {
                    if (injectVariable(
                            scope.ctxPtr,
                            globalHandle,
                            key,
                            value,
                            bindings.dangerousApi
                        )
                    ) {
                        injectedKeys.add(key)
                    }
                }
            } finally {
                if (globalHandle != 0L) QuickJsNative.nativeFreeHandle(scope.ctxPtr, globalHandle)
            }
        } finally {
            QuickJsContext.threadLocalContext.set(previousThreadContext)
        }
        return injectedKeys
    }

    /** 抓取一次 globalThis 句柄, 供批量注入时复用 (免每 key 一次 GetGlobalObject + FreeHandle) */
    private fun fetchGlobalHandle(ctxPtr: Long): Long {
        val globalObj = QuickJsNative.nativeGetGlobalObject(ctxPtr)
        return (globalObj as? Number)?.toLong() ?: 0L
    }

    /**
     * 清理 [injectBindings] 注入的变量,实现子 scope 隔离。
     *
     * 用于 eval(js, scope, bindings) 执行后:删除注入的全局变量,
     * 避免下次复用 scope 时变量泄漏(如不同书源切换时,旧 source 还可见)。
     *
     * 对应 rhino 的子 scope 关闭时 bindings 自动清理。
     *
     * 精简版 (方案B): 移除 JS 层 __cleanupBindings, 直接在 Kotlin 层处理。
     * - bootstrap globals (java/Packages/JavaImporter 等) 是 [[Configurable]]:false,
     *   delete 静默失败, 走 nativeSetPropertyHandle 恢复缓存的初值句柄 ([[Writable]]:true 允许);
     * - 其它 (cache/book/source 等) 是 injectVariable 创建的 [[Configurable]]:true,
     *   走 nativeDeleteProperty (等价 `delete globalThis[k]`), 让 `typeof k` 回到 `undefined`.
     */
    fun cleanupBindings(scope: QuickJsContext, keys: List<String>) {
        if (keys.isEmpty()) return
        val validKeys = keys.filter { isValidVarName(it) }
        if (validKeys.isEmpty()) return
        // 懒加载: 单次 eval 路径不调用 cleanup, 此处首次进入时才抓取 bootstrap globals 初值,
        // 避免 createNativeCtx 阶段对所有 ctx 都跑一遍 (省 ~25 次 JNI/ctx)。
        if (!scope.bootstrapGlobalsCached) {
            cacheBootstrapGlobals(scope)
            scope.bootstrapGlobalsCached = true
        }
        val previousThreadContext = QuickJsContext.threadLocalContext.get()
        QuickJsContext.threadLocalContext.set(scope)
        try {
            val globalHandle = fetchGlobalHandle(scope.ctxPtr)
            if (globalHandle == 0L) return
            try {
                for (key in validKeys) {
                    val cachedHandle = scope.bootstrapGlobalsHandles[key]
                    if (cachedHandle != null && cachedHandle != 0L) {
                        // bootstrap 名字: 恢复缓存的初值句柄 (Configurable:false 无法 delete)
                        QuickJsNative.nativeSetPropertyHandle(
                            scope.ctxPtr,
                            globalHandle,
                            key,
                            cachedHandle
                        )
                    } else {
                        // 业务注入名字: 真删除 (Configurable:true)
                        QuickJsNative.nativeDeleteProperty(scope.ctxPtr, globalHandle, key)
                    }
                }
            } finally {
                QuickJsNative.nativeFreeHandle(scope.ctxPtr, globalHandle)
            }
        } finally {
            QuickJsContext.threadLocalContext.set(previousThreadContext)
        }
    }

    /**
     * 编译 JS 为 bytecode,对应 RhinoScriptEngine.compile(script)。
     *
     * 使用临时 ctx (复用 [compilerCtx]), compile 不执行代码。
     * bytecode 可缓存复用,通过 [evalBytecode] 的 scope 执行 (QuickJS bytecode 跨实例兼容)。
     *
     * 线程安全: [compilerCtx] 是全局单例, QuickJS 的 JSContext 非线程安全,
     * 多协程并发编译 (如书架刷新 onEachParallel 并发 evalJS) 会破坏 ctx 内部状态导致 native crash。
     * 用 synchronized(this) 序列化所有 compile 调用, 保证同一时刻只有一个线程操作 compilerCtx。
     * (compile 是纯 CPU 操作, 无 IO 等待, 串行化对吞吐影响有限)
     */
    fun compile(script: String): CompiledScript = synchronized(this) {
        val ctxPtr = getCompilerCtx()
        val bytecode = try {
            QuickJsNative.nativeCompile(ctxPtr, script)
        } catch (e: JsNativeException) {
            // native 层编译失败 (如语法错误) 会抛 JsNativeException 携带实际错误信息,
            // 转为 ScriptException 保持与 eval 路径一致的异常类型
            throw ScriptException(e.message)
        }
        bytecode ?: throw ScriptException("Compile failed", null)
        CompiledScript(bytecode)
    }

    /**
     * 复用目标 scope 编译 JS,避免创建临时 ctx。
     *
     * 用于 AnalyzeRule 等已知目标 scope 的场景:
     * 编译与执行在同一 scope,符号解析一致,且无临时 ctx 初始化开销。
     *
     * @param script JS 源码
     * @param scope 目标 scope (执行时也用此 scope)
     */
    fun compile(script: String, scope: QuickJsContext): CompiledScript {
        val previousThreadContext = QuickJsContext.threadLocalContext.get()
        QuickJsContext.threadLocalContext.set(scope)
        try {
            val bytecode = QuickJsNative.nativeCompile(scope.ctxPtr, script)
                ?: throw ScriptException("Compile failed", null)
            return CompiledScript(bytecode)
        } finally {
            QuickJsContext.threadLocalContext.set(previousThreadContext)
        }
    }

    /**
     * 创建运行时 scope,对应 RhinoScriptEngine.getRuntimeScope(bindings)。
     *
     * 创建新 native JSRuntime + JSContext → evaluate bootstrap bytecode(预编译,跳过解析)
     * → 注册 binding → 注入 bindings 变量 → 返回 context。
     * SharedJsScope 用此方法创建共享作用域;BaseSource.evalJS 无 sharedScope 时也用此方法。
     */
    fun getRuntimeScope(bindings: ScriptBindings): QuickJsContext {
        val ctx = createNativeCtx()
        val previous = QuickJsContext.threadLocalContext.get()
        QuickJsContext.threadLocalContext.set(ctx)
        try {
            // 同步 dangerousApi (仅变化时调用 nativeSetDangerousApi, bootstrap 默认 false)
            ctx.dangerousApi = bindings.dangerousApi
            syncDangerousApiIfNeeded(ctx)
            // 注入 bindings 里的变量 (java/source/baseUrl/cookie/cache 等)
            val globalHandle = fetchGlobalHandle(ctx.ctxPtr)
            try {
                for ((key, value) in bindings) {
                    injectVariable(ctx.ctxPtr, globalHandle, key, value, bindings.dangerousApi)
                }
            } finally {
                if (globalHandle != 0L) QuickJsNative.nativeFreeHandle(ctx.ctxPtr, globalHandle)
            }
        } finally {
            QuickJsContext.threadLocalContext.set(previous)
        }
        return ctx
    }

    /**
     * 解包 JS 返回值,对应 RhinoScriptEngine.unwrapReturnValue。
     *
     * JS 返回的 JavaObject (native JavaObjectClass 实例) 解包为原始 Java 对象,
     * 其他类型原样返回。
     *
     * 注意: 与旧版不同, 不再依赖 __java_handle__ 字段。
     * native 层 toJavaObject 已对 JavaObjectClass 实例做解包 (返回原始 jobject),
     * 但 basic 类型 (String/Number/Boolean) 直接返回, 不需要解包。
     * 此方法保留兼容性, 实际 native 层已处理大部分解包, 这里只处理边界情况。
     */
    private fun unwrapReturnValue(result: Any?): Any? {
        // native 层 toJavaObject 已对 JavaObject 解包为原始 Java 对象, 这里直接返回
        return result
    }

    /**
     * 创建 native JSRuntime + JSContext 并注入 bootstrap + 注册 binding。
     *
     * 优先用预编译 bytecode,避免每次重新解析 bootstrap 源码 (~6KB)。
     *
     * 性能优化 (懒加载): [cacheBootstrapHelpers] / [cacheBootstrapGlobals] 不在此立即调用,
     * 改为在 [enterBindings] / [cleanupBindings] 首次进入时按需触发。
     * 单次 [eval] 一次性场景从不走 evalInSubScope/cleanupBindings, 可省 ~29 次 JNI。
     */
    private fun createNativeCtx(): QuickJsContext {
        val rtPtr = QuickJsNative.nativeCreateRuntime()
        if (rtPtr == 0L) {
            throw ScriptException("Failed to create JSRuntime", null)
        }
        val ctxPtr = QuickJsNative.nativeCreateContext(rtPtr)
        if (ctxPtr == 0L) {
            QuickJsNative.nativeFreeRuntime(rtPtr)
            throw ScriptException("Failed to create JSContext", null)
        }
        // 先注册所有 binding, 再执行 bootstrap (bootstrap 中使用了这些 binding 函数)
        registerBindings(ctxPtr)
        // evaluate bootstrap bytecode (首次启动时编译并缓存, 后续直接复用)
        val bytecode = getBootstrapBytecode()
        QuickJsNative.nativeEvalBytecode(ctxPtr, bytecode)
        val ctx = QuickJsContext(rtPtr, ctxPtr)
        // bootstrap helper / globals 句柄懒加载: 仅在 evalInSubScope / cleanupBindings
        // 首次进入时抓取, 单次 eval 路径不访问, 省掉 29 次 JNI (4 + 25)。
        return ctx
    }

    /**
     * bootstrap 全局变量名列表 (用于缓存初值)。
     */
    private val BOOTSTRAP_GLOBAL_NAMES = arrayOf(
        "Packages", "java", "javax", "android", "org", "com", "io", "cn",
        "JavaImporter", "JavaAdapter", "importClass", "importPackage"
    )

    /**
     * 缓存 bootstrap globals 的初值句柄。
     *
     * 精简版 (方案B): cleanup 移到 Kotlin 层, 需要抓取初值用于恢复。
     * bootstrap 执行后通过 nativeGetProperty 抓取每个 global 的句柄,
     * 存储到 ctx.bootstrapGlobalsHandles。
     */
    private fun cacheBootstrapGlobals(ctx: QuickJsContext) {
        val ctxPtr = ctx.ctxPtr
        val globalHandle = fetchGlobalHandle(ctxPtr)
        if (globalHandle == 0L) return
        try {
            for (name in BOOTSTRAP_GLOBAL_NAMES) {
                val handle = getFuncHandle(ctxPtr, globalHandle, name)
                if (handle != 0L) {
                    ctx.bootstrapGlobalsHandles[name] = handle
                }
            }
        } finally {
            QuickJsNative.nativeFreeHandle(ctxPtr, globalHandle)
        }
    }

    /**
     * 一次性抓取 bootstrap 里 binding 生命周期 helper 的函数句柄, 缓存到 [QuickJsContext] 上,
     * 后续通过 [QuickJsNative.nativeCallJsHandle] 直接调用, 免掉每次 nativeEval 解析。
     *
     * 精简版 (方案B): 只抓取 enter/exitBindings, cleanup 已移到 Kotlin 层。
     */
    private fun cacheBootstrapHelpers(ctx: QuickJsContext) {
        val ctxPtr = ctx.ctxPtr
        val globalHandle = fetchGlobalHandle(ctxPtr)
        if (globalHandle == 0L) return
        try {
            ctx.enterBindingsHandle = getFuncHandle(ctxPtr, globalHandle, "__enterBindings")
            ctx.exitBindingsHandle = getFuncHandle(ctxPtr, globalHandle, "__exitBindings")
        } finally {
            QuickJsNative.nativeFreeHandle(ctxPtr, globalHandle)
        }
    }

    private fun getFuncHandle(ctxPtr: Long, globalHandle: Long, name: String): Long =
        (QuickJsNative.nativeGetProperty(ctxPtr, globalHandle, name) as? Number)?.toLong() ?: 0L

    /**
     * 获取预编译的 bootstrap bytecode,懒加载。
     *
     * 首次调用时用 [compilerCtx] 编译 [JsBootstrap.code], 后续直接复用。
     * bytecode 与具体 ctx 实例无关 (QuickJS bytecode 格式标准), 可跨实例执行。
     */
    private fun getBootstrapBytecode(): ByteArray {
        bootstrapBytecode?.let { return it }
        synchronized(this) {
            bootstrapBytecode?.let { return it }
            // 用 compilerCtx 编译, 避免在目标 ctx 上编译 (避免污染)
            val compilerCtxPtr = getCompilerCtx()
            val bytecode = QuickJsNative.nativeCompile(compilerCtxPtr, JsBootstrap.code)
                ?: throw ScriptException("Failed to compile bootstrap", null)
            bootstrapBytecode = bytecode
            return bytecode
        }
    }

    /**
     * 创建独立的 native ctx (已注入 bootstrap + 注册 binding),返回 QuickJsContext。
     *
     * 用于 [io.legado.app.ui.association.JsActivity] 等需要独立 scope 的场景,
     * 不与 SharedJsScope 缓存的 scope 共享。
     *
     * 注意: 与旧版不同, 返回类型改为 [QuickJsContext] (而非 quickjs-kt 的 QuickJs)。
     * 业务层 JsActivity 通过 cx.close() 释放, 不再调用 cx.quickJs.close()。
     */
    fun createQuickJsForActivity(): QuickJsContext = createNativeCtx()

    /**
     * 注入单个 bindings 变量到 native ctx 全局作用域。
     *
     * - null/基本类型 (String/Number/Boolean) 直接走 nativeSetProperty
     *   (fromJavaObject 把 Java 值转 JS 值), 免掉 JS 解析开销
     * - Java 对象通过 [QuickJsNative.nativeWrapJavaObject] 包装为 JSValue,
     *   再用 nativeSetPropertyHandle 设置到 globalThis
     *
     * 走属性赋值不创建词法绑定, 不会与书源 JS 的 `let`/`const` 冲突 (对齐 rhino 行为)。
     *
     * @param globalHandle 调用方预先抓取好的 globalThis 句柄, 复用避免逐 key 重取
     * @return true 表示注入成功 (变量名合法), false 表示跳过 (变量名非法或对象不可见)
     */
    private fun injectVariable(
        ctxPtr: Long,
        globalHandle: Long,
        key: String,
        value: Any?,
        dangerousApi: Boolean
    ): Boolean {
        if (!isValidVarName(key)) return false
        if (globalHandle == 0L) return false
        when {
            // null/String/Boolean/Number: fromJavaObject 直接转 JS 值, 不走 JS 解析
            value == null || value is String || value is Boolean || value is Number -> {
                QuickJsNative.nativeSetProperty(ctxPtr, globalHandle, key, value)
            }
            else -> {
                // Java 对象: 显式安全检查 + wrap + SetPropertyHandle
                // (nativeSetProperty 走 fromJavaObject 会自动 wrap, 但那绕过 isObjectVisible)
                if (!JsSecurityPolicy.isObjectVisible(value, dangerousApi)) return false
                val jsValueHandle = QuickJsNative.nativeWrapJavaObject(ctxPtr, value)
                val valueHandle = (jsValueHandle as? Number)?.toLong() ?: 0L
                if (valueHandle == 0L) return false
                QuickJsNative.nativeSetPropertyHandle(ctxPtr, globalHandle, key, valueHandle)
                // SetPropertyHandle 内部 DupValue, 这里安全 Free
                QuickJsNative.nativeFreeHandle(ctxPtr, valueHandle)
            }
        }
        return true
    }

    private fun isValidVarName(name: String): Boolean {
        if (name.isEmpty()) return false
        val first = name[0]
        if (!first.isJavaIdentifierStart()) return false
        for (i in 1 until name.length) {
            if (!name[i].isJavaIdentifierPart()) return false
        }
        return true
    }

    /**
     * 同步 dangerousApi 到 native ctx opaque (仅当 scope.dangerousApi 与上次同步的值不同时)。
     *
     * 精简版 (方案B): 移除 JS 全局变量同步, dangerousApi 从 ctx opaque 读取 (__getDangerousApi binding)。
     * 只需调用 nativeSetDangerousApi 设置 ctx opaque, JS 层的 __getDangerousApi() 自动返回正确值。
     */
    private fun syncDangerousApiIfNeeded(scope: QuickJsContext) {
        if (scope.lastSyncedDangerousApi == scope.dangerousApi) return
        QuickJsNative.nativeSetDangerousApi(scope.ctxPtr, scope.dangerousApi)
        scope.lastSyncedDangerousApi = scope.dangerousApi
    }

    /**
     * `(function(){with(__currentBindings()){return eval(<源码>);}})()` 三层包装:
     * - `with(__currentBindings())`: bindings 走 [evalInSubScope] 压栈成栈顶对象, user JS 里
     *   `java`/`cache`/`source` 走 with 命中; 空栈时穿透到 globalThis。
     * - IIFE 隔离 `let`/`const`/`var`, 不污染 topScope (避免 "redeclaration")。
     * - eval + return: 返回末尾表达式值, 顶层 return 生效 (对齐 rhino script.exec)。
     *
     * 用于复用 sharedScope 的场景; jsLib 本身在 topScope 上定义, 不需要包裹。
     */
    fun wrapJsForEval(jsStr: String): String {
        val jsLiteral = JsStringUtils.escape(jsStr)
        return "(function(){with(__currentBindings()){return eval($jsLiteral);}})()"
    }

    /** [wrapJsForEval] 包装后编译为 bytecode, 供 [evalInSubScope] 执行。 */
    fun compileForSubScope(jsStr: String): CompiledScript = compile(wrapJsForEval(jsStr))

    /**
     * 在指定 scope 上执行 nativeEval,处理 ThreadLocalContext / 递归检查 / coroutineContext 同步。
     *
     * 抽取 [eval] 和 [evalBytecode] 的共用模板,确保 ThreadLocal 设置 / 资源清理一致。
     *
     * 注意: block 是非 suspend 的, 因为 nativeEval 是同步 JNI 调用。
     * 用 inline 避免额外对象分配 (性能优化)。
     */
    private inline fun <T> withEvalContext(
        scope: QuickJsContext,
        coroutineContext: CoroutineContext?,
        crossinline block: () -> T
    ): T {
        val previousThreadContext = QuickJsContext.threadLocalContext.get()
        QuickJsContext.threadLocalContext.set(scope)
        val previousCoroutineContext = scope.coroutineContext
        val previousAllowScriptRun = scope.allowScriptRun
        if (coroutineContext != null) {
            scope.coroutineContext = coroutineContext
        }
        scope.allowScriptRun = true
        scope.recursiveCount++
        try {
            scope.checkRecursive()
            return block()
        } finally {
            scope.coroutineContext = previousCoroutineContext
            scope.allowScriptRun = previousAllowScriptRun
            scope.recursiveCount--
            QuickJsContext.threadLocalContext.set(previousThreadContext)
        }
    }

    /**
     * 注册所有 binding,供 JS bootstrap 调用 Kotlin。
     *
     * 架构 A: 通过 [QuickJsNative.nativeDefineBinding] 注册 binding 名,
     * native 层的 jsBindingCall 回调统一分发到 [BindingHandler.call]。
     *
     * 注册的 binding 列表 (与 [JsBootstrap] 依赖一致):
     * - __loadJavaClass / __classExists / __isInterface (类加载)
     * - __newJavaInstance / __callStaticMethod / __getStaticField / __setStaticField (静态成员)
     * - __newJavaAdapter / __registerJsFunctionNative (JavaAdapter)
     * - __wrapJavaHandle (句柄包装, 供 JsFunctionHandle 用)
     * - __getDangerousApi (从 ctx opaque 读取 dangerousApi, native 层直接处理不走 Kotlin)
     */
    private fun registerBindings(ctxPtr: Long) {
        // 性能优化: 一次 JNI 调用批量注册 (替代原 12 次 nativeDefineBinding 循环, 省 11 次 JNI)。
        val bindings = arrayOf(
            "__loadJavaClass",
            "__classExists",
            "__isInterface",
            "__newJavaInstance",
            "__callStaticMethod",
            "__getStaticField",
            "__setStaticField",
            "__newJavaAdapter",
            "__registerJsFunctionNative",
            "__wrapJavaHandle",
            "__getDangerousApi"
        )
        QuickJsNative.nativeDefineBindings(ctxPtr, bindings)
    }

    /**
     * 获取用于编译 bootstrap/业务脚本 的临时 ctx (复用)。
     *
     * 不需要注入 bootstrap, compile 只需要空的 native ctx。
     * 复用同一个 ctx 避免每次 compile 都创建/销毁 ctx 的开销。
     */
    private fun getCompilerCtx(): Long {
        if (compilerCtx != 0L) return compilerCtx
        synchronized(this) {
            if (compilerCtx != 0L) return compilerCtx
            val rtPtr = QuickJsNative.nativeCreateRuntime()
            if (rtPtr == 0L) {
                throw ScriptException("Failed to create compiler JSRuntime", null)
            }
            val ctxPtr = QuickJsNative.nativeCreateContext(rtPtr)
            if (ctxPtr == 0L) {
                QuickJsNative.nativeFreeRuntime(rtPtr)
                throw ScriptException("Failed to create compiler JSContext", null)
            }
            // compilerCtx 不需要注册 binding (compile 不执行代码)
            // 但需要保留 rtPtr 引用避免被 GC, 这里简单 leak (compilerCtx 生命周期与进程一致)
            // 实际上 nativeFreeContext 时 rtPtr 也会被引用, 但 nativeFreeRuntime 不会自动释放 ctx
            // 简化处理: compilerCtx 永不释放 (单例, 进程级)
            compilerCtx = ctxPtr
            return ctxPtr
        }
    }
}
