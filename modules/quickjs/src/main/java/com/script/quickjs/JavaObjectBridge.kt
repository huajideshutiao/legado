package com.script.quickjs

import android.util.Log
import androidx.collection.LongSparseArray
import com.script.quickjs.JavaObjectBridge.jsToJavaArgs
import com.script.quickjs.JavaObjectBridge.jsToJavaValue
import com.script.quickjs.JavaObjectBridge.loadJavaClass
import com.script.quickjs.JavaObjectBridge.releaseScope
import java.lang.reflect.InvocationHandler
import java.lang.reflect.Method
import java.lang.reflect.Modifier
import java.lang.reflect.Proxy
import java.util.concurrent.atomic.AtomicLong

/**
 * Java 对象句柄管理 + 反射桥接。
 *
 * QuickJS 端无法直接持有 Java 对象引用,所有 Java 对象都通过 Long 句柄访问。
 * 本类负责:
 * 1. 注册 Java 对象/Class,返回 Long 句柄
 * 2. 通过句柄查找对象
 * 3. 反射调用实例/静态方法
 * 4. 反射获取/设置字段
 * 5. 反射实例化对象
 * 6. 创建 JavaAdapter(java.lang.reflect.Proxy)
 *
 * 资源管理: 句柄按 [QuickJsContext.scopeId] 分组, [releaseScope] 释放某 scope 全部句柄,
 * 由 [QuickJsContext.close] 触发。避免长期运行累积内存泄漏。
 *
 * 安全名单:所有类访问/方法调用都经过 [JsSecurityPolicy] 检查,
 * dangerousApi = true 时旁路。
 */
object JavaObjectBridge {

    private const val TAG = "JavaObjectBridge"

    private val handleCounter = AtomicLong(1L)
    private val objectMap = LongSparseArray<Any>()  // handle -> Java 对象
    private val classMap = LongSparseArray<Class<*>>()  // handle -> Class 对象
    private val adapterMap = LongSparseArray<Any>()  // handle -> JavaAdapter 代理对象

    /** scopeId -> 该 scope 注册的句柄集合,close 时批量释放 */
    private val scopeHandles = LongSparseArray<MutableSet<Long>>()
    private val lock = Any()

    /**
     * 把 handle 记录到当前线程的 scope,close 时批量释放。
     * 调用方需在 [QuickJsEngine.eval] / [getRuntimeScope] / [injectBindings] 期间调用,
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

    /**
     * 注册 Java 对象,返回句柄。
     */
    fun registerObject(obj: Any): Long {
        val handle = handleCounter.getAndIncrement()
        synchronized(lock) {
            objectMap.put(handle, obj)
        }
        trackHandle(handle)
        return handle
    }

    /**
     * 注册 Class 对象,返回句柄。
     */
    fun registerClass(clazz: Class<*>): Long {
        val handle = handleCounter.getAndIncrement()
        synchronized(lock) {
            classMap.put(handle, clazz)
        }
        trackHandle(handle)
        return handle
    }

    /**
     * 按完整类名加载 Class。
     *
     * @return Class 句柄(>0)或 0(类不存在或被安全名单拦截)
     */
    fun loadJavaClass(fullClassName: String, dangerousApi: Boolean): Long {
        if (!JsSecurityPolicy.isClassVisible(fullClassName, dangerousApi)) {
            return 0L
        }
        return try {
            // 处理数组类名 [Ljava.lang.String;
            val clazz = if (fullClassName.startsWith("[")) {
                Class.forName(fullClassName)
            } else {
                Class.forName(fullClassName, false, this.javaClass.classLoader)
            }
            registerClass(clazz)
        } catch (e: ClassNotFoundException) {
            0L
        } catch (e: LinkageError) {
            // NoClassDefFoundError / ExceptionInInitializerError 等,类加载失败
            Log.w(TAG, "loadJavaClass failed: $fullClassName", e)
            0L
        }
    }

    /**
     * 查找对象句柄对应的 Java 对象。
     */
    fun getObject(handle: Long): Any? {
        synchronized(lock) {
            return objectMap.get(handle) ?: adapterMap.get(handle)
        }
    }

    /**
     * 查找 Class 句柄对应的 Class 对象。
     */
    fun getClass(handle: Long): Class<*>? {
        synchronized(lock) {
            return classMap.get(handle)
        }
    }

    /**
     * 仅检查类是否存在(可见性 + Class.forName),不注册句柄。
     *
     * 用于 JavaImporter.has trap:在 with 语句中需要判断"包下是否有此类",
     * 但不能预注册句柄(否则即使属性从未访问,句柄也会累积,造成泄漏)。
     * 用此方法做"探测",真正访问时再走 [loadJavaClass] 注册。
     */
    fun classExists(fullClassName: String, dangerousApi: Boolean): Boolean {
        if (!JsSecurityPolicy.isClassVisible(fullClassName, dangerousApi)) {
            return false
        }
        return try {
            if (fullClassName.startsWith("[")) {
                Class.forName(fullClassName)
            } else {
                Class.forName(fullClassName, false, this.javaClass.classLoader)
            }
            true
        } catch (_: ClassNotFoundException) {
            false
        } catch (_: LinkageError) {
            false
        }
    }

    /**
     * 判断 classHandle 对应的 Class 是否为接口。
     *
     * 用于 `new Interface(implObj)` JavaAdapter 语法检测:
     * 仅当 Class 是 interface 且 args[0] 是 JS 对象(非 Java 句柄)时,走 JavaAdapter 路径。
     */
    fun isInterface(classHandle: Long, dangerousApi: Boolean): Boolean {
        val clazz = getClass(classHandle) ?: return false
        if (!JsSecurityPolicy.isClassVisible(clazz, dangerousApi)) return false
        return clazz.isInterface
    }

    /**
     * 实例化 Java 对象。
     *
     * @param classHandle [loadJavaClass] 返回的句柄
     * @param args 构造参数(JS 侧传入,需要 [jsToJavaArgs] 转换)
     * @return 新对象句柄(>0)或 0(失败)
     */
    fun newJavaInstance(classHandle: Long, args: Array<Any?>, dangerousApi: Boolean): Long {
        val clazz = getClass(classHandle) ?: return 0L
        if (!JsSecurityPolicy.isClassVisible(clazz, dangerousApi)) return 0L
        return try {
            val javaArgs = jsToJavaArgs(args)
            val instance = createInstance(clazz, javaArgs)
            if (instance != null && JsSecurityPolicy.isObjectVisible(instance, dangerousApi)) {
                registerObject(instance)
            } else 0L
        } catch (e: Exception) {
            Log.w(TAG, "newJavaInstance failed: ${clazz.name}", e)
            0L
        } catch (e: LinkageError) {
            Log.w(TAG, "newJavaInstance linkage error: ${clazz.name}", e)
            0L
        }
    }

    /**
     * 反射查找匹配的构造器并实例化。
     */
    private fun createInstance(clazz: Class<*>, args: Array<Any?>): Any? {
        val constructors = clazz.constructors
        // 精确匹配参数个数
        val candidates = constructors.filter { it.parameterCount == args.size }
        if (candidates.isEmpty()) {
            // 尝试无参构造
            if (args.isEmpty()) {
                return clazz.getDeclaredConstructor().takeIf {
                    Modifier.isPublic(it.modifiers)
                }?.newInstance()
            }
            return null
        }
        // 找第一个参数类型全兼容的构造器
        for (ctor in candidates) {
            if (isArgsCompatible(ctor.parameterTypes, args)) {
                ctor.isAccessible = true
                return ctor.newInstance(*coerceArgs(ctor.parameterTypes, args))
            }
        }
        return null
    }

    /**
     * 调用实例方法。
     */
    fun callInstanceMethod(
        objHandle: Long,
        methodName: String,
        args: Array<Any?>,
        dangerousApi: Boolean
    ): Any? {
        val obj = getObject(objHandle) ?: return null
        if (!JsSecurityPolicy.isObjectVisible(obj, dangerousApi)) return null
        if (!JsSecurityPolicy.isMethodVisible(obj.javaClass.name, methodName, dangerousApi)) {
            return null
        }
        return try {
            val javaArgs = jsToJavaArgs(args)
            val method = findMethod(obj.javaClass, methodName, javaArgs)
            if (method != null) {
                method.isAccessible = true
                val result = method.invoke(obj, *coerceArgs(method.parameterTypes, javaArgs))
                return javaToJsResult(result, dangerousApi)
            }
            // 找不到方法时尝试 getter: getXxx / isXxx (兼容 rhino 的 obj.prop() 语法调用 getter)
            if (args.isEmpty()) {
                val getterName = buildString(methodName.length + 3) {
                    append("get")
                    if (methodName.isNotEmpty()) {
                        methodName.first().uppercaseChar().let { append(it) }
                        append(methodName.substring(1))
                    }
                }
                val getter = findMethod(obj.javaClass, getterName, emptyArray())
                if (getter != null) {
                    getter.isAccessible = true
                    val result = getter.invoke(obj)
                    return javaToJsResult(result, dangerousApi)
                }
                val isName = buildString(methodName.length + 2) {
                    append("is")
                    if (methodName.isNotEmpty()) {
                        methodName.first().uppercaseChar().let { append(it) }
                        append(methodName.substring(1))
                    }
                }
                val isGetter = findMethod(obj.javaClass, isName, emptyArray())
                if (isGetter != null) {
                    isGetter.isAccessible = true
                    val result = isGetter.invoke(obj)
                    return javaToJsResult(result, dangerousApi)
                }
            }
            null
        } catch (e: Exception) {
            Log.w(TAG, "callInstanceMethod failed: ${obj.javaClass.name}.$methodName", e)
            null
        } catch (e: LinkageError) {
            Log.w(TAG, "callInstanceMethod linkage error: ${obj.javaClass.name}.$methodName", e)
            null
        }
    }

    /**
     * 检查实例方法是否存在(不触发调用)。
     *
     * 用于 JS Proxy get 判断 field+method 同名场景(如 StrResponse.body):
     * - 有同名 method → 返回 FieldAndMessages 风格 callable(method 优先,与 rhino LiveConnect 一致)
     * - 无同名 method → 返回纯 field/getter 值
     *
     * 只检查方法名为 [methodName] 的 public 方法(不包括 getXxx/isXxx getter,因为名字不同)。
     */
    fun hasInstanceMethod(
        objHandle: Long,
        methodName: String,
        dangerousApi: Boolean
    ): Boolean {
        val obj = getObject(objHandle) ?: return false
        if (!JsSecurityPolicy.isObjectVisible(obj, dangerousApi)) return false
        if (!JsSecurityPolicy.isMethodVisible(obj.javaClass.name, methodName, dangerousApi)) {
            return false
        }
        val candidates = mutableListOf<Method>()
        collectMethods(obj.javaClass, methodName, candidates)
        return candidates.isNotEmpty()
    }

    /**
     * 获取 Java 对象的所有可枚举属性名(与 rhino NativeJavaObject.getIds() 行为一致)。
     *
     * 用于 JS Proxy 的 ownKeys trap,让 Object.entries/Object.keys 能枚举 Java 对象属性。
     *
     * 返回值类型规则(与 rhino 一致):
     * - Map: 返回 keySet() (key 转 String),对应 rhino NativeJavaMap.getIds()
     * - List/Array: 返回 0 until size 的字符串索引,对应 rhino NativeJavaList.getIds()
     * - JsonObject (gson): 返回 entrySet() 的 key (gson JsonObject 未实现 Map 接口,
     *   但 rhino LiveConnect 下通过 entrySet/keySet 方法可枚举)
     * - 普通 Java 对象: 返回所有 public method name + public field name (去重),
     *   对应 rhino NativeJavaObject.getIds() 返回 members.getIds(false)
     */
    fun getInstanceKeys(objHandle: Long, dangerousApi: Boolean): Array<String> {
        val obj = getObject(objHandle) ?: return emptyArray()
        if (!JsSecurityPolicy.isObjectVisible(obj, dangerousApi)) return emptyArray()
        return try {
            getInstanceKeysImpl(obj, dangerousApi)
        } catch (e: Exception) {
            Log.w(TAG, "getInstanceKeys failed: ${obj.javaClass.name}", e)
            emptyArray()
        } catch (e: LinkageError) {
            Log.w(TAG, "getInstanceKeys linkage error: ${obj.javaClass.name}", e)
            emptyArray()
        }
    }

    private fun getInstanceKeysImpl(obj: Any, dangerousApi: Boolean): Array<String> {
        // Map: 返回 keySet() (与 rhino NativeJavaMap.getIds() 一致)
        // 注意: net.minidev.json.JSONObject (jayway jsonpath 默认) 实现了 Map 接口,
        // 会走这个分支,使 Object.entries(map) 能正确返回 [[key, value], ...]
        if (obj is Map<*, *>) {
            return obj.keys.map { it?.toString() ?: "null" }.toTypedArray()
        }
        // List: 返回 0 until size 的字符串索引 (与 rhino NativeJavaList.getIds() 一致)
        if (obj is List<*>) {
            return (0 until obj.size).map { it.toString() }.toTypedArray()
        }
        // Java 数组: 返回索引
        if (obj.javaClass.isArray) {
            val len = java.lang.reflect.Array.getLength(obj)
            return (0 until len).map { it.toString() }.toTypedArray()
        }
        // JsonObject (gson): 没有实现 Map,但有 entrySet()/keySet() 方法
        // 通过反射调用 keySet(),使 Object.entries(jsonObject) 能返回 key-value 对
        // (gson JsonObject 在 rhino 下也会被特殊处理以支持 key 枚举)
        val className = obj.javaClass.name
        if (className == "com.google.gson.JsonObject") {
            val keySetMethod = try {
                obj.javaClass.getMethod("keySet")
            } catch (_: NoSuchMethodException) {
                null
            }
            if (keySetMethod != null) {
                val keySet = keySetMethod.invoke(obj) as? Set<*>
                if (keySet != null) {
                    return keySet.map { it?.toString() ?: "null" }.toTypedArray()
                }
            }
        }
        // 普通 Java 对象: 返回所有 public method name + public field name (去重)
        // 与 rhino NativeJavaObject.getIds() 返回 members.getIds(false) 一致
        // (members 包含所有 field 和 method,去重后返回)
        val names = linkedSetOf<String>()
        try {
            obj.javaClass.methods.forEach { m ->
                if (JsSecurityPolicy.isMethodVisible(obj.javaClass.name, m.name, dangerousApi)) {
                    names.add(m.name)
                }
            }
        } catch (_: Throwable) {
        }
        try {
            var c: Class<*>? = obj.javaClass
            while (c != null) {
                c.declaredFields.forEach { f ->
                    if (Modifier.isPublic(f.modifiers)) {
                        names.add(f.name)
                    }
                }
                c = c.superclass
            }
        } catch (_: Throwable) {
        }
        return names.toTypedArray()
    }

    /**
     * 调用静态方法。
     */
    fun callStaticMethod(
        classHandle: Long,
        methodName: String,
        args: Array<Any?>,
        dangerousApi: Boolean
    ): Any? {
        val clazz = getClass(classHandle) ?: return null
        if (!JsSecurityPolicy.isClassVisible(clazz, dangerousApi)) return null
        if (!JsSecurityPolicy.isMethodVisible(clazz.name, methodName, dangerousApi)) return null
        return try {
            val javaArgs = jsToJavaArgs(args)
            val method = findStaticMethod(clazz, methodName, javaArgs) ?: return null
            method.isAccessible = true
            val result = method.invoke(null, *coerceArgs(method.parameterTypes, javaArgs))
            javaToJsResult(result, dangerousApi)
        } catch (e: Exception) {
            Log.w(TAG, "callStaticMethod failed: ${clazz.name}.$methodName", e)
            null
        } catch (e: LinkageError) {
            Log.w(TAG, "callStaticMethod linkage error: ${clazz.name}.$methodName", e)
            null
        }
    }

    /**
     * 获取实例字段。
     *
     * 与 Rhino LiveConnect 行为一致:
     * - Map: map.key -> map.get(key) (对应 rhino FEATURE_ENABLE_JAVA_MAP_ACCESS)
     * - List/Array: list[i] -> list.get(i) / array[i], list.length -> size (对应 rhino NativeJavaList)
     * - 普通 Java 对象: 优先 getter(getXxx/isXxx),找不到再直接反射字段,
     *   这样可访问 Kotlin 接口属性(如 BaseBook.bookUrl,只有 private backing field)。
     */
    fun getInstanceField(objHandle: Long, fieldName: String, dangerousApi: Boolean): Any? {
        val obj = getObject(objHandle) ?: return null
        if (!JsSecurityPolicy.isObjectVisible(obj, dangerousApi)) return null
        // Map/List/Array 特判: 对齐 rhino FEATURE_ENABLE_JAVA_MAP_ACCESS
        // 让 bindings 注入的 Map/List 在 JS 端 map.key / list[0] / list.length 可访问
        getCollectionField(obj, fieldName)?.let { return javaToJsResult(it, dangerousApi) }
        return try {
            // 优先 getter
            val getter = findGetter(obj.javaClass, fieldName)
            if (getter != null) {
                if (!JsSecurityPolicy.isMethodVisible(
                        obj.javaClass.name,
                        getter.name,
                        dangerousApi
                    )
                ) return null
                getter.isAccessible = true
                return javaToJsResult(getter.invoke(obj), dangerousApi)
            }
            // 再尝试字段
            val field = findField(obj.javaClass, fieldName) ?: return null
            field.isAccessible = true
            javaToJsResult(field.get(obj), dangerousApi)
        } catch (e: Exception) {
            Log.w(TAG, "getInstanceField failed: ${obj.javaClass.name}.$fieldName", e)
            null
        } catch (e: LinkageError) {
            Log.w(TAG, "getInstanceField linkage error: ${obj.javaClass.name}.$fieldName", e)
            null
        }
    }

    /**
     * Map/List/Array 的字段访问特判,模拟 rhino FEATURE_ENABLE_JAVA_MAP_ACCESS。
     *
     * - Map: 若包含 key 返回 value,不含则返回 null(继续走 getter/field 逻辑)
     * - List/Array: "length" 返回长度(field 别名,与 rhino NativeJavaList 一致);
     *   数字索引返回对应元素
     *
     * 注意: 不把 "size" 当作 field 别名。rhino 中 size 是 method,
     * `list.size` 返回 method callable,`list.size()` 调用返回长度。
     * 若把 size 也当 field 别名,`list.size()` 会因调用 Number 而失败。
     *
     * 返回 null 表示非集合或未命中,调用方继续走普通字段反射。
     */
    private fun getCollectionField(obj: Any, fieldName: String): Any? {
        when (obj) {
            is Map<*, *> -> {
                if (obj.containsKey(fieldName)) return obj[fieldName]
                return null
            }

            is List<*> -> {
                if (fieldName == "length") return obj.size
                val idx = fieldName.toIntOrNull() ?: return null
                if (idx in 0 until obj.size) return obj[idx]
                return null
            }

            else -> if (obj.javaClass.isArray) {
                if (fieldName == "length") {
                    return java.lang.reflect.Array.getLength(obj)
                }
                val idx = fieldName.toIntOrNull() ?: return null
                val len = java.lang.reflect.Array.getLength(obj)
                if (idx in 0 until len) return java.lang.reflect.Array.get(obj, idx)
                return null
            }
        }
        return null
    }

    /**
     * 获取静态字段。
     */
    fun getStaticField(classHandle: Long, fieldName: String, dangerousApi: Boolean): Any? {
        val clazz = getClass(classHandle) ?: return null
        if (!JsSecurityPolicy.isClassVisible(clazz, dangerousApi)) return null
        return try {
            val field = findField(clazz, fieldName) ?: return null
            field.isAccessible = true
            javaToJsResult(field.get(null), dangerousApi)
        } catch (e: Exception) {
            Log.w(TAG, "getStaticField failed: ${clazz.name}.$fieldName", e)
            null
        } catch (e: LinkageError) {
            Log.w(TAG, "getStaticField linkage error: ${clazz.name}.$fieldName", e)
            null
        }
    }

    /**
     * 设置实例字段。
     *
     * 与 Rhino LiveConnect 行为一致:
     * - MutableMap: map.key = value -> map.put(key, value)
     * - MutableList: list[i] = value -> list.set(i, value)
     * - 普通 Java 对象: 优先 setter(setXxx),找不到再直接反射字段。
     */
    fun setInstanceField(
        objHandle: Long,
        fieldName: String,
        value: Any?,
        dangerousApi: Boolean
    ): Boolean {
        val obj = getObject(objHandle) ?: return false
        if (!JsSecurityPolicy.isObjectVisible(obj, dangerousApi)) return false
        // Map/List 特判: 对齐 rhino FEATURE_ENABLE_JAVA_MAP_ACCESS
        if (setCollectionField(obj, fieldName, value)) return true
        return try {
            // 优先 setter
            val setter = findSetter(obj.javaClass, fieldName)
            if (setter != null) {
                if (!JsSecurityPolicy.isMethodVisible(
                        obj.javaClass.name,
                        setter.name,
                        dangerousApi
                    )
                ) return false
                setter.isAccessible = true
                setter.invoke(obj, jsToJavaValue(value, setter.parameterTypes[0]))
                return true
            }
            // 再尝试字段
            val field = findField(obj.javaClass, fieldName) ?: return false
            field.isAccessible = true
            field.set(obj, jsToJavaValue(value, field.type))
            true
        } catch (e: Exception) {
            Log.w(TAG, "setInstanceField failed: ${obj.javaClass.name}.$fieldName", e)
            false
        } catch (e: LinkageError) {
            Log.w(TAG, "setInstanceField linkage error: ${obj.javaClass.name}.$fieldName", e)
            false
        }
    }

    /**
     * MutableMap/MutableList 的字段设置特判。
     * value 先经 [jsToJavaValue] 解引用(处理 JS Proxy 句柄)。
     * @return true 表示已处理(集合命中),false 表示非集合或未命中(继续走 setter/field)
     */
    private fun setCollectionField(obj: Any, fieldName: String, value: Any?): Boolean {
        val javaValue = jsToJavaValue(value, null)
        when (obj) {
            is MutableMap<*, *> -> {
                @Suppress("UNCHECKED_CAST")
                (obj as MutableMap<String, Any?>)[fieldName] = javaValue
                return true
            }

            is MutableList<*> -> {
                val idx = fieldName.toIntOrNull() ?: return false
                if (idx in 0 until obj.size) {
                    @Suppress("UNCHECKED_CAST")
                    (obj as MutableList<Any?>)[idx] = javaValue
                    return true
                }
                return false
            }
        }
        return false
    }

    /**
     * 设置静态字段。
     */
    fun setStaticField(
        classHandle: Long,
        fieldName: String,
        value: Any?,
        dangerousApi: Boolean
    ): Boolean {
        val clazz = getClass(classHandle) ?: return false
        if (!JsSecurityPolicy.isClassVisible(clazz, dangerousApi)) return false
        return try {
            val field = findField(clazz, fieldName) ?: return false
            field.isAccessible = true
            field.set(null, jsToJavaValue(value, field.type))
            true
        } catch (e: Exception) {
            Log.w(TAG, "setStaticField failed: ${clazz.name}.$fieldName", e)
            false
        } catch (e: LinkageError) {
            Log.w(TAG, "setStaticField linkage error: ${clazz.name}.$fieldName", e)
            false
        }
    }

    /**
     * 创建 JavaAdapter(java.lang.reflect.Proxy 实现 Java 接口)。
     *
     * JS 侧用法:`new android.view.View.OnClickListener({ onClick: function(v) {...} })`
     * JS 对象的方法名 -> Java 接口方法名,调用时回调到 JS function。
     *
     * @param classHandle 接口 Class 句柄
     * @param jsObjectHandle JS 对象句柄(由 [JsFunctionHandle] 管理,调用时通过 QuickJs evaluate 执行)
     * @return 代理对象句柄(>0)或 0(失败)
     */
    fun newJavaAdapter(
        classHandle: Long,
        jsObjectHandle: Long,
        dangerousApi: Boolean
    ): Long {
        val clazz = getClass(classHandle) ?: return 0L
        if (!JsSecurityPolicy.isClassVisible(clazz, dangerousApi)) return 0L
        if (!clazz.isInterface) return 0L

        val jsHandler = JsFunctionHandle.get(jsObjectHandle) ?: return 0L

        // InvocationHandler 第一参数即 proxy 对象,用其计算 hashCode/equals
        // (修复旧实现 this 误指 JavaObjectBridge 单例导致所有 adapter 共享 hashCode、equals 永远 false)
        val handler = InvocationHandler { proxy, method, args ->
            // Object 方法直接转发到代理对象
            if (method.declaringClass == Any::class.java) {
                return@InvocationHandler when (method.name) {
                    "hashCode" -> System.identityHashCode(proxy)
                    "equals" -> proxy === args?.getOrNull(0)
                    "toString" -> "JavaAdapter@${jsObjectHandle}"
                    else -> null
                }
            }
            // 调用 JS 侧的方法
            jsHandler.invokeJsMethod(method.name, args ?: emptyArray())
        }
        val proxy = Proxy.newProxyInstance(
            this.javaClass.classLoader,
            arrayOf(clazz),
            handler
        )
        val handle = handleCounter.getAndIncrement()
        synchronized(lock) {
            adapterMap.put(handle, proxy)
        }
        trackHandle(handle)
        return handle
    }

    /**
     * 释放单个句柄。
     */
    fun releaseHandle(handle: Long) {
        synchronized(lock) {
            objectMap.remove(handle)
            classMap.remove(handle)
            adapterMap.remove(handle)
        }
    }

    /**
     * 释放某 scope 注册的全部句柄,由 [QuickJsContext.close] 调用。
     *
     * 避免全局 clearAll 影响其他活跃 scope(旧实现 clearAll 会清空所有句柄)。
     */
    fun releaseScope(scopeId: Long) {
        synchronized(lock) {
            val handles = scopeHandles.get(scopeId) ?: return
            handles.forEach { h ->
                objectMap.remove(h)
                classMap.remove(h)
                adapterMap.remove(h)
            }
            scopeHandles.remove(scopeId)
        }
    }

    /**
     * 清空所有句柄(仅用于进程退出或测试,正常路径请用 [releaseScope])。
     */
    fun clearAll() {
        synchronized(lock) {
            objectMap.clear()
            classMap.clear()
            adapterMap.clear()
            scopeHandles.clear()
        }
    }

    // ============ 反射辅助 ============

    private fun findMethod(clazz: Class<*>, name: String, args: Array<Any?>): Method? {
        // 收集本类 + 父类 + 接口的 public 方法
        val candidates = mutableListOf<Method>()
        collectMethods(clazz, name, candidates)
        // 按参数个数过滤
        val matched = candidates.filter { it.parameterCount == args.size }
        if (matched.isEmpty()) return null
        // 找参数类型全兼容的重载
        val compatible = matched.filter { isArgsCompatible(it.parameterTypes, args) }
        if (compatible.isEmpty()) return matched.first()
        if (compatible.size == 1) return compatible.first()
        // 多个兼容重载时,按参数类型具体程度选择最优(模拟 rhino 的 NativeJavaMethod 行为)
        // 背景: JS Number 经 quickjs-kt 传入是 Double,String.valueOf(Object) 会抢先返回 "123.0"
        // 应优先匹配 String.valueOf(int) 返回 "123",与 rhino 行为一致
        return compatible.minByOrNull { m ->
            m.parameterTypes.indices.sumOf { i ->
                paramSpecificityScore(m.parameterTypes[i], args[i])
            }
        } ?: compatible.first()
    }

    /**
     * 方法参数类型具体程度评分(越低越优先)。
     *
     * 模拟 rhino 的重载选择:
     * - 精确类型匹配(含 primitive vs wrapper) > 父类型 > Number->数值基本类型 > Object
     * - 整数值 Number 优先匹配 int/long(如 String.valueOf(123) -> "123" 而非 "123.0")
     * - 非整数值 Number 优先匹配 double/float
     */
    private fun paramSpecificityScore(paramType: Class<*>, arg: Any?): Int {
        if (arg == null) return if (paramType.isPrimitive) 10 else 0
        // Object 参数优先级最低,避免 String.valueOf(Object) 抢先
        if (paramType == Any::class.java || paramType == Object::class.java) return 10
        // 精确类型匹配
        if (paramType == arg.javaClass) return 0
        // primitive vs wrapper 视为精确匹配(int 参数 vs Integer arg)
        if (paramType.isPrimitive && isPrimitiveWrapperOf(paramType, arg.javaClass)) return 0
        if (paramType.isAssignableFrom(arg.javaClass)) return 1
        // Number -> 数值基本类型:根据是否整数值优先匹配
        if (arg is Number && paramType.isPrimitive) {
            val isIntValue = isIntegerNumber(arg)
            return when (paramType) {
                Int::class.javaPrimitiveType, Long::class.javaPrimitiveType ->
                    if (isIntValue) 2 else 4

                Double::class.javaPrimitiveType, Float::class.javaPrimitiveType ->
                    if (isIntValue) 3 else 2

                else -> 5
            }
        }
        return 5
    }

    private fun isPrimitiveWrapperOf(primitiveType: Class<*>, wrapperType: Class<*>): Boolean {
        if (!primitiveType.isPrimitive) return false
        return when (primitiveType) {
            Int::class.javaPrimitiveType -> wrapperType == Int::class.java
            Long::class.javaPrimitiveType -> wrapperType == Long::class.java
            Boolean::class.javaPrimitiveType -> wrapperType == Boolean::class.java
            Double::class.javaPrimitiveType -> wrapperType == Double::class.java
            Float::class.javaPrimitiveType -> wrapperType == Float::class.java
            Short::class.javaPrimitiveType -> wrapperType == Short::class.java
            Byte::class.javaPrimitiveType -> wrapperType == Byte::class.java
            Char::class.javaPrimitiveType -> wrapperType == Char::class.java
            else -> false
        }
    }

    private fun isIntegerNumber(num: Number): Boolean {
        return when (num) {
            is Int, is Long, is Short, is Byte -> true
            is Double -> num == num.toInt().toDouble()
            is Float -> num == num.toInt().toFloat()
            else -> false
        }
    }

    private fun collectMethods(clazz: Class<*>, name: String, out: MutableList<Method>) {
        try {
            clazz.methods.forEach { m ->
                if (m.name == name) out.add(m)
            }
        } catch (_: Throwable) {
        }
    }

    private fun findStaticMethod(clazz: Class<*>, name: String, args: Array<Any?>): Method? {
        val m = findMethod(clazz, name, args) ?: return null
        return if (Modifier.isStatic(m.modifiers)) m else null
    }

    private fun findField(clazz: Class<*>, name: String): java.lang.reflect.Field? {
        var c: Class<*>? = clazz
        while (c != null) {
            try {
                // 不限制 public: Kotlin data class 的 backing field 通常是 private,
                // 调用方会 setAccessible(true)。与 Rhino LiveConnect 行为一致(通过 getter/setter 访问)
                return c.getDeclaredField(name)
            } catch (_: NoSuchFieldException) {
            }
            c = c.superclass
        }
        return null
    }

    /**
     * 查找 getter 方法(getXxx/isXxx),与 Rhino LiveConnect 行为一致。
     *
     * Kotlin 接口属性(如 BaseBook.bookUrl)在实现类中只有 private backing field,
     * 直接反射字段需要 setAccessible。优先用 getter 访问更符合 JavaBean 规范。
     */
    private fun findGetter(clazz: Class<*>, name: String): Method? {
        val capName =
            name.replaceFirstChar { if (it.isLowerCase()) it.titlecase() else it.toString() }
        val getterNames = listOf("get$capName", "is$capName")
        var c: Class<*>? = clazz
        while (c != null) {
            for (getterName in getterNames) {
                try {
                    val m = c.getDeclaredMethod(getterName)
                    if (m.parameterCount == 0 && !Modifier.isStatic(m.modifiers)) return m
                } catch (_: NoSuchMethodException) {
                }
            }
            c = c.superclass
        }
        return null
    }

    /**
     * 查找 setter 方法(setXxx),与 Rhino LiveConnect 行为一致。
     */
    private fun findSetter(clazz: Class<*>, name: String): Method? {
        val capName =
            name.replaceFirstChar { if (it.isLowerCase()) it.titlecase() else it.toString() }
        val setterName = "set$capName"
        var c: Class<*>? = clazz
        while (c != null) {
            try {
                val m = c.getDeclaredMethod(setterName)
                if (m.parameterCount == 1 && !Modifier.isStatic(m.modifiers)) return m
            } catch (_: NoSuchMethodException) {
            }
            c = c.superclass
        }
        return null
    }

    private fun isArgsCompatible(paramTypes: Array<Class<*>>, args: Array<Any?>): Boolean {
        if (paramTypes.size != args.size) return false
        for (i in paramTypes.indices) {
            val arg = args[i]
            val paramType = paramTypes[i]
            if (arg == null) {
                if (paramType.isPrimitive) return false
                continue
            }
            val argType = arg.javaClass
            if (!paramType.isAssignableFrom(argType)) {
                // 尝试基本类型 + 包装类兼容
                if (!isPrimitiveCompatible(paramType, argType)) return false
            }
        }
        return true
    }

    private fun isPrimitiveCompatible(paramType: Class<*>, argType: Class<*>): Boolean {
        if (!paramType.isPrimitive) return false
        // Number 子类(Int/Long/Double/Float/Short/Byte)可兼容所有数值基本类型,
        // coerceArgs/coerceValue 会做实际转换。
        // 背景: JS 数字经 quickjs-kt 传入通常是 Double,导致 Point(int,int) 这类构造器匹配失败。
        if (Number::class.java.isAssignableFrom(argType)) {
            return when (paramType) {
                Int::class.javaPrimitiveType,
                Long::class.javaPrimitiveType,
                Double::class.javaPrimitiveType,
                Float::class.javaPrimitiveType,
                Short::class.javaPrimitiveType,
                Byte::class.javaPrimitiveType -> true

                else -> false
            }
        }
        val wrapper = when (paramType) {
            Int::class.javaPrimitiveType -> Int::class.java
            Long::class.javaPrimitiveType -> Long::class.java
            Boolean::class.javaPrimitiveType -> Boolean::class.java
            Double::class.javaPrimitiveType -> Double::class.java
            Float::class.javaPrimitiveType -> Float::class.java
            Short::class.javaPrimitiveType -> Short::class.java
            Byte::class.javaPrimitiveType -> Byte::class.java
            Char::class.javaPrimitiveType -> Char::class.java
            else -> return false
        }
        return wrapper.isAssignableFrom(argType)
    }

    /**
     * 把 JS 侧传入的参数转换为 Java 类型(处理句柄 -> 对象 解引用)。
     */
    private fun jsToJavaArgs(args: Array<Any?>): Array<Any?> {
        return Array(args.size) { i -> jsToJavaValue(args[i], null) }
    }

    /**
     * JS 值转 Java 值。
     *
     * 句柄对象(包含 __java_handle__ 字段)会解引用为原始 Java 对象。
     * 其它类型原样返回(targetType 为 null 时)或尝试强制转换。
     */
    private fun jsToJavaValue(value: Any?, targetType: Class<*>?): Any? {
        if (value == null) return null
        // 句柄对象解引用
        val handle = extractLongField(value, "__java_handle__")
        if (handle != 0L) {
            val obj = getObject(handle)
            if (obj != null && targetType != null && !targetType.isAssignableFrom(obj.javaClass)) {
                // 类型不匹配,尝试返回 null 让上层报错
                if (!isPrimitiveCompatible(targetType, obj.javaClass)) return null
            }
            return obj ?: value
        }
        // Class 句柄对象解引用
        val classHandle = extractLongField(value, "__java_class_handle__")
        if (classHandle != 0L) {
            return getClass(classHandle)
        }
        // 字符串/数字/布尔等基本类型直接返回
        return value
    }

    /**
     * 从 JS 对象 Map 中提取 Long 字段(用于句柄解引用)。
     *
     * quickjs-kt 把 JS 对象转成 Kotlin Map,字段名通过 [field] 查询。
     * 兼容 Long/Number 两种存储形式。
     */
    private fun extractLongField(value: Any?, field: String): Long {
        if (value == null) return 0L
        if (value is Map<*, *>) {
            val h = value[field]
            return (h as? Long) ?: (h as? Number)?.toLong() ?: 0L
        }
        return 0L
    }

    /**
     * 强制转换参数到目标类型(基本类型 + 包装类)。
     */
    private fun coerceArgs(paramTypes: Array<Class<*>>, args: Array<Any?>): Array<Any?> {
        return Array(args.size) { i ->
            val v = args[i]
            val t = paramTypes[i]
            if (v == null) {
                if (t.isPrimitive) 0 else null
            } else {
                coerceValue(v, t)
            }
        }
    }

    @Suppress("UNCHECKED_CAST")
    private fun coerceValue(value: Any, targetType: Class<*>): Any {
        if (targetType.isAssignableFrom(value.javaClass)) return value
        // 包装类 -> 基本类型
        return when (targetType) {
            Int::class.javaPrimitiveType -> (value as Number).toInt()
            Long::class.javaPrimitiveType -> (value as Number).toLong()
            Short::class.javaPrimitiveType -> (value as Number).toShort()
            Byte::class.javaPrimitiveType -> (value as Number).toByte()
            Float::class.javaPrimitiveType -> (value as Number).toFloat()
            Double::class.javaPrimitiveType -> (value as Number).toDouble()
            Boolean::class.javaPrimitiveType -> value as Boolean
            Char::class.javaPrimitiveType -> value.toString().firstOrNull() ?: ' '
            Int::class.java -> (value as Number).toInt()
            Long::class.java -> (value as Number).toLong()
            Short::class.java -> (value as Number).toShort()
            Byte::class.java -> (value as Number).toByte()
            Float::class.java -> (value as Number).toFloat()
            Double::class.java -> (value as Number).toDouble()
            String::class.java -> value.toString()
            // List/Array -> Java Array (如 ajaxAll(Array<String>) 接收 JS 数组)
            else -> coerceToArray(value, targetType)
        }
    }

    /**
     * List/Array 转 Java Array (如 ajaxAll(Array<String>) 接收 JS 数组)。
     *
     * quickjs-kt 把 JS 数组转成 List 或 Array,但 Java 方法参数可能是 Array<String> 等,
     * 需要转换。元素用 jsToJavaValue 解引用(处理句柄对象)。
     * rhino 会自动把 NativeArray 转成 Java 数组,这里模拟该行为。
     */
    private fun coerceToArray(value: Any, targetType: Class<*>): Any {
        if (!targetType.isArray) return value
        val componentType = targetType.componentType ?: return value
        val source: List<*> = when {
            value is List<*> -> value
            value.javaClass.isArray -> {
                val len = java.lang.reflect.Array.getLength(value)
                (0 until len).map { java.lang.reflect.Array.get(value, it) }
            }

            else -> return value
        }
        val array = java.lang.reflect.Array.newInstance(componentType, source.size)
        source.forEachIndexed { i, item ->
            val coercedItem = if (item != null) jsToJavaValue(item, componentType) else null
            java.lang.reflect.Array.set(array, i, coercedItem)
        }
        return array
    }

    /**
     * Java 返回值转 JS 可用类型。
     *
     * 基本类型(String/Number/Boolean)直接返回。
     * Java 对象包装为句柄对象 { __java_handle__: handle },由 JS bootstrap 解包。
     * 数组/集合转 JS 数组(List)。
     */
    private fun javaToJsResult(result: Any?, dangerousApi: Boolean): Any? {
        if (result == null) return null
        // 基本类型保留原始类型,让 quickjs 自动处理(JS Number/Boolean/String)
        if (result is String) return result
        if (result is CharSequence) return result.toString()
        if (result is Number) return result
        if (result is Boolean) return result
        if (result is ByteArray) return result
        if (result.javaClass.isArray) {
            // 数组转 List
            val len = java.lang.reflect.Array.getLength(result)
            return (0 until len).map {
                javaToJsResult(
                    java.lang.reflect.Array.get(result, it),
                    dangerousApi
                )
            }
        }
        if (result is Collection<*>) {
            return result.map { javaToJsResult(it, dangerousApi) }
        }
        if (result is Map<*, *>) {
            return result.entries.associate { (k, v) ->
                k?.toString() to javaToJsResult(v, dangerousApi)
            }
        }
        // Java 对象包装为句柄对象
        if (!JsSecurityPolicy.isObjectVisible(result, dangerousApi)) return null
        val handle = registerObject(result)
        return mapOf("__java_handle__" to handle)
    }
}
