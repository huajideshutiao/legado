package io.legado.app.model.script

import kotlin.coroutines.CoroutineContext

/**
 * JS 引擎类型开关。
 *
 * 由 [io.legado.app.help.config.AppConfig.jsEngine] 控制，
 * "quickjs" → [QUICKJS]（默认），"rhino" → [RHINO]。
 */
enum class JsEngineType { RHINO, QUICKJS }

/**
 * app 侧统一的 JS 变量绑定容器。
 *
 * 不继承任何引擎类型（rhino 的 ScriptBindings 继承 NativeObject、quickjs 的继承 LinkedHashMap），
 * 纯 Map 实现。各引擎实现负责在 [JsEngine.getRuntimeScope] / [JsEngine.eval] 入口
 * 把 entries 转换为对应引擎的 ScriptBindings。
 *
 * [dangerousApi] 控制是否旁路引擎安全名单（由 BaseSource.enableDangerousApi 控制）。
 */
class JsBindings : MutableMap<String, Any?> by LinkedHashMap() {
    var dangerousApi: Boolean = false
}

/**
 * 构建 [JsBindings] 的便利函数。
 *
 * 业务层典型用法:
 * ```
 * val bindings = buildScriptBindings { bindings ->
 *     bindings["java"] = this
 *     bindings["result"] = result
 *     bindings.dangerousApi = source?.enableDangerousApi == true
 * }
 * ```
 *
 * 对应 quickjs 的 `com.script.quickjs.buildScriptBindings` 和 rhino 的
 * `com.script.buildScriptBindings`，业务代码只需改 import 包名。
 */
inline fun buildScriptBindings(block: (JsBindings) -> Unit): JsBindings {
    val bindings = JsBindings()
    block(bindings)
    return bindings
}

/**
 * JS 执行作用域抽象。
 *
 * - rhino 实现 [RhinoJsScope]: 持 `org.mozilla.javascript.Scriptable` (sharedScope, 已 sealObject)
 *   + 通过 `Context.getCurrentContext() as? RhinoContext` 动态访问协程上下文
 * - quickjs 实现 [QuickJsJsScope]: 持 `com.script.quickjs.QuickJsContext` (线程独占, 持 native 指针)
 *
 * 业务层 (AnalyzeRule/BaseSource/AnalyzeUrl) 通过本接口持有 scope,
 * 不再直接依赖 QuickJsContext 或 Scriptable。
 */
interface JsScope : AutoCloseable {

    /** 协程上下文，用于 ensureActive 取消传递。null 表示无协程关联。 */
    var coroutineContext: CoroutineContext?

    /** 标记下一次 eval 是否允许执行（对应 rhino allowScriptRun / quickjs allowScriptRun）。 */
    var allowScriptRun: Boolean

    /** 递归深度计数（防止 JS 递归栈溢出）。 */
    var recursiveCount: Int

    /** 检查协程是否已取消，在 binding handler 中由业务层调用。 */
    fun ensureActive()

    /** 递归深度检查。 */
    fun checkRecursive()
}

/**
 * 编译后的 JS 脚本抽象。
 *
 * - rhino 实现: 持 `com.script.CompiledScript` (RhinoCompiledScript)，[bytecode] 为 null
 * - quickjs 实现: 持 `com.script.quickjs.CompiledScript`，[bytecode] 非 null (QuickJS bytecode)
 *
 * [bytecode] 仅 quickjs 支持（SharedJsScope 的 bytecodeCache 用）；rhino 走 [eval] 路径。
 */
interface JsCompiledScript {
    /** quickjs 的 bytecode，rhino 下为 null。 */
    val bytecode: ByteArray?

    /** 在指定 scope 上执行，返回 JS 求值结果。 */
    fun eval(scope: JsScope, coroutineContext: CoroutineContext?): Any?
}

/**
 * JS Object 在 Kotlin 侧的统一视图（替代 NativeObject）。
 *
 * - rhino: `org.mozilla.javascript.NativeObject`（不直接实现 java.util.Map，由 RhinoJsObject 适配）
 * - quickjs: `com.script.quickjs.NativeObject`（继承 LinkedHashMap，直接 by delegate）
 *
 * 业务层用 [JsEngines.asJsObject] 工厂获取本接口实例，`jsObj[rule]` 走 Map 索引。
 */
interface JsObject : MutableMap<String, Any?>

/**
 * JS function 在 Kotlin 侧的统一视图（替代 JsFunction / org.mozilla...Function）。
 */
interface JsFn {
    val dangerousApi: Boolean
    fun call(vararg args: Any?): Any?
}

/**
 * JS 引擎抽象。每个方法对应业务层当前调用的 QuickJsEngine API。
 *
 * 实现类:
 * - [io.legado.app.model.script.rhino.RhinoJsEngine]: 委托 `com.script.rhino.RhinoScriptEngine`，
 *   用 rhino 语义实现 quickjs 独有 API（wrapJsForEval 直接返回源码、evalInSubScope 走 prototype 继承等）
 * - [io.legado.app.model.script.quickjs.QuickJsJsEngine]: 委托 `com.script.quickjs.QuickJsEngine`，直接转发
 */
interface JsEngine {

    /** 引擎类型，用于 JsEngines 分派 / SharedJsScope 缓存选择。 */
    val type: JsEngineType

    // ============ 一次性 eval ============

    /** 一次性 eval，内部创建 scope 执行后立即释放。对应 QuickJsEngine.eval(js, bindingsConfig)。 */
    fun eval(js: String, bindingsConfig: JsBindings.() -> Unit = {}): Any?

    /** 一次性 eval，传入预构建 bindings。对应 QuickJsEngine.eval(js, bindings)。 */
    fun eval(js: String, bindings: JsBindings): Any?

    // ============ scope 创建/复用 ============

    /** 创建运行时 scope，注入 bindings 变量。对应 QuickJsEngine.getRuntimeScope。 */
    fun getRuntimeScope(bindings: JsBindings): JsScope

    /** 创建独立 scope（JsActivity 用），不与 SharedJsScope 共享。对应 createQuickJsForActivity。 */
    fun createStandaloneScope(): JsScope

    // ============ 编译 ============

    /** 编译 JS。对应 QuickJsEngine.compile(script)。 */
    fun compile(script: String): JsCompiledScript

    /** 在指定 scope 上编译（复用 scope，符号解析一致）。对应 QuickJsEngine.compile(script, scope)。 */
    fun compile(script: String, scope: JsScope): JsCompiledScript

    // ============ quickjs 独有 API 的统一抽象 ============

    /**
     * 把 JS 包成可子 scope 执行的形式。
     * - quickjs: `(function(){return eval(<源码>);})()`
     * - rhino: 直接返回源码（rhino 原生支持顶层 return + 子 scope 词法隔离）
     */
    fun wrapJsForEval(jsStr: String): String

    /** compile(wrapJsForEval(jsStr))。 */
    fun compileForSubScope(jsStr: String): JsCompiledScript

    /**
     * 在共享 topScope 上执行包装后的 compiled，bindings 注入子 scope。
     * - quickjs: pushBindingSnapshot → injectBindings → evalBytecode → popBindingSnapshot
     * - rhino: `bindings.prototype = sharedScope` 后 `compiled.eval(scope, bindings, ctx)`
     *   （rhino 子 scope 出栈自动清理，无需 cleanup）
     */
    fun evalInSubScope(
        compiled: JsCompiledScript,
        scope: JsScope,
        bindings: JsBindings,
        coroutineContext: CoroutineContext?
    ): Any?

    /** 在指定 scope 上执行 JS（已 wrap 或未 wrap 由调用方决定）。对应 QuickJsEngine.eval(js, scope, ctx)。 */
    fun eval(js: String, scope: JsScope, coroutineContext: CoroutineContext?): Any?

    /**
     * 在指定 scope 上执行 JS 并注入 bindings（一次性，不做子 scope 隔离清理）。
     * 对应 QuickJsEngine.eval(js, scope, bindings, coroutineContext)。
     */
    fun eval(
        js: String,
        scope: JsScope,
        bindings: JsBindings,
        coroutineContext: CoroutineContext?
    ): Any?

    /** 执行 bytecode（quickjs 独有，rhino 走 JsCompiledScript.eval，bytecode 参数忽略）。 */
    fun evalBytecode(
        bytecode: ByteArray?,
        scope: JsScope,
        coroutineContext: CoroutineContext?
    ): Any?

    /**
     * 注入 bindings 到 scope，返回注入的键列表（供 cleanupBindings 用）。
     * - quickjs: 实际注入 globalThis + 返回 keys
     * - rhino: 子 scope prototype 继承自动管理，此处空实现返回空 list
     */
    fun injectBindings(scope: JsScope, bindings: JsBindings): List<String>

    /**
     * 清理 injectBindings 注入的变量。
     * - quickjs: 按 keys delete / 恢复 bootstrap 初值
     * - rhino: 空实现（子 scope 关闭自动清理）
     */
    fun cleanupBindings(scope: JsScope, keys: List<String>)

    // ============ JsFunction 创建 ============

    /**
     * 把 JS 函数表达式包装为 [JsFn]，供 JsActivity 跨 Activity 调用。
     * 对应 quickjs 的 `JsFunction(ctx, expr)`。
     */
    fun wrapJsFn(scope: JsScope, functionExpr: String, dangerousApi: Boolean = false): JsFn
}

/**
 * app 侧统一 ScriptException 类型别名。
 *
 * rhino `com.script.ScriptException` 与 quickjs `com.script.quickjs.ScriptException`
 * 都继承 `java.lang.Exception`，此处用 Exception 兜底，业务代码 `is ScriptException`
 * 仍能匹配两个引擎抛出的异常。
 *
 * 注意：语义变宽（捕获所有 Exception），但 HttpReadAloudService/CheckSourceService
 * 等使用处的 when 分支有其他兜底（NoStackTraceException 等），业务上可接受。
 */
typealias ScriptException = Exception
