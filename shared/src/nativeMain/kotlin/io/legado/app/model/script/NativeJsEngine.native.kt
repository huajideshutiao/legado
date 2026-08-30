@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package io.legado.app.model.script

import com.fleeksoft.ksoup.nodes.Node
import com.script.jsdispatch.JsValueConverters

import io.legado.app.data.entities.BookChapterLike
import io.legado.app.data.entities.BookLike
import io.legado.app.help.JsExtensionsCommon
import io.legado.app.help.source.SourceCacheProvider
import io.legado.app.help.source.SourceNetworkProvider
import io.legado.app.napi.quickjs.JSContext
import io.legado.app.napi.quickjs.JSPropertyEnum
import io.legado.app.napi.quickjs.JSRuntime
import io.legado.app.napi.quickjs.JSValue
import io.legado.app.napi.quickjs.JS_TAG_FLOAT64
import io.legado.app.napi.quickjs.JS_TAG_INT
import io.legado.app.napi.quickjs.JS_TAG_NULL
import io.legado.app.napi.quickjs.JS_TAG_UNDEFINED
import io.legado.app.napi.quickjs.qjs_EvalTypeGlobal
import io.legado.app.napi.quickjs.JS_Call
import io.legado.app.napi.quickjs.JS_Eval
import io.legado.app.napi.quickjs.JS_EvalFunction
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
import io.legado.app.napi.quickjs.JS_GetRuntime
import io.legado.app.napi.quickjs.JS_UpdateStackTop
import io.legado.app.napi.quickjs.qjs_ArrayBufferRead
import io.legado.app.napi.quickjs.qjs_ArrayBufferSize
import io.legado.app.napi.quickjs.qjs_NewUint8ArrayCopy
import io.legado.app.napi.quickjs.JS_IsArray
import io.legado.app.napi.quickjs.JS_IsArrayBuffer
import io.legado.app.napi.quickjs.JS_IsError
import io.legado.app.napi.quickjs.JS_NewArray
import io.legado.app.napi.quickjs.JS_NewContext
import io.legado.app.napi.quickjs.JS_NewObject
import io.legado.app.napi.quickjs.JS_NewRuntime
import io.legado.app.napi.quickjs.JS_NewUint8Array
import io.legado.app.napi.quickjs.JS_SetPropertyStr
import io.legado.app.napi.quickjs.JS_SetPropertyUint32
import io.legado.app.napi.quickjs.JS_ToBool
import io.legado.app.napi.quickjs.JS_ToFloat64
import io.legado.app.napi.quickjs.JS_ToInt32
import io.legado.app.napi.quickjs.qjs_AtomToCString
import io.legado.app.napi.quickjs.qjs_EvalFlagCompileOnly
import io.legado.app.napi.quickjs.qjs_FreeBytecodeBuf
import io.legado.app.napi.quickjs.qjs_ReadBytecode
import io.legado.app.napi.quickjs.qjs_WriteBytecode
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
import kotlinx.cinterop.CFunction
import kotlinx.cinterop.CPointer
import kotlinx.cinterop.CPointerVar
import kotlinx.cinterop.CValue
import kotlinx.cinterop.COpaquePointer
import kotlinx.cinterop.DoubleVar
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.LongVar
import kotlinx.cinterop.UByteVar
import kotlinx.cinterop.UIntVar
import kotlinx.cinterop.ULongVar
import kotlinx.cinterop.alloc
import kotlinx.cinterop.allocArray
import kotlinx.cinterop.cValue
import kotlinx.cinterop.cstr
import kotlinx.cinterop.get
import kotlinx.cinterop.interpretCPointer
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.plus
import kotlinx.cinterop.pointed
import kotlinx.cinterop.ptr
import kotlinx.cinterop.readValue
import kotlinx.cinterop.refTo
import kotlinx.cinterop.reinterpret
import kotlinx.cinterop.sizeOf
import kotlinx.cinterop.toCPointer
import kotlinx.cinterop.toKString
import kotlinx.cinterop.useContents
import kotlinx.cinterop.value
import kotlinx.atomicfu.atomic
import kotlinx.atomicfu.locks.SynchronizedObject
import kotlinx.atomicfu.locks.synchronized
import kotlinx.cinterop.readBytes
import kotlinx.coroutines.ensureActive
import kotlin.coroutines.ContinuationInterceptor
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.coroutineContext
import platform.posix.memcpy

/**
 * JS_Eval 的 UTF-8 安全封装 (compile / evalInternal / bridge 工厂 eval 共用)。
 *
 * input_len 必须是 UTF-8 字节数: cinterop 把 Kotlin String 按 UTF-8 编码传给
 * const char *input, 若传 String.length (UTF-16 单元数), 含中文的书源 JS 字节数更大,
 * QuickJS 只解析前 length 字节, 尾部 ");}})()" 被截断, 报
 * "SyntaxError: unexpected end of string" (Android/Desktop 走 JNI 用 strlen 传字节数,
 * 无此问题)。input 指针由 cinterop 自动按 UTF-8 编码, 长度另用 encodeToByteArray
 * 按字节计, 保证完整解析。
 */
internal fun qjsEvalUtf8(
    ctx: CPointer<JSContext>,
    js: String,
    filename: String,
    evalFlags: Int
): CValue<JSValue> {
    // JS_Eval 按 [0, input_len) 读入不要求 NUL 结尾; input 形参 (const char *) 被
    // cinterop 绑定为 String?, 只能直接传 String, 不能传 ByteArray 的 refTo(0) 字节指针
    return JS_Eval(ctx, js, js.encodeToByteArray().size.toULong(), filename, evalFlags)
}

/**
 * native 端 (iOS/鸿蒙) JS 引擎: 基于 quickjs-ng C 源码 (cinterop 编译), 与 Android/Desktop
 * [QuickJsJsEngine] 共用同一 quickjs 引擎 (全平台统一)。原 iosMain [IosJsEngine] /
 * ohosMain [OhosJsEngine] 逻辑一致, 下沉到 nativeMain, 平台端 typealias 指向本类。
 *
 * 选型: 原 iOS 用 JavaScriptCore、鸿蒙用 JSVM-API (ArkJS/V8), 与 quickjs 行为不一致
 * (ES 特性/错误信息/bytecode), 改 cinterop 直接编译 shared/src/cinterop/quickjs-ng/ 的 C 源码
 * (单一数据源, iOS/鸿蒙 cinterop 与 Android/Desktop JNI CMake 共用; Kotlin/Native 无 JNI)。
 *
 * 与 [QuickJsJsEngine] 差异: 编译缓存 = quickjs bytecode (JS_Eval + JS_EVAL_FLAG_COMPILE_ONLY +
 * JS_WriteObject 序列化, 自带 atom 表可跨 ctx/runtime 复用);
 * Java 桥 = JS_SetPropertyStr + Map/List (无反射); Packages/importClass 等 LiveConnect
 * 通路恒失败 (桩函数返回 0/false/null), 需改用 java.* JsExtensions 绑定;
 * JavaAdapter 抛异常; 资源管理 = JS_FreeContext/JS_FreeRuntime。
 *
 * 注册: 宿主启动早期经 registerNativeJsEngines 注册到 [JsEngines]。
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

    /** 编译用临时 ctx 单例 (进程级, 永不释放 — 对齐 JVM QuickJsEngine.compilerCtx 生命周期)。 */
    private val compilerLock = SynchronizedObject()
    private var compilerCtx: CPointer<JSContext>? = null

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
            }
        } finally {
            JS_FreeValue(ctx, global)
        }
    }

    // ============ 编译 ============

    /**
     * 编译 JS 为 bytecode, 供 [evalBytecode] 执行 (跳过每次执行的 parse+compile)。
     *
     * 语义对齐 JVM nativeCompile: JS_Eval + JS_EVAL_FLAG_COMPILE_ONLY 得到 function object,
     * JS_WriteObject 序列化为 ByteArray。编译期异常 (语法错误) 抛 [NativeScriptException]
     * 带 JS stack (与运行期异常同型, 对齐 JVM compile 抛 ScriptException)。
     * bytecode 自带 atom 表 (quickjs.c JS_WriteObjectAtoms), 可跨 ctx/runtime 读回
     * (JVM bootstrapBytecode 跨实例复用同依据), 故可放心在任意 scope 上执行。
     *
     * 线程安全: 编译在共享 [compilerCtx] 上执行 (quickjs JSContext 非线程安全),
     * 整体串行化在 [compilerLock] 内 (对齐 JVM compile 的 synchronized)。
     */
    override fun compile(script: String): JsCompiledScript = synchronized(compilerLock) {
        compileToBytecode(script, getCompilerCtx(), "<compile>")
    }

    /** 在指定 scope 上编译 (复用 scope 的 ctx, 符号解析一致, 对齐 JVM compile(script, scope))。 */
    override fun compile(script: String, scope: JsScope): JsCompiledScript {
        val nativeScope = scope as? NativeJsScope
            ?: error("NativeJsEngine.compile requires NativeJsScope, got ${scope::class}")
        return compileToBytecode(script, nativeScope.ctx!!, "<compile>")
    }

    /**
     * 获取编译用临时 ctx (懒创建, 复用; 进程级生命周期, 永不释放 — 对齐 JVM getCompilerCtx)。
     * compile 只解析不执行, 无需 bootstrap/bindings。
     * 调用方需已持有 [compilerLock] (见 [compile])。
     */
    private fun getCompilerCtx(): CPointer<JSContext> {
        compilerCtx?.let { return it }
        val rt = JS_NewRuntime() ?: error("JS_NewRuntime() returned null")
        val ctx = JS_NewContext(rt) ?: error("JS_NewContext() returned null")
        compilerCtx = ctx
        return ctx
    }

    /**
     * 编译 JS 为 bytecode (供 compile / compile(script, scope) / compileForSubScope 复用)。
     *
     * JS_Eval(COMPILE_ONLY) → JS_WriteObject 序列化 → ByteArray; 序列化缓冲区 js_free 释放。
     * 先 JS_UpdateStackTop: compilerCtx 会被多线程复用, 栈检查需基于当前线程栈 (对齐 JVM
     * nativeCompile 的同样处理, 否则基于错误 stack_top 误报 "Maximum call stack size exceeded")。
     */
    private fun compileToBytecode(
        script: String,
        ctx: CPointer<JSContext>,
        filename: String
    ): NativeJsCompiledScript {
        val bytecode = memScoped {
            // 跨线程使用共享 compilerCtx 时, 栈检查需基于当前线程栈指针
            JS_UpdateStackTop(JS_GetRuntime(ctx))
            val funVal = qjsEvalUtf8(
                ctx, script, filename,
                qjs_EvalTypeGlobal() or qjs_EvalFlagCompileOnly()
            )
            try {
                if (qjs_IsException(funVal) != 0) {
                    // 编译错误 (语法错误等): 取异常信息抛 NativeScriptException (对齐 JVM compile)
                    val exc = JS_GetException(ctx)
                    try {
                        throw NativeScriptException(exceptionToString(ctx, exc))
                    } finally {
                        JS_FreeValue(ctx, exc)
                    }
                }
                val pLen = alloc<ULongVar>()
                val bufPtr = qjs_WriteBytecode(ctx, funVal, pLen.ptr)
                // 序列化失败 (非 JS 错误): 对齐 JVM "Compile failed"
                if (bufPtr == 0UL) throw NativeScriptException("Compile failed")
                try {
                    val len = pLen.value.toInt()
                    val buf = bufPtr.toLong().toCPointer<ByteVar>()
                    if (buf == null || len <= 0) throw NativeScriptException("Compile failed")
                    buf.readBytes(len)
                } finally {
                    qjs_FreeBytecodeBuf(ctx, bufPtr)
                }
            } finally {
                JS_FreeValue(ctx, funVal)
            }
        }
        return NativeJsCompiledScript(script, bytecode)
    }

    // ============ quickjs 独有 API 的统一抽象 ============

    /**
     * 把 JS 包成可子 scope 执行的形式。
     *
     * 复用 quickjs 的三层包装: `(function(){with(__currentBindings()){return eval(<源码>);}})()`
     * - with(__currentBindings()): bindings 走 [evalInSubScope] 压栈成栈顶对象, user JS 里
     *   `java`/`cache`/`source` 走 with 命中; 空栈时穿透到 globalThis。
     * - IIFE 隔离 let/const/var, 不污染 topScope。
     * - eval + return: 返回末尾表达式值 (对齐 rhino script.exec)。完成值是 eval/script 目标
     *   特有语义, 函数调用只认显式 return, 故源码无法逐字嵌入函数体 (对齐 JVM 实证结论)。
     * - 已知限制: user JS 顶层 return 落在 eval 里, 报 "Illegal return statement"。
     */
    override fun wrapJsForEval(jsStr: String): String {
        val jsLiteral = escapeJsString(jsStr)
        return "(function(){with(__currentBindings()){return eval($jsLiteral);}})()"
    }

    /** [wrapJsForEval] 包装后编译为 bytecode, 供 [evalInSubScope] 执行 (对齐 JVM compileForSubScope)。 */
    override fun compileForSubScope(jsStr: String): JsCompiledScript = compile(wrapJsForEval(jsStr))

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
        val nativeCompiled = compiled as? NativeJsCompiledScript
            ?: error("NativeJsEngine.evalInSubScope requires NativeJsCompiledScript, got ${compiled::class}")
        // 同步 dangerousApi (native 上语义为 no-op, 保留字段对齐)
        nativeScope.dangerousApi = bindings.dangerousApi
        val prevScope = threadLocalScope.value
        threadLocalScope.value = nativeScope
        nativeScope.coroutineContext = coroutineContext
        nativeScope.recursiveCount++
        try {
            nativeScope.checkRecursive()
            // 快照 handle 数: eval 后释放本次注入新增的 handle (对齐 JVM releaseNewHandles)
            val handleSnapshot = nativeScope.handles.size
            // 构造 kvs (须在压栈后: toJsValue 的 JsExtensionsCommon/BookLike 分支依赖 threadLocalScope)
            val kvs = buildBindingKvsValues(nativeScope.ctx!!, bindings)
            try {
                // 进入子 scope: JS_Call 值参数压栈 bindings (免字符串 eval)
                enterBindingsWithValues(nativeScope.ctx!!, kvs)
                return try {
                    // bytecode 优先, 读回失败 fallback 源码 (evalCompiledInContext 内部处理)
                    evalCompiledInContext(nativeCompiled, nativeScope)
                } finally {
                    // 退出子 scope: 弹栈 bindings (即使中途异常也要弹栈)
                    evalInternal(nativeScope.ctx!!, "__exitBindings()", "<exitBindings>", checkException = false)
                    // 释放本次注入新增的 handle (防共享 topScope 长期膨胀)
                    releaseNewHandles(nativeScope, handleSnapshot)
                }
            } finally {
                // 释放 kvs 转换的 JSValue 引用 (JS_Call 不转移 argv 所有权, JS 侧已持有引用)
                for ((_, v) in kvs) JS_FreeValue(nativeScope.ctx!!, v)
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
     * 执行 bytecode: JS_ReadObject 反序列化 → JS_EvalFunction 执行 (跳过重新 parse+compile)。
     *
     * 读回失败 (bytecode 损坏/版本不兼容) 抛 [NativeScriptException] (对齐 JVM evalBytecode);
     * 带源码的执行路径 ([evalInSubScope] / [NativeJsCompiledScript.eval]) 会 fallback 源码 eval,
     * 不会让整条书源挂掉。
     */
    override fun evalBytecode(
        bytecode: ByteArray?,
        scope: JsScope,
        coroutineContext: CoroutineContext?
    ): Any? {
        if (bytecode == null) return null
        val nativeScope = scope as? NativeJsScope
            ?: error("NativeJsEngine.evalBytecode requires NativeJsScope, got ${scope::class}")
        return withEvalContext(nativeScope, coroutineContext) {
            try {
                evalBytecodeInternal(nativeScope.ctx!!, bytecode)
            } catch (e: NativeBytecodeReadException) {
                throw NativeScriptException(e.message ?: "Eval bytecode failed")
            }
        }
    }

    /**
     * 注入 bindings 变量到 scope 的 globalThis, 返回注入成功的键列表 (供 cleanupBindings 用)。
     *
     * native cinterop 实现 (iOS/鸿蒙一致): 调 JS_GetGlobalObject 取 globalThis,
     * 然后 JS_SetPropertyStr 注入每个 key。
     * - 基本类型 (String/Number/Boolean/null) 用 qjs_NewXxx 创建 JSValue 后注入;
     * - Map<String,Any?> / List<Any?> 递归转换为 JS_NewObject / JS_NewArray 后注入;
     * - 其他复杂 Kotlin 对象 (Book/BookChapter 等) 用 handle 表 + 属性 getter 工厂桥接
     *   (NativeJsPropertyBridge); 真·未知类型才跳过注入;
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

    /** 当前线程的 JS scope (可空), 供桥接层 (NativeJsPropertyBridge) 登记嵌套 handle。 */
    internal fun currentScope(): NativeJsScope? = threadLocalScope.value

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

    /**
     * suspend 版本, 取当前协程上下文传给 scope (对齐 JVM runScriptWithContext(block)):
     * kotlin.coroutines.coroutineContext 在 suspend 函数内可取当前协程上下文;
     * minusKey(ContinuationInterceptor) 不把调度器存进 scope (与 JVM 端实现一致)。
     */
    override suspend fun <T> runScriptWithContext(block: () -> T): T {
        val scope = threadLocalScope.value
        val prevCtx = scope?.coroutineContext
        scope?.coroutineContext = coroutineContext.minusKey(ContinuationInterceptor)
        return try {
            block()
        } finally {
            scope?.coroutineContext = prevCtx
        }
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
            val result = qjsEvalUtf8(ctx, js, filename, qjs_EvalTypeGlobal())
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
     * 在指定 scope 上执行 compiled 脚本 (bytecode 优先, 读回失败 fallback 源码 eval)。
     *
     * 带完整 eval 上下文 (withEvalContext), 供 [NativeJsCompiledScript.eval] 使用;
     * [evalInSubScope] 已自行建立上下文, 直接调 [evalCompiledInContext]。
     */
    internal fun evalCompiled(
        compiled: NativeJsCompiledScript,
        scope: NativeJsScope,
        coroutineContext: CoroutineContext?
    ): Any? = withEvalContext(scope, coroutineContext) {
        evalCompiledInContext(compiled, scope)
    }

    /**
     * 执行 compiled 脚本 (bytecode 优先, 读回失败 fallback 源码 eval)。
     * 调用方需已建立 eval 上下文 (withEvalContext 或 evalInSubScope 手动栈)。
     * fallback 只兜 bytecode 反序列化失败 (quickjs 版本不兼容/损坏), 不吞运行期 JS 异常。
     */
    private fun evalCompiledInContext(compiled: NativeJsCompiledScript, scope: NativeJsScope): Any? {
        val ctx = scope.ctx!!
        val bytecode = compiled.bytecode
        if (bytecode == null) {
            return evalInternal(ctx, compiled.source, "<subScope>", checkException = true)
        }
        return try {
            evalBytecodeInternal(ctx, bytecode)
        } catch (e: NativeBytecodeReadException) {
            // bytecode 读回失败: fallback 源码 eval, 不让整条书源挂掉 (执行语义不变)
            evalInternal(ctx, compiled.source, "<subScope>", checkException = true)
        }
    }

    /**
     * 在指定 ctx 上执行 bytecode (JS_ReadObject → JS_EvalFunction), 返回 Kotlin 值。
     *
     * - 反序列化失败: 抛 [NativeBytecodeReadException] (由调用方决定 fallback 或转异常);
     * - 运行期 JS 异常: 抛 [NativeScriptException] 带 JS stack (与 [evalInternal] 同型);
     * - 注意: JS_EvalFunction 消费 fun_obj 引用 (quickjs.c 内部各路径 FreeValue),
     *   成功路径不再 JS_FreeValue(funVal), 与 JVM nativeEvalBytecode 一致。
     */
    private fun evalBytecodeInternal(ctx: CPointer<JSContext>, bytecode: ByteArray): Any? {
        memScoped {
            val funVal = qjs_ReadBytecode(ctx, bytecode.refTo(0), bytecode.size.toULong())
            if (qjs_IsException(funVal) != 0) {
                val exc = JS_GetException(ctx)
                val msg = try {
                    exceptionToString(ctx, exc)
                } finally {
                    JS_FreeValue(ctx, exc)
                }
                JS_FreeValue(ctx, funVal)
                throw NativeBytecodeReadException(msg)
            }
            val result = JS_EvalFunction(ctx, funVal)  // 消费 funVal 引用
            try {
                if (qjs_IsException(result) != 0) {
                    val exc = JS_GetException(ctx)
                    try {
                        throw NativeScriptException(exceptionToString(ctx, exc))
                    } finally {
                        JS_FreeValue(ctx, exc)
                    }
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
        val cstr = qjs_ToCString(ctx, v)
        if (cstr != null) {
            return try {
                cstr.toKString()
            } finally {
                qjs_FreeCString(ctx, cstr)
            }
        }
        // toString 失败 (抛出的值不可字符串化, 如无 toString/valueOf 的对象):
        // 用纯 tag 判定给出类型描述 (对齐 Android 端 buildExceptionMessage),
        // 避免只报裸 "JS exception" 让用户无法判断书源抛的是什么。
        return describeThrownValue(ctx, v)
    }

    /**
     * 对 toString 失败的值做纯 tag 判定生成类型描述。
     *
     * 约束: 此时 ctx 的 current_exception 已被 toString 抛出的新异常占用,
     * 只使用 qjs_IsXxx 等不执行 JS 的判定, 不覆盖该异常。
     * (基本类型的 toString 不会失败, 实际只会落到对象分支。)
     */
    private fun describeThrownValue(ctx: CPointer<JSContext>, v: CValue<JSValue>): String {
        val kind = when {
            qjs_IsNull(v) != 0 -> "null"
            qjs_IsUndefined(v) != 0 -> "undefined"
            qjs_IsBool(v) != 0 -> if (qjs_ValueGetBool(v) != 0) "true" else "false"
            qjs_IsNumber(v) != 0 -> "a number"
            qjs_IsString(v) != 0 -> "a string"
            qjs_IsObject(v) != 0 -> "an object"
            else -> "an unknown value"
        }
        return "JS exception (thrown value: $kind)"
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
            // Uint8Array/TypedArray (解密脚本返回值): 直接拷回 ByteArray,
            // ImageUtils.decode 的 is ByteArray 分支直接消费, 无需 List 中间态
            tryGetUint8ArrayBytes(ctx, v)?.let { return it }
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
     * - ByteArray → Uint8Array (1 次 memcpy + JS_NewUint8Array, 见 [byteArrayToJsUint8Array];
     *   解密脚本需要 result.length/索引读写, TypedArray 语义与原版 quickjs JavaObjectBridge 的
     *   byte[] 一致; 脚本写回后整体返回, 由 [tryGetUint8ArrayBytes] 拷回 ByteArray)
     * - 其他对象 → null (P0 stub, 复杂对象不桥接, 调用方跳过此 key)
     *
     * 注: BookLike/BookChapterLike 等复杂对象 (book/chapter binding) 由
     * [NativeJsPropertyBridge] 属性 getter 工厂桥接 (handle 表 + propertyId 静态分派);
     * SourceNetworkProvider/SourceCacheProvider (cookie/cache binding) 经
     * [NativeJsExtensionsBridge.createJsObject] 工厂映射表桥接 (__createCookieObj/__createCacheObj)。
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
            is ByteArray -> byteArrayToJsUint8Array(ctx, converted)
            is JsExtensionsCommon -> {
                // JsExtensions 桥接: 通过 handle 表 + JS 工厂函数桥接为 JS 对象 (NativeJsExtensionsBridge)
                // 需要当前 scope (threadLocalScope) 记录 handle, close 时清理
                val currentScope = threadLocalScope.value
                    ?: return null  // 无 scope 上下文, 跳过 (非 injectBindings 调用路径)
                NativeJsExtensionsBridge.createJsObject(ctx, converted, currentScope)
            }
            is BookLike, is BookChapterLike -> {
                // 复杂对象 (book/chapter) 桥接: 复用 handle 表 + 属性 getter 工厂
                // (NativeJsPropertyBridge, propertyId 1700+); 放在 JsExtensionsCommon 之后保持原通路顺序
                val currentScope = threadLocalScope.value
                    ?: return null  // 无 scope 上下文, 跳过 (非 injectBindings 调用路径)
                NativeJsExtensionsBridge.createJsObject(ctx, converted, currentScope)
            }
            is SourceNetworkProvider, is SourceCacheProvider -> {
                // cookie/cache binding: 复用 createJsObject 工厂映射表 (__createCookieObj/__createCacheObj),
                // 不重复写工厂判断; 与上方复杂对象分支同型 (handle 表 + scope 记录)
                val currentScope = threadLocalScope.value
                    ?: return null  // 无 scope 上下文, 跳过 (非 injectBindings 调用路径)
                NativeJsExtensionsBridge.createJsObject(ctx, converted, currentScope)
            }
            is Node -> {
                // ksoup 节点 (src binding 逐项循环场景): 同 book/chapter 的 handle 表 + 属性工厂
                val currentScope = threadLocalScope.value
                    ?: return null  // 无 scope 上下文, 跳过 (非 injectBindings 调用路径)
                NativeJsExtensionsBridge.createJsObject(ctx, converted, currentScope)
            }
            else -> null  // 真·未知类型跳过注入 (调用方 continue), 不注册 handle 不泄漏
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

    /**
     * ByteArray → Uint8Array (1 次 memcpy, 零逐字节装箱):
     *
     * 走 quickjs.def 的 qjs_NewUint8ArrayCopy (JS_NewUint8ArrayCopy 语义: 内部 malloc +
     * memcpy + js_array_buffer_free 自管内存), Kotlin 侧无分配/持有/释放, 无生命周期风险;
     * 指针经 uintptr_t 整数传参 (各 target cinterop 指针映射不一致, 见 quickjs.def 注释)。
     * JS 侧得到 Uint8Array: `length` 是 number、索引读写直接改内存 (TypedArray),
     * 与原版 quickjs JavaObjectBridge 的 byte[] 语义一致 (length/索引可用),
     * 脚本 `result.length==undefined` 判断走主分支, 不会进 Packages 分支。
     */
    private fun byteArrayToJsUint8Array(
        ctx: CPointer<JSContext>,
        bytes: ByteArray
    ): CValue<JSValue> {
        val size = bytes.size
        // 数组参数形式 (qjs_NewUint8ArrayCopy 的 src[]): cinterop 生成 CValuesRef<ByteVar>,
        // 经 refTo(0) 传 CPointer (ByteArray 不能直接传 CValuesRef); 空数组 refTo(0) 返回 null
        // (copy 版 len=0 时内部 js_mallocz(1) 安全处理)
        val arr = qjs_NewUint8ArrayCopy(ctx, bytes.refTo(0), size.toULong())
        return if (qjs_IsException(arr) != 0) jsNullValue() else arr
    }

    /**
     * 判定 JS 值是否为 TypedArray (Uint8Array 等) 并拷回 ByteArray:
     *
     * 读 `buffer` 属性 → JS_IsArrayBuffer → JS_GetArrayBuffer 拿数据指针+总长,
     * 再按 `byteOffset`/`byteLength` 偏移拷回 (脚本 slice/subarray 返回的视图也支持)。
     * 非 TypedArray 返回 null (交由后续分支处理)。返回的 ByteArray 由调用方持有。
     */
    private fun tryGetUint8ArrayBytes(ctx: CPointer<JSContext>, v: CValue<JSValue>): ByteArray? {
        val buf = JS_GetPropertyStr(ctx, v, "buffer")
        if (qjs_IsException(buf) != 0) {
            JS_FreeValue(ctx, buf)
            return null
        }
        try {
            // JS_IsArrayBuffer 返回 C bool (cinterop 映射 Boolean), 与 JS_IsArray 同用法
            if (!JS_IsArrayBuffer(buf)) return null
            return memScoped {
                // 数据长度整数返回 + C 侧拷贝到 ByteArray (数组参数), 零指针转换
                val totalSize = qjs_ArrayBufferSize(ctx, buf).toInt()
                val byteOffset = jsPropNumber(ctx, v, "byteOffset")?.toInt() ?: 0
                val byteLength = jsPropNumber(ctx, v, "byteLength")?.toInt()
                    ?: (totalSize - byteOffset).coerceAtLeast(0)
                if (byteOffset < 0 || byteLength <= 0) return@memScoped null
                val len = minOf(byteLength, (totalSize - byteOffset).coerceAtLeast(0))
                val out = ByteArray(len)
                if (len > 0) {
                    // C 侧按 offset 拷贝 (JS_GetArrayBuffer 数据指针 + 偏移, 不暴露指针给 Kotlin)
                    val n = qjs_ArrayBufferRead(
                        ctx, buf, byteOffset.toULong(), out.refTo(0), len.toULong()
                    )
                    if (n.toInt() != len) return@memScoped null
                }
                out
            }
        } finally {
            JS_FreeValue(ctx, buf)
        }
    }

    /** 读 JS 对象的数字属性 (byteOffset/byteLength 等), 非数字返回 null。 */
    private fun jsPropNumber(ctx: CPointer<JSContext>, obj: CValue<JSValue>, key: String): Number? {
        val v = JS_GetPropertyStr(ctx, obj, key)
        if (qjs_IsException(v) != 0) {
            JS_FreeValue(ctx, v)
            return null
        }
        try {
            if (qjs_IsNumber(v) == 0) return null
            val tag = qjs_ValueGetTag(v)
            return if (tag == JS_TAG_INT) qjs_ValueGetInt(v) else qjs_ValueGetFloat64(v)
        } finally {
            JS_FreeValue(ctx, v)
        }
    }

    /** 构造 JS null JSValue (cValue 构造, tag=JS_TAG_NULL, u 默认 0)。 */
    private fun jsNullValue(): CValue<JSValue> = cValue {
        tag = JS_TAG_NULL.toLong()
    }

    /** 构造 JS undefined JSValue (与 [NativeJsExtensionsBridge.jsUndefined] 同实现)。 */
    private fun jsUndefined(): CValue<JSValue> = cValue {
        tag = JS_TAG_UNDEFINED.toLong()
    }

    // ============ private helper: bindings kvs 值构造 ============

    /**
     * 构造 __enterBindings(k1,v1,k2,v2,...) 调用的值参数列表 (对齐 JVM buildBindingKvs)。
     *
     * 逐 key 用 [toJsValue] 转换 (Map/List/复杂对象经 handle 桥接, 非法变量名跳过);
     * 返回的 JSValue 归调用方所有 (JS_Call 后须 JS_FreeValue)。
     */
    private fun buildBindingKvsValues(
        ctx: CPointer<JSContext>,
        bindings: JsBindings
    ): List<Pair<String, CValue<JSValue>>> {
        val kvs = ArrayList<Pair<String, CValue<JSValue>>>(bindings.size)
        for ((key, value) in bindings) {
            if (!isValidVarName(key)) continue
            val jsValue = toJsValue(ctx, value) ?: continue
            kvs.add(key to jsValue)
        }
        return kvs
    }

    /**
     * 以值参数调 __enterBindings(k1,v1,...): 取 globalThis 上函数后 JS_Call (免字符串 eval)。
     *
     * JS_Call 不转移 argv 所有权 (argv 是内存拷贝): key/result 在此配对释放,
     * value 的 JSValue 由调用方释放; __enterBindings 内部赋值已持有 JS 侧引用。
     */
    private fun enterBindingsWithValues(
        ctx: CPointer<JSContext>,
        kvs: List<Pair<String, CValue<JSValue>>>
    ) {
        memScoped {
            val global = JS_GetGlobalObject(ctx)
            try {
                val func = JS_GetPropertyStr(ctx, global, "__enterBindings")
                try {
                    if (qjs_IsException(func) != 0) {
                        throw NativeScriptException("Failed to get __enterBindings")
                    }
                    val keyValues = ArrayList<CValue<JSValue>>(kvs.size)
                    try {
                        val argc = kvs.size * 2
                        val result = if (argc == 0) {
                            JS_Call(ctx, func, global, 0, null)
                        } else {
                            // argv 为 key/value 的 JSValue 内存拷贝 (引用计数不转移)
                            val argv = allocArray<JSValue>(argc)
                            var i = 0
                            for ((key, value) in kvs) {
                                val keyVal = qjs_NewString(ctx, key)
                                keyVal.useContents {
                                    memcpy(
                                        interpretCPointer<ByteVar>(argv[i.toLong()].rawPtr),
                                        interpretCPointer<ByteVar>(this.rawPtr),
                                        sizeOf<JSValue>().toULong()
                                    )
                                }
                                value.useContents {
                                    memcpy(
                                        interpretCPointer<ByteVar>(argv[i.toLong() + 1].rawPtr),
                                        interpretCPointer<ByteVar>(this.rawPtr),
                                        sizeOf<JSValue>().toULong()
                                    )
                                }
                                keyValues.add(keyVal)
                                i += 2
                            }
                            JS_Call(ctx, func, global, argc, argv)
                        }
                        try {
                            if (qjs_IsException(result) != 0) {
                                val exc = JS_GetException(ctx)
                                try {
                                    throw NativeScriptException(exceptionToString(ctx, exc))
                                } finally {
                                    JS_FreeValue(ctx, exc)
                                }
                            }
                        } finally {
                            JS_FreeValue(ctx, result)
                        }
                    } finally {
                        for (kv in keyValues) JS_FreeValue(ctx, kv)
                    }
                } finally {
                    JS_FreeValue(ctx, func)
                }
            } finally {
                JS_FreeValue(ctx, global)
            }
        }
    }

    /**
     * 释放 scope 中快照后新增的 handle (本次注入注册的), 防共享 topScope 长期膨胀
     * (对齐 JVM releaseNewHandles; 属性 getter 的嵌套 handle 不在 scope.handles, 不受影响)。
     */
    private fun releaseNewHandles(scope: NativeJsScope, snapshotSize: Int) {
        val handles = scope.handles
        while (handles.size > snapshotSize) {
            NativeJsExtensionsBridge.unregisterObject(handles.removeAt(handles.size - 1))
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
     * LiveConnect 通路恒失败 (桩函数返回 0/false/null 或抛异常), 书源需改用 java.* JsExtensions 绑定:
     * - `__loadJavaClass` → 0 (类加载恒失败)
     * - `__classExists` → false
     * - `__isInterface` → false
     * - `__callStaticMethod` → null
     * - `__newJavaInstance` / `__getStaticField` / `__setStaticField` → null/false (无对象实例化/字段反射)
     * - `__newJavaAdapter` → 抛异常 (native 无 Java 反射)
     * - `__getDangerousApi` → true (native 无安全模型, 无 Java 反射可旁路)
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

// ============ Java 类兼容面 (native 无 Java 反射, LiveConnect 通路恒失败) ============
// 与 quickjs 端一致: Packages/importClass/JavaAdapter 等 rhino LiveConnect 兼容 API 通过
// __loadJavaClass/__callStaticMethod 等桩函数回 Kotlin 分派。native 无 Java 反射, 桩函数
// 恒定返回 0/false/null, 书源 JS 明确失败 (规则层 runCatching 后呈现为规则错误);
// 跨端书源请改用 java.* JsExtensions 绑定 (native 全量支持)。

function __getDangerousApi() {
    // native 无 Java 反射亦无安全模型: 不存在可旁路的任意类加载, dangerousApi 语义
    // 天然全放行 (放行也不会新增能力, 仅让书源走 java.* 路径)
    return true;
}

function __loadJavaClass(fullName, dangerousApi) {
    // native 无 Java 反射: 类加载恒失败, 返回 0 (类句柄, 表示加载失败)
    return 0;
}

function __classExists(fullName, dangerousApi) {
    return false;
}

function __isInterface(classHandle, dangerousApi) {
    return false;
}

function __newJavaInstance(classHandle, args, dangerousApi) {
    // native 无对象实例化 (无反射), 返回 null 让书源明确失败
    return null;
}

function __callStaticMethod(classHandle, methodName, args, dangerousApi) {
    return null;
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
 * native 端 (iOS/鸿蒙) "编译"脚本包装 — 持 bytecode + 源码 (bytecode 读回失败时 fallback 源码 eval)。
 *
 * 对应 quickjs 的 QuickJsJsCompiledScript (持 CompiledScript(bytecode))。
 * bytecode 由 compile/compileForSubScope 生成 (JS_WriteObject 序列化), 执行时
 * JS_ReadObject + JS_EvalFunction 跳过重新 parse (见 [NativeJsEngine.evalCompiledInContext])。
 * bytecode 自带 atom 表可跨 ctx/runtime 复用, AnalyzeRuleCore 的 scriptCache (LRU 16)
 * 命中后直接走 bytecode, 不再每次重新编译。
 */
class NativeJsCompiledScript(val source: String, bytecode: ByteArray) : JsCompiledScript {

    /** 防御性拷贝, 避免外部经 [JsCompiledScript.bytecode] 篡改 (对齐 JVM CompiledScript)。 */
    override val bytecode: ByteArray? = bytecode.copyOf()

    /** 在指定 scope 上执行 (bytecode 优先, 读回失败 fallback 源码 eval)。 */
    override fun eval(scope: JsScope, coroutineContext: CoroutineContext?): Any? {
        val nativeScope = scope as? NativeJsScope
            ?: error("NativeJsCompiledScript requires NativeJsScope, got ${scope::class}")
        return NativeJsEngine.evalCompiled(this, nativeScope, coroutineContext)
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

/**
 * bytecode 反序列化失败标记 (internal, 由 [NativeJsEngine.evalBytecodeInternal] 抛出)。
 *
 * 与运行期 JS 异常 ([NativeScriptException]) 区分: 带源码的执行路径据此 fallback 源码 eval,
 * 独立 evalBytecode 路径转 [NativeScriptException] (对齐 JVM "Eval bytecode failed" 语义)。
 */
private class NativeBytecodeReadException(message: String) : Exception(message)

