package com.script.quickjs

import com.script.quickjs.QuickJsNative.nativeSetProperty


/**
 * QuickJS native 函数声明。
 *
 * 对应 native 层 jni_bridge.cpp,所有 JS 操作通过此 object 调用。
 *
 * 设计原则: Opaque 句柄模式
 * - Runtime/Context/JSValue 都用 Long 句柄传递
 * - JS 对象/数组/函数返回 java.lang.Long 句柄 (Kotlin 侧用 JsValue 包装)
 * - 基本类型 (Boolean/Integer/Double/String) 直接返回 Java 包装类
 * - null/undefined 返回 null
 *
 * 线程模型: JSRuntime 单线程,所有 native 调用必须在同一线程
 * (由 QuickJsEngineV2 的单线程 executor 保证)
 */
object QuickJsNative {

    init {
        // 加载 native 库
        // 库名 legado_quickjs 对应 CMakeLists.txt 中的 add_library(legado_quickjs SHARED ...)
        System.loadLibrary("legado_quickjs")
    }

    // ============ Runtime / Context 生命周期 ============

    /** 创建 JSRuntime,返回指针句柄。0 表示失败。 */
    external fun nativeCreateRuntime(): Long

    /** 释放 JSRuntime。 */
    external fun nativeFreeRuntime(rtPtr: Long)

    /** 创建 JSContext (并初始化 JavaObject 自定义类),返回指针句柄。0 表示失败。 */
    external fun nativeCreateContext(rtPtr: Long): Long

    /** 释放 JSContext (同时释放此 ctx 的所有 JSValue 句柄)。 */
    external fun nativeFreeContext(ctxPtr: Long)

    // ============ JS 执行 ============

    /**
     * 执行 JS 代码。
     *
     * @return JS 结果:
     *   - null = JS null/undefined
     *   - Boolean/Integer/Double/String = JS 基本类型
     *   - java.lang.Long = JS 对象/数组/函数句柄 (用 JsValue 包装)
     * @throws JsNativeException JS 执行异常
     */
    external fun nativeEval(ctxPtr: Long, code: String): Any?

    // ============ 句柄管理 ============

    /** 释放 JSValue 句柄。 */
    external fun nativeFreeHandle(ctxPtr: Long, handle: Long)

    /** 复制句柄 (DupValue),返回新句柄。 */
    external fun nativeDupHandle(ctxPtr: Long, handle: Long): Long

    // ============ 全局对象 ============

    /** 获取全局对象,返回句柄。 */
    external fun nativeGetGlobalObject(ctxPtr: Long): Any?

    // ============ 属性操作 ============

    /**
     * 获取对象属性。
     * @return 属性值 (同 nativeEval 返回类型)
     */
    external fun nativeGetProperty(ctxPtr: Long, objHandle: Long, name: String): Any?

    /**
     * 设置对象属性。
     * @param value Java 值 (基本类型或 JsValue 句柄)
     * @return true 表示成功
     */
    external fun nativeSetProperty(
        ctxPtr: Long,
        objHandle: Long,
        name: String,
        value: Any?
    ): Boolean

    /**
     * 设置对象属性 (句柄版本)。
     *
     * 与 [nativeSetProperty] 区别: value 直接以 Long 句柄传入, 从 JsHandleTable 取 JSValue,
     * 避免 [JniValueConvert.fromJavaObject] 把 Long 句柄误当成普通数字。
     *
     * 用于 [QuickJsEngine.injectVariable] 注入 nativeWrapJavaObject 返回的句柄。
     *
     * @param valueHandle JsHandleTable 句柄 (nativeWrapJavaObject 返回值)
     * @return true 表示成功
     */
    external fun nativeSetPropertyHandle(
        ctxPtr: Long,
        objHandle: Long,
        name: String,
        valueHandle: Long
    ): Boolean

    /** 检查对象是否有某属性。 */
    external fun nativeHasProperty(ctxPtr: Long, objHandle: Long, name: String): Boolean

    // ============ 类型查询与转换 ============

    /**
     * 获取 JSValue 类型标签。
     *
     * 返回值:
     *   0 = null, 1 = undefined, 2 = boolean, 3 = int32, 4 = float64,
     *   5 = string, 6 = object, 7 = array, 8 = function,
     *   9 = exception, 10 = JavaObject (Java 对象包装)
     */
    external fun nativeGetTypeTag(ctxPtr: Long, handle: Long): Int

    /** JSValue 转 Boolean。 */
    external fun nativeToBoolean(ctxPtr: Long, handle: Long): Boolean

    /** JSValue 转 Int32。 */
    external fun nativeToInt32(ctxPtr: Long, handle: Long): Int

    /** JSValue 转 Float64。 */
    external fun nativeToFloat64(ctxPtr: Long, handle: Long): Double

    /** JSValue 转 String。 */
    external fun nativeToString(ctxPtr: Long, handle: Long): String?

    // ============ 从 Java 值创建 JSValue ============

    external fun nativeNewBoolean(ctxPtr: Long, value: Boolean): Any?
    external fun nativeNewInt32(ctxPtr: Long, value: Int): Any?
    external fun nativeNewFloat64(ctxPtr: Long, value: Double): Any?
    external fun nativeNewString(ctxPtr: Long, value: String): Any?
    external fun nativeNewArray(ctxPtr: Long): Any?

    // ============ JavaObject 包装 ============

    /**
     * 包装 Java 对象为 JSValue (JavaObject 自定义类实例)。
     * JS 侧访问属性走 exotic trap,直通 Java 反射。
     */
    external fun nativeWrapJavaObject(ctxPtr: Long, javaObj: Any): Any?

    // ============ 异常处理 ============

    /** 获取并清除当前异常,返回异常对象。 */
    external fun nativeGetException(ctxPtr: Long): Any?

    /** 检查 JSValue 是否为 Error 对象。 */
    external fun nativeIsError(ctxPtr: Long, handle: Long): Boolean

    // ============ 函数调用 ============

    /**
     * 调用 JS 函数。
     * @param funcHandle 函数句柄
     * @param thisHandle this 对象句柄 (0 表示 undefined)
     * @param argHandles 参数句柄数组
     * @return 返回值 (同 nativeEval 返回类型)
     */
    external fun nativeCallFunction(
        ctxPtr: Long,
        funcHandle: Long,
        thisHandle: Long,
        argHandles: LongArray
    ): Any?

    // ============ bytecode 编译与执行 ============

    /** 编译 JS 为 bytecode。 */
    external fun nativeCompile(ctxPtr: Long, code: String): ByteArray?

    /** 执行 bytecode。 */
    external fun nativeEvalBytecode(ctxPtr: Long, bytecode: ByteArray): Any?

    // ============ Binding 注册 ============

    /**
     * 注册 JS 全局函数 (binding)。
     * JS 调用此函数时, native 层通过 JNI 回调 Java BindingHandler.call(name, args)。
     *
     * @param ctxPtr ctx 指针
     * @param name binding 名称 (如 "__loadJavaClass")
     * @return true 成功, false 失败
     */
    external fun nativeDefineBinding(ctxPtr: Long, name: String): Boolean

    // ============ dangerousApi 管理 ============

    /**
     * 设置 ctx 的 dangerousApi 标志。
     * native trap 回调时从 ctx opaque 读取此值, 传递给 Java 侧。
     *
     * @param ctxPtr ctx 指针
     * @param dangerousApi 是否旁路安全名单
     */
    external fun nativeSetDangerousApi(ctxPtr: Long, dangerousApi: Boolean)
}
