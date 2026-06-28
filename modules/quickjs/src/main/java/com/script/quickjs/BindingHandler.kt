package com.script.quickjs

import android.util.Log
import com.script.quickjs.BindingHandler.call

/**
 * Binding 调用统一处理器。
 *
 * native 层的 binding JS 函数 (通过 [QuickJsNative.nativeDefineBinding] 注册) 被调用时,
 * native 层通过 JNI 回调 [call], 根据 name 分发到具体实现。
 *
 * 架构 A (native trap 完整实现):
 * - Java 对象的属性访问 (field/method) 由 native exotic trap 直接处理, 不需要 binding
 * - binding 仅用于 Packages/JavaImporter/JavaAdapter 的静态成员访问
 *
 * 与 [JavaObjectBridge] 的关系:
 * - 静态方法/字段/实例化: 委托给 [JavaObjectBridge] (复用反射逻辑)
 * - JavaAdapter: 委托给 [JavaObjectBridge.newJavaAdapter] + [JsFunctionHandle]
 *
 * 性能优化:
 * - args 数组直接传递, 避免 quickjs-kt 的 List 转换开销
 * - 基本类型直接 as 转换, 避免反射
 *
 * 注意: args 数组的元素类型由 [JniValueConvert.jsArgsToJavaArray] 决定:
 * - JS Number (int) -> java.lang.Integer
 * - JS Number (float) -> java.lang.Double
 * - JS String -> java.lang.String
 * - JS Boolean -> java.lang.Boolean
 * - JS null/undefined -> null
 * - JS object/array -> java.lang.Long (句柄)
 * - JS JavaObject (自定义类) -> 解包为原始 jobject
 */
object BindingHandler {

    private const val TAG = "BindingHandler"

    /**
     * 处理 binding 调用。
     *
     * @param name binding 名称 (如 "__loadJavaClass")
     * @param args 参数数组 (基本类型为 Java 包装类, JS 对象为 Long 句柄)
     * @return 返回值 (基本类型为 Java 包装类, Java 对象通过 native trap 包装)
     */
    @JvmStatic
    fun call(name: String, args: Array<Any?>): Any? {
        val result = when (name) {
            // ============ 类加载 ============

            "__loadJavaClass" -> {
                val fullName = args.getOrNull(0) as? String ?: return 0L
                val dangerousApi = (args.getOrNull(1) as? Boolean) ?: false
                JavaObjectBridge.loadJavaClass(fullName, dangerousApi)
            }

            "__classExists" -> {
                val fullName = args.getOrNull(0) as? String ?: return false
                val dangerousApi = (args.getOrNull(1) as? Boolean) ?: false
                JavaObjectBridge.classExists(fullName, dangerousApi)
            }

            "__isInterface" -> {
                val classHandle = (args.getOrNull(0) as? Number)?.toLong() ?: 0L
                val dangerousApi = (args.getOrNull(1) as? Boolean) ?: false
                if (classHandle == 0L) false
                else JavaObjectBridge.isInterface(classHandle, dangerousApi)
            }

            // ============ 实例化 ============
            // 返回原始 Java 对象 (非句柄 Map), native 层 JniValueConvert.fromJavaObject 会包装为 JavaObject

            "__newJavaInstance" -> {
                val classHandle = (args.getOrNull(0) as? Number)?.toLong() ?: 0L
                val ctorArgs = unwrapArgsList(args.getOrNull(1))
                val dangerousApi = (args.getOrNull(2) as? Boolean) ?: false
                if (classHandle == 0L) null
                else JavaObjectBridgeNative.newJavaInstanceRaw(classHandle, ctorArgs, dangerousApi)
            }

            // ============ 静态成员 ============
            // 返回原始 Java 对象 (非句柄 Map), native 层 JniValueConvert.fromJavaObject 会包装为 JavaObject

            "__callStaticMethod" -> {
                val classHandle = (args.getOrNull(0) as? Number)?.toLong() ?: 0L
                val methodName = args.getOrNull(1) as? String
                val methodArgs = unwrapArgsList(args.getOrNull(2))
                val dangerousApi = (args.getOrNull(3) as? Boolean) ?: false
                if (classHandle == 0L || methodName.isNullOrEmpty()) null
                else JavaObjectBridgeNative.callStaticMethodRaw(
                    classHandle,
                    methodName,
                    methodArgs,
                    dangerousApi
                )
            }

            "__getStaticField" -> {
                val classHandle = (args.getOrNull(0) as? Number)?.toLong() ?: 0L
                val fieldName = args.getOrNull(1) as? String
                val dangerousApi = (args.getOrNull(2) as? Boolean) ?: false
                if (classHandle == 0L || fieldName.isNullOrEmpty()) null
                else JavaObjectBridgeNative.getStaticFieldRaw(classHandle, fieldName, dangerousApi)
            }

            "__setStaticField" -> {
                val classHandle = (args.getOrNull(0) as? Number)?.toLong() ?: 0L
                val fieldName = args.getOrNull(1) as? String
                val value = args.getOrNull(2)
                val dangerousApi = (args.getOrNull(3) as? Boolean) ?: false
                if (classHandle == 0L || fieldName.isNullOrEmpty()) false
                else JavaObjectBridgeNative.setStaticFieldRaw(
                    classHandle,
                    fieldName,
                    value,
                    dangerousApi
                )
            }

            // ============ JavaAdapter (Phase 3 完整实现) ============
            // 返回原始 Java 代理对象, native 层包装为 JavaObject

            "__newJavaAdapter" -> {
                val classHandle = (args.getOrNull(0) as? Number)?.toLong() ?: 0L
                val jsFnHandle = (args.getOrNull(1) as? Number)?.toLong() ?: 0L
                val dangerousApi = (args.getOrNull(2) as? Boolean) ?: false
                if (classHandle == 0L || jsFnHandle == 0L) null
                else JavaObjectBridgeNative.newJavaAdapterRaw(classHandle, jsFnHandle, dangerousApi)
            }

            "__registerJsFunctionNative" -> {
                // 注册 JS function 对象, 返回 JsFunctionHandle 句柄
                val jsObjectExpr = args.getOrNull(0) as? String
                val dangerousApi = (args.getOrNull(1) as? Boolean) ?: false
                if (jsObjectExpr.isNullOrEmpty()) 0L
                else {
                    // 通过 ThreadLocal 获取当前 ctx 的 ctxPtr
                    val ctx = QuickJsContext.threadLocalContext.get()
                    if (ctx == null) {
                        Log.w(
                            TAG,
                            "__registerJsFunctionNative: no QuickJsContext in current thread"
                        )
                        0L
                    } else {
                        JsFunctionHandle.register(ctx.ctxPtr, jsObjectExpr, dangerousApi)
                    }
                }
            }

            // ============ 句柄包装 (供 JsFunctionHandle.javaArgToJsExpr 用) ============

            "__wrapJavaHandle" -> {
                // 把 Java 对象句柄转换为原始 Java 对象, native 层 fromJavaObject 会包装为 JavaObject
                // 用于 JsFunctionHandle.invokeJsMethod 把 Java 参数 (如 OnClickListener 的 View)
                // 通过 JS 表达式 __wrapJavaHandle(handle) 转换为 JS 可访问的 JavaObject
                val handle = (args.getOrNull(0) as? Number)?.toLong() ?: 0L
                if (handle == 0L) null
                else JavaObjectBridge.getObject(handle)
            }

            else -> {
                Log.w(TAG, "Unknown binding: $name")
                null
            }
        }
        return result
    }

    /**
     * 解包参数列表。
     * native 层传入的 args 元素可能是 Long 句柄 (JS 对象) 或基本类型。
     * 如果是 Array<Any?>, 直接使用; 否则包装为单元素数组。
     */
    private fun unwrapArgsList(arg: Any?): Array<Any?> {
        @Suppress("UNCHECKED_CAST")
        return when (arg) {
            is Array<*> -> arg as Array<Any?>
            is List<*> -> arg.toTypedArray()
            else -> emptyArray()
        }
    }
}

