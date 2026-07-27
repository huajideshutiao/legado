package com.script.quickjs

import java.lang.reflect.InvocationHandler
import java.lang.reflect.Method
import java.lang.reflect.Proxy

/**
 * SAM (Single Abstract Method) 接口适配器: 把 JS function 包装为 Java 函数式接口代理。
 *
 * 对齐 rhino NativeJavaObject.coerceTypeImpl 的 FunctionAdapter 行为:
 * JS function 传给期望 SAM 接口 (Consumer/Function/Predicate/Supplier/Runnable 等) 的
 * Java 方法时, 自动包装为该接口的 Proxy, 接口方法调用通过 [QuickJsNative.nativeCallJsHandle]
 * 回调到原始 JS function。
 *
 * 典型场景: `tmp.forEach(i => {...})` 中 tmp 是 Java ArrayList, JS 箭头函数被包装为
 * Consumer 代理, ArrayList.forEach 调用 accept(element) 时回调 JS function。
 *
 * 生命周期:
 * - [jsFunctionHandle] 是 JsHandleTable 句柄 (native 层, scope-bound)
 * - 仅支持同步调用 (如 ArrayList.forEach, Stream 操作): JS 执行线程内完成, scope 活跃
 * - 异步场景 (如 OnClickListener) 需走 QuickJsEngine 单线程 executor 或用 nativeDupHandle
 *   增加引用计数; scope close 后句柄失效, [QuickJsNative.nativeCallJsHandle] 安全返回 null
 *
 * 已知限制:
 * - default 方法不支持 (抛 UnsupportedOperationException, 避免静默调用 JS function 返回错误类型)
 * - Long 句柄作为 args 会被 fromJavaObject 误转为数字 (见 nativeCallJsHandle 注释)
 * - 异常经 method.invoke 包装为 InvocationTargetException, JS catch 拿到的是包装异常 (非原始 JS Error)
 */
class JsSamAdapter(private val jsFunctionHandle: Long) : InvocationHandler {

    override fun invoke(proxy: Any, method: Method, args: Array<Any?>?): Any? {
        // Object 方法直接处理 (对齐 JavaObjectBridge.newJavaAdapter 第 1568-1574 行)
        if (method.declaringClass == Any::class.java) {
            return when (method.name) {
                "hashCode" -> System.identityHashCode(proxy)
                "equals" -> proxy === args?.getOrNull(0)
                "toString" -> "JsSamAdapter@$jsFunctionHandle"
                else -> null
            }
        }
        // default 方法不支持: Proxy.newProxyInstance 会把 default 方法调用转发到此,
        // 若放行会让 JS function 收到非预期参数返回错误类型 (如 Consumer.andThen 应返回
        // 新 Consumer, 但 JS function 不会返回), 显式抛异常让调用方知晓
        if (method.isDefault) {
            throw UnsupportedOperationException(
                "JsSamAdapter does not support default method: ${method.name}"
            )
        }
        // SAM 抽象方法: 调用 JS function
        // 不 catch JsNativeException (对齐"不吞异常"原则, 见 project_memory):
        // 异常传播到 jsMethodCallable 的 ExceptionCheck 分支, 由 JavaObjectClass::wrap
        // 包装为 JS 异常供 JS try-catch 捕获
        return QuickJsNative.nativeCallJsHandle(jsFunctionHandle, args ?: emptyArray())
    }

    companion object {
        /**
         * 把 JS function 句柄包装为目标 SAM 接口的 Proxy 代理。
         *
         * @param jsFunctionHandle JsHandleTable 句柄 (指向 JS function)
         * @param targetType 目标 SAM 接口 (如 Consumer::class.java)
         * @return 实现 targetType 的 Proxy 对象
         */
        fun wrapAsSam(jsFunctionHandle: Long, targetType: Class<*>): Any {
            return Proxy.newProxyInstance(
                targetType.classLoader,
                arrayOf(targetType),
                JsSamAdapter(jsFunctionHandle)
            )
        }
    }
}
