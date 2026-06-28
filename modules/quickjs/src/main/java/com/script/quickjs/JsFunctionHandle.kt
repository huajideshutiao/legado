package com.script.quickjs

import android.util.Log
import androidx.collection.LongSparseArray
import com.script.quickjs.JsFunctionHandle.Companion.releaseScope
import java.util.concurrent.atomic.AtomicLong

/**
 * JS function 对象的句柄包装。
 *
 * 用于 JavaAdapter 回调:Java 侧触发接口方法时,通过句柄找到 JS function 对象,
 * 在对应 native QuickJs ctx 上 evaluate 调用。
 *
 * 架构 A (native trap + 极简 bootstrap):
 * - 持有 [ctxPtr] (native JSContext 指针), 通过 [QuickJsNative.nativeEval] 执行 JS
 * - 不再依赖 quickjs-kt 的 QuickJs (Phase 5 已移除)
 *
 * 线程模型说明:
 * - native JSRuntime 单线程, JS 执行必须在持有 ctx 的线程
 * - 如果 JavaAdapter 回调发生在 JS evaluate 进行中 (JS->Kotlin->JavaAdapter->JS),
 *   会重入 nativeEval。native JSRuntime 是可重入的 (JS_RunEval 内部支持重入)
 * - 常见场景 (OnClickListener) 是 evaluate 完成后回调, 不会重入
 *
 * JS function 通过 [jsObjectExpr] 引用, 在 QuickJs 全局作用域里是唯一变量名。
 *
 * 资源管理: 句柄按 [QuickJsContext.scopeId] 分组, [releaseScope] 释放某 scope 全部句柄,
 * 由 [QuickJsContext.close] 触发。避免长期运行累积内存泄漏。
 *
 * 注意: 不会释放 [ctxPtr] 本身 (由 [QuickJsContext.close] 释放)。
 * 这里只清理 handleMap 中的 JsFunctionHandle 条目, 让强引用断开。
 * JS 全局变量 (__jsFn_xxx__) 的清理依赖 ctx 销毁时的 GC。
 */
class JsFunctionHandle private constructor(
    val ctxPtr: Long,
    val jsObjectExpr: String,
    val dangerousApi: Boolean
) {
    /**
     * 调用 JS function 对象上的方法。
     *
     * @param methodName JS 对象的方法名 (如 "onClick")
     * @param args Java 方法参数, 会通过 [javaArgToJsExpr] 转换为 JS 表达式
     * @return JS function 返回值 (基本类型 / Java 对象包装为 JavaObject / null)
     */
    fun invokeJsMethod(methodName: String, args: Array<Any?>): Any? {
        val jsArgs = args.joinToString(",") { javaArgToJsExpr(it) }
        // 用 IIFE 包裹避免污染全局作用域, 并捕获异常记录日志
        // null/undefined 经 nativeEval 返回 Java null
        val jsCode =
            "(function(){return ($jsObjectExpr && $jsObjectExpr.$methodName && $jsObjectExpr.$methodName($jsArgs));})();"
        return try {
            // 设置 ThreadLocalContext, 让 binding handler 能访问到当前 ctx
            // (JavaAdapter 回调可能来自非 JS 执行线程, 需要确保 ThreadLocal 有效)
            val ctx = QuickJsContext.threadLocalContext.get()
            val previousCtx = if (ctx?.ctxPtr == ctxPtr) null else run {
                // 当前线程没有 context 或不是本 handle 对应的 ctx,
                // 需要临时设置 (用于 binding handler 调用)
                val ownerCtx = findOwnerCtx()
                if (ownerCtx != null) {
                    val prev = QuickJsContext.threadLocalContext.get()
                    QuickJsContext.threadLocalContext.set(ownerCtx)
                    prev
                } else null
            }
            try {
                QuickJsNative.nativeEval(ctxPtr, jsCode)
            } finally {
                if (previousCtx != null) {
                    QuickJsContext.threadLocalContext.set(previousCtx)
                }
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
     * - Java 对象通过 [JavaObjectBridge.registerObject] 注册句柄,
     *   用 nativeWrapJavaObject 包装为 JS JavaObject (走 native trap)
     *
     * 注意: 不能用 nativeWrapJavaObject 因为它是同步 JNI 调用,
     * 这里通过 evaluate JS 表达式实现: 先在 native 层注册句柄,
     * JS 侧通过 binding 函数 __wrapJavaObjectNative(handle) 获取包装后的 JavaObject。
     * 但 bootstrap 中已无 __wrapJavaObjectNative, 改为直接 evaluate 一段 JS 表达式
     * 让 native 层通过 injectVariable 机制注入。
     *
     * 简化方案: 直接调用 QuickJsNative.nativeWrapJavaObject 拿到 JSValue 句柄,
     * 然后用 nativeSetProperty 设置到一个临时全局变量, JS 代码引用这个变量名。
     *
     * 但更简单: nativeEval 时直接传 JS 表达式, 对 Java 对象用一个特殊函数
     * __javaObj(handle) 由 native 层解释。这里采取 evaluate + 句柄路径。
     */
    private fun javaArgToJsExpr(arg: Any?): String {
        if (arg == null) return "null"
        if (arg is String) return JsStringUtils.escape(arg)
        if (arg is Number) return arg.toString()
        if (arg is Boolean) return arg.toString()
        if (arg is Char) return JsStringUtils.escape(arg.toString())
        if (arg is ByteArray) {
            // ByteArray 通过句柄注入 (JavaObject trap 支持 length/索引访问)
            val handle = JavaObjectBridge.registerObject(arg)
            return "__wrapJavaHandle(${handle}L)"
        }
        // Java 对象通过句柄包装
        if (!JsSecurityPolicy.isObjectVisible(arg, dangerousApi)) return "null"
        val handle = JavaObjectBridge.registerObject(arg)
        // 通过 bootstrap 提供的 __wrapJavaHandle(handle) 包装为 JavaObject
        return "__wrapJavaHandle(${handle}L)"
    }

    /**
     * 在 ctxPtrToCtxMap 中查找当前 ctxPtr 对应的 QuickJsContext。
     * 用于 JavaAdapter 回调时设置 ThreadLocal。
     */
    private fun findOwnerCtx(): QuickJsContext? {
        // 简单实现: 遍历所有活跃 scope 查找
        // 优化: 可以维护一个 ctxPtr -> QuickJsContext 的全局映射, 但当前 scope 数量少, 直接遍历
        return QuickJsContext.findByCtxPtr(ctxPtr)
    }

    companion object {
        private const val TAG = "JsFunctionHandle"

        private val handleCounter = AtomicLong(1L)
        private val handleMap = LongSparseArray<JsFunctionHandle>()

        /** scopeId -> 该 scope 注册的 JsFunctionHandle 句柄集合, close 时批量释放 */
        private val scopeHandles = LongSparseArray<MutableSet<Long>>()
        private val lock = Any()

        /**
         * 注册一个 JS function 对象, 返回句柄。
         *
         * @param ctxPtr native JSContext 指针
         * @param jsObjectExpr JS 对象表达式 (如 "__jsHandler_123__", 指向全局作用域里的变量)
         * @param dangerousApi 是否旁路安全名单
         */
        fun register(ctxPtr: Long, jsObjectExpr: String, dangerousApi: Boolean): Long {
            val handle = handleCounter.getAndIncrement()
            val h = JsFunctionHandle(ctxPtr, jsObjectExpr, dangerousApi)
            synchronized(lock) {
                handleMap.put(handle, h)
            }
            trackHandle(handle)
            return handle
        }

        /**
         * 把 handle 记录到当前线程的 scope, close 时批量释放。
         * 调用方需在 [QuickJsEngine.eval] / [QuickJsEngine.injectBindings] 期间调用,
         * 这些路径已设置 [QuickJsContext.threadLocalContext]。
         */
        private fun trackHandle(handle: Long) {
            val scopeId = QuickJsContext.threadLocalContext.get()?.scopeId ?: return
            synchronized(lock) {
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
         * 释放某 scope 注册的全部 JsFunctionHandle 句柄, 由 [QuickJsContext.close] 调用。
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
