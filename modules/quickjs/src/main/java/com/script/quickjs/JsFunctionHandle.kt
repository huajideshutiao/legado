package com.script.quickjs

import android.util.Log
import androidx.collection.LongSparseArray
import com.dokar.quickjs.QuickJs
import com.script.quickjs.JsFunctionHandle.Companion.releaseScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import java.util.concurrent.atomic.AtomicLong

/**
 * JS function 对象的句柄包装。
 *
 * 用于 JavaAdapter 回调:Java 侧触发接口方法时,通过句柄找到 JS function 对象,
 * 在对应 QuickJs 实例上 evaluate 调用。
 *
 * 线程模型说明:
 * - QuickJs 的 evaluate 是 suspend + Mutex 串行。
 * - 如果 JavaAdapter 回调发生在 JS evaluate 进行中(JS->Kotlin->JavaAdapter->JS),
 *   runBlocking 会死锁。常见场景(OnClickListener)是 evaluate 完成后回调,不会死锁。
 * - 对于同步有返回值的接口方法(如 Comparator.compare),如果死锁,会抛 IllegalStateException。
 *
 * JS function 通过 [jsObjectExpr] 引用,在 QuickJs 全局作用域里是唯一变量名。
 *
 * 资源管理: 句柄按 [QuickJsContext.scopeId] 分组, [releaseScope] 释放某 scope 全部句柄,
 * 由 [QuickJsContext.close] 触发。避免长期运行累积内存泄漏。
 */
class JsFunctionHandle private constructor(
    val quickJs: QuickJs,
    val jsObjectExpr: String,
    val dangerousApi: Boolean
) {
    /**
     * 调用 JS function 对象上的方法。
     *
     * @param methodName JS 对象的方法名(如 "onClick")
     * @param args Java 方法参数,会通过 [JavaObjectBridge] 转换为 JS 可用形式
     * @return JS function 返回值(基本类型直接返回,Java 对象通过句柄包装)
     */
    fun invokeJsMethod(methodName: String, args: Array<Any?>): Any? {
        val jsArgs = args.joinToString(",") { javaArgToJsExpr(it) }
        // 用 IIFE 包裹避免污染全局作用域,并捕获异常记录日志
        val jsCode =
            "(function(){return ($jsObjectExpr && $jsObjectExpr.$methodName && $jsObjectExpr.$methodName($jsArgs));})();"
        return try {
            runBlocking(Dispatchers.Unconfined) {
                quickJs.evaluate<Any?>(jsCode)
            }
        } catch (e: Throwable) {
            Log.w(TAG, "invokeJsMethod failed: $jsObjectExpr.$methodName", e)
            null
        }
    }

    /**
     * 把 Java 参数转为 JS 表达式字符串。
     *
     * - null/基本类型直接拼字面量
     * - Java 对象通过 [JavaObjectBridge.registerObject] 注册句柄,JS 用 `__wrapJavaObject(handle)` 解包
     */
    private fun javaArgToJsExpr(arg: Any?): String {
        if (arg == null) return "null"
        if (arg is String) return JsStringUtils.escape(arg)
        if (arg is Number) return arg.toString()
        if (arg is Boolean) return arg.toString()
        if (arg is Char) return JsStringUtils.escape(arg.toString())
        if (arg is ByteArray) {
            // ByteArray 转 JS Uint8Array(通过 handle)
            val handle = JavaObjectBridge.registerObject(arg)
            return "__wrapJavaObject($handle)"
        }
        // Java 对象通过句柄包装
        if (!JsSecurityPolicy.isObjectVisible(arg, dangerousApi)) return "null"
        val handle = JavaObjectBridge.registerObject(arg)
        return "__wrapJavaObject($handle)"
    }

    companion object {
        private const val TAG = "JsFunctionHandle"

        private val handleCounter = AtomicLong(1L)
        private val handleMap = LongSparseArray<JsFunctionHandle>()

        /** scopeId -> 该 scope 注册的 JsFunctionHandle 句柄集合,close 时批量释放 */
        private val scopeHandles = LongSparseArray<MutableSet<Long>>()
        private val lock = Any()

        /**
         * 注册一个 JS function 对象,返回句柄。
         *
         * @param quickJs QuickJs 实例
         * @param jsObjectExpr JS 对象表达式(如 "__jsHandler_123__",指向全局作用域里的变量)
         * @param dangerousApi 是否旁路安全名单
         */
        fun register(quickJs: QuickJs, jsObjectExpr: String, dangerousApi: Boolean): Long {
            val handle = handleCounter.getAndIncrement()
            val h = JsFunctionHandle(quickJs, jsObjectExpr, dangerousApi)
            synchronized(lock) {
                handleMap.put(handle, h)
            }
            trackHandle(handle)
            return handle
        }

        /**
         * 把 handle 记录到当前线程的 scope,close 时批量释放。
         * 调用方需在 [QuickJsEngine.eval] / [QuickJsEngine.injectBindings] 期间调用,
         * 这些路径已设置 [QuickJsContext.threadLocalContext]。
         */
        private fun trackHandle(handle: Long) {
            val scopeId = QuickJsContext.threadLocalContext.get()?.scopeId ?: return
            synchronized(lock) {
                // LongSparseArray 无 getOrPut 扩展,手动实现
                var set = scopeHandles.get(scopeId)
                if (set == null) {
                    set = mutableSetOf()
                    scopeHandles.put(scopeId, set)
                }
                set.add(handle)
            }
        }

        fun get(handle: Long): JsFunctionHandle? {
            synchronized(lock) {
                return handleMap.get(handle)
            }
        }

        fun release(handle: Long) {
            synchronized(lock) {
                handleMap.remove(handle)
            }
        }

        /**
         * 释放某 scope 注册的全部 JsFunctionHandle 句柄,由 [QuickJsContext.close] 调用。
         *
         * 避免全局 clearAll 影响其他活跃 scope。
         */
        fun releaseScope(scopeId: Long) {
            synchronized(lock) {
                val handles = scopeHandles.get(scopeId) ?: return
                handles.forEach { handleMap.remove(it) }
                scopeHandles.remove(scopeId)
            }
        }

        fun clearAll() {
            synchronized(lock) {
                handleMap.clear()
                scopeHandles.clear()
            }
        }
    }
}
