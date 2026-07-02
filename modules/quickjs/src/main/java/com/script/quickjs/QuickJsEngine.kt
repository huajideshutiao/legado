package com.script.quickjs

import com.script.quickjs.QuickJsEngine.cleanupBindings
import com.script.quickjs.QuickJsEngine.compileForSubScope
import com.script.quickjs.QuickJsEngine.compilerCtx
import com.script.quickjs.QuickJsEngine.createNativeCtx
import com.script.quickjs.QuickJsEngine.eval
import com.script.quickjs.QuickJsEngine.evalBytecode
import com.script.quickjs.QuickJsEngine.evalInSubScope
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
     * 在 SharedJsScope 缓存的 topScope 上执行用户 JS bytecode。
     *
     * 早期版本曾尝试用子 scope object + `with(__b)` 包装来"隔离 bindings 不污染 topScope",
     * 但那种写法会让 jsLib 里定义在 topScope 上的函数(如 `lk`)在执行时拿不到 binding:
     * `lk` 的词法作用域 = topScope, 内部访问自由变量 `cache` 只会走 topScope, 而 binding
     * 只存在于子 scope, 于是 `cache` 解析为 undefined, 复刻 rhino 行为时就破功。
     *
     * SharedJsScope 的 ctx 本身就是 ThreadLocal 线程独占的 (见 SharedJsScope.threadCache),
     * topScope 不存在跨线程共享, 所以直接走"注入到 globalThis + 执行后恢复"是安全的:
     * - bindings 进入 globalThis ⇒ user JS 和 jsLib 函数都能通过普通自由变量查找命中
     * - 执行结束后 pop 快照, 把 globalThis 上的 binding 恢复到 push 前的值
     *
     * 对齐 rhino 子 scope 的 push/pop 语义:
     * - rhino 用 `bindings.prototype = topScope` + 词法环境,子 scope 出栈后父 scope 变量不变
     * - quickjs 无 prototype 链,改用"快照当前值 → 注入 → 执行 → 恢复快照"
     * - 关键场景: 外层 evalJS 在 java.ajax() 里触发内层 evalJS (如 header <js>),
     *   内层的 push/pop 只影响内层生命周期, 内层 pop 把 globalThis.java 恢复为外层注入的值,
     *   避免历史上"内层 cleanup 直接 delete → 外层 java undefined → De(java.ajax(...))
     *   在 java.ajax() 求值期间就把外层的 java 抹掉"这类跨栈污染。
     *
     * @param compiled [compileForSubScope] 编译出的 wrapJsForEval 包装后的 bytecode
     * @param scope SharedJsScope 缓存的 topScope (线程独占)
     * @param bindings 变量注入到 topScope 的 globalThis, 执行后按快照恢复
     */
    fun evalInSubScope(
        compiled: CompiledScript,
        scope: QuickJsContext,
        bindings: ScriptBindings,
        coroutineContext: CoroutineContext?
    ): Any? {
        val keys = bindings.keys.filter { isValidVarName(it) }
        pushBindingSnapshot(scope, keys)
        try {
            injectBindings(scope, bindings)
            return evalBytecode(compiled.bytecode, scope, coroutineContext)
        } finally {
            if (keys.isNotEmpty()) popBindingSnapshot(scope)
        }
    }

    /**
     * 把 globalThis 上 [keys] 的当前值快照到 `__bindingSnapshots__` 栈顶。
     *
     * 用哨兵 `__NA__` 标记"原本不存在", 省掉 existed 副表。栈用普通数组挂在 globalThis,
     * `__` 前缀命名约定避免与 user JS 冲突。
     */
    private fun pushBindingSnapshot(scope: QuickJsContext, keys: List<String>) {
        if (keys.isEmpty()) return
        val keysLiteral = keys.joinToString(",") { "\"$it\"" }
        val script = "(function(){var ks=[$keysLiteral],g=globalThis," +
            "s=g.__bindingSnapshots__||(g.__bindingSnapshots__=[]),snap={_k:ks};" +
            "for(var i=0;i<ks.length;i++){var k=ks[i];" +
            "snap[k]=Object.prototype.hasOwnProperty.call(g,k)?g[k]:snap;}" +
            "s.push(snap);})();"
        QuickJsNative.nativeEval(scope.ctxPtr, script)
    }

    /**
     * 弹出栈顶快照, 恢复到 push 前状态。哨兵 (snap 自身) 表示原本不存在 → delete。
     */
    private fun popBindingSnapshot(scope: QuickJsContext) {
        val script = "(function(){var g=globalThis,s=g.__bindingSnapshots__;" +
            "if(!s||!s.length)return;var snap=s.pop(),ks=snap._k;" +
            "for(var i=0;i<ks.length;i++){var k=ks[i];" +
            "if(snap[k]===snap){try{delete g[k];}catch(e){}}else{g[k]=snap[k];}}" +
            "})();"
        QuickJsNative.nativeEval(scope.ctxPtr, script)
    }

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
            for ((key, value) in bindings) {
                if (injectVariable(scope.ctxPtr, key, value, bindings.dangerousApi)) {
                    injectedKeys.add(key)
                }
            }
        } finally {
            QuickJsContext.threadLocalContext.set(previousThreadContext)
        }
        return injectedKeys
    }

    /**
     * 清理 [injectBindings] 注入的变量,实现子 scope 隔离。
     *
     * 用于 eval(js, scope, bindings) 执行后:删除注入的全局变量,
     * 避免下次复用 scope 时变量泄漏(如不同书源切换时,旧 source 还可见)。
     *
     * 对应 rhino 的子 scope 关闭时 bindings 自动清理。
     */
    fun cleanupBindings(scope: QuickJsContext, keys: List<String>) {
        if (keys.isEmpty()) return
        val validKeys = keys.filter { isValidVarName(it) }
        if (validKeys.isEmpty()) return
        val previousThreadContext = QuickJsContext.threadLocalContext.get()
        QuickJsContext.threadLocalContext.set(scope)
        try {
            // bootstrap 用 var/function 创建的全局 (java/Packages/JavaImporter 等) 是
            // [[Configurable]]:false, delete 静默失败会让 injectBindings 写入的 BaseSource
            // 等残留 -> 下次 jsLib 函数访问 java.lang.X 拿到 BaseSource.lang = undefined,
            // 不同书源 BaseSource 还会跨调用串味. 这些名字必须重写赋值恢复初值
            // ([[Writable]]:true 允许); 其它 (cache/book/source 等) 是 injectVariable
            // 时 setProperty 创建的 configurable:true, delete 即可.
            // 一次 eval 批量处理所有 key, 避免逐键 nativeEval 解析开销.
            val keysLiteral = validKeys.joinToString(",") { "\"$it\"" }
            val script = "(function(){var ks=[$keysLiteral],b=__bootstrapGlobals__,g=globalThis;" +
                "for(var i=0;i<ks.length;i++){var k=ks[i];" +
                "if(Object.prototype.hasOwnProperty.call(b,k)){g[k]=b[k];}else{delete g[k];}}})();"
            QuickJsNative.nativeEval(scope.ctxPtr, script)
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
            for ((key, value) in bindings) {
                injectVariable(ctx.ctxPtr, key, value, bindings.dangerousApi)
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
        // evaluate bootstrap bytecode (首次启动时编译并缓存, 后续直接复用)
        val bytecode = getBootstrapBytecode()
        QuickJsNative.nativeEvalBytecode(ctxPtr, bytecode)
        // 注册所有 binding
        registerBindings(ctxPtr)
        return QuickJsContext(rtPtr, ctxPtr)
    }

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
     * - null/基本类型 (String/Number/Boolean) 直接拼字面量, 用 nativeEval 设置
     * - Java 对象通过 [QuickJsNative.nativeWrapJavaObject] 包装为 JSValue,
     *   再用 nativeSetProperty 设置到 globalThis
     *
     * 注意: 用 `globalThis.$key = ...` 而非 `var $key = ...`,
     * 因为 QuickJS 中 `var` 与书源 JS 的 `let`/`const` 同名会报 "redeclaration",
     * rhino 允许此行为。属性赋值不创建词法绑定,不会冲突。
     * (如 ruleBookInfo.init 里有 `let url=...`,bindings 注入 `url` 变量)
     *
     * @return true 表示注入成功(变量名合法),false 表示跳过(变量名非法或对象不可见)
     */
    private fun injectVariable(
        ctxPtr: Long,
        key: String,
        value: Any?,
        dangerousApi: Boolean
    ): Boolean {
        if (!isValidVarName(key)) return false
        when {
            value == null -> {
                QuickJsNative.nativeEval(ctxPtr, "globalThis.$key = null;")
            }

            value is String -> {
                val jsLiteral = JsStringUtils.escape(value)
                QuickJsNative.nativeEval(ctxPtr, "globalThis.$key = $jsLiteral;")
            }

            value is Boolean -> {
                QuickJsNative.nativeEval(ctxPtr, "globalThis.$key = $value;")
            }

            value is Number -> {
                // Number 直接拼字面量 (Int/Long/Double/Float 都用 toString)
                // 注意: Long 在 JS 中是 float64, 但值小于 2^53 时精度无损
                QuickJsNative.nativeEval(ctxPtr, "globalThis.$key = $value;")
            }
            else -> {
                // Java 对象通过 nativeWrapJavaObject 包装为 JavaObject
                if (!JsSecurityPolicy.isObjectVisible(value, dangerousApi)) return false
                // nativeWrapJavaObject 返回 JSValue 句柄 (Long)
                val jsValueHandle = QuickJsNative.nativeWrapJavaObject(ctxPtr, value)
                // 用 nativeSetPropertyHandle 设置到 globalThis
                // 重要: 不能用 nativeSetProperty, 因为 fromJavaObject 会把 Long 句柄当数字
                val globalObj = QuickJsNative.nativeGetGlobalObject(ctxPtr)
                val globalHandle = (globalObj as? Number)?.toLong() ?: 0L
                val valueHandle = (jsValueHandle as? Number)?.toLong() ?: 0L
                if (globalHandle != 0L && valueHandle != 0L) {
                    QuickJsNative.nativeSetPropertyHandle(ctxPtr, globalHandle, key, valueHandle)
                    // 释放 globalObj 句柄 (避免泄漏)
                    QuickJsNative.nativeFreeHandle(ctxPtr, globalHandle)
                    // jsValueHandle 由 globalObj 持有 (SetPropertyStr 内部 DupValue), 这里 Free 安全
                    QuickJsNative.nativeFreeHandle(ctxPtr, valueHandle)
                }
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
     * 通过 [QuickJsContext.lastSyncedDangerousApi] 跟踪上次同步值,
     * 避免每次 eval 都调用 [QuickJsNative.nativeSetDangerousApi]。
     * 同一 scope 连续多次 eval 同一书源时 (dangerousApi 不变), 仅首次同步。
     */
    private fun syncDangerousApiIfNeeded(scope: QuickJsContext) {
        if (scope.lastSyncedDangerousApi == scope.dangerousApi) return
        QuickJsNative.nativeSetDangerousApi(scope.ctxPtr, scope.dangerousApi)
        // 同步 JS 端 __dangerousApi__ 全局变量 (bootstrap 中定义, 供 binding 调用时传参)
        // 仅 native opaque 同步不够, JS 代码读取的是 __dangerousApi__ 变量
        QuickJsNative.nativeEval(scope.ctxPtr, "__dangerousApi__ = ${scope.dangerousApi};")
        scope.lastSyncedDangerousApi = scope.dangerousApi
    }

    /**
     * 用 IIFE + eval 包裹用户 JS,模拟 rhino 的 bindings scope 隔离。
     *
     * rhino: `bindings.prototype = topScope`,JS 在 bindings scope 执行,
     * `let`/`const` 留在 bindings 词法环境,不污染 topScope。
     *
     * quickjs 没有 prototype 链,用 IIFE + eval 包裹:
     * - `let`/`const` 留在 eval 词法环境(或 IIFE 函数作用域),不污染 topScope
     *   (避免重复执行报 "redeclaration of 'xxx'")
     * - `return` 在 IIFE 函数内生效(模拟 rhino 顶层 return 扩展)
     * - eval 返回末尾表达式值(模拟 rhino script.exec 返回最后一个表达式)
     *
     * 用于 AnalyzeRule/AnalyzeUrl/BaseSource 等复用 sharedScope 的场景。
     * SharedJsScope 的 jslib 执行不需要包裹(本身就在 topScope 上定义)。
     */
    fun wrapJsForEval(jsStr: String): String {
        val jsLiteral = JsStringUtils.escape(jsStr)
        return "(function(){return eval($jsLiteral);})()"
    }

    /**
     * 把用户 JS 包装后编译为 bytecode, 供 [evalInSubScope] 在 SharedJsScope 的 topScope 上执行。
     *
     * 走 [wrapJsForEval] 的 `(function(){return eval(<源码>);})()` 包装:
     * - let/const/var 留在 IIFE 函数作用域, 不污染 topScope (避免重复执行报 "redeclaration")
     * - 顶层 return 在 IIFE 内生效 (对齐 rhino script.exec 顶层 return 扩展)
     * - eval 返回末尾表达式值 (对齐 rhino script.exec 返回最后一个表达式)
     *
     * 与早期 native 端 `(function(__b){...with(__b){...}})` 包装的区别: 这里不再创建子 scope,
     * binding 由 [evalInSubScope] 直接注入 globalThis, jsLib 里定义在 topScope 上的自由函数
     * 也能命中 binding (对齐 rhino 行为, 见 [evalInSubScope] 注释)。
     */
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
     */
    private fun registerBindings(ctxPtr: Long) {
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
            "__wrapJavaHandle"
        )
        for (name in bindings) {
            QuickJsNative.nativeDefineBinding(ctxPtr, name)
        }
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
