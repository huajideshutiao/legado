package com.script.quickjs

import com.script.jsdispatch.JsValueConverters

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
 * - method callable 回调 (callMethodByObj) 接受 jobject, 返回原始 Java 对象。
 * - 静态成员/JavaAdapter 供 Phase 3 bootstrap 调用。
 *
 * 类型处理:
 * - Map: getProperty 返回 map[key], getPropertyNames 返回 keySet()
 * - List/Array: getProperty 返回 list[index], getPropertyNames 返回 0 until size
 * - 普通 Java 对象: 反射 field/method
 */
object JavaObjectBridgeNative {

    // ============ native exotic trap 回调 ============

    /**
     * hasProperty trap: 检查属性是否存在。
     * 哨兵协议下 getJavaPropertyRaw 返回 null 就是不存在, 任何非 null (包括 sentinel) 都是存在。
     */
    @JvmStatic
    fun hasProperty(obj: Any, name: String, dangerousApi: Boolean): Boolean {
        return JavaObjectBridge.getJavaPropertyRaw(obj, name, dangerousApi) != null
    }

    /**
     * getProperty trap: 查询属性, 返回值使用哨兵协议 (见 [JavaObjectBridge.METHOD_MARKER] /
     * [JavaObjectBridge.NULL_FIELD_MARKER]), native 侧用 IsSameObject 与全局引用对比。
     *
     * 协议:
     * - null → 属性不存在, native 沿原型链查
     * - METHOD_MARKER → 有同名方法, native 创建 method callable
     * - NULL_FIELD_MARKER → field 存在但值为 null, native 返回 JS_NULL
     * - 其他 → 该对象即 fieldValue, native 直接 fromJavaObject
     */
    @JvmStatic
    fun getPropertyInfo(obj: Any, name: String, dangerousApi: Boolean): Any? {
        val raw = JavaObjectBridge.getJavaPropertyRaw(obj, name, dangerousApi)
        // 哨兵必须原样返回供 native IsSameObject 判定; 实际属性值才做宿主→JS 转换。
        return when (raw) {
            null, JavaObjectBridge.METHOD_MARKER, JavaObjectBridge.NULL_FIELD_MARKER -> raw
            else -> JsValueConverters.convertAll(raw)
        }
    }

    /**
     * native 侧 JNI_OnLoad 用: 读取 METHOD_MARKER 全局引用, 缓存供 IsSameObject 判定。
     */
    @JvmStatic
    fun getMethodMarker(): Any = JavaObjectBridge.METHOD_MARKER

    /**
     * native 侧 JNI_OnLoad 用: 读取 NULL_FIELD_MARKER 全局引用, 缓存供 IsSameObject 判定。
     */
    @JvmStatic
    fun getNullFieldMarker(): Any = JavaObjectBridge.NULL_FIELD_MARKER

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

    /** 创建与源数组组件类型一致的新数组。 */
    @JvmStatic
    fun newArrayLike(source: Any, values: Array<Any?>): Any {
        val componentType = source.javaClass.componentType
            ?: throw IllegalArgumentException("Source is not an array")
        val result = java.lang.reflect.Array.newInstance(componentType, values.size)
        values.forEachIndexed { index, value ->
            java.lang.reflect.Array.set(result, index, coerceArrayElement(componentType, value))
        }
        return result
    }

    private fun coerceArrayElement(componentType: Class<*>, value: Any?): Any? {
        if (!componentType.isPrimitive || value == null) return value
        return when (componentType) {
            java.lang.Byte.TYPE -> (value as Number).toByte()
            java.lang.Short.TYPE -> (value as Number).toShort()
            java.lang.Integer.TYPE -> (value as Number).toInt()
            java.lang.Long.TYPE -> (value as Number).toLong()
            java.lang.Float.TYPE -> (value as Number).toFloat()
            java.lang.Double.TYPE -> (value as Number).toDouble()
            java.lang.Character.TYPE -> when (value) {
                is Char -> value
                is Number -> value.toInt().toChar()
                else -> value.toString().single()
            }

            java.lang.Boolean.TYPE -> value as Boolean
            else -> value
        }
    }

    // ============ method callable 回调 ============

    /**
     * method callable 回调 (Tier 2 共享 callable 唯一路径)。
     *
     * native 层的共享 method callable JS 函数被调用时, 通过 JNI 回调此方法。
     * 从 this_val 取 jobject 直接传入, 省掉 handle 往返与 IdentityHashMap 查询。
     *
     * callInstanceMethodRaw 内部已把 callHotTypeMethod 返回的句柄 Map 反解为原始对象,
     * 此处不需再 unwrap。native 层 fromJavaObject 直接消化 raw 结果。
     *
     * @return 原始 Java 对象 (native 层用 JniValueConvert.fromJavaObject 转换)
     */
    @JvmStatic
    fun callMethodByObj(
        obj: Any,
        methodName: String,
        args: Array<Any?>,
        dangerousApi: Boolean
    ): Any? {
        val raw = JavaObjectBridge.callInstanceMethodRaw(obj, methodName, args, dangerousApi)
        // 统一转换入口: Java 方法返回值跨入 JS 前, 先转 JS 原生值
        // (JsonElement → String/Number/Boolean/Map/List, 详见 JsValueConverters)
        return JsValueConverters.convertAll(raw)
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
        return JsValueConverters.convertAll(unwrapResult(result))
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
        return JsValueConverters.convertAll(unwrapResult(result))
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
        // 刚注册的 handle 紧接着 getObject 却查不到, 必然是生命周期 bug, 不应静默返回 null
        return JavaObjectBridge.getObject(objHandle)
            ?: throw IllegalStateException(
                "Newly created instance handle $objHandle not in objectMap (lifecycle bug)."
            )
    }

    /**
     * 创建 JavaAdapter (按 classHandle)。
     * BindingHandler.__newJavaAdapter 通过此方法调用, 返回原始 Java 代理对象。
     */
    @JvmStatic
    fun newJavaAdapterRaw(classHandle: Long, jsFnHandle: Long, dangerousApi: Boolean): Any? {
        val adapterHandle = JavaObjectBridge.newJavaAdapter(classHandle, jsFnHandle, dangerousApi)
        if (adapterHandle == 0L) return null
        // 刚注册的 handle 紧接着 getObject 却查不到, 必然是生命周期 bug, 不应静默返回 null
        return JavaObjectBridge.getObject(adapterHandle)
            ?: throw IllegalStateException(
                "Newly created adapter handle $adapterHandle not in objectMap (lifecycle bug)."
            )
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
        // 刚注册的 handle 紧接着 getClass 却查不到, 必然是生命周期 bug, 不应静默返回 null
        return JavaObjectBridge.getClass(handle)
            ?: throw IllegalStateException(
                "Newly loaded class handle $handle not in classMap (lifecycle bug)."
            )
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
     *
     * 注意: handle 失效 (getObject 返回 null) 时抛异常而非静默返回 null。
     * result 是 Map 句柄格式说明刚注册过 handle, 紧接着 getObject 却查不到,
     * 必然是 handle 生命周期 bug (如 releaseNewHandles 提前释放)。
     * 静默返回 null 会让调用方误判 (如 getStaticField 误认为字段不存在,
     * __wrapClass 落到 method callable 兜底, 把枚举常量变成 function)。
     */
    private fun unwrapResult(result: Any?): Any? {
        if (result == null) return null
        if (result is Map<*, *>) {
            val handle = result["__java_handle__"]
            if (handle is Long && handle != 0L) {
                val obj = JavaObjectBridge.getObject(handle) ?: throw IllegalStateException(
                    "Java object handle $handle released before unwrapResult " +
                        "(objectMap miss). This is a handle lifecycle bug."
                )
                return obj
            }
        }
        return result
    }
}
