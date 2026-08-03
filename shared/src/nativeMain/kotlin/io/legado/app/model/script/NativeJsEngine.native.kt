@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package io.legado.app.model.script

import com.script.jsdispatch.JsValueConverters

import io.legado.app.help.JsExtensionsCommon
import io.legado.app.napi.quickjs.JSContext
import io.legado.app.napi.quickjs.JSPropertyEnum
import io.legado.app.napi.quickjs.JSRuntime
import io.legado.app.napi.quickjs.JSValue
import io.legado.app.napi.quickjs.JS_TAG_FLOAT64
import io.legado.app.napi.quickjs.JS_TAG_INT
import io.legado.app.napi.quickjs.JS_TAG_NULL
import io.legado.app.napi.quickjs.JS_TAG_UNDEFINED
import io.legado.app.napi.quickjs.qjs_EvalTypeGlobal
import io.legado.app.napi.quickjs.JS_Eval
import io.legado.app.napi.quickjs.JS_FreeAtom
import io.legado.app.napi.quickjs.JS_GetLength
import io.legado.app.napi.quickjs.JS_GetPropertyStr
import io.legado.app.napi.quickjs.JS_FreeContext
import io.legado.app.napi.quickjs.qjs_FreeCString
import io.legado.app.napi.quickjs.JS_FreePropertyEnum
import io.legado.app.napi.quickjs.JS_FreeRuntime
import io.legado.app.napi.quickjs.JS_FreeValue
import io.legado.app.napi.quickjs.JS_GetException
import io.legado.app.napi.quickjs.JS_GetGlobalObject
import io.legado.app.napi.quickjs.JS_GetOwnPropertyNames
import io.legado.app.napi.quickjs.JS_GetProperty
import io.legado.app.napi.quickjs.JS_GetPropertyUint32
import io.legado.app.napi.quickjs.JS_IsArray
import io.legado.app.napi.quickjs.JS_IsError
import io.legado.app.napi.quickjs.JS_NewArray
import io.legado.app.napi.quickjs.JS_NewContext
import io.legado.app.napi.quickjs.JS_NewObject
import io.legado.app.napi.quickjs.JS_NewRuntime
import io.legado.app.napi.quickjs.JS_SetPropertyStr
import io.legado.app.napi.quickjs.JS_SetPropertyUint32
import io.legado.app.napi.quickjs.JS_ToBool
import io.legado.app.napi.quickjs.JS_ToFloat64
import io.legado.app.napi.quickjs.JS_ToInt32
import io.legado.app.napi.quickjs.qjs_AtomToCString
import io.legado.app.napi.quickjs.qjs_IsBool
import io.legado.app.napi.quickjs.qjs_IsException
import io.legado.app.napi.quickjs.qjs_IsNull
import io.legado.app.napi.quickjs.qjs_IsNumber
import io.legado.app.napi.quickjs.qjs_IsObject
import io.legado.app.napi.quickjs.qjs_IsString
import io.legado.app.napi.quickjs.qjs_IsUndefined
import io.legado.app.napi.quickjs.qjs_NewBool
import io.legado.app.napi.quickjs.qjs_NewCFunction
import io.legado.app.napi.quickjs.qjs_NewFloat64
import io.legado.app.napi.quickjs.qjs_NewInt32
import io.legado.app.napi.quickjs.qjs_NewString
import io.legado.app.napi.quickjs.qjs_ToCString
import io.legado.app.napi.quickjs.qjs_ValueGetBool
import io.legado.app.napi.quickjs.qjs_ValueGetFloat64
import io.legado.app.napi.quickjs.qjs_ValueGetInt
import io.legado.app.napi.quickjs.qjs_ValueGetTag
import kotlinx.cinterop.ByteVar
import kotlinx.cinterop.CPointer
import kotlinx.cinterop.CPointerVar
import kotlinx.cinterop.CValue
import kotlinx.cinterop.DoubleVar
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.LongVar
import kotlinx.cinterop.UIntVar
import kotlinx.cinterop.alloc
import kotlinx.cinterop.cValue
import kotlinx.cinterop.cstr
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.plus
import kotlinx.cinterop.pointed
import kotlinx.cinterop.ptr
import kotlinx.cinterop.staticCFunction
import kotlinx.cinterop.toKString
import kotlinx.cinterop.useContents
import kotlinx.cinterop.value
import kotlinx.atomicfu.atomic
import kotlinx.coroutines.ensureActive
import kotlin.coroutines.CoroutineContext

/**
 * native 端 (iOS/鸿蒙) JS 引擎: 基于 quickjs-ng C 源码 (cinterop 编译), 与 Android/Desktop
 * [QuickJsJsEngine] 共用同一 quickjs 引擎 (全平台统一)。原 iosMain [IosJsEngine] /
 * ohosMain [OhosJsEngine] 逻辑一致, 下沉到 nativeMain, 平台端 typealias 指向本类。
 *
 * 选型: 原 iOS 用 JavaScriptCore、鸿蒙用 JSVM-API (ArkJS/V8), 与 quickjs 行为不一致
 * (ES 特性/错误信息/bytecode), 改 cinterop 直接编译 shared/src/cinterop/quickjs-ng/ 的 C 源码
 * (单一数据源, iOS/鸿蒙 cinterop 与 Android/Desktop JNI CMake 共用; Kotlin/Native 无 JNI)。
 *
 * 与 [QuickJsJsEngine] 差异: 编译缓存 = 源码字符串 (无 bytecode, TODO 补 JS_EVAL_FLAG_COMPILE_ONLY);
 * Java 桥 = JS_SetPropertyStr + Map/List (无反射); Packages/importClass 走 [NativeJavaCompat]
 * 白名单类表 (System/URLEncoder/URLDecoder/UUID 静态方法, 表外类明确失败而非静默),
 * JavaAdapter 抛异常; 资源管理 = JS_FreeContext/JS_FreeRuntime。
 *
 * 注册: 宿主启动早期经 registerIosJsEngines/registerOhosJsEngines 注册到 [JsEngines]。
 * 编译验证: ./gradlew :shared:compileKotlinIosArm64 / compileKotlinIosSimulatorArm64 /
 * compileKotlinLinuxArm64 -PenableOhosTarget=true。
 */
object NativeJsEngine : JsEngine {

    /** 引擎类型, 复用 QUICKJS ([JsEngines.type] 硬编码 QUICKJS, 切换入口已撤)。 */
    override val type: JsEngineType = JsEngineType.QUICKJS

    /** 当前线程的 JS 执行上下文 (ThreadLocal), 对应 quickjs 的 QuickJsContext.threadLocalContext。 */
    private val threadLocalScope = atomic<NativeJsScope?>(null)

    /** JS 递归深度上限, 对齐 QuickJsContext.MAX_RECURSION。 */
    private const val MAX_RECURSION = 10

    // ============ 一次性 eval ============

    /** 一次性 eval, 内部创建 scope 执行后立即释放。 */
    override fun eval(js: String, bindingsConfig: JsBindings.() -> Unit): Any? {
        if (js.isBlank()) return null
        return eval(js, JsBindings().apply(bindingsConfig))
    }

    /** 一次性 eval, 传入预构建 bindings。 */
    override fun eval(js: String, bindings: JsBindings): Any? {
        if (js.isBlank()) return null
        val scope = getRuntimeScope(bindings)
        return try {
            eval(js, scope, null)
        } finally {
            scope.close()
        }
    }

    // ============ scope 创建/复用 ============

    /**
     * 创建运行时 scope: 新建 JSRuntime + JSContext + 注入 bootstrap + 注入 bindings 变量。
     *
     * 对应 QuickJsEngine.getRuntimeScope: createNativeCtx + bootstrap + injectBindings。
     * 每个独立 scope 独立 JSRuntime (与原 IosJsEngine/OhosJsEngine 每 JSContext/JSVM_VM 独立一致),
     * quickjs 单线程模型, runtime 间互不影响。
     */
    override fun getRuntimeScope(bindings: JsBindings): JsScope {
        val rt = JS_NewRuntime() ?: error("JS_NewRuntime() returned null")
        val ctx = JS_NewContext(rt) ?: error("JS_NewContext() returned null")
        // 注入 bootstrap (bindingsStack + Java 类相关 stub + JsExtensions 桥接工厂)
        evalInternal(ctx, BOOTSTRAP_CODE, "<bootstrap>", checkException = true)
        // 注册 native 分派函数 __nativeDispatch 到 globalThis (供 JsExtensions 桥接 JS 层回调)
        registerNativeDispatch(ctx)
        val scope = NativeJsScope(rt, ctx).apply {
            dangerousApi = bindings.dangerousApi
        }
        // 注入 bindings 变量到 globalThis
        injectBindings(scope, bindings)
        return scope
    }

    /** 创建独立 scope, 不与 SharedJsScope 共享。 */
    override fun createStandaloneScope(): JsScope {
        val rt = JS_NewRuntime() ?: error("JS_NewRuntime() returned null")
        val ctx = JS_NewContext(rt) ?: error("JS_NewContext() returned null")
        evalInternal(ctx, BOOTSTRAP_CODE, "<bootstrap>", checkException = true)
        // 独立 scope 也需注册 __nativeDispatch (可能注入 java 变量)
        registerNativeDispatch(ctx)
        return NativeJsScope(rt, ctx)
    }

    /**
     * 注册 native 分派函数 __nativeDispatch 到 globalThis。
     *
     * 用 qjs_NewCFunction 把 [NativeJsExtensionsBridge.nativeDispatchFn] (staticCFunction) 包装为 JS 函数,
     * 注入 globalThis.__nativeDispatch, 供 JS 工厂函数 __createJavaObj 等回调 Kotlin 层分派。
     * 同时注入 __nativeJavaCompat (白名单 Java 类静态方法兼容面, 见 [NativeJavaCompat])。
     *
     * 必须在 [BOOTSTRAP_CODE] eval 之后 (JS_FACTORY_CODE 依赖 __nativeDispatch 已定义) 调用。
     */
    private fun registerNativeDispatch(ctx: CPointer<JSContext>) {
        val global = JS_GetGlobalObject(ctx)
        try {
            memScoped {
                val fn = qjs_NewCFunction(
                    ctx,
                    NativeJsExtensionsBridge.nativeDispatchFn,
                    "__nativeDispatch",
                    3
                )
                JS_SetPropertyStr(ctx, global, "__nativeDispatch", fn)
                val javaCompatFn = qjs_NewCFunction(
                    ctx,
                    nativeJavaCompatFn,
                    "__nativeJavaCompat",
                    4
                )
                JS_SetPropertyStr(ctx, global, "__nativeJavaCompat", javaCompatFn)
            }
        } finally {
            JS_FreeValue(ctx, global)
        }
    }

    // ============ 编译 ============

    /**
     * "编译" JS — P0 阶段仅缓存源码字符串 (与 Android 端接口对齐)。
     *
     * TODO: 后续用 JS_Eval + JS_EVAL_FLAG_COMPILE_ONLY 编译为 bytecode,
     * 再用 JS_ReadObject + JS_EvalFunction 执行, 提升性能。
     * 返回 [NativeJsCompiledScript] 包装源码, [evalBytecode] 时 fallback 为源码 eval。
     */
    override fun compile(script: String): JsCompiledScript = NativeJsCompiledScript(script)

    /** 在指定 scope 上编译 (复用 scope, native 上等价于 [compile], 无 scope 复用收益)。 */
    override fun compile(script: String, scope: JsScope): JsCompiledScript {
        require(scope is NativeJsScope) { "NativeJsEngine.compile requires NativeJsScope, got ${scope::class}" }
        return NativeJsCompiledScript(script)
    }

    // ============ quickjs 独有 API 的统一抽象 ============

    /**
     * 把 JS 包成可子 scope 执行的形式。
     *
     * 复用 quickjs 的三层包装: `(function(){with(__currentBindings()){return eval(<源码>);}})()`
     * - with(__currentBindings()): bindings 走 [evalInSubScope] 压栈成栈顶对象, user JS 里
     *   `java`/`cache`/`source` 走 with 命中; 空栈时穿透到 globalThis。
     * - IIFE 隔离 let/const/var, 不污染 topScope。
     * - eval + return: 返回末尾表达式值, 顶层 return 生效 (对齐 rhino script.exec)。
     */
    override fun wrapJsForEval(jsStr: String): String {
        val jsLiteral = escapeJsString(jsStr)
        return "(function(){with(__currentBindings()){return eval($jsLiteral);}})()"
    }

    /** [wrapJsForEval] 包装后"编译" (native 上仅缓存源码)。 */
    override fun compileForSubScope(jsStr: String): JsCompiledScript =
        NativeJsCompiledScript(wrapJsForEval(jsStr))

    /**
     * 在共享 topScope 上执行包装后的 compiled, bindings 走 __enterBindings / __exitBindings
     * 的子 scope 栈隔离 (对齐 quickjs evalInSubScope 的 __bindingsStack__ 语义)。
     */
    override fun evalInSubScope(
        compiled: JsCompiledScript,
        scope: JsScope,
        bindings: JsBindings,
        coroutineContext: CoroutineContext?
    ): Any? {
        val nativeScope = scope as? NativeJsScope
            ?: error("NativeJsEngine.evalInSubScope requires NativeJsScope, got ${scope::class}")
        val nativeCompiled = (compiled as? NativeJsCompiledScript)?.source
            ?: error("NativeJsEngine.evalInSubScope requires NativeJsCompiledScript, got ${compiled::class}")
        // 同步 dangerousApi (native 上语义为 no-op, 保留字段对齐)
        nativeScope.dangerousApi = bindings.dangerousApi
        // 构造 __enterBindings(k1,v1,k2,v2,...) 调用参数
        val kvsJs = buildBindingKvsJs(bindings)
        val prevScope = threadLocalScope.value
        threadLocalScope.value = nativeScope
        nativeScope.coroutineContext = coroutineContext
        nativeScope.recursiveCount++
        try {
            nativeScope.checkRecursive()
            // 进入子 scope: 压栈 bindings
            evalInternal(nativeScope.ctx!!, "__enterBindings($kvsJs)", "<enterBindings>", checkException = true)
            return try {
                evalInternal(nativeScope.ctx!!, nativeCompiled, "<subScope>", checkException = true)
            } finally {
                // 退出子 scope: 弹栈 bindings (即使中途异常也要弹栈)
                evalInternal(nativeScope.ctx!!, "__exitBindings()", "<exitBindings>", checkException = false)
            }
        } finally {
            nativeScope.recursiveCount--
            nativeScope.coroutineContext = null
            threadLocalScope.value = prevScope
        }
    }

    /** 在指定 scope 上执行 JS。 */
    override fun eval(js: String, scope: JsScope, coroutineContext: CoroutineContext?): Any? {
        if (js.isBlank()) return null
        val nativeScope = scope as? NativeJsScope
            ?: error("NativeJsEngine.eval requires NativeJsScope, got ${scope::class}")
        return withEvalContext(nativeScope, coroutineContext) {
            evalInternal(nativeScope.ctx!!, js, "<eval>", checkException = true)
        }
    }

    /** 在指定 scope 上执行 JS 并注入 bindings (一次性, 不做子 scope 隔离清理)。 */
    override fun eval(
        js: String,
        scope: JsScope,
        bindings: JsBindings,
        coroutineContext: CoroutineContext?
    ): Any? {
        val nativeScope = scope as? NativeJsScope
            ?: error("NativeJsEngine.eval requires NativeJsScope, got ${scope::class}")
        // 注入 bindings 变量到 globalThis (不清理, 调用方如需隔离用 injectBindings + eval + cleanupBindings)
        injectBindings(nativeScope, bindings)
        return eval(js, nativeScope, coroutineContext)
    }

    /**
     * 执行 "bytecode" — P0 阶段 fallback 为源码 eval (与原 IosJsEngine/OhosJsEngine 行为一致)。
     *
     * [bytecode] 参数在 native 上为 null 或源码字符串的 UTF-8 字节 (NativeJsCompiledScript 内部存源码)。
     * 实际由 [NativeJsCompiledScript] 包装源码, 此处接收 [JsCompiledScript] 包装。
     * TODO: 后续用 JS_Eval + JS_EVAL_FLAG_COMPILE_ONLY 真正编译为 bytecode。
     */
    override fun evalBytecode(
        bytecode: ByteArray?,
        scope: JsScope,
        coroutineContext: CoroutineContext?
    ): Any? {
        if (bytecode == null) return null
        // native P0 阶段 bytecode 实际是源码字符串的 UTF-8 字节, fallback 为源码 eval
        val source = bytecode.decodeToString()
        return eval(source, scope, coroutineContext)
    }

    /**
     * 注入 bindings 变量到 scope 的 globalThis, 返回注入成功的键列表 (供 cleanupBindings 用)。
     *
     * native cinterop 实现 (iOS/鸿蒙一致): 调 JS_GetGlobalObject 取 globalThis,
     * 然后 JS_SetPropertyStr 注入每个 key。
     * - 基本类型 (String/Number/Boolean/null) 用 qjs_NewXxx 创建 JSValue 后注入;
     * - Map<String,Any?> / List<Any?> 递归转换为 JS_NewObject / JS_NewArray 后注入;
     * - 其他复杂 Kotlin 对象 (JsExtensions/BookSource 等) 跳过 (TODO: 后续用 JS_NewCFunction + JSClassDef 完整支持);
     * - JS_SetPropertyStr 转移 value 所有权, 不需要额外 JS_FreeValue(value);
     * - globalThis 来自 JS_GetGlobalObject (引用计数 +1), 用完需 JS_FreeValue。
     */
    override fun injectBindings(scope: JsScope, bindings: JsBindings): List<String> {
        val nativeScope = scope as? NativeJsScope
            ?: error("NativeJsEngine.injectBindings requires NativeJsScope, got ${scope::class}")
        nativeScope.dangerousApi = bindings.dangerousApi
        val injectedKeys = mutableListOf<String>()
        val prevScope = threadLocalScope.value
        threadLocalScope.value = nativeScope
        val ctx = nativeScope.ctx!!
        try {
            val global = JS_GetGlobalObject(ctx)
            try {
                memScoped {
                    for ((key, value) in bindings) {
                        if (!isValidVarName(key)) continue
                        val jsValue = toJsValue(ctx, value) ?: continue
                        // JS_SetPropertyStr 转移 jsValue 所有权, 不需要 JS_FreeValue(jsValue)
                        JS_SetPropertyStr(ctx, global, key, jsValue)
                        injectedKeys.add(key)
                    }
                }
            } finally {
                JS_FreeValue(ctx, global)
            }
        } finally {
            threadLocalScope.value = prevScope
        }
        return injectedKeys
    }

    /**
     * 清理 [injectBindings] 注入的变量。
     *
     * native 实现 (iOS/鸿蒙一致): 通过 JS_Eval 执行 `delete globalThis[key]` 删除 (JS delete 操作符)。
     * 与 quickjs 的 nativeDeleteProperty 语义一致 (Configurable:true 的属性可删除)。
     */
    override fun cleanupBindings(scope: JsScope, keys: List<String>) {
        val nativeScope = scope as? NativeJsScope
            ?: error("NativeJsEngine.cleanupBindings requires NativeJsScope, got ${scope::class}")
        if (keys.isEmpty()) return
        val validKeys = keys.filter { isValidVarName(it) }
        if (validKeys.isEmpty()) return
        // 拼接 delete 语句 (一次性 JS_Eval, 避免逐 key 等价往返)
        val js = buildString(validKeys.sumOf { it.length + 24 }) {
            validKeys.forEach { key ->
                append("try{delete globalThis[")
                append(escapeJsString(key))
                append("]}catch(e){};")
            }
        }
        evalInternal(nativeScope.ctx!!, js, "<cleanupBindings>", checkException = false)
    }

    // ============ 双判谓词 (native 端具体类型自报) ============

    /** 判断对象是否为 native 端 JS Object ([NativeNativeObject])。 */
    override fun isJsObject(obj: Any?): Boolean = obj is NativeNativeObject

    /** 把 [NativeNativeObject] 包装为 [NativeJsObject], 非 JS Object 返回 null。 */
    override fun asJsObject(obj: Any?): JsObject? =
        (obj as? NativeNativeObject)?.let { NativeJsObject(it) }

    /** 判断是否为 JS 异常 — native 上 JS 异常通过 [NativeScriptException] 包装。 */
    override fun isJsException(t: Throwable): Boolean = t is NativeScriptException

    // ============ 当前线程 JS 执行上下文 ============

    /** 当前线程的 JS 执行上下文 (可空), 包装为 [NativeJsScope]。 */
    override fun currentContext(): JsScope? =
        threadLocalScope.value?.let { NativeJsScope(it.rt, it.ctx) }

    /** 当前线程的 JS 执行上下文 (非空), 无上下文时抛 [IllegalStateException]。 */
    override fun currentContextNonNull(): JsScope =
        threadLocalScope.value ?: error("No NativeJsScope on current thread")

    /**
     * 在当前 JS 执行上下文上设 coroutineContext 后执行 block (不创建新 scope)。
     *
     * 对应 quickjs 的 runScriptWithContext(context, block)。
     */
    override fun <T> runScriptWithContext(context: CoroutineContext, block: () -> T): T {
        val scope = threadLocalScope.value
        val prevCtx = scope?.coroutineContext
        if (scope != null) scope.coroutineContext = context
        return try {
            block()
        } finally {
            if (scope != null) scope.coroutineContext = prevCtx
        }
    }

    /** suspend 版本, 自动取当前协程上下文 (与原 IosJsEngine/OhosJsEngine 行为一致)。 */
    @Suppress("UNUSED_PARAMETER")
    override suspend fun <T> runScriptWithContext(block: () -> T): T {
        // 当前协程上下文由调用方通过 runScriptWithContext(context, block) 显式传递,
        // 此处无法取 currentCoroutineContext() (需 suspend coroutineContext),
        // 直接 fallback 为 block() — native 上 JSContext 跨协程访问由调用方保证。
        return block()
    }

    // ============ private helper: JS eval / 异常处理 ============

    /**
     * 在指定 ctx 上执行 JS, 返回 Kotlin 值。
     *
     * - 调 JS_Eval 执行脚本, 检查 [qjs_IsException] 判断异常;
     * - 异常时调 JS_GetException 取异常对象, 转 Kotlin 字符串后抛 [NativeScriptException];
     * - 正常时调 [fromJsValue] 把 JSValue 转为 Kotlin 值;
     * - JS_Eval 返回的 JSValue 必须 JS_FreeValue (无论成功/异常)。
     *
     * @param ctx quickjs JSContext 指针
     * @param js JS 源码字符串
     * @param filename 文件名 (用于错误信息, cinterop 需要传 C 字符串)
     * @param checkException 是否检查异常 (false 时仅执行不抛, 用于 __exitBindings 等清理调用)
     */
    private fun evalInternal(
        ctx: CPointer<JSContext>,
        js: String,
        filename: String,
        checkException: Boolean
    ): Any? {
        memScoped {
            val result = JS_Eval(ctx, js, js.length.toULong(), filename, qjs_EvalTypeGlobal())
            try {
                if (qjs_IsException(result) != 0) {
                    if (checkException) {
                        val exc = JS_GetException(ctx)
                        try {
                            val msg = exceptionToString(ctx, exc)
                            throw NativeScriptException(msg)
                        } finally {
                            JS_FreeValue(ctx, exc)
                        }
                    }
                    return null
                }
                return fromJsValue(ctx, result)
            } finally {
                JS_FreeValue(ctx, result)
            }
        }
    }

    /**
     * 把 JS 异常对象转为字符串描述。
     *
     * - Error 对象: 取 "stack" 属性 (含错误信息 + 调用栈), fallback 到 toString();
     * - 其他值: 用 [fromJsValue] 转 Kotlin 后 toString();
     * - 转换失败: fallback 到 "JS exception"。
     */
    private fun exceptionToString(ctx: CPointer<JSContext>, exc: CValue<JSValue>): String {
        return try {
            if (JS_IsError(exc)) {
                // Error 对象: 取 stack 属性 (quickjs Error 自动带 stack)
                memScoped {
                    val stackVal = JS_GetPropertyStr(ctx, exc, "stack")
                    try {
                        if (qjs_IsException(stackVal) != 0) return@memScoped jsValueToString(
                            ctx,
                            exc
                        )
                        if (qjs_IsString(stackVal) != 0 || qjs_IsUndefined(stackVal) == 0) {
                            val stackCstrVal = qjs_ToCString(ctx, stackVal)
                            if (stackCstrVal != null) {
                                val s = stackCstrVal.toKString()
                                qjs_FreeCString(ctx, stackCstrVal)
                                return@memScoped s.ifEmpty { jsValueToString(ctx, exc) }
                            }
                        }
                        return@memScoped jsValueToString(ctx, exc)
                    } finally {
                        JS_FreeValue(ctx, stackVal)
                    }
                }
            }
            jsValueToString(ctx, exc)
        } catch (t: Throwable) {
            "JS exception"
        }
    }

    /** 把任意 JSValue 转为字符串 (通过 JS_ToString + qjs_ToCString)。 */
    private fun jsValueToString(ctx: CPointer<JSContext>, v: CValue<JSValue>): String {
        // 用 qjs_ToCString 直接转 (quickjs 内部会调 JS_ToString)
        val cstr = qjs_ToCString(ctx, v) ?: return "JS exception"
        return try {
            cstr.toKString()
        } finally {
            qjs_FreeCString(ctx, cstr)
        }
    }

    // ============ private helper: JSValue <-> Kotlin 值转换 ============

    /**
     * 把 JSValue 转换为 Kotlin 值。
     *
     * - undefined/null → null
     * - boolean → Boolean (qjs_ValueGetBool)
     * - number → Int (JS_TAG_INT) 或 Double (JS_TAG_FLOAT64)
     * - string → String (qjs_ToCString + qjs_FreeCString)
     * - array → List (递归, JS_GetLength + JS_GetPropertyUint32)
     * - object → [NativeNativeObject] (递归, JS_GetOwnPropertyNames + JS_GetProperty)
     * - 其他 → null (fallback)
     *
     * 注意: 调用方负责 JS_FreeValue(input), 本函数不释放 input;
     * 但本函数内部创建的临时 JSValue (如 array 元素、object 属性值) 会即时 JS_FreeValue。
     */
    private fun fromJsValue(ctx: CPointer<JSContext>, v: CValue<JSValue>): Any? {
        if (qjs_IsUndefined(v) != 0 || qjs_IsNull(v) != 0) return null
        if (qjs_IsBool(v) != 0) return qjs_ValueGetBool(v) != 0
        if (qjs_IsNumber(v) != 0) {
            val tag = qjs_ValueGetTag(v)
            if (tag == JS_TAG_INT) return qjs_ValueGetInt(v)
            if (tag == JS_TAG_FLOAT64) return qjs_ValueGetFloat64(v)
            // 其他 number tag (如 JS_TAG_BIG_INT), fallback 用 JS_ToFloat64
            return memScoped {
                val pres = alloc<DoubleVar>()
                if (JS_ToFloat64(ctx, pres.ptr, v) == 0) pres.value else null
            }
        }
        if (qjs_IsString(v) != 0) {
            val cstr = qjs_ToCString(ctx, v) ?: return ""
            return try {
                cstr.toKString()
            } finally {
                qjs_FreeCString(ctx, cstr)
            }
        }
        if (qjs_IsObject(v) != 0) {
            // 判断 array 还是 plain object
            if (JS_IsArray(v)) {
                return fromJsArray(ctx, v)
            }
            return fromJsObject(ctx, v)
        }
        // 其他类型 (symbol/bigint/function 等) fallback 到字符串
        val cstr = qjs_ToCString(ctx, v) ?: return null
        return try {
            cstr.toKString()
        } finally {
            qjs_FreeCString(ctx, cstr)
        }
    }

    /** 把 JS array 转为 Kotlin List (递归)。 */
    private fun fromJsArray(ctx: CPointer<JSContext>, arr: CValue<JSValue>): List<Any?> {
        return memScoped {
            val pLen = alloc<LongVar>()
            if (JS_GetLength(ctx, arr, pLen.ptr) != 0) return@memScoped emptyList<Any?>()
            val count = pLen.value.toInt()
            if (count <= 0) return@memScoped emptyList<Any?>()
            val result = ArrayList<Any?>(count)
            for (i in 0 until count) {
                val elem = JS_GetPropertyUint32(ctx, arr, i.toUInt())
                try {
                    result.add(fromJsValue(ctx, elem))
                } finally {
                    JS_FreeValue(ctx, elem)
                }
            }
            result
        }
    }

    /** 把 JS plain object 转为 [NativeNativeObject] (递归)。 */
    private fun fromJsObject(ctx: CPointer<JSContext>, obj: CValue<JSValue>): NativeNativeObject {
        val result = NativeNativeObject()
        memScoped {
            val pTab = alloc<CPointerVar<JSPropertyEnum>>()
            val pLen = alloc<UIntVar>()
            // JS_GPN_STRING_MASK: 仅字符串 key; JS_GPN_ENUM_ONLY: 仅可枚举属性
            val flags = 1 or 16  // JS_GPN_STRING_MASK | JS_GPN_ENUM_ONLY
            if (JS_GetOwnPropertyNames(ctx, pTab.ptr, pLen.ptr, obj, flags) != 0) return@memScoped
            val count = pLen.value.toInt()
            val tab = pTab.value ?: return@memScoped
            try {
                for (i in 0 until count) {
                    val atom = tab[i.toLong()].atom
                    try {
                        val keyCstr = qjs_AtomToCString(ctx, atom)
                        if (keyCstr != null) {
                            val key = keyCstr.toKString()
                            qjs_FreeCString(ctx, keyCstr)
                            // 用 atom 取属性值 (避免再次通过 C 字符串解析)
                            val value = JS_GetProperty(ctx, obj, atom)
                            try {
                                result[key] = fromJsValue(ctx, value)
                            } finally {
                                JS_FreeValue(ctx, value)
                            }
                        }
                    } finally {
                        JS_FreeAtom(ctx, atom)
                    }
                }
            } finally {
                JS_FreePropertyEnum(ctx, tab, count.toUInt())
            }
        }
        return result
    }

    /**
     * 把 Kotlin 值转换为 JSValue (供 JS_SetPropertyStr 注入)。
     *
     * - null → JS_NULL (cValue 构造)
     * - Boolean → qjs_NewBool
     * - Int/Short/Byte → qjs_NewInt32
     * - Long/Float/Double/Number → qjs_NewFloat64
     * - String → qjs_NewString (cstr 在 memScope 内分配)
     * - Map<String,Any?> → JS_NewObject + 递归 JS_SetPropertyStr
     * - List<Any?> → JS_NewArray + 递归 JS_SetPropertyUint32
     * - 其他对象 → null (P0 stub, 复杂对象不桥接, 调用方跳过此 key)
     *
     * 注意: 返回的 JSValue 所有权归调用方 (除非传给 JS_SetPropertyStr 转移所有权);
     * 调用方需在不需要时 JS_FreeValue。
     *
     * @return JSValue, 或 null 表示跳过此 binding (复杂对象不桥接)
     */
    private fun toJsValue(ctx: CPointer<JSContext>, value: Any?): CValue<JSValue>? {
        val converted = JsValueConverters.convertAll(value)
        return when (converted) {
            null -> jsNullValue()
            is Boolean -> qjs_NewBool(ctx, if (converted) 1 else 0)
            is Int, is Short, is Byte -> qjs_NewInt32(ctx, converted.toInt())
            is Long, is Float, is Double -> {
                val d = (converted as Number).toDouble()
                if (d.isNaN() || d.isInfinite()) jsNullValue() else qjs_NewFloat64(ctx, d)
            }
            is Number -> {
                val d = converted.toDouble()
                if (d.isNaN() || d.isInfinite()) jsNullValue() else qjs_NewFloat64(ctx, d)
            }
            is String -> {
                qjs_NewString(ctx, converted)
            }
            is Map<*, *> -> {
                @Suppress("UNCHECKED_CAST")
                mapToJsObject(ctx, converted as Map<String, Any?>)
            }

            is List<*> -> listToJsArray(ctx, converted)
            is JsExtensionsCommon -> {
                // JsExtensions 桥接: 通过 handle 表 + JS 工厂函数桥接为 JS 对象 (NativeJsExtensionsBridge)
                // 需要当前 scope (threadLocalScope) 记录 handle, close 时清理
                val currentScope = threadLocalScope.value
                    ?: return null  // 无 scope 上下文, 跳过 (非 injectBindings 调用路径)
                NativeJsExtensionsBridge.createJsObject(ctx, converted, currentScope)
            }
            else -> null  // 其他复杂对象暂不桥接 (TODO: 后续按需扩展)
        }
    }

    /** Map 递归转 JS object (供 JS_SetPropertyStr 注入)。 */
    private fun mapToJsObject(ctx: CPointer<JSContext>, map: Map<String, Any?>): CValue<JSValue> {
        val obj = JS_NewObject(ctx)
        memScoped {
            for ((k, v) in map) {
                val keyStr = k as? String ?: continue
                val jsV = toJsValue(ctx, v) ?: continue
                // JS_SetPropertyStr 转移 jsV 所有权, 不需要 JS_FreeValue(jsV)
                JS_SetPropertyStr(ctx, obj, keyStr, jsV)
            }
        }
        return obj
    }

    /** List 递归转 JS array (供 JS_SetPropertyStr 注入)。 */
    private fun listToJsArray(ctx: CPointer<JSContext>, list: List<*>): CValue<JSValue> {
        val arr = JS_NewArray(ctx)
        list.forEachIndexed { i, item ->
            val jsItem = toJsValue(ctx, item) ?: return@forEachIndexed
            // JS_SetPropertyUint32 转移 jsItem 所有权, 不需要 JS_FreeValue(jsItem)
            JS_SetPropertyUint32(ctx, arr, i.toUInt(), jsItem)
        }
        return arr
    }

    /** 构造 JS null JSValue (cValue 构造, tag=JS_TAG_NULL, u 默认 0)。 */
    private fun jsNullValue(): CValue<JSValue> = cValue {
        tag = JS_TAG_NULL.toLong()
    }

    // ============ private helper: bindings kvs 字面量构造 ============

    /**
     * 构造 __enterBindings(k1,v1,k2,v2,...) 调用的 JS 参数字符串。
     *
     * 把 bindings 铺成 JS 字面量参数 (对齐 quickjs 的 buildBindingKvs):
     * - String → 转义后的字符串字面量
     * - Number/Boolean → 原样
     * - null → null
     * - Map/List/复杂对象 → 跳过 (通过 JS_SetPropertyStr 注入 globalThis, 不走 __enterBindings)
     */
    private fun buildBindingKvsJs(bindings: JsBindings): String {
        val parts = ArrayList<String>(bindings.size * 2)
        for ((key, value) in bindings) {
            if (!isValidVarName(key)) continue
            val valueJs = toJsLiteral(value)
            if (valueJs == null) continue  // Map/List/复杂对象跳过
            parts.add(escapeJsString(key))
            parts.add(valueJs)
        }
        return parts.joinToString(",")
    }

    /** 把 Kotlin 值转为 JS 字面量字符串 (用于拼接 __enterBindings 调用)。null 表示跳过。 */
    private fun toJsLiteral(value: Any?): String? {
        val converted = JsValueConverters.convertAll(value)
        return when (converted) {
            null -> "null"
            is Boolean -> converted.toString()
            is Int, is Long, is Short, is Byte -> converted.toString()
            is Float, is Double -> {
                val d = (converted as Number).toDouble()
                if (d.isNaN() || d.isInfinite()) "null" else d.toString()
            }

            is Number -> converted.toDouble().toString()
            is String -> escapeJsString(converted)
            // Map/List 通过 JS_SetPropertyStr 注入 globalThis, 不走 __enterBindings (避免 JSON 序列化丢失类型)
            is Map<*, *> -> null
            is List<*> -> null
            else -> null  // P0 stub: 复杂对象不通过 __enterBindings 注入
        }
    }

    // ============ private helper: 通用工具 ============

    /**
     * 在指定 scope 上执行 block, 处理 ThreadLocal / 递归检查 / coroutineContext 同步。
     * 对应 quickjs/iOS/鸿蒙的 withEvalContext (公共模板)。
     */
    private inline fun <T> withEvalContext(
        scope: NativeJsScope,
        coroutineContext: CoroutineContext?,
        crossinline block: () -> T
    ): T {
        val prevScope = threadLocalScope.value
        threadLocalScope.value = scope
        val prevCoroutineContext = scope.coroutineContext
        val prevAllowScriptRun = scope.allowScriptRun
        if (coroutineContext != null) {
            scope.coroutineContext = coroutineContext
        }
        scope.allowScriptRun = true
        scope.recursiveCount++
        return try {
            scope.checkRecursive()
            block()
        } finally {
            scope.coroutineContext = prevCoroutineContext
            scope.allowScriptRun = prevAllowScriptRun
            scope.recursiveCount--
            threadLocalScope.value = prevScope
        }
    }

    /** 变量名合法性检查 (对齐 quickjs/iOS/鸿蒙的 isValidVarName)。 */
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
     * 把 Kotlin 字符串转为 JS 字符串字面量 (含首尾双引号)。
     *
     * 对齐 quickjs/iOS/鸿蒙的 JsStringUtils.escape, 确保 \b / \f / \u0000-\u001F 等控制字符一致转义。
     * (modules/quickjs 的 JsStringUtils 是 internal, native 端无法复用, 此处独立实现)
     */
    private fun escapeJsString(s: String): String {
        val sb = StringBuilder(s.length + 2)
        sb.append('"')
        for (c in s) {
            when (c) {
                '\\' -> sb.append("\\\\")
                '"' -> sb.append("\\\"")
                '\n' -> sb.append("\\n")
                '\r' -> sb.append("\\r")
                '\t' -> sb.append("\\t")
                '\u0008' -> sb.append("\\b")  // \b (U+0008)
                '\u000C' -> sb.append("\\f")  // \f (U+000C)
                else -> {
                    if (c.code < 0x20) {
                        // 纯 Kotlin 等价 "\\u%04x".format(c.code) (Native 无 String.format);
                        // c.code < 0x20 恒为正, 小写 hex 左补零到 4 位
                        sb.append("\\u").append(c.code.toString(16).padStart(4, '0'))
                    } else {
                        sb.append(c)
                    }
                }
            }
        }
        sb.append('"')
        return sb.toString()
    }

    /**
     * native 端 (iOS/鸿蒙) bootstrap 脚本。
     *
     * 仅注入 [evalInSubScope] 需要的 bindings 子 scope 栈 (与 quickjs JsBootstrap 一致):
     * - `__bindingsStack__` / `__currentBindings` / `__enterBindings` / `__exitBindings`
     *
     * Java 类相关 (Packages/JavaImporter/JavaAdapter/__loadJavaClass 等): native 无 Java 反射,
     * 用白名单类表兼容面 [NativeJavaCompat] 支持常用类 (java.lang.System /
     * java.net.URLEncoder / java.net.URLDecoder / java.util.UUID) 的静态方法:
     * - `__loadJavaClass` → 白名单类句柄 (正整数) / 表外类 0
     * - `__classExists` → 白名单类 true / 其他 false
     * - `__isInterface` → false
     * - `__callStaticMethod` → 经 `__nativeJavaCompat` 分派到 Kotlin 静态方法表 (表外方法 null)
     * - `__newJavaInstance` / `__getStaticField` / `__setStaticField` → null/false (无对象实例化/字段反射)
     * - `__newJavaAdapter` → 抛异常 (native 无 Java 反射)
     * - `__getDangerousApi` → true (native 无安全模型: 类表固定且安全, 不存在可旁路的任意类加载)
     *
     * 这些桩让 bootstrap 不依赖 native binding (quickjs 通过 nativeDefineBinding 注册),
     * JS 层直接定义全局函数, 行为等价于"binding 调用 Kotlin 后返回桩值"。
     */
    private val BOOTSTRAP_CODE: String = """
// ============ bindings 子 scope 栈 (与 quickjs JsBootstrap 一致) ============
var __bindingsStack__ = [];

function __currentBindings() {
    return __bindingsStack__[__bindingsStack__.length - 1] || {};
}

function __enterBindings() {
    var obj = {};
    for (var i = 0; i < arguments.length; i += 2) {
        obj[arguments[i]] = arguments[i + 1];
    }
    var prev = {};
    var existed = {};
    var keys = Object.keys(obj);
    for (var i = 0; i < keys.length; i++) {
        var key = keys[i];
        existed[key] = globalThis.hasOwnProperty(key);
        if (existed[key]) prev[key] = globalThis[key];
        globalThis[key] = obj[key];
    }
    obj.__prevGlobals__ = prev;
    obj.__prevKeys__ = keys;
    obj.__prevExisted__ = existed;
    __bindingsStack__.push(obj);
}

function __exitBindings() {
    var obj = __bindingsStack__.pop();
    if (obj && obj.__prevKeys__) {
        var prev = obj.__prevGlobals__;
        var keys = obj.__prevKeys__;
        var existed = obj.__prevExisted__;
        for (var i = 0; i < keys.length; i++) {
            var key = keys[i];
            if (existed[key]) {
                globalThis[key] = prev[key];
            } else {
                delete globalThis[key];
            }
        }
    }
}

// ============ Java 类兼容面 (native 无 Java 反射, 用白名单类表 + 静态方法表) ============
// 与 quickjs 端一致: Packages/importClass/JavaAdapter 等 rhino LiveConnect 兼容 API 通过
// __loadJavaClass/__callStaticMethod 等桩函数回 Kotlin 分派。native 侧由 __nativeJavaCompat
// (NativeJavaCompat) 实现白名单类 (System/URLEncoder/URLDecoder/UUID) 的静态方法,
// 其余类返回 0/null 让书源 JS 明确失败 (规则层 runCatching 后呈现为规则错误)。

function __getDangerousApi() {
    // native 无 Java 反射亦无安全模型: 类表固定且仅含白名单安全类, 不存在可旁路的
    // 任意类加载, dangerousApi 语义天然全放行 (放行也不会新增能力, 仅让书源走 java.* 路径)
    return true;
}

function __loadJavaClass(fullName, dangerousApi) {
    // 返回类句柄 (正整数); 白名单外返回 0 (类句柄, 表示加载失败)
    return __nativeJavaCompat("load", fullName, "", []);
}

function __classExists(fullName, dangerousApi) {
    return __nativeJavaCompat("exists", fullName, "", []) !== 0;
}

function __isInterface(classHandle, dangerousApi) {
    return false;
}

function __newJavaInstance(classHandle, args, dangerousApi) {
    // native 无对象实例化 (无反射), 返回 null 让书源明确失败
    return null;
}

function __callStaticMethod(classHandle, methodName, args, dangerousApi) {
    return __nativeJavaCompat("callStatic", classHandle, methodName, args || []);
}

function __getStaticField(classHandle, fieldName, dangerousApi) {
    return null;
}

function __setStaticField(classHandle, fieldName, value, dangerousApi) {
    return false;
}

function __newJavaAdapter(classHandle, jsFnHandle, dangerousApi) {
    throw new Error('JavaAdapter not supported on native (no Java reflection); use java.* JsExtensions bindings instead');
}

function __registerJsFunctionNative(jsObjectExpr, dangerousApi) {
    return 0;
}

function __wrapJavaHandle(handle) {
    return null;
}
    """.trimIndent() + "\n" + NativeJsExtensionsBridge.JS_FACTORY_CODE

    // ============ Java 类兼容面 (Packages/importClass 白名单类表) ============

    /**
     * 全局 native Java 兼容分派函数 C 指针, 由 bootstrap 注入 globalThis.__nativeJavaCompat。
     *
     * 签名: __nativeJavaCompat(method, arg1, arg2, args) → any
     * - method "load"      : arg1 = 类全名 → 类句柄 (Int) 或 0
     * - method "exists"    : arg1 = 类全名 → 0/1
     * - method "callStatic": arg1 = 类句柄 (Int), arg2 = 方法名, args = 参数数组
     *
     * 与 [NativeJsExtensionsBridge.nativeDispatchFn] 同模式 (staticCFunction, 不捕获上下文)。
     */
    internal val nativeJavaCompatFn =
        staticCFunction { ctx: CPointer<JSContext>?, thisVal: CValue<JSValue>, argc: Int, argv: CPointer<JSValue>? ->
            javaCompatImpl(ctx, thisVal, argc, argv)
        }

    /** [nativeJavaCompatFn] 的 Kotlin 实现 (object 方法, 可被 staticCFunction 调用)。 */
    private fun javaCompatImpl(
        ctx: CPointer<JSContext>?,
        @Suppress("UNUSED_PARAMETER") thisVal: CValue<JSValue>,
        argc: Int,
        argv: CPointer<JSValue>?
    ): CValue<JSValue> {
        val ctxNotNull = ctx ?: return jsUndefined()
        if (argc < 3 || argv == null) return jsUndefined()
        try {
            val method = jsValueToString(ctxNotNull, argv[0L].readValue())
            when (method) {
                "load" -> {
                    val className = jsValueToString(ctxNotNull, argv[1L].readValue())
                    return qjs_NewInt32(ctxNotNull, NativeJavaCompat.classIdOf(className))
                }

                "exists" -> {
                    val className = jsValueToString(ctxNotNull, argv[1L].readValue())
                    return qjs_NewBool(
                        ctxNotNull,
                        if (NativeJavaCompat.classIdOf(className) != 0) 1 else 0
                    )
                }

                "callStatic" -> {
                    if (argc < 4) return jsUndefined()
                    val classId = qjs_ValueGetInt(argv[1L].readValue())
                    val methodName = jsValueToString(ctxNotNull, argv[2L].readValue())
                    val argsArray = argv[3L].readValue()
                    val result = NativeJavaCompat.callStatic(
                        classId, methodName, fromJsArray(ctxNotNull, argsArray)
                    )
                    // 结果经 toJsValue 转换 (基本类型/字符串), 复杂对象保持 null (同注入语义)
                    return toJsValue(ctxNotNull, result) ?: jsNullValue()
                }

                else -> return jsUndefined()
            }
        } catch (t: Throwable) {
            // 兼容面异常返回 undefined, 避免 JS 引擎崩溃 (与 nativeDispatchImpl 行为一致)
            return jsUndefined()
        }
    }
}

/**
 * 白名单 Java 类静态方法兼容面 (native 无反射, 供 Packages.java.xxx / importClass 使用)。
 *
 * # 覆盖范围 (常用书源类)
 * - `java.lang.System`: currentTimeMillis() / nanoTime() / lineSeparator() / getProperty() → null
 * - `java.net.URLEncoder`: encode(String, String) (UTF-8, 空格 → '+', 与 JDK 行为一致)
 * - `java.net.URLDecoder`: decode(String, String) (UTF-8, '+' → 空格)
 * - `java.util.UUID`: randomUUID() → 标准 v4 UUID 字符串
 *
 * # 限制 (明确失败而非静默)
 * - 表外类: __loadJavaClass 返回 0 → quickjs 端 Packages 代理 fallback 后方法调用返回 undefined;
 * - 表内类但方法不在表内: callStatic 返回 null;
 * - 对象实例化 / 字段读写 / JavaAdapter: 无反射不支持, 返回 null/false/抛异常。
 *
 * 建议书源优先使用 `java.*` JsExtensionsCommon 绑定 (native 全量支持),
 * 本表仅为历史书源 (importClass 风格) 的兼容兜底。
 */
private object NativeJavaCompat {

    private const val CLASS_SYSTEM = 1
    private const val CLASS_URL_ENCODER = 2
    private const val CLASS_URL_DECODER = 3
    private const val CLASS_UUID = 4

    /** 类全名 → 类句柄; 表外类返回 0。 */
    fun classIdOf(fullName: String): Int = when (fullName) {
        "java.lang.System" -> CLASS_SYSTEM
        "java.net.URLEncoder" -> CLASS_URL_ENCODER
        "java.net.URLDecoder" -> CLASS_URL_DECODER
        "java.util.UUID" -> CLASS_UUID
        else -> 0
    }

    /** 静态方法分派; 表外方法返回 null (书源侧得到 null/undefined 明确失败)。 */
    fun callStatic(classId: Int, methodName: String, args: List<Any?>): Any? = when (classId) {
        CLASS_SYSTEM -> when (methodName) {
            "currentTimeMillis" -> io.legado.app.utils.systemCurrentTimeMillis()
            "nanoTime" -> io.legado.app.utils.systemNanoTime()
            "lineSeparator" -> "\n"
            // getProperty 依赖平台属性表, native 不支持 → null (书源侧判空)
            else -> null
        }

        CLASS_URL_ENCODER -> when (methodName) {
            "encode" -> urlEncode(args.getString(0))
            else -> null
        }

        CLASS_URL_DECODER -> when (methodName) {
            "decode" -> urlDecode(args.getString(0))
            else -> null
        }

        CLASS_UUID -> when (methodName) {
            "randomUUID" -> io.legado.app.utils.randomUUIDString()
            else -> null
        }

        else -> null
    }

    private fun List<Any?>.getString(index: Int): String =
        getOrNull(index)?.toString() ?: ""

    private val HEX = "0123456789ABCDEF"

    /**
     * java.net.URLEncoder.encode(str, "UTF-8") 等价实现: 字母数字与 -_.* 原样,
     * 空格 → '+', 其余字节 %XX (UTF-8)。
     */
    private fun urlEncode(str: String): String {
        val bytes = str.encodeToByteArray()
        val sb = StringBuilder(bytes.size)
        for (b in bytes) {
            val c = b.toInt() and 0xFF
            val keep = (c in 'A'.code..'Z'.code) || (c in 'a'.code..'z'.code) ||
                (c in '0'.code..'9'.code) || c == '-'.code || c == '_'.code ||
                c == '.'.code || c == '*'.code
            when {
                keep -> sb.append(c.toChar())
                c == ' '.code -> sb.append('+')
                else -> sb.append('%').append(HEX[c ushr 4]).append(HEX[c and 0xF])
            }
        }
        return sb.toString()
    }

    /**
     * java.net.URLDecoder.decode(str, "UTF-8") 等价实现: '+' → 空格, %XX 解码 (UTF-8),
     * 非 ASCII 原字符先按 UTF-8 编码再解码 (与 JDK 对非法串的宽容处理一致: 非法 % 序列原样保留)。
     */
    private fun urlDecode(str: String): String {
        val bytes = ArrayList<Byte>(str.length)
        var i = 0
        while (i < str.length) {
            val c = str[i]
            when {
                c == '+' -> {
                    bytes.add(' '.code.toByte())
                    i++
                }

                c == '%' && i + 2 < str.length -> {
                    val hi = hexVal(str[i + 1])
                    val lo = hexVal(str[i + 2])
                    if (hi >= 0 && lo >= 0) {
                        bytes.add(((hi shl 4) or lo).toByte())
                        i += 3
                    } else {
                        bytes.add(c.code.toByte())
                        i++
                    }
                }

                else -> {
                    bytes.addAll(c.toString().encodeToByteArray().toList())
                    i++
                }
            }
        }
        return bytes.toByteArray().decodeToString()
    }

    private fun hexVal(c: Char): Int = when (c) {
        in '0'..'9' -> c - '0'
        in 'a'..'f' -> c - 'a' + 10
        in 'A'..'F' -> c - 'A' + 10
        else -> -1
    }
}

/**
 * native 端 (iOS/鸿蒙) JS 执行 scope, 持有 quickjs [JSRuntime] / [JSContext] 指针。
 *
 * 对应 quickjs 的 QuickJsJsScope (包装 QuickJsContext)。
 * quickjs 单线程模型, 一个 [JSRuntime] + [JSContext] 对应一个 scope, 跨 scope 互不影响。
 *
 * # 资源管理
 * - [rt] / [ctx] 由 [NativeJsEngine.getRuntimeScope] / [createStandaloneScope] 创建;
 * - [close] 时调用 JS_FreeContext + JS_FreeRuntime 显式释放 (与原 IosJsEngine JavaScriptCore /
 *   OhosJsEngine JSVM-API 不同, quickjs 需手动释放, 否则内存泄漏);
 * - [ctx] 可能为 null (close 后), 调用方需判空 (evalInternal 等内部方法用 ctx!! 强制非空)。
 *
 * @property rt quickjs JSRuntime 指针 (cinterop CPointer<JSRuntime>), 由 JS_NewRuntime 创建
 * @property ctx quickjs JSContext 指针 (cinterop CPointer<JSContext>), 由 JS_NewContext 创建
 * @property dangerousApi 是否旁路安全名单 (native 上语义为 no-op, 保留字段对齐接口)
 */
class NativeJsScope(
    /** quickjs JSRuntime 指针 (CPointer<JSRuntime>), 由 JS_NewRuntime 创建。null = 已 close。 */
    val rt: CPointer<JSRuntime>?,
    /** quickjs JSContext 指针 (CPointer<JSContext>), 由 JS_NewContext 创建。null = 已 close。 */
    val ctx: CPointer<JSContext>?,
    /** native 上 dangerousApi 语义为 no-op (无 Java 反射可旁路), 保留字段仅为接口对齐 */
    var dangerousApi: Boolean = false
) : JsScope {

    override var coroutineContext: CoroutineContext? = null
    override var allowScriptRun: Boolean = false
    override var recursiveCount: Int = 0

    /**
     * JsExtensions 桥接器 handle 列表 (NativeJsExtensionsBridge 注册的 handle)。
     *
     * [NativeJsExtensionsBridge.createJsObject] 注册 handle 时同步记录到此列表,
     * [close] 时统一调 [NativeJsExtensionsBridge.unregisterObject] 清理, 防止 Kotlin 对象内存泄漏。
     */
    val handles = mutableListOf<Long>()

    /** 检查协程是否已取消, 在 binding handler 中由业务层调用。 */
    override fun ensureActive() {
        coroutineContext?.ensureActive()
    }

    /** 递归深度检查, 防止 JS 递归调用导致栈溢出。 */
    override fun checkRecursive() {
        if (recursiveCount >= MAX_RECURSION) {
            throw NativeScriptException("Maximum recursion depth ($MAX_RECURSION) exceeded")
        }
    }

    /**
     * 关闭 scope — 显式释放 quickjs JSContext + JSRuntime 资源。
     *
     * 与原 IosJsEngine (JavaScriptCore JSContext 由 ARC 管理) / OhosJsEngine (JSVM-API VM/Env) 不同,
     * quickjs 需手动释放:
     * 1. JS_FreeContext(ctx) — 释放 JSContext (会触发 GC 释放所有 JS 对象)
     * 2. JS_FreeRuntime(rt) — 释放 JSRuntime (会检查内存泄漏, dump 残留对象)
     *
     * 幂等: 多次调用安全 (rt/ctx 释放后置 null, 后续调用 no-op)。
     * 保留 close() 实现为满足 AutoCloseable 接口 + 业务层 try-finally 模式。
     */
    override fun close() {
        val localCtx = ctx
        val localRt = rt
        if (localCtx != null) {
            JS_FreeContext(localCtx)
        }
        if (localRt != null) {
            JS_FreeRuntime(localRt)
        }
        // 清理 JsExtensions 桥接器 handle 表 (防止 Kotlin 对象内存泄漏)
        for (handle in handles) {
            NativeJsExtensionsBridge.unregisterObject(handle)
        }
        handles.clear()
        // 注: rt/ctx 是 val 不能置 null, 但 close 后不应再使用;
        // evalInternal 等方法用 ctx!! 强制非空, close 后调用会 NPE (符合预期, 调用方应避免)
    }

    private companion object {
        const val MAX_RECURSION = 10
    }
}

/**
 * native 端 (iOS/鸿蒙) "编译"脚本包装 — 持有源码字符串 (P0 阶段无 bytecode)。
 *
 * 对应 quickjs 的 QuickJsJsCompiledScript (持 CompiledScript(bytecode))。
 * [bytecode] 恒为 null (P0 阶段), 业务层通过 [eval] 执行源码。
 *
 * TODO: 后续用 JS_Eval + JS_EVAL_FLAG_COMPILE_ONLY 编译为 bytecode,
 * 用 JS_WriteObject 序列化为 ByteArray, 再用 JS_ReadObject + JS_EvalFunction 执行。
 *
 * 注: [JsCompiledScript.bytecode] 接口要求返回 ByteArray?, native P0 阶段恒 null,
 * SharedJsScope 的 bytecodeCache 在 native 上不会命中 (key 对应 null bytecode)。
 */
class NativeJsCompiledScript(val source: String) : JsCompiledScript {

    /** native P0 阶段无 bytecode, 恒返回 null。 */
    override val bytecode: ByteArray? = null

    /** 在指定 scope 上执行源码 (fallback 为源码 eval)。 */
    override fun eval(scope: JsScope, coroutineContext: CoroutineContext?): Any? {
        val nativeScope = scope as? NativeJsScope
            ?: error("NativeJsCompiledScript requires NativeJsScope, got ${scope::class}")
        return NativeJsEngine.eval(source, nativeScope, coroutineContext)
    }
}

/**
 * native 端 (iOS/鸿蒙) JS Object 标记类型, 对齐 quickjs 的 NativeObject。
 *
 * 由 [NativeJsEngine.fromJsValue] 把 JS plain object (非 array/function) 包装为本类实例,
 * 业务代码可用 `is NativeNativeObject` 区分 JS 返回的对象与其他来源的 Map
 * (与 quickjs 的 `is NativeObject` / 原 `is IosNativeObject` / `is OhosNativeObject` 行为一致)。
 *
 * K/N 的 LinkedHashMap 为 final 不可继承, 改用接口委托保持 Map 兼容性,
 * 业务代码可直接当 Map 使用。
 *
 * 注: 类名 NativeNativeObject 中第一个 Native 指 nativeMain 源集 (iOS/鸿蒙共用),
 * 第二个 Native 对齐 quickjs 的 NativeObject 命名 (JS 原生对象标记)。
 */
class NativeNativeObject(initialCapacity: Int = 0) :
    MutableMap<String, Any?> by LinkedHashMap(initialCapacity)

/**
 * native 端 (iOS/鸿蒙) [NativeNativeObject] 的 [JsObject] 包装。
 *
 * 对应 quickjs 的 QuickJsJsObject (包装 NativeObject)。
 * 业务层 `jsObj[rule]` 走 Map 索引 (by delegate 到 [NativeNativeObject])。
 */
class NativeJsObject(val delegate: NativeNativeObject) : JsObject,
    MutableMap<String, Any?> by delegate

/**
 * native 端 (iOS/鸿蒙) JS 执行异常。
 *
 * 对应 quickjs 的 com.script.quickjs.ScriptException。
 * 由 [NativeJsEngine.evalInternal] 在 JS 抛异常时构造, 业务层 catch 时
 * `is Exception` 可匹配。
 */
class NativeScriptException(message: String) : Exception(message)

