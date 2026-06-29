package com.script.quickjs

import android.util.Log
import androidx.collection.LongSparseArray
import com.script.quickjs.JavaObjectBridge.NO_RESULT
import com.script.quickjs.JavaObjectBridge.appendToBuilder
import com.script.quickjs.JavaObjectBridge.callInstanceMethod
import com.script.quickjs.JavaObjectBridge.callStringBuilderMethod
import com.script.quickjs.JavaObjectBridge.getInstanceField
import com.script.quickjs.JavaObjectBridge.getInstanceKeys
import com.script.quickjs.JavaObjectBridge.getJavaProperty
import com.script.quickjs.JavaObjectBridge.hasInstanceMethod
import com.script.quickjs.JavaObjectBridge.jsToJavaArgs
import com.script.quickjs.JavaObjectBridge.jsToJavaValue
import com.script.quickjs.JavaObjectBridge.loadJavaClass
import com.script.quickjs.JavaObjectBridge.releaseScope
import com.script.quickjs.JavaObjectBridge.setInstanceField
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
     * 方法查找结果缓存 (类名#方法名(参数类型列表) -> Method)。
     *
     * 反射查找 findMethod 涉及 collectMethods + isArgsCompatible + paramSpecificityScore,
     * 对于 List/Map/StringBuilder 等热点类型的循环调用是显著开销。
     * 由于 Class 结构运行时不变,同一 (类, 方法, 参数类型签名) 总是解析到同一 Method,
     * 可全局缓存跨 scope 复用。
     *
     * 命中:methodCache;未命中:methodMissCache 避免重复反射查找。
     *
     * LRU 限制: 1000 条上限防止长期运行累积内存泄漏 (10000+ 书源场景)。
     * LinkedHashMap accessOrder=true 实现 LRU, removeEldestEntry 淘汰最旧条目。
     * 手动 synchronized 保证线程安全 (Collections.synchronizedMap 不支持 accessOrder)。
     */
    private val methodCache = object : LinkedHashMap<String, Method>(128, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Method>?) =
            size > 1000
    }
    private val methodMissCache = object : LinkedHashMap<String, Boolean>(128, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Boolean>?) =
            size > 1000
    }
    private val cacheLock = Any()

    /**
     * 快速路径未命中哨兵对象。callHotTypeMethod 返回此值表示该 methodName 未处理,
     * 调用方需继续走原 findMethod 反射路径。
     * 不能用 null 区分,null 是合法返回值(如 Map.get 不存在的 key 返回 null)。
     */
    private val NO_RESULT = Any()

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
        // 异常不 catch: 传播到 native 层, 由 jni_callbacks.cpp jsBindingCall 用
        // JavaObjectClass::wrap 把 Throwable 包装成 JavaObject 传给 JS catch,
        // 对齐 rhino WrappedException。原先 catch 返回 0L 让 JS 侧拿到 null
        // 完全不知出错, 且让 native 的 ExceptionCheck 分支成为死代码。
        val javaArgs = jsToJavaArgs(args)
        val instance = createInstance(clazz, javaArgs)
        return if (instance != null && JsSecurityPolicy.isObjectVisible(instance, dangerousApi)) {
            registerObject(instance)
        } else 0L
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
                return ctor.newInstance(*coerceArgs(ctor.parameterTypes, args, ctor.isVarArgs))
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
        // 热点类型快速路径: List/Map/StringBuilder/StringBuffer 绕过 findMethod 反射,
        // 直接调用常用方法。这些类型在书源 JS 循环中频繁使用 (list.add/get/size,
        // map.put/get/containsKey, sb.append/toString),反射开销显著。
        // 快速路径未命中的方法 (如 list.subList) 返回 NO_RESULT,走原 findMethod 路径。
        val fastResult = callHotTypeMethod(obj, methodName, args, dangerousApi)
        if (fastResult !== NO_RESULT) return fastResult
        return try {
            val javaArgs = jsToJavaArgs(args)
            val method = findMethod(obj.javaClass, methodName, javaArgs)
            if (method != null) {
                method.isAccessible = true
                val result = method.invoke(
                    obj,
                    *coerceArgs(method.parameterTypes, javaArgs, method.isVarArgs)
                )
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
     * 热点类型方法快速路径分发。
     *
     * @return 处理结果(null 也是合法返回值);[NO_RESULT] 表示未命中,调用方走反射路径
     */
    private fun callHotTypeMethod(
        obj: Any,
        methodName: String,
        args: Array<Any?>,
        dangerousApi: Boolean
    ): Any? {
        return when (obj) {
            is MutableList<*> -> callListMethod(
                obj as MutableList<Any?>,
                methodName,
                args,
                dangerousApi
            )

            is MutableMap<*, *> -> callMapMethod(
                obj as MutableMap<Any?, Any?>,
                methodName,
                args,
                dangerousApi
            )

            is StringBuilder -> callStringBuilderMethod(obj, methodName, args, dangerousApi)
            is StringBuffer -> callStringBufferMethod(obj, methodName, args, dangerousApi)
            else -> NO_RESULT
        }
    }

    /**
     * List 热点方法快速路径。
     * 覆盖书源 JS 高频方法:size/get/add/remove/contains/indexOf/clear/isEmpty。
     * 未覆盖的方法(如 subList/toArray/listIterator)走反射。
     *
     * @return 处理结果或 [NO_RESULT]
     */
    private fun callListMethod(
        list: MutableList<Any?>,
        methodName: String,
        args: Array<Any?>,
        dangerousApi: Boolean
    ): Any? {
        return when (methodName) {
            "size" -> if (args.isEmpty()) javaToJsResult(list.size, dangerousApi) else NO_RESULT
            "isEmpty" -> if (args.isEmpty()) javaToJsResult(
                list.isEmpty(),
                dangerousApi
            ) else NO_RESULT

            "get" -> if (args.size == 1) {
                // 支持字符串索引 (for...in 枚举返回字符串 "0","1"..., 对齐 rhino)
                val idx = when (val a = args[0]) {
                    is Number -> a.toInt()
                    is String -> a.toIntOrNull() ?: return NO_RESULT
                    else -> return NO_RESULT
                }
                // 对齐 rhino NativeJavaList: 越界抛 IndexOutOfBoundsException, JS 可 try-catch 捕获
                if (idx in 0 until list.size) javaToJsResult(list[idx], dangerousApi)
                else throw IndexOutOfBoundsException("Index: $idx, Size: ${list.size}")
            } else NO_RESULT

            "add" -> when (args.size) {
                1 -> {
                    list.add(jsToJavaValue(args[0], null))
                    javaToJsResult(true, dangerousApi)
                }

                2 -> {
                    // 支持字符串索引 (对齐 rhino)
                    val idx = when (val a = args[0]) {
                        is Number -> a.toInt()
                        is String -> a.toIntOrNull() ?: return NO_RESULT
                        else -> return NO_RESULT
                    }
                    list.add(idx, jsToJavaValue(args[1], null))
                    null
                }

                else -> NO_RESULT
            }

            "remove" -> if (args.size == 1) {
                val arg = args[0]
                if (arg is Number) {
                    val idx = arg.toInt()
                    if (idx in 0 until list.size) javaToJsResult(
                        list.removeAt(idx),
                        dangerousApi
                    ) else null
                } else {
                    javaToJsResult(list.remove(jsToJavaValue(arg, null)), dangerousApi)
                }
            } else NO_RESULT

            "contains" -> if (args.size == 1) {
                javaToJsResult(list.contains(jsToJavaValue(args[0], null)), dangerousApi)
            } else NO_RESULT

            "indexOf" -> if (args.size == 1) {
                javaToJsResult(list.indexOf(jsToJavaValue(args[0], null)), dangerousApi)
            } else NO_RESULT

            "lastIndexOf" -> if (args.size == 1) {
                javaToJsResult(list.lastIndexOf(jsToJavaValue(args[0], null)), dangerousApi)
            } else NO_RESULT

            "clear" -> if (args.isEmpty()) {
                list.clear()
                null
            } else NO_RESULT

            "set" -> if (args.size == 2) {
                val idx = (args[0] as? Number)?.toInt()
                    ?: return NO_RESULT
                if (idx in 0 until list.size) {
                    javaToJsResult(list.set(idx, jsToJavaValue(args[1], null)), dangerousApi)
                } else null
            } else NO_RESULT

            else -> NO_RESULT
        }
    }

    /**
     * Map 热点方法快速路径。
     * 覆盖书源 JS 高频方法:size/get/put/remove/containsKey/containsValue/keySet/values/entrySet/isEmpty/clear。
     *
     * @return 处理结果或 [NO_RESULT]
     */
    private fun callMapMethod(
        map: MutableMap<Any?, Any?>,
        methodName: String,
        args: Array<Any?>,
        dangerousApi: Boolean
    ): Any? {
        return when (methodName) {
            "size" -> if (args.isEmpty()) javaToJsResult(map.size, dangerousApi) else NO_RESULT
            "isEmpty" -> if (args.isEmpty()) javaToJsResult(
                map.isEmpty(),
                dangerousApi
            ) else NO_RESULT

            "get" -> if (args.size == 1) {
                javaToJsResult(map[jsToJavaValue(args[0], null)], dangerousApi)
            } else NO_RESULT

            "put" -> if (args.size == 2) {
                javaToJsResult(
                    map.put(jsToJavaValue(args[0], null), jsToJavaValue(args[1], null)),
                    dangerousApi
                )
            } else NO_RESULT

            "putIfAbsent" -> if (args.size == 2) {
                javaToJsResult(
                    map.putIfAbsent(jsToJavaValue(args[0], null), jsToJavaValue(args[1], null)),
                    dangerousApi
                )
            } else NO_RESULT

            "remove" -> when (args.size) {
                1 -> javaToJsResult(map.remove(jsToJavaValue(args[0], null)), dangerousApi)
                2 -> javaToJsResult(
                    map.remove(jsToJavaValue(args[0], null), jsToJavaValue(args[1], null)),
                    dangerousApi
                )

                else -> NO_RESULT
            }

            "containsKey" -> if (args.size == 1) {
                javaToJsResult(map.containsKey(jsToJavaValue(args[0], null)), dangerousApi)
            } else NO_RESULT

            "containsValue" -> if (args.size == 1) {
                javaToJsResult(map.containsValue(jsToJavaValue(args[0], null)), dangerousApi)
            } else NO_RESULT

            "keySet" -> if (args.isEmpty()) {
                javaToJsResult(map.keys, dangerousApi)
            } else NO_RESULT

            "values" -> if (args.isEmpty()) {
                javaToJsResult(map.values, dangerousApi)
            } else NO_RESULT

            "entrySet" -> if (args.isEmpty()) {
                javaToJsResult(map.entries, dangerousApi)
            } else NO_RESULT

            "clear" -> if (args.isEmpty()) {
                map.clear()
                null
            } else NO_RESULT

            "getOrDefault" -> if (args.size == 2) {
                javaToJsResult(
                    map.getOrDefault(jsToJavaValue(args[0], null), jsToJavaValue(args[1], null)),
                    dangerousApi
                )
            } else NO_RESULT

            else -> NO_RESULT
        }
    }

    /**
     * StringBuilder 热点方法快速路径。
     * 覆盖书源 JS 高频方法:append/length/charAt/toString/delete/replace/insert/substring/reverse。
     *
     * 注意:append 整数 Number 时用 append(int) 而非 toString(),
     * 避免 Double 0.0 被转为 "0.0"(Rhino LiveConnect 中 sb.append(0) 返回 "0")。
     *
     * @return 处理结果或 [NO_RESULT]
     */
    private fun callStringBuilderMethod(
        sb: StringBuilder,
        methodName: String,
        args: Array<Any?>,
        dangerousApi: Boolean
    ): Any? {
        return when (methodName) {
            "append" -> if (args.size == 1 && args[0] is String) {
                sb.append(args[0] as String)
                javaToJsResult(sb, dangerousApi)
            } else if (args.size == 1) {
                appendToBuilder(sb, args[0])
                javaToJsResult(sb, dangerousApi)
            } else NO_RESULT

            "appendCodePoint" -> if (args.size == 1) {
                val cp = (args[0] as? Number)?.toInt() ?: return NO_RESULT
                sb.appendCodePoint(cp)
                javaToJsResult(sb, dangerousApi)
            } else NO_RESULT

            "length" -> if (args.isEmpty()) {
                javaToJsResult(sb.length, dangerousApi)
            } else NO_RESULT

            "charAt" -> if (args.size == 1) {
                val idx = (args[0] as? Number)?.toInt() ?: return NO_RESULT
                if (idx in 0 until sb.length) javaToJsResult(sb[idx], dangerousApi) else null
            } else NO_RESULT

            "setLength" -> if (args.size == 1) {
                val len = (args[0] as? Number)?.toInt() ?: return NO_RESULT
                sb.setLength(len)
                null
            } else NO_RESULT

            "delete" -> if (args.size == 2) {
                val s = (args[0] as? Number)?.toInt() ?: return NO_RESULT
                val e = (args[1] as? Number)?.toInt() ?: return NO_RESULT
                javaToJsResult(sb.delete(s, e), dangerousApi)
            } else NO_RESULT

            "deleteCharAt" -> if (args.size == 1) {
                val idx = (args[0] as? Number)?.toInt() ?: return NO_RESULT
                javaToJsResult(sb.deleteCharAt(idx), dangerousApi)
            } else NO_RESULT

            "replace" -> if (args.size == 3) {
                val s = (args[0] as? Number)?.toInt() ?: return NO_RESULT
                val e = (args[1] as? Number)?.toInt() ?: return NO_RESULT
                javaToJsResult(sb.replace(s, e, args[2]?.toString() ?: "null"), dangerousApi)
            } else NO_RESULT

            "insert" -> if (args.size == 2) {
                val idx = (args[0] as? Number)?.toInt() ?: return NO_RESULT
                javaToJsResult(sb.insert(idx, args[1]?.toString() ?: "null"), dangerousApi)
            } else NO_RESULT

            "substring" -> when (args.size) {
                1 -> {
                    val s = (args[0] as? Number)?.toInt() ?: return NO_RESULT
                    javaToJsResult(sb.substring(s), dangerousApi)
                }

                2 -> {
                    val s = (args[0] as? Number)?.toInt() ?: return NO_RESULT
                    val e = (args[1] as? Number)?.toInt() ?: return NO_RESULT
                    javaToJsResult(sb.substring(s, e), dangerousApi)
                }

                else -> NO_RESULT
            }

            "indexOf" -> if (args.size == 1) {
                javaToJsResult(sb.indexOf(args[0]?.toString() ?: "null"), dangerousApi)
            } else if (args.size == 2) {
                val from = (args[1] as? Number)?.toInt() ?: return NO_RESULT
                javaToJsResult(sb.indexOf(args[0]?.toString() ?: "null", from), dangerousApi)
            } else NO_RESULT

            "lastIndexOf" -> if (args.size == 1) {
                javaToJsResult(sb.lastIndexOf(args[0]?.toString() ?: "null"), dangerousApi)
            } else NO_RESULT

            "reverse" -> if (args.isEmpty()) {
                javaToJsResult(sb.reverse(), dangerousApi)
            } else NO_RESULT

            "toString" -> if (args.isEmpty()) {
                javaToJsResult(sb.toString(), dangerousApi)
            } else NO_RESULT

            else -> NO_RESULT
        }
    }

    /**
     * StringBuffer 快速路径,委托到 [callStringBuilderMethod] (两者 API 等价)。
     */
    private fun callStringBufferMethod(
        sb: StringBuffer,
        methodName: String,
        args: Array<Any?>,
        dangerousApi: Boolean
    ): Any? {
        // StringBuffer 与 StringBuilder 方法签名一致,转换 args[0] 同样的方式处理
        // 为避免重复实现,直接调用对应方法。StringBuffer 是 synchronized 的 StringBuilder。
        return when (methodName) {
            "append" -> if (args.size == 1) {
                appendToBuffer(sb, args[0])
                javaToJsResult(sb, dangerousApi)
            } else NO_RESULT

            "length" -> if (args.isEmpty()) javaToJsResult(sb.length, dangerousApi) else NO_RESULT
            "charAt" -> if (args.size == 1) {
                val idx = (args[0] as? Number)?.toInt() ?: return NO_RESULT
                if (idx in 0 until sb.length) javaToJsResult(sb[idx], dangerousApi) else null
            } else NO_RESULT

            "delete" -> if (args.size == 2) {
                val s = (args[0] as? Number)?.toInt() ?: return NO_RESULT
                val e = (args[1] as? Number)?.toInt() ?: return NO_RESULT
                javaToJsResult(sb.delete(s, e), dangerousApi)
            } else NO_RESULT

            "replace" -> if (args.size == 3) {
                val s = (args[0] as? Number)?.toInt() ?: return NO_RESULT
                val e = (args[1] as? Number)?.toInt() ?: return NO_RESULT
                javaToJsResult(sb.replace(s, e, args[2]?.toString() ?: "null"), dangerousApi)
            } else NO_RESULT

            "insert" -> if (args.size == 2) {
                val idx = (args[0] as? Number)?.toInt() ?: return NO_RESULT
                javaToJsResult(sb.insert(idx, args[1]?.toString() ?: "null"), dangerousApi)
            } else NO_RESULT

            "substring" -> when (args.size) {
                1 -> {
                    val s = (args[0] as? Number)?.toInt() ?: return NO_RESULT
                    javaToJsResult(sb.substring(s), dangerousApi)
                }

                2 -> {
                    val s = (args[0] as? Number)?.toInt() ?: return NO_RESULT
                    val e = (args[1] as? Number)?.toInt() ?: return NO_RESULT
                    javaToJsResult(sb.substring(s, e), dangerousApi)
                }

                else -> NO_RESULT
            }

            "reverse" -> if (args.isEmpty()) javaToJsResult(
                sb.reverse(),
                dangerousApi
            ) else NO_RESULT

            "toString" -> if (args.isEmpty()) javaToJsResult(
                sb.toString(),
                dangerousApi
            ) else NO_RESULT

            else -> NO_RESULT
        }
    }

    /**
     * StringBuilder/StringBuffer append 参数智能转换。
     *
     * 模拟 Rhino LiveConnect 的 append 重载选择行为:
     * - 整数值 Number (Int/Long/整数值 Double) → append(int/long),返回 "0" 而非 "0.0"
     * - 非整数 Double → append(double)
     * - String → append(String)
     * - Boolean → append(boolean)
     * - null → append("null") (Java 原生 append(Object null) 行为)
     * - 其他对象 → append(obj.toString())
     */
    private fun appendToBuilder(sb: StringBuilder, value: Any?) {
        when (value) {
            is String -> sb.append(value)
            is Boolean -> sb.append(value)
            is Number -> {
                if (isIntegerNumber(value)) {
                    val longVal = value.toLong()
                    if (longVal in Int.MIN_VALUE..Int.MAX_VALUE) {
                        sb.append(longVal.toInt())
                    } else {
                        sb.append(longVal)
                    }
                } else {
                    sb.append(value.toDouble())
                }
            }

            null -> sb.append("null")
            else -> sb.append(value.toString())
        }
    }

    /** [appendToBuilder] 的 StringBuffer 版本 (签名不同需要单独实现)。 */
    private fun appendToBuffer(sb: StringBuffer, value: Any?) {
        when (value) {
            is String -> sb.append(value)
            is Boolean -> sb.append(value)
            is Number -> {
                if (isIntegerNumber(value)) {
                    val longVal = value.toLong()
                    if (longVal in Int.MIN_VALUE..Int.MAX_VALUE) {
                        sb.append(longVal.toInt())
                    } else {
                        sb.append(longVal)
                    }
                } else {
                    sb.append(value.toDouble())
                }
            }

            null -> sb.append("null")
            else -> sb.append(value.toString())
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
     * 一次性查 field + method 是否存在,合并 [getInstanceField] + [hasInstanceMethod] 两次 bridge。
     *
     * JS proxy.get 里 `__getJavaPropertyValue` 原本对每个属性访问都要 2 次 native 调用 (取 field
     * + 判断 method 是否存在),实测书源 JS 循环里 (`sb.append(x)`、`list.get(i)`) 这是热点。
     * 本函数把两次合并成一次,Kotlin 内部复用 collectMethods/findGetter/getCollectionField。
     *
     * 返回值 (List 元素):
     *   - `[0] fieldValue`: 字段值(已经 javaToJsResult 包装),null 表示当前查询不到字段值
     *   - `[1] fieldExists`: 字段空间内"可能存在"(即下次访问可能解析到 field 分支)。
     *      用于 3.1 缓存判断:仅当 `!fieldExists && hasMethod` 时,JS 侧才能安全缓存 method
     *      callable。Map 因为 key 空间动态可写,即使当前 key 缺失也标 true 防止缓存失效;
     *      List/Array 的非数字 prop 永不会成为字段,可以放 false 让 JS 缓存。
     *   - `[2] hasMethod`: 是否有同名实例方法
     *
     * @return null 表示 field 与 method 均不存在 (JS 侧返回 undefined);
     *         否则返回 `[fieldValue, fieldExists, hasMethod]` 三元素 List
     */
    fun getJavaProperty(
        objHandle: Long,
        fieldName: String,
        dangerousApi: Boolean
    ): Any? {
        val obj = getObject(objHandle) ?: return null
        if (!JsSecurityPolicy.isObjectVisible(obj, dangerousApi)) return null

        var fieldExists = false
        // Field 部分: 复用 getInstanceField 的查找顺序 (Map/List/Array → getter → field)
        val fieldValue: Any? = try {
            val collectionVal = getCollectionField(obj, fieldName)
            if (collectionVal != null) {
                fieldExists = true
                javaToJsResult(collectionVal, dangerousApi)
            } else when {
                // Map: 任意 key 都可能后续被 setInstanceField 注入,标 fieldExists 阻断缓存
                obj is Map<*, *> -> {
                    fieldExists = true
                    null
                }
                // List/Array: 非数字 prop 永远不会被 setInstanceField 接收 (setCollectionField
                // 只认数字索引),非数字 prop 走 method 分支后可以让 JS 安全缓存 callable
                else -> {
                    val getter = findGetter(obj.javaClass, fieldName)
                    if (getter != null
                        && JsSecurityPolicy.isMethodVisible(
                            obj.javaClass.name, getter.name, dangerousApi
                        )
                    ) {
                        fieldExists = true
                        getter.isAccessible = true
                        javaToJsResult(getter.invoke(obj), dangerousApi)
                    } else {
                        val field = findField(obj.javaClass, fieldName)
                        if (field != null) {
                            fieldExists = true
                            field.isAccessible = true
                            javaToJsResult(field.get(obj), dangerousApi)
                        } else null
                    }
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "getJavaProperty field failed: ${obj.javaClass.name}.$fieldName", e)
            null
        } catch (e: LinkageError) {
            Log.w(TAG, "getJavaProperty linkage error: ${obj.javaClass.name}.$fieldName", e)
            null
        }

        // Method 部分: 复用 hasInstanceMethod 的逻辑
        val hasMethod = if (
            JsSecurityPolicy.isMethodVisible(obj.javaClass.name, fieldName, dangerousApi)
        ) {
            val candidates = mutableListOf<Method>()
            collectMethods(obj.javaClass, fieldName, candidates)
            candidates.isNotEmpty()
        } else false

        // 三重未命中: 返回 null 让 JS 一行结束 (省一次解构开销)
        if (fieldValue == null && !fieldExists && !hasMethod) return null
        return listOf(fieldValue, fieldExists, hasMethod)
    }

    /**
     * native 版本专用: 查 field + method, 返回原始 Java 对象 (不经过 javaToJsResult 包装)。
     *
     * 与 [getJavaProperty] 逻辑相同, 但 fieldValue 是原始 Java 对象,
     * 供 native exotic trap 回调后由 native 层用 JavaObjectClass.wrap 包装为 JSValue。
     *
     * @return null 表示 field 与 method 均不存在;
     *         否则返回 `[fieldValue(原始对象), fieldExists, hasMethod]` 三元素 Array
     */
    internal fun getJavaPropertyRaw(
        obj: Any,
        fieldName: String,
        dangerousApi: Boolean
    ): Array<Any?>? {
        if (!JsSecurityPolicy.isObjectVisible(obj, dangerousApi)) return null

        var fieldExists = false
        // 对齐 rhino NativeJavaClass: obj 是 Class 对象时, 用其表示的类查找静态成员/内部类,
        // 而非 Class 类本身。例: Bitmap.Config → Bitmap 内部类; Bitmap.Config.ARGB_8888
        // → Bitmap$Config 静态字段; Bitmap.createBitmap → Bitmap 静态方法。
        // 原先 obj.javaClass 当 obj 是 Class<*> 时返回 Class 类本身, 查不到 Bitmap 的成员。
        val lookupClass = if (obj is Class<*>) obj else obj.javaClass
        // 异常不 catch: 传播到 native 层, 由 jni_object_class.cpp getProperty trap 用
        // JavaObjectClass::wrap 把 Throwable 包装成 JavaObject 传给 JS catch,
        // 对齐 rhino WrappedException。原先 catch 返回 null 让 JS 侧拿到 undefined
        // 完全不知出错, 且让 native 的 ExceptionCheck 分支成为死代码。
        val fieldValue: Any? = run {
            val collectionVal = getCollectionField(obj, fieldName)
            if (collectionVal != null) {
                fieldExists = true
                collectionVal // 原始对象, 不调 javaToJsResult
            } else when {
                obj is Map<*, *> -> {
                    // 对齐 rhino FEATURE_ENABLE_JAVA_MAP_ACCESS:
                    // - key 存在: 返回 map.get(key) (即使 value=null, fieldExists=true)
                    // - key 不存在: fieldExists=false, fieldValue=null
                    //   (让 native 层 getProperty trap 返回 JS_UNDEFINED, 而非 JS_NULL)
                    if (obj.containsKey(fieldName)) {
                        fieldExists = true
                        obj[fieldName] // 可能是 null (value=null)
                    } else null
                }

                else -> {
                    val getter = findGetter(lookupClass, fieldName)
                    if (getter != null && JsSecurityPolicy.isMethodVisible(
                            lookupClass.name, getter.name, dangerousApi
                        )
                    ) {
                        fieldExists = true
                        getter.isAccessible = true
                        getter.invoke(if (obj is Class<*>) null else obj)
                    } else {
                        val field = findField(lookupClass, fieldName)
                        if (field != null) {
                            fieldExists = true
                            field.isAccessible = true
                            field.get(if (obj is Class<*>) null else obj)
                        } else if (obj is Class<*>) {
                            // 内部类查找 (对齐 rhino NativeJavaClass):
                            // Java 反射不把内部类作为字段暴露 (getField/getDeclaredField 都
                            // 找不到), 需用 getDeclaredClasses 按 simpleName 匹配, 或
                            // Class.forName("Outer$Inner") 兜底。
                            findInnerClass(obj, fieldName)?.also { fieldExists = true }
                        } else null
                    }
                }
            }
        }

        val hasMethod =
            if (JsSecurityPolicy.isMethodVisible(lookupClass.name, fieldName, dangerousApi)) {
                val candidates = mutableListOf<Method>()
                collectMethods(lookupClass, fieldName, candidates)
                candidates.isNotEmpty()
            } else false

        if (fieldValue == null && !fieldExists && !hasMethod) return null
        return arrayOf(fieldValue, fieldExists, hasMethod)
    }

    /**
     * native 版本专用: 设置实例字段, 接受原始 obj 和原始 value。
     *
     * 与 [setInstanceField] 逻辑相同, 但 value 是原始 Java 对象 (非句柄 Map),
     * 若 value 是 Long 句柄 (native 层包装的 JS 对象) 会先解包。
     */
    internal fun setInstanceFieldRaw(
        obj: Any,
        fieldName: String,
        value: Any?,
        dangerousApi: Boolean
    ): Boolean {
        if (!JsSecurityPolicy.isObjectVisible(obj, dangerousApi)) return false
        val javaValue = unwrapHandleValue(value)
        if (setCollectionField(obj, fieldName, javaValue)) return true
        // 异常不 catch: 传播到 native 层, 对齐 rhino (见 callInstanceMethodRaw 注释)
        val setter = findSetter(obj.javaClass, fieldName)
        if (setter != null) {
            if (!JsSecurityPolicy.isMethodVisible(
                    obj.javaClass.name, setter.name, dangerousApi
                )
            ) return false
            setter.isAccessible = true
            setter.invoke(obj, coerceValue(javaValue, setter.parameterTypes[0]))
            return true
        }
        val field = findField(obj.javaClass, fieldName) ?: return false
        field.isAccessible = true
        field.set(obj, coerceValue(javaValue, field.type))
        return true
    }

    /**
     * native 版本专用: 获取实例属性名, 接受原始 obj。
     * 与 [getInstanceKeys] 逻辑相同, 但接受 obj 而非 handle。
     */
    internal fun getInstanceKeysRaw(obj: Any, dangerousApi: Boolean): Array<String> {
        if (!JsSecurityPolicy.isObjectVisible(obj, dangerousApi)) return emptyArray()
        // 异常不 catch: 传播到 native 层, 对齐 rhino (见 callInstanceMethodRaw 注释)
        return getInstanceKeysImpl(obj, dangerousApi)
    }

    /**
     * native 版本专用: 调用实例方法, 接受原始 obj, 返回原始 Java 对象。
     *
     * 与 [callInstanceMethod] 逻辑相同, 但:
     * - 接受 obj 而非 handle
     * - args 中的 Long 句柄会先解包
     * - 返回值是原始 Java 对象 (不经过 javaToJsResult 包装)
     */
    internal fun callInstanceMethodRaw(
        obj: Any,
        methodName: String,
        args: Array<Any?>,
        dangerousApi: Boolean
    ): Any? {
        if (!JsSecurityPolicy.isObjectVisible(obj, dangerousApi)) return null
        // 对齐 rhino NativeJavaClass: obj 是 Class 对象时, 用其表示的类查找静态方法,
        // 而非 Class 类本身。例: Bitmap.Config.values() → obj 是 Bitmap$Config Class 对象,
        // findMethod(Bitmap$Config, "values") 而非 findMethod(Class, "values")。
        // 由 getStaticField 返回的内部类 Class 对象被包装为 JavaObject, 访问 .values()
        // 走 method callable → 此方法, 需用 lookupClass 查到内部类的静态方法。
        val lookupClass = if (obj is Class<*>) obj else obj.javaClass
        if (!JsSecurityPolicy.isMethodVisible(lookupClass.name, methodName, dangerousApi)) {
            return null
        }
        // 热点类型快速路径
        val fastResult = callHotTypeMethod(obj, methodName, args, dangerousApi)
        if (fastResult !== NO_RESULT) return fastResult
        // 异常不 catch: 传播到 native 层, 由 jni_callbacks.cpp jsMethodCallable 用
        // JavaObjectClass::wrap 把 Throwable 包装成 JavaObject 传给 JS catch,
        // 对齐 rhino WrappedException。原先 catch 返回 null 让 JS 侧拿到 null
        // 完全不知出错, 且让 native 的 ExceptionCheck 分支成为死代码。
        val javaArgs = Array(args.size) { i -> unwrapHandleValue(args[i]) }
        val method = findMethod(lookupClass, methodName, javaArgs)
        if (method != null) {
            method.isAccessible = true
            // 静态方法 obj 被反射忽略, 传 Class 对象或 null 均可; 实例方法传 obj
            val invokeTarget = if (Modifier.isStatic(method.modifiers)) null else obj
            val result = method.invoke(
                invokeTarget,
                *coerceArgs(method.parameterTypes, javaArgs, method.isVarArgs)
            )
            return result // 原始对象, 不调 javaToJsResult
        }
        // getter 回退
        if (args.isEmpty()) {
            val getterName = buildString(methodName.length + 3) {
                append("get")
                if (methodName.isNotEmpty()) {
                    methodName.first().uppercaseChar().let { append(it) }
                    append(methodName.substring(1))
                }
            }
            val getter = findMethod(lookupClass, getterName, emptyArray())
            if (getter != null) {
                getter.isAccessible = true
                return getter.invoke(if (Modifier.isStatic(getter.modifiers)) null else obj)
            }
            val isName = buildString(methodName.length + 2) {
                append("is")
                if (methodName.isNotEmpty()) {
                    methodName.first().uppercaseChar().let { append(it) }
                    append(methodName.substring(1))
                }
            }
            val isGetter = findMethod(lookupClass, isName, emptyArray())
            if (isGetter != null) {
                isGetter.isAccessible = true
                return isGetter.invoke(if (Modifier.isStatic(isGetter.modifiers)) null else obj)
            }
        }
        return null
    }

    /**
     * native 版本专用: 解包 Long 句柄为原始 Java 对象。
     * 若 value 是 Long 且对应已注册句柄, 返回原始对象; 否则原样返回。
     */
    internal fun unwrapHandleValue(value: Any?): Any? {
        if (value is Long && value != 0L) {
            val obj = getObject(value)
            if (obj != null) return obj
        }
        return value
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
        // 异常不 catch: 传播到 native 层, 对齐 rhino (见 callInstanceMethodRaw 注释)
        val javaArgs = jsToJavaArgs(args)
        val method = findStaticMethod(clazz, methodName, javaArgs) ?: return null
        method.isAccessible = true
        val result =
            method.invoke(null, *coerceArgs(method.parameterTypes, javaArgs, method.isVarArgs))
        return javaToJsResult(result, dangerousApi)
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
     *
     * 注: 内部类访问 (如 Bitmap.Config) 不在此处理, 而是在 JS bootstrap 的 __wrapClass
     * Proxy get trap 中用 __loadJavaClass(path + "$" + prop) 加载内部类。原因:
     * JsSecurityPolicy.isObjectVisible 禁止 Class<*> 对象返回 JS 侧 (安全策略,
     * 防止反射滥用), 所以此处即使 findInnerClass 找到内部类, javaToJsResult 也会
     * 因 isObjectVisible 拦截返回 null。JS 层 __loadJavaClass 返回 classHandle,
     * 用 __wrapClass 包装为 Proxy, 不暴露原始 Class 对象。
     */
    fun getStaticField(classHandle: Long, fieldName: String, dangerousApi: Boolean): Any? {
        val clazz = getClass(classHandle) ?: return null
        if (!JsSecurityPolicy.isClassVisible(clazz, dangerousApi)) return null
        // 异常不 catch: 传播到 native 层, 对齐 rhino (见 callInstanceMethodRaw 注释)
        val field = findField(clazz, fieldName) ?: return null
        field.isAccessible = true
        return javaToJsResult(field.get(null), dangerousApi)
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
        // 异常不 catch: 传播到 native 层, 对齐 rhino (见 callInstanceMethodRaw 注释)
        val field = findField(clazz, fieldName) ?: return false
        field.isAccessible = true
        field.set(null, jsToJavaValue(value, field.type))
        return true
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
        // 方法缓存与 ClassLoader 强相关,测试场景需重置避免跨用例污染
        synchronized(cacheLock) {
            methodCache.clear()
            methodMissCache.clear()
        }
    }

    // ============ 反射辅助 ============

    private fun findMethod(clazz: Class<*>, name: String, args: Array<Any?>): Method? {
        // 缓存查找:Class 结构运行时不变,同一 (类,方法,参数类型签名) 解析结果相同
        val cacheKey = buildMethodCacheKey(clazz, name, args)
        synchronized(cacheLock) {
            methodCache[cacheKey]?.let { return it }
            if (methodMissCache.containsKey(cacheKey)) return null
        }

        // 反射查找放在锁外,避免长时间持锁阻塞并发调用
        val method = findMethodImpl(clazz, name, args)
        synchronized(cacheLock) {
            if (method != null) {
                methodCache[cacheKey] = method
            } else {
                methodMissCache[cacheKey] = true
            }
        }
        return method
    }

    /**
     * 构造方法缓存 key: "className#methodName(argType1,argType2,...)"。
     * null 参数用 "null" 表示。key 稳定,相同签名总是相同字符串。
     */
    private fun buildMethodCacheKey(clazz: Class<*>, name: String, args: Array<Any?>): String {
        if (args.isEmpty()) return "${clazz.name}#$name()"
        val sb = StringBuilder(clazz.name.length + name.length + args.size * 20 + 4)
        sb.append(clazz.name).append('#').append(name).append('(')
        for (i in args.indices) {
            if (i > 0) sb.append(',')
            sb.append(args[i]?.javaClass?.name ?: "null")
        }
        sb.append(')')
        return sb.toString()
    }

    private fun findMethodImpl(clazz: Class<*>, name: String, args: Array<Any?>): Method? {
        // 收集本类 + 父类 + 接口的 public 方法
        val candidates = mutableListOf<Method>()
        collectMethods(clazz, name, candidates)
        // 按参数个数过滤
        val matched = candidates.filter { it.parameterCount == args.size }
        if (matched.isNotEmpty()) {
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
        // 精确参数个数匹配失败,尝试 varargs 匹配
        // 场景: String.format(String, Object...) 调用时 args.size > parameterCount
        // varargs 方法的最后一个参数是数组, args.size >= parameterCount - 1
        val varargsMatched = candidates.filter {
            it.isVarArgs && args.size >= it.parameterCount - 1
        }
        if (varargsMatched.isNotEmpty()) {
            // 检查类型兼容性 (前 parameterCount-1 个固定参数 + varargs 参数)
            val compatible = varargsMatched.filter { isVarArgsCompatible(it, args) }
            if (compatible.isEmpty()) return varargsMatched.first()
            return compatible.first()
        }
        return null
    }

    /**
     * varargs 方法类型兼容性检查。
     *
     * varargs 方法的最后一个参数是数组 (如 Object[]),其 componentType 是实际参数类型。
     * 前 parameterCount-1 个参数按普通方式检查,多余的参数都应该是 componentType。
     */
    private fun isVarArgsCompatible(method: Method, args: Array<Any?>): Boolean {
        val paramTypes = method.parameterTypes
        val fixedCount = paramTypes.size - 1
        // 固定参数检查
        for (i in 0 until fixedCount) {
            val arg = args[i]
            if (arg == null) {
                if (paramTypes[i].isPrimitive) return false
                continue
            }
            if (!paramTypes[i].isAssignableFrom(arg.javaClass) &&
                !isPrimitiveCompatible(paramTypes[i], arg.javaClass)
            ) return false
        }
        // varargs 参数检查 (最后一个参数是数组,检查 componentType)
        // componentType 为 null 说明最后一个参数不是数组 (不应在 varargs 场景出现), 返回 false
        val componentType = paramTypes[fixedCount].componentType ?: return false
        for (i in fixedCount until args.size) {
            val arg = args[i]
            if (arg == null) {
                if (componentType.isPrimitive) return false
                continue
            }
            if (!componentType.isAssignableFrom(arg.javaClass) &&
                !isPrimitiveCompatible(componentType, arg.javaClass)
            ) return false
        }
        return true
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
     * 查找内部类 (对齐 rhino NativeJavaClass)。
     * Java 反射不把内部类作为字段暴露 (getField/getDeclaredField 都找不到),
     * 需用 getDeclaredClasses 按 simpleName 匹配, 或 Class.forName("Outer$Inner") 兜底。
     * 用于支持 `Bitmap.Config` 这类内部类访问语法 (Bitmap 是 Class 对象,
     * .Config 访问其表示类 Bitmap 的内部类 Bitmap$Config)。
     */
    private fun findInnerClass(clazz: Class<*>, name: String): Class<*>? {
        return try {
            // 优先用 getDeclaredClasses 匹配 simpleName
            for (inner in clazz.declaredClasses) {
                if (inner.simpleName == name) return inner
            }
            // 兜底: 用完整类名查找 (处理非 declared 的情况)
            Class.forName(clazz.name + "$" + name, false, clazz.classLoader)
        } catch (_: ClassNotFoundException) {
            null
        } catch (_: LinkageError) {
            null
        }
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
    private fun coerceArgs(
        paramTypes: Array<Class<*>>,
        args: Array<Any?>,
        isVarArgs: Boolean = false
    ): Array<Any?> {
        // varargs 处理: 方法/构造器是 varargs 且 args 数量不等于 paramTypes 数量
        // (args.size == paramTypes.size 时,整个数组作为单个参数,走普通路径)
        if (isVarArgs && args.size >= paramTypes.size - 1 && args.size != paramTypes.size) {
            val fixedCount = paramTypes.size - 1
            val result = arrayOfNulls<Any>(paramTypes.size)
            // 固定参数
            for (i in 0 until fixedCount) {
                val v = args[i]
                result[i] = if (v == null) {
                    if (paramTypes[i].isPrimitive) 0 else null
                } else {
                    coerceValue(v, paramTypes[i])
                }
            }
            // varargs 参数打包为数组 (componentType 是数组元素类型)
            // isVarArgs 保证最后一个参数是数组, componentType 一定非空
            val componentType = paramTypes[fixedCount].componentType!!
            val varargsCount = args.size - fixedCount
            val varargsArray = java.lang.reflect.Array.newInstance(componentType, varargsCount)
            for (i in 0 until varargsCount) {
                val v = args[fixedCount + i]
                val coerced = if (v == null) {
                    if (componentType.isPrimitive) 0 else null
                } else {
                    coerceValue(v, componentType)
                }
                java.lang.reflect.Array.set(varargsArray, i, coerced)
            }
            result[fixedCount] = varargsArray
            return result
        }
        // 普通参数
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
    private fun coerceValue(value: Any?, targetType: Class<*>): Any? {
        // null 处理: 基本类型抛 NPE (与 rhino NativeJavaObject.coerceType 一致),
        // 引用类型直接返回 null
        if (value == null) {
            return if (targetType.isPrimitive) {
                throw NullPointerException("Cannot coerce null to primitive ${targetType.name}")
            } else {
                null
            }
        }
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
     * 数组转 JS 数组(List)。
     *
     * 句柄复用 (优化 3.2): 同一 Java 对象多次返回时, 通过 [QuickJsContext.identityHandles]
     * 复用已注册的句柄, 避免 objectMap 膨胀 + JS 侧拿到多个 Proxy(身份不一致)。
     */
    private fun javaToJsResult(result: Any?, dangerousApi: Boolean): Any? {
        if (result == null) return null
        // 基本类型保留原始类型,让 quickjs 自动处理(JS Number/Boolean/String)
        // 与 Rhino LiveConnect 一致: Java String/Boolean → JS 基本类型
        // Number 仅 java.lang 标准包装类型(Byte/Short/Integer/Long/Float/Double)映射为 JS Number,
        // 其他 Number 子类(BigInteger/BigDecimal/AtomicInteger 等)走 Java 句柄路径,
        // 因为 quickjs-kt 无法处理这些类型,且 Rhino LiveConnect 也会把它们包装为 NativeJavaObject
        if (result is String) return result
        if (result is Boolean) return result
        if (result is ByteArray) return result
        if (result is Number && result.javaClass.name.startsWith("java.lang.")) return result
        if (result.javaClass.isArray) {
            // Java 数组转 List (quickjs-kt 转 JS Array)
            // 与 Rhino LiveConnect 一致: Java 数组 → NativeArray (JS Array)
            val len = java.lang.reflect.Array.getLength(result)
            return (0 until len).map {
                javaToJsResult(
                    java.lang.reflect.Array.get(result, it),
                    dangerousApi
                )
            }
        }
        // Map 走 Java 句柄路径 (不复制),支持真正互操作:
        // JS 侧 body[key]/body.get(key)/body.put(key,val)/for...in 直接操作原 Java Map,
        // 而非 quickjs-kt 创建的 ES6 Map 副本。
        // 标记 __java_is_map__ 让 JS bootstrap 用 __wrapJavaMap 包装 (增加 ownKeys trap)。
        if (result is Map<*, *>) {
            if (!JsSecurityPolicy.isObjectVisible(result, dangerousApi)) return null
            val handle = getOrRegisterIdentityHandle(result)
            return mapOf("__java_handle__" to handle, "__java_is_map__" to true)
        }
        // Java 对象包装为句柄对象
        if (!JsSecurityPolicy.isObjectVisible(result, dangerousApi)) return null
        val handle = getOrRegisterIdentityHandle(result)
        return mapOf("__java_handle__" to handle)
    }

    /**
     * 按对象身份复用句柄, 同一 Java 对象在同一 scope 内只注册一次。
     *
     * 命中 [QuickJsContext.identityHandles] 直接返回旧 handle, 未命中则 registerObject + 写入映射。
     * 没有 ctx (如 JavaAdapter 回调线程无 threadLocalContext) 时退化为直接 registerObject。
     */
    internal fun getOrRegisterIdentityHandle(obj: Any): Long {
        val ctx = QuickJsContext.threadLocalContext.get()
            ?: return registerObject(obj)
        val existing = ctx.identityHandles[obj]
        if (existing != null) return existing
        val handle = registerObject(obj)
        ctx.identityHandles[obj] = handle
        return handle
    }
}
