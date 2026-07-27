package io.legado.app.model.script

import kotlin.concurrent.Volatile
import kotlinx.atomicfu.locks.SynchronizedObject
import kotlinx.atomicfu.locks.synchronized
import kotlin.coroutines.CoroutineContext

/**
 * JS 引擎实现提供者。引擎绑定（quickjs/rhino 适配层）留 app，
 * 宿主启动早期经 [JsEngines.registerProvider] 注册一次（任何 JS eval 之前）。
 */
fun interface JsEngineProvider {
    fun create(type: JsEngineType): JsEngine
}

/**
 * JS 引擎分派单例。
 *
 * 按 [type] 返回对应引擎实现并缓存实例。
 * 切换开关后下次访问 [get] 自动取新引擎（缓存失效重建）。
 *
 * 业务层（AnalyzeRule/BaseSource/AnalyzeUrl/SharedJsScope/JsActivity）通过 [get] 获取当前引擎，
 * 不再直接依赖 `QuickJsEngine` 或 `RhinoScriptEngine` 静态方法。
 *
 * 具体引擎实现经 [registerProvider] 注入（安卓在 App.onCreate 注册 quickjs/rhino 适配），
 * 抽象面不见 `com.script.quickjs.*` / `org.mozilla.*` 任何具体类。
 *
 * 双判谓词（[isJsObject]/[asJsObject]/[isJsException]）处理同名不同包类型：
 * rhino `org.mozilla.javascript.NativeObject` vs quickjs `com.script.quickjs.NativeObject`，
 * rhino `com.script.ScriptException` vs quickjs `com.script.quickjs.ScriptException`。
 */
object JsEngines {

    private val lock = SynchronizedObject()

    /** 固定 quickjs：rhino 已弃用（代码保留、不进产物，KMP 裁决 2026-07-17），切换入口已撤。 */
    val type: JsEngineType
        get() = JsEngineType.QUICKJS

    @Volatile
    private var provider: JsEngineProvider? = null

    /** 宿主启动早期注册一次（任何 JS eval 之前）。 */
    fun registerProvider(engineProvider: JsEngineProvider) {
        provider = engineProvider
    }

    /** 缓存的引擎实例 + 对应类型，切换开关后 [get] 检测到 type 变化时重建。 */
    @Volatile
    private var cachedType: JsEngineType? = null

    @Volatile
    private var cachedEngine: JsEngine? = null

    /** 当前引擎实例（懒加载，首次访问时创建）。 */
    val current: JsEngine
        get() = get()

    /**
     * 获取当前引擎（带缓存失效检查，切换开关后重建）。
     *
     * 线程安全：双检 + @Volatile。两个引擎实现都是 object 单例，重建只是引用切换，
     * 无实际创建开销，但避免每次都走 when 分支。
     */
    fun get(): JsEngine {
        val t = type
        if (cachedType != t || cachedEngine == null) {
            synchronized(lock) {
                if (cachedType != t || cachedEngine == null) {
                    val p = checkNotNull(provider) {
                        "JsEngineProvider 未注册(宿主启动早期 registerProvider)"
                    }
                    cachedType = t
                    cachedEngine = p.create(t)
                }
            }
        }
        return cachedEngine!!
    }

    // ============ 双判谓词（下放引擎实例自报，抽象面不见具体引擎类）============

    /**
     * 判断对象是否为 JS Object（rhino NativeObject / quickjs NativeObject）。
     *
     * 对应 AnalyzeRule 中 `result is NativeObject` 的判断，业务层改用本方法
     * 避免直接依赖某个引擎的 NativeObject 类型。判定逻辑下放到 [JsEngine] 实例。
     */
    fun isJsObject(obj: Any?): Boolean {
        if (obj == null) return false
        return current.isJsObject(obj)
    }

    /**
     * 把 JS Object 包装为 [JsObject]（rhino NativeObject / quickjs NativeObject）。
     *
     * 业务层 `jsObj[rule]` 走 Map 索引，统一两个引擎的 NativeObject 访问。
     * 非 JS Object 返回 null。
     */
    fun asJsObject(obj: Any?): JsObject? {
        if (obj == null) return null
        return current.asJsObject(obj)
    }

    /**
     * 判断是否为 JS 异常（rhino ScriptException / quickjs ScriptException）。
     *
     * 用于业务层 catch 分支区分 JS 错误与其他异常。
     */
    fun isJsException(t: Throwable): Boolean {
        return current.isJsException(t)
    }

    // ============ 当前线程 JS 执行上下文（转发到 current 引擎实例）============

    /**
     * 当前线程的 JS 执行上下文（非空，无上下文时抛 [IllegalStateException]）。
     *
     * 转发到 [JsEngine.currentContextNonNull]。对应 app 顶层 `jsContext`。
     */
    val jsContext: JsScope
        get() = current.currentContextNonNull()

    /**
     * 当前线程的 JS 执行上下文（可空）。
     *
     * 转发到 [JsEngine.currentContext]。对应 app 顶层 `jsContextOrNull`。
     */
    val jsContextOrNull: JsScope?
        get() = current.currentContext()

    /**
     * 在 JS 执行上下文中运行 block（同步版）。
     *
     * 转发到 [JsEngine.runScriptWithContext]。对应 app 顶层 `runScriptWithContext(context, block)`。
     */
    fun <T> runScriptWithContext(context: CoroutineContext, block: () -> T): T =
        current.runScriptWithContext(context, block)

    /**
     * 在 JS 执行上下文中运行 block（suspend 版）。
     *
     * 转发到 [JsEngine.runScriptWithContext]。对应 app 顶层 `runScriptWithContext(block)`。
     */
    suspend fun <T> runScriptWithContext(block: () -> T): T =
        current.runScriptWithContext(block)
}
