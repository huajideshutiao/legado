package com.script.quickjs

/**
 * Java 对象反射桥接, 供 native exotic trap 回调。
 *
 * 当 JS 侧访问 JavaObject 的属性时, native 层的 JSClassExoticMethods trap
 * 会通过 JNI 调用此对象的方法, 实现 Java 反射。
 *
 * 本类是 [JavaObjectBridge] 的 native 适配层:
 * - 属性访问 (hasProperty/getPropertyInfo/setProperty/getPropertyNames) 接受原始 obj,
 *   内部调用 JavaObjectBridge 的 Raw 方法, 返回原始 Java 对象 (非句柄 Map),
 *   供 native 层用 JavaObjectClass.wrap 包装为 JSValue。
 * - method callable 回调 (callMethod) 接受 objHandle, 返回原始 Java 对象。
 * - 静态成员/JavaAdapter 供 Phase 3 bootstrap 调用。
 *
 * 类型处理:
 * - Map: getProperty 返回 map[key], getPropertyNames 返回 keySet()
 * - List/Array: getProperty 返回 list[index], getPropertyNames 返回 0 until size
 * - 普通 Java 对象: 反射 field/method
 */
object JavaObjectBridgeNative {

    // ============ 句柄管理 ============

    /**
     * 获取 Java 对象的句柄 (身份去重)。
     * method callable 创建时调用, 存储到 JS_NewCFunctionData 的 func_data 中。
     */
    @JvmStatic
    fun getHandle(obj: Any): Long = JavaObjectBridge.getOrRegisterIdentityHandle(obj)

    /**
     * 按 handle 查找 Java 对象 (method callable 回调时用)。
     */
    @JvmStatic
    fun getObject(handle: Long): Any? = JavaObjectBridge.getObject(handle)

    // ============ native exotic trap 回调 ============

    /**
     * hasProperty trap: 检查属性是否存在。
     */
    @JvmStatic
    fun hasProperty(obj: Any, name: String, dangerousApi: Boolean): Boolean {
        // 复用 getPropertyInfo, 检查 fieldExists 或 hasMethod
        val info = JavaObjectBridge.getJavaPropertyRaw(obj, name, dangerousApi) ?: return false
        val fieldExists = info[1] as? Boolean ?: false
        val hasMethod = info[2] as? Boolean ?: false
        return fieldExists || hasMethod
    }

    /**
     * getProperty trap: 查询属性, 返回 [fieldValue, fieldExists, hasMethod]。
     *
     * - fieldValue: 原始 Java 对象 (可能为 null), native 层用 JniValueConvert.fromJavaObject 转换
     * - fieldExists: 字段空间是否可能存在 (用于 method callable 缓存判断)
     * - hasMethod: 是否有同名实例方法 (native 层据此创建 method callable)
     *
     * @return null 表示属性不存在; 否则返回三元素 Array
     */
    @JvmStatic
    fun getPropertyInfo(obj: Any, name: String, dangerousApi: Boolean): Array<Any?>? {
        return JavaObjectBridge.getJavaPropertyRaw(obj, name, dangerousApi)
    }

    /**
     * setProperty trap: 设置属性值。
     * value 是原始 Java 对象 (native 层 JniValueConvert.toJavaObject 转换后)。
     */
    @JvmStatic
    fun setProperty(obj: Any, name: String, value: Any?, dangerousApi: Boolean): Boolean {
        return JavaObjectBridge.setInstanceFieldRaw(obj, name, value, dangerousApi)
    }

    /**
     * getPropertyNames trap: 获取所有可枚举属性名。
     */
    @JvmStatic
    fun getPropertyNames(obj: Any, dangerousApi: Boolean): Array<String> {
        return JavaObjectBridge.getInstanceKeysRaw(obj, dangerousApi)
    }

    // ============ method callable 回调 ============

    /**
     * method callable 回调: 调用实例方法。
     *
     * native 层的 method callable JS 函数被调用时, 通过 JNI 回调此方法。
     * args 中的 Long 句柄会被 JavaObjectBridge.callInstanceMethodRaw 自动解包。
     *
     * 重要: callInstanceMethodRaw 内部的 callHotTypeMethod 用 javaToJsResult 包装返回值,
     * 对于非基本类型对象 (如 StringBuilder) 返回句柄 Map ({__java_handle__=handle})。
     * 如果不解包, native 层 fromJavaObject 会把句柄 Map 当成普通 Map 包装为 JavaObject,
     * 导致 JS 侧拿到 Map 而非原始 Java 对象 (如 sb.append 返回的 StringBuilder)。
     * 因此必须用 unwrapResult 解包句柄 Map 为原始 Java 对象。
     *
     * @return 原始 Java 对象 (native 层用 JniValueConvert.fromJavaObject 转换)
     */
    @JvmStatic
    fun callMethod(
        objHandle: Long,
        methodName: String,
        args: Array<Any?>,
        dangerousApi: Boolean
    ): Any? {
        val obj = JavaObjectBridge.getObject(objHandle) ?: return null
        val result = JavaObjectBridge.callInstanceMethodRaw(obj, methodName, args, dangerousApi)
        return unwrapResult(result)
    }

    // ============ 静态成员 (Phase 3 JavaClass trap 用) ============
    // 提供两套重载: 按 Class 对象 / 按 classHandle (Long)
    // 均返回原始 Java 对象 (非句柄 Map), 供 native 层 JniValueConvert.fromJavaObject 包装

    /**
     * 调用静态方法。
     * @param classObj Class 对象
     * @return 原始 Java 对象 (native 层用 JniValueConvert.fromJavaObject 转换)
     */
    @JvmStatic
    fun callStaticMethod(
        classObj: Class<*>,
        methodName: String,
        args: Array<Any?>,
        dangerousApi: Boolean
    ): Any? {
        val classHandle = JavaObjectBridge.registerClass(classObj)
        return callStaticMethodRaw(classHandle, methodName, args, dangerousApi)
    }

    /**
     * 调用静态方法 (按 classHandle)。
     * BindingHandler.__callStaticMethod 通过此方法调用, 返回原始 Java 对象。
     */
    @JvmStatic
    fun callStaticMethodRaw(
        classHandle: Long,
        methodName: String,
        args: Array<Any?>,
        dangerousApi: Boolean
    ): Any? {
        val result = JavaObjectBridge.callStaticMethod(classHandle, methodName, args, dangerousApi)
        return unwrapResult(result)
    }

    /**
     * 获取静态字段。
     * @return 原始 Java 对象
     */
    @JvmStatic
    fun getStaticField(classObj: Class<*>, fieldName: String, dangerousApi: Boolean): Any? {
        val classHandle = JavaObjectBridge.registerClass(classObj)
        return getStaticFieldRaw(classHandle, fieldName, dangerousApi)
    }

    /**
     * 获取静态字段 (按 classHandle)。
     * BindingHandler.__getStaticField 通过此方法调用, 返回原始 Java 对象。
     */
    @JvmStatic
    fun getStaticFieldRaw(classHandle: Long, fieldName: String, dangerousApi: Boolean): Any? {
        val result = JavaObjectBridge.getStaticField(classHandle, fieldName, dangerousApi)
        return unwrapResult(result)
    }

    /**
     * 设置静态字段。
     */
    @JvmStatic
    fun setStaticField(
        classObj: Class<*>,
        fieldName: String,
        value: Any?,
        dangerousApi: Boolean
    ): Boolean {
        val classHandle = JavaObjectBridge.registerClass(classObj)
        return setStaticFieldRaw(classHandle, fieldName, value, dangerousApi)
    }

    /**
     * 设置静态字段 (按 classHandle)。
     * BindingHandler.__setStaticField 通过此方法调用。
     */
    @JvmStatic
    fun setStaticFieldRaw(
        classHandle: Long,
        fieldName: String,
        value: Any?,
        dangerousApi: Boolean
    ): Boolean {
        return JavaObjectBridge.setStaticField(classHandle, fieldName, value, dangerousApi)
    }

    /**
     * 实例化 Java 对象。
     * @return 原始 Java 对象
     */
    @JvmStatic
    fun newJavaInstance(classObj: Class<*>, args: Array<Any?>, dangerousApi: Boolean): Any? {
        val classHandle = JavaObjectBridge.registerClass(classObj)
        return newJavaInstanceRaw(classHandle, args, dangerousApi)
    }

    /**
     * 实例化 Java 对象 (按 classHandle)。
     * BindingHandler.__newJavaInstance 通过此方法调用, 返回原始 Java 对象。
     */
    @JvmStatic
    fun newJavaInstanceRaw(classHandle: Long, args: Array<Any?>, dangerousApi: Boolean): Any? {
        val objHandle = JavaObjectBridge.newJavaInstance(classHandle, args, dangerousApi)
        if (objHandle == 0L) return null
        return JavaObjectBridge.getObject(objHandle)
    }

    /**
     * 创建 JavaAdapter (按 classHandle)。
     * BindingHandler.__newJavaAdapter 通过此方法调用, 返回原始 Java 代理对象。
     */
    @JvmStatic
    fun newJavaAdapterRaw(classHandle: Long, jsFnHandle: Long, dangerousApi: Boolean): Any? {
        val adapterHandle = JavaObjectBridge.newJavaAdapter(classHandle, jsFnHandle, dangerousApi)
        if (adapterHandle == 0L) return null
        return JavaObjectBridge.getObject(adapterHandle)
    }

    // ============ 类加载 (Phase 3 Packages/JavaImporter 用) ============

    /**
     * 按完整类名加载 Class。
     * @return Class 对象或 null (类不存在或被安全名单拦截)
     */
    @JvmStatic
    fun loadJavaClass(fullName: String, dangerousApi: Boolean): Class<*>? {
        val handle = JavaObjectBridge.loadJavaClass(fullName, dangerousApi)
        if (handle == 0L) return null
        return JavaObjectBridge.getClass(handle)
    }

    /**
     * 检查类是否存在 (不注册句柄, 避免 has 泄漏)。
     */
    @JvmStatic
    fun classExists(fullName: String, dangerousApi: Boolean): Boolean {
        return JavaObjectBridge.classExists(fullName, dangerousApi)
    }

    /**
     * 判断 Class 是否为 interface (JavaAdapter 语法检测)。
     */
    @JvmStatic
    fun isInterface(classObj: Class<*>): Boolean {
        return classObj.isInterface
    }

    // ============ JavaAdapter (Phase 3 用) ============
    // newJavaAdapterRaw 已在上方"静态成员"区域定义 (按 classHandle 调用)

    // ============ 辅助函数 ============

    /**
     * 解包 JavaObjectBridge 返回的句柄 Map 为原始 Java 对象。
     * javaToJsResult 返回 mapOf("__java_handle__" to handle), 这里解包为原始对象。
     */
    private fun unwrapResult(result: Any?): Any? {
        if (result == null) return null
        if (result is Map<*, *>) {
            val handle = result["__java_handle__"]
            if (handle is Long && handle != 0L) {
                return JavaObjectBridge.getObject(handle)
            }
        }
        return result
    }
}
