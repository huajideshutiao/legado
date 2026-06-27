package io.legado.app.model

import androidx.collection.LruCache
import com.google.gson.reflect.TypeToken
import com.script.quickjs.QuickJsContext
import com.script.quickjs.QuickJsEngine
import com.script.quickjs.ScriptBindings
import io.legado.app.exception.NoStackTraceException
import io.legado.app.help.http.newCallStrResponse
import io.legado.app.help.http.okHttpClient
import io.legado.app.utils.ACache
import io.legado.app.utils.GSON
import io.legado.app.utils.MD5Utils
import io.legado.app.utils.isAbsUrl
import io.legado.app.utils.isJsonObject
import kotlinx.coroutines.runBlocking
import splitties.init.appCtx
import java.io.File
import kotlin.coroutines.CoroutineContext

/**
 * JS 共享作用域缓存。
 *
 * 对应 rhino 版 SharedJsScope,缓存 [QuickJsContext] 而非 Scriptable。
 * 每个 jsLib 对应一个独立的 QuickJs 实例(已 evaluate bootstrap + jsLib),
 * 不同书源/规则通过 [QuickJsEngine.injectBindings] 复用同一实例的 jsLib 定义。
 *
 * 资源管理: 用强引用 LruCache 持有 scope,淘汰时调用 [QuickJsContext.close]
 * 释放 native QuickJs 资源(避免内存泄漏)。
 * 业务层([BaseSource]/[AnalyzeRule])通过 [getShareScope] 获取 scope 引用,
 * 在 scope 仍在使用期间不会被淘汰(LruCache 基于访问时间,活跃 scope 不会被淘汰)。
 *
 * 线程安全:QuickJs 实例非线程安全,业务层应保证同一 scope 的 evalJS 串行调用。
 */
object SharedJsScope {

    private val cacheFolder = File(appCtx.cacheDir, "shareJs")
    private val aCache = ACache.get(cacheFolder)

    private val scopeMap = object : LruCache<String, QuickJsContext>(16) {
        /**
         * LRU 淘汰时调用 [QuickJsContext.close] 释放 native QuickJs 资源,
         * 避免长期运行累积内存泄漏。
         */
        override fun entryRemoved(
            evicted: Boolean,
            key: String,
            oldValue: QuickJsContext,
            newValue: QuickJsContext?
        ) {
            oldValue.close()
        }
    }

    fun getScope(
        jsLib: String?,
        enableDangerousApi: Boolean,
        coroutineContext: CoroutineContext?
    ): QuickJsContext? {
        if (jsLib.isNullOrBlank()) {
            return null
        }
        val key = MD5Utils.md5Encode(jsLib)
        var scope = scopeMap[key]
        if (scope == null) {
            scope = QuickJsEngine.getRuntimeScope(
                ScriptBindings().apply {
                    this.dangerousApi = enableDangerousApi
                }
            )
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
                        QuickJsEngine.eval(js, scope, coroutineContext)
                    }
                }
            } else {
                QuickJsEngine.eval(jsLib, scope, coroutineContext)
            }
            scopeMap.put(key, scope)
        }
        return scope
    }

    fun remove(jsLib: String?) {
        if (jsLib.isNullOrBlank()) {
            return
        }
        val key = MD5Utils.md5Encode(jsLib)
        scopeMap.remove(key)
    }

}
