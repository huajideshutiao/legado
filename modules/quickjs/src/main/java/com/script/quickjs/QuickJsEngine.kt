package com.script.quickjs

import com.dokar.quickjs.QuickJs
import com.dokar.quickjs.QuickJsException
import com.dokar.quickjs.binding.FunctionBinding
import com.script.quickjs.QuickJsEngine.cleanupBindings
import com.script.quickjs.QuickJsEngine.createQuickJs
import com.script.quickjs.QuickJsEngine.eval
import com.script.quickjs.QuickJsEngine.evalBytecode
import com.script.quickjs.QuickJsEngine.getRuntimeScope
import com.script.quickjs.QuickJsEngine.injectBindings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlin.coroutines.CoroutineContext

/**
 * QuickJS 脚本引擎,对应 Rhino 的 [com.script.rhino.RhinoScriptEngine]。
 *
 * 单例 object。每个 [getRuntimeScope] 创建独立的 [QuickJs] 实例 + [QuickJsContext],
 * 用于实现 SharedJsScope 的作用域隔离。实例创建后立即 evaluate [JsBootstrap.code]
 * (优先用预编译 bytecode,避免每次重新解析)注入 Packages/JavaImporter/JavaAdapter,
 * 并注册 12 个 function binding 供 JS 调用 Kotlin。
 *
 * 同步桥接: 业务层 [eval] 是同步签名,内部用 [runBlocking](Dispatchers.Unconfined) 包裹
 * quickjs-kt 的 suspend evaluate。Unconfined 保证 evaluate 在当前线程执行,
 * 使 [QuickJsContext.threadLocalContext] 在 binding handler 中有效。
 *
 * 安全名单: 通过 [JsSecurityPolicy] + [JavaObjectBridge] 实现,由 [ScriptBindings.dangerousApi] 控制。
 *
 * 性能优化:
 * - bootstrap 预编译为 bytecode,避免每次 [getRuntimeScope] 重复解析
 * - dangerousApi 仅在变化时同步到 JS 全局变量(减少 evaluate 调用)
 * - [injectBindings] 返回注入键, [cleanupBindings] 可清理,实现子 scope 隔离
 */
@Suppress("MemberVisibilityCanBePrivate")
object QuickJsEngine {

    /**
     * bootstrap 预编译 bytecode,懒加载。
     *
     * 解析 JS 是较慢的操作(bootstrap ~6KB,需要词法+语法分析+字节码生成)。
     * 缓存 bytecode 后,每次 [createQuickJs] 只需 evaluate(bytecode),跳过解析步骤,
     * 显著降低 [getRuntimeScope] 的初始化开销。
     */
    @Volatile
    private var bootstrapBytecode: ByteArray? = null

    /**
     * 执行 JS(最常用入口)。
     *
     * 对应 RhinoScriptEngine.eval(js, bindingsConfig)。
     * 内部构建 bindings 并创建新 scope 执行。
     *
     * 注意: 此方法每次都创建新 scope,适合一次性 JS 执行。
     * 高频复用场景(如 BaseSource.evalJS)应使用 SharedJsScope 缓存 scope,
     * 配合 [injectBindings] + [cleanupBindings] 实现子 scope 隔离。
     */
    fun eval(js: String, bindingsConfig: ScriptBindings.() -> Unit = {}): Any? {
        if (js.isBlank()) return null
        val bindings = ScriptBindings().apply(bindingsConfig)
        val ctx = getRuntimeScope(bindings)
        return eval(js, ctx, null)
    }

    /**
     * 执行 JS,使用预构建的 [ScriptBindings]。
     *
     * 对应 RhinoScriptEngine.eval(js, bindings: ScriptBindings)。
     * 用于 BookExtensions/RegexExtensions/FileBook 等直接传入 bindings 的场景。
     */
    fun eval(js: String, bindings: ScriptBindings): Any? {
        if (js.isBlank()) return null
        val ctx = getRuntimeScope(bindings)
        return eval(js, ctx, null)
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
                // dangerousApi 仅在变化时同步(减少 evaluate 调用)
                syncDangerousApiIfNeeded(scope)
                val result = scope.quickJs.evaluate<Any?>(js)
                unwrapReturnValue(result)
            } catch (e: QuickJsException) {
                throw ScriptException(e.message, e)
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
                val result = scope.quickJs.evaluate<Any?>(bytecode)
                unwrapReturnValue(result)
            } catch (e: QuickJsException) {
                throw ScriptException(e.message, e)
            }
        }
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
        runBlocking(Dispatchers.Unconfined) {
            val previousThreadContext = QuickJsContext.threadLocalContext.get()
            QuickJsContext.threadLocalContext.set(scope)
            try {
                // 同步 dangerousApi (仅变化时执行 evaluate)
                scope.dangerousApi = bindings.dangerousApi
                syncDangerousApiIfNeeded(scope)
                for ((key, value) in bindings) {
                    if (injectVariable(scope.quickJs, key, value, bindings.dangerousApi)) {
                        injectedKeys.add(key)
                    }
                }
            } finally {
                QuickJsContext.threadLocalContext.set(previousThreadContext)
            }
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
        runBlocking(Dispatchers.Unconfined) {
            val previousThreadContext = QuickJsContext.threadLocalContext.get()
            QuickJsContext.threadLocalContext.set(scope)
            try {
                for (key in keys) {
                    if (isValidVarName(key)) {
                        // delete 全局变量,释放引用的 Java 对象句柄
                        scope.quickJs.evaluate<Any?>("delete globalThis['$key'];")
                    }
                }
            } finally {
                QuickJsContext.threadLocalContext.set(previousThreadContext)
            }
        }
    }

    /**
     * 编译 JS 为 bytecode,对应 RhinoScriptEngine.compile(script)。
     *
     * 使用临时 QuickJs 实例(无需 bootstrap,compile 不执行代码)。
     * bytecode 可缓存复用,通过 [evalBytecode] 的 scope 执行(QuickJS bytecode 跨实例兼容)。
     */
    fun compile(script: String): CompiledScript {
        val quickJs = QuickJs.create(Dispatchers.Default)
        try {
            return CompiledScript(quickJs.compile(script))
        } catch (e: QuickJsException) {
            throw ScriptException(e.message, e)
        } finally {
            quickJs.close()
        }
    }

    /**
     * 复用目标 scope 编译 JS,避免创建临时 QuickJs 实例。
     *
     * 用于 AnalyzeRule 等已知目标 scope 的场景:
     * 编译与执行在同一 scope,符号解析一致,且无临时实例初始化开销。
     *
     * @param script JS 源码
     * @param scope 目标 scope(执行时也用此 scope)
     */
    fun compile(script: String, scope: QuickJsContext): CompiledScript {
        return runBlocking(Dispatchers.Unconfined) {
            val previousThreadContext = QuickJsContext.threadLocalContext.get()
            QuickJsContext.threadLocalContext.set(scope)
            try {
                CompiledScript(scope.quickJs.compile(script))
            } catch (e: QuickJsException) {
                throw ScriptException(e.message, e)
            } finally {
                QuickJsContext.threadLocalContext.set(previousThreadContext)
            }
        }
    }

    /**
     * 创建运行时 scope,对应 RhinoScriptEngine.getRuntimeScope(bindings)。
     *
     * 创建新 QuickJs 实例 → evaluate bootstrap bytecode(预编译,跳过解析)→ 注入 bindings 变量 → 返回 context。
     * SharedJsScope 用此方法创建共享作用域;BaseSource.evalJS 无 sharedScope 时也用此方法。
     */
    fun getRuntimeScope(bindings: ScriptBindings): QuickJsContext {
        val quickJs = createQuickJs()
        val ctx = QuickJsContext(quickJs).apply {
            dangerousApi = bindings.dangerousApi
        }
        runBlocking(Dispatchers.Unconfined) {
            val previous = QuickJsContext.threadLocalContext.get()
            QuickJsContext.threadLocalContext.set(ctx)
            try {
                // 同步 dangerousApi (仅变化时执行 evaluate,bootstrap 默认 false)
                syncDangerousApiIfNeeded(ctx)
                // 注入 bindings 里的变量(java/source/baseUrl/cookie/cache 等)
                for ((key, value) in bindings) {
                    injectVariable(quickJs, key, value, bindings.dangerousApi)
                }
            } finally {
                QuickJsContext.threadLocalContext.set(previous)
            }
        }
        return ctx
    }

    /**
     * 解包 JS 返回值,对应 RhinoScriptEngine.unwrapReturnValue。
     *
     * JS 返回的 Java 对象句柄({__java_handle__: handle})解包为原始 Java 对象,
     * 其他类型原样返回。
     */
    private fun unwrapReturnValue(result: Any?): Any? {
        if (result is Map<*, *>) {
            val handle = result["__java_handle__"]
            if (handle != null) {
                val h = (handle as? Long) ?: (handle as? Number)?.toLong() ?: 0L
                if (h != 0L) {
                    return JavaObjectBridge.getObject(h) ?: result
                }
            }
        }
        return result
    }

    /**
     * 创建 QuickJs 实例并注入 bootstrap。
     *
     * 优先用预编译 bytecode,避免每次重新解析 bootstrap 源码(~6KB)。
     */
    private fun createQuickJs(): QuickJs {
        val quickJs = QuickJs.create(Dispatchers.Default)
        runBlocking(Dispatchers.Unconfined) {
            val bytecode = getBootstrapBytecode()
            quickJs.evaluate<Any?>(bytecode)
        }
        registerBindings(quickJs)
        return quickJs
    }

    /**
     * 获取预编译的 bootstrap bytecode,懒加载。
     *
     * 首次调用时用临时 QuickJs 实例编译 [JsBootstrap.code],后续直接复用。
     * 编译结果与具体 QuickJs 实例无关(QuickJS bytecode 格式标准),可跨实例执行。
     */
    private fun getBootstrapBytecode(): ByteArray {
        bootstrapBytecode?.let { return it }
        synchronized(this) {
            bootstrapBytecode?.let { return it }
            val tmp = QuickJs.create(Dispatchers.Default)
            try {
                val bytecode = runBlocking(Dispatchers.Unconfined) {
                    tmp.compile(JsBootstrap.code)
                }
                bootstrapBytecode = bytecode
                return bytecode
            } finally {
                tmp.close()
            }
        }
    }

    /**
     * 创建独立的 QuickJs 实例(已注入 bootstrap + 注册 binding)。
     *
     * 用于 [io.legado.app.ui.association.JsActivity] 等需要独立 scope 的场景,
     * 不与 SharedJsScope 缓存的 scope 共享。
     */
    fun createQuickJsForActivity(): QuickJs = createQuickJs()

    /**
     * 注入单个 bindings 变量到 QuickJs 全局作用域。
     *
     * - null/基本类型(String/Number/Boolean)直接拼字面量
     * - Java 对象通过 [JavaObjectBridge.registerObject] 注册句柄,JS 用 __wrapJavaObject 解包
     *
     * 注意: 用 `globalThis.$key = ...` 而非 `var $key = ...`,
     * 因为 QuickJS 中 `var` 与书源 JS 的 `let`/`const` 同名会报 "redeclaration",
     * rhino 允许此行为。属性赋值不创建词法绑定,不会冲突。
     * (如 ruleBookInfo.init 里有 `let url=...`,bindings 注入 `url` 变量)
     *
     * @return true 表示注入成功(变量名合法),false 表示跳过(变量名非法或对象不可见)
     */
    private fun injectVariable(
        quickJs: QuickJs,
        key: String,
        value: Any?,
        dangerousApi: Boolean
    ): Boolean {
        if (!isValidVarName(key)) return false
        val jsExpr = when {
            value == null -> "null"
            value is String -> JsStringUtils.escape(value)
            value is Boolean -> value.toString()
            value is Number -> value.toString()
            else -> {
                // Java 对象通过句柄注入
                if (!JsSecurityPolicy.isObjectVisible(value, dangerousApi)) return false
                val handle = JavaObjectBridge.registerObject(value)
                "__wrapJavaObject($handle)"
            }
        }
        runBlocking(Dispatchers.Unconfined) {
            quickJs.evaluate<Any?>("globalThis.$key = $jsExpr;")
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
     * 同步 dangerousApi 到 JS 全局变量(仅当 scope.dangerousApi 与上次同步的值不同时)。
     *
     * 通过 [QuickJsContext.lastSyncedDangerousApi] 跟踪上次同步值,
     * 避免每次 eval 都执行 `__dangerousApi__ = true/false` 的 evaluate 调用。
     * 同一 scope 连续多次 eval 同一书源时(dangerousApi 不变),仅首次同步。
     */
    private fun syncDangerousApiIfNeeded(scope: QuickJsContext) {
        if (scope.lastSyncedDangerousApi == scope.dangerousApi) return
        runBlocking(Dispatchers.Unconfined) {
            scope.quickJs.evaluate<Any?>("__dangerousApi__ = ${scope.dangerousApi};")
        }
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
     * 在指定 scope 上执行 evaluate,处理 ThreadLocalContext / 递归检查 / coroutineContext 同步。
     *
     * 抽取 [eval] 和 [evalBytecode] 的共用模板,确保 ThreadLocal 设置 / 资源清理一致。
     *
     * 注意: block 必须是 suspend 的,因为内部会调用 [QuickJs.evaluate](suspend 函数)。
     * 用 inline + crossinline 避免额外对象分配(性能优化)。
     */
    private inline fun <T> withEvalContext(
        scope: QuickJsContext,
        coroutineContext: CoroutineContext?,
        crossinline block: suspend () -> T
    ): T {
        return runBlocking(Dispatchers.Unconfined) {
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
                block()
            } finally {
                scope.coroutineContext = previousCoroutineContext
                scope.allowScriptRun = previousAllowScriptRun
                scope.recursiveCount--
                QuickJsContext.threadLocalContext.set(previousThreadContext)
            }
        }
    }

    /**
     * 注册 12 个 function binding,供 JS bootstrap 调用 Kotlin。
     *
     * 这些函数由 [JsBootstrap] 的 JS 代码调用,实现 Packages/JavaImporter/JavaAdapter。
     * 所有 args 用安全转换,避免 JS 传入错误类型导致崩溃。
     */
    private fun registerBindings(quickJs: QuickJs) {
        // 1. 按类名加载 Class,返回 Class 句柄(>0)或 0(失败/拦截)
        quickJs.defineBinding(
            "__loadJavaClass",
            FunctionBinding<Any?> { args ->
                val fullName = args.getOrNull(0) as? String
                if (fullName.isNullOrEmpty()) return@FunctionBinding 0L
                val dangerousApi = (args.getOrNull(1) as? Boolean) ?: false
                JavaObjectBridge.loadJavaClass(fullName, dangerousApi)
            }
        )

        // 1.5 仅检查类是否存在,不注册句柄(用于 JavaImporter.has 探测,避免 has 泄漏)
        quickJs.defineBinding(
            "__classExists",
            FunctionBinding<Any?> { args ->
                val fullName = args.getOrNull(0) as? String
                if (fullName.isNullOrEmpty()) return@FunctionBinding false
                val dangerousApi = (args.getOrNull(1) as? Boolean) ?: false
                JavaObjectBridge.classExists(fullName, dangerousApi)
            }
        )

        // 1.6 判断 Class 是否为 interface(用于 new Interface(impl) JavaAdapter 语法检测)
        quickJs.defineBinding(
            "__isInterface",
            FunctionBinding<Any?> { args ->
                val classHandle = (args.getOrNull(0) as? Number)?.toLong() ?: 0L
                val dangerousApi = (args.getOrNull(1) as? Boolean) ?: false
                if (classHandle == 0L) return@FunctionBinding false
                JavaObjectBridge.isInterface(classHandle, dangerousApi)
            }
        )

        // 2. 实例化 Java 对象,返回对象句柄(>0)或 0(失败)
        quickJs.defineBinding(
            "__newJavaInstance",
            FunctionBinding<Any?> { args ->
                val classHandle = (args.getOrNull(0) as? Number)?.toLong() ?: 0L
                val ctorArgs = (args.getOrNull(1) as? List<*>)?.toTypedArray() ?: emptyArray()
                val dangerousApi = (args.getOrNull(2) as? Boolean) ?: false
                if (classHandle == 0L) return@FunctionBinding 0L
                JavaObjectBridge.newJavaInstance(classHandle, ctorArgs, dangerousApi)
            }
        )

        // 3. 调用静态方法
        quickJs.defineBinding(
            "__callStaticMethod",
            FunctionBinding<Any?> { args ->
                val classHandle = (args.getOrNull(0) as? Number)?.toLong() ?: 0L
                val methodName = args.getOrNull(1) as? String
                val methodArgs = (args.getOrNull(2) as? List<*>)?.toTypedArray() ?: emptyArray()
                val dangerousApi = (args.getOrNull(3) as? Boolean) ?: false
                if (classHandle == 0L || methodName.isNullOrEmpty()) return@FunctionBinding null
                JavaObjectBridge.callStaticMethod(classHandle, methodName, methodArgs, dangerousApi)
            }
        )

        // 4. 获取静态字段
        quickJs.defineBinding(
            "__getStaticField",
            FunctionBinding<Any?> { args ->
                val classHandle = (args.getOrNull(0) as? Number)?.toLong() ?: 0L
                val fieldName = args.getOrNull(1) as? String
                val dangerousApi = (args.getOrNull(2) as? Boolean) ?: false
                if (classHandle == 0L || fieldName.isNullOrEmpty()) return@FunctionBinding null
                JavaObjectBridge.getStaticField(classHandle, fieldName, dangerousApi)
            }
        )

        // 5. 调用实例方法
        quickJs.defineBinding(
            "__callInstanceMethod",
            FunctionBinding<Any?> { args ->
                val objHandle = (args.getOrNull(0) as? Number)?.toLong() ?: 0L
                val methodName = args.getOrNull(1) as? String
                val methodArgs = (args.getOrNull(2) as? List<*>)?.toTypedArray() ?: emptyArray()
                val dangerousApi = (args.getOrNull(3) as? Boolean) ?: false
                if (objHandle == 0L || methodName.isNullOrEmpty()) return@FunctionBinding null
                JavaObjectBridge.callInstanceMethod(objHandle, methodName, methodArgs, dangerousApi)
            }
        )

        // 6. 获取实例字段
        quickJs.defineBinding(
            "__getInstanceField",
            FunctionBinding<Any?> { args ->
                val objHandle = (args.getOrNull(0) as? Number)?.toLong() ?: 0L
                val fieldName = args.getOrNull(1) as? String
                val dangerousApi = (args.getOrNull(2) as? Boolean) ?: false
                if (objHandle == 0L || fieldName.isNullOrEmpty()) return@FunctionBinding null
                JavaObjectBridge.getInstanceField(objHandle, fieldName, dangerousApi)
            }
        )

        // 7. 检查实例方法是否存在(不触发调用)
        // 用于 Proxy get 判断 field+method 同名场景,实现 rhino LiveConnect 的 method 优先语义
        quickJs.defineBinding(
            "__hasInstanceMethod",
            FunctionBinding<Any?> { args ->
                val objHandle = (args.getOrNull(0) as? Number)?.toLong() ?: 0L
                val methodName = args.getOrNull(1) as? String
                val dangerousApi = (args.getOrNull(2) as? Boolean) ?: false
                if (objHandle == 0L || methodName.isNullOrEmpty()) return@FunctionBinding false
                JavaObjectBridge.hasInstanceMethod(objHandle, methodName, dangerousApi)
            }
        )

        // 7.5 获取实例对象的所有可枚举属性名 (用于 __keys 函数 / Object.entries 等)
        // 与 rhino NativeJavaObject.getIds() / NativeJavaMap.getIds() / NativeJavaList.getIds() 行为一致
        // 返回 String[] (JS ownKeys 要求 string|symbol)
        quickJs.defineBinding(
            "__getInstanceKeys",
            FunctionBinding<Any?> { args ->
                val objHandle = (args.getOrNull(0) as? Number)?.toLong() ?: 0L
                val dangerousApi = (args.getOrNull(1) as? Boolean) ?: false
                if (objHandle == 0L) return@FunctionBinding emptyArray<String>()
                JavaObjectBridge.getInstanceKeys(objHandle, dangerousApi)
            }
        )

        // 8. 设置实例字段
        quickJs.defineBinding(
            "__setInstanceField",
            FunctionBinding<Any?> { args ->
                val objHandle = (args.getOrNull(0) as? Number)?.toLong() ?: 0L
                val fieldName = args.getOrNull(1) as? String
                val value = args.getOrNull(2)
                val dangerousApi = (args.getOrNull(3) as? Boolean) ?: false
                if (objHandle == 0L || fieldName.isNullOrEmpty()) return@FunctionBinding false
                JavaObjectBridge.setInstanceField(objHandle, fieldName, value, dangerousApi)
            }
        )

        // 9. 创建 JavaAdapter(Proxy 实现接口),返回代理对象句柄
        quickJs.defineBinding(
            "__newJavaAdapter",
            FunctionBinding<Any?> { args ->
                val classHandle = (args.getOrNull(0) as? Number)?.toLong() ?: 0L
                val jsFnHandle = (args.getOrNull(1) as? Number)?.toLong() ?: 0L
                val dangerousApi = (args.getOrNull(2) as? Boolean) ?: false
                if (classHandle == 0L || jsFnHandle == 0L) return@FunctionBinding 0L
                JavaObjectBridge.newJavaAdapter(classHandle, jsFnHandle, dangerousApi)
            }
        )

        // 10. 注册 JS function 对象,返回 JsFunctionHandle 句柄(用于 JavaAdapter 回调)
        quickJs.defineBinding(
            "__registerJsFunctionNative",
            FunctionBinding<Any?> { args ->
                val jsObjectExpr = args.getOrNull(0) as? String
                val dangerousApi = (args.getOrNull(1) as? Boolean) ?: false
                if (jsObjectExpr.isNullOrEmpty()) return@FunctionBinding 0L
                JsFunctionHandle.register(
                    quickJs = quickJs,
                    jsObjectExpr = jsObjectExpr,
                    dangerousApi = dangerousApi
                )
            }
        )
    }
}
