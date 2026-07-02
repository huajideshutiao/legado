package io.legado.app.model.script

import io.legado.app.help.config.AppConfig
import io.legado.app.model.script.JsEngines.asJsObject
import io.legado.app.model.script.JsEngines.get
import io.legado.app.model.script.JsEngines.isJsException
import io.legado.app.model.script.JsEngines.isJsObject
import io.legado.app.model.script.quickjs.QuickJsJsEngine
import io.legado.app.model.script.quickjs.QuickJsJsObject
import io.legado.app.model.script.rhino.RhinoJsEngine
import io.legado.app.model.script.rhino.RhinoJsObject

/**
 * JS 引擎分派单例。
 *
 * 按 [AppConfig.jsEngine] 返回对应引擎实现并缓存实例。
 * 切换开关后下次访问 [get] 自动取新引擎（缓存失效重建）。
 *
 * 业务层（AnalyzeRule/BaseSource/AnalyzeUrl/SharedJsScope/JsActivity）通过 [get] 获取当前引擎，
 * 不再直接依赖 `QuickJsEngine` 或 `RhinoScriptEngine` 静态方法。
 *
 * 双判谓词（[isJsObject]/[asJsObject]/[isJsException]）处理同名不同包类型：
 * rhino `org.mozilla.javascript.NativeObject` vs quickjs `com.script.quickjs.NativeObject`，
 * rhino `com.script.ScriptException` vs quickjs `com.script.quickjs.ScriptException`。
 */
object JsEngines {

    /** 当前引擎类型（每次读取 AppConfig，运行时切换立即生效）。 */
    val type: JsEngineType
        get() = when (AppConfig.jsEngine) {
            "rhino" -> JsEngineType.RHINO
            else -> JsEngineType.QUICKJS
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
            synchronized(this) {
                if (cachedType != t || cachedEngine == null) {
                    cachedType = t
                    cachedEngine = create(t)
                }
            }
        }
        return cachedEngine!!
    }

    private fun create(type: JsEngineType): JsEngine = when (type) {
        JsEngineType.RHINO -> RhinoJsEngine
        JsEngineType.QUICKJS -> QuickJsJsEngine
    }

    // ============ 双判谓词（同名不同包类型统一判断）============

    /**
     * 判断对象是否为 JS Object（rhino NativeObject / quickjs NativeObject）。
     *
     * 对应 AnalyzeRule 中 `result is NativeObject` 的判断，业务层改用本方法
     * 避免直接依赖某个引擎的 NativeObject 类型。
     */
    fun isJsObject(obj: Any?): Boolean {
        if (obj == null) return false
        return when (type) {
            JsEngineType.RHINO -> obj is org.mozilla.javascript.NativeObject
            JsEngineType.QUICKJS -> obj is com.script.quickjs.NativeObject
        }
    }

    /**
     * 把 JS Object 包装为 [JsObject]（rhino NativeObject / quickjs NativeObject）。
     *
     * 业务层 `jsObj[rule]` 走 Map 索引，统一两个引擎的 NativeObject 访问。
     * 非 JS Object 返回 null。
     */
    fun asJsObject(obj: Any?): JsObject? {
        if (obj == null) return null
        return when (type) {
            JsEngineType.RHINO ->
                (obj as? org.mozilla.javascript.NativeObject)?.let { RhinoJsObject(it) }

            JsEngineType.QUICKJS ->
                (obj as? com.script.quickjs.NativeObject)?.let { QuickJsJsObject(it) }
        }
    }

    /**
     * 判断是否为 JS 异常（rhino ScriptException / quickjs ScriptException）。
     *
     * 用于业务层 catch 分支区分 JS 错误与其他异常。
     */
    fun isJsException(t: Throwable): Boolean {
        return when (type) {
            JsEngineType.RHINO -> t is com.script.ScriptException
            JsEngineType.QUICKJS -> t is com.script.quickjs.ScriptException
        }
    }
}
