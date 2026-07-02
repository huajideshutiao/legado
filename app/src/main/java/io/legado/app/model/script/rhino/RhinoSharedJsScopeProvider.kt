package io.legado.app.model.script.rhino

import androidx.collection.LruCache
import com.google.gson.reflect.TypeToken
import com.script.ScriptBindings
import com.script.rhino.RhinoScriptEngine
import io.legado.app.exception.NoStackTraceException
import io.legado.app.help.http.newCallStrResponse
import io.legado.app.help.http.okHttpClient
import io.legado.app.model.script.JsScope
import io.legado.app.model.script.SharedJsScopeProvider
import io.legado.app.utils.ACache
import io.legado.app.utils.GSON
import io.legado.app.utils.MD5Utils
import io.legado.app.utils.isAbsUrl
import io.legado.app.utils.isJsonObject
import kotlinx.coroutines.runBlocking
import org.mozilla.javascript.Scriptable
import org.mozilla.javascript.ScriptableObject
import splitties.init.appCtx
import java.io.File
import java.lang.ref.WeakReference
import kotlin.coroutines.CoroutineContext

/**
 * rhino 版 SharedJsScope provider。
 *
 * 复刻 master 分支 rhino 版 SharedJsScope 语义：全局 LruCache + sealObject()。
 *
 * rhino 的 [ScriptableObject.sealObject] 让 scope 全局只读共享，子 scope 通过 prototype 继承
 * + `Context.enter()` 线程私有，无需 ThreadLocal 缓存（与 quickjs 的三层缓存不同）。
 *
 * jsLib JSON Map 下载逻辑从 quickjs 版 [io.legado.app.model.script.quickjs.QuickJsSharedJsScopeProvider]
 * 复刻，区别：quickjs 编译为 bytecode 缓存，rhino 直接 eval 源码到 scope。
 */
object RhinoSharedJsScopeProvider : SharedJsScopeProvider {

    /**
     * 全局缓存容量（对齐 master 分支）。
     *
     * rhino scope 经 sealObject 后只读共享，WeakReference 让业务层不强引用时 GC 可回收。
     */
    private const val CACHE_SIZE = 16

    private val cacheFolder = File(appCtx.cacheDir, "shareJs")
    private val aCache = ACache.get(cacheFolder)

    private val cache = LruCache<String, WeakReference<Scriptable>>(CACHE_SIZE)

    override fun getScope(
        jsLib: String?,
        enableDangerousApi: Boolean,
        coroutineContext: CoroutineContext?
    ): JsScope? {
        if (jsLib.isNullOrBlank()) return null
        val key = MD5Utils.md5Encode(jsLib)
        // 缓存命中：弱引用未死则复用共享 scope
        cache.get(key)?.get()?.let { return RhinoJsScope(it) }
        // 未命中：创建新 scope + eval jsLib + sealObject
        val bindings = ScriptBindings().apply { this.dangerousApi = enableDangerousApi }
        val scope = RhinoScriptEngine.getRuntimeScope(bindings)
        // 支持 JSON Map 形式 jsLib（按 url 下载每个 js 文件后 eval）
        evalJsLib(jsLib, scope, coroutineContext)
        // sealObject 让 scope 全局只读共享（对齐 master 分支）
        // sealObject 后 scope 不可修改，子 scope 通过 prototype 继承变量
        (scope as? ScriptableObject)?.sealObject()
        cache.put(key, WeakReference(scope))
        return RhinoJsScope(scope)
    }

    override fun remove(jsLib: String?) {
        if (jsLib.isNullOrBlank()) return
        cache.remove(MD5Utils.md5Encode(jsLib))
    }

    override fun clearAll() {
        cache.evictAll()
    }

    /**
     * 把 jsLib 执行到 scope 上。
     *
     * 支持 JSON Map 形式 jsLib：`{"name1": "url1", "name2": "url2"}`，
     * 按 url 下载每个 js 文件（ACache 缓存）后 eval。
     * 普通 JS 字符串直接 eval。
     *
     * 与 quickjs 版区别：quickjs 编译为 bytecode 缓存复用，rhino 直接 eval 源码
     * （rhino 的 CompiledScript 也可缓存，但 master 分支 SharedJsScope 缓存的是 scope 而非 bytecode）。
     */
    private fun evalJsLib(jsLib: String, scope: Scriptable, coroutineContext: CoroutineContext?) {
        if (jsLib.isJsonObject()) {
            val jsMap: Map<String, String> = GSON.fromJson(
                jsLib,
                TypeToken.getParameterized(
                    Map::class.java,
                    String::class.java,
                    String::class.java
                ).type
            )
            jsMap.values.forEach { value ->
                if (value.isAbsUrl()) {
                    val fileName = MD5Utils.md5Encode(value)
                    var js = aCache.getAsString(fileName)
                    if (js == null) {
                        js = runBlocking {
                            okHttpClient.newCallStrResponse {
                                url(value)
                            }.body
                        }
                        if (js != null) {
                            aCache.put(fileName, js)
                        } else {
                            throw NoStackTraceException("下载jsLib-${value}失败")
                        }
                    }
                    RhinoScriptEngine.eval(js, scope, coroutineContext)
                }
            }
        } else {
            RhinoScriptEngine.eval(jsLib, scope, coroutineContext)
        }
    }
}
