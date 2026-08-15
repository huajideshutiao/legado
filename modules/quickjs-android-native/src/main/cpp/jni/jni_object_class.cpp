#include "jni_object_class.h"
#include "jni_value_convert.h"
#include "jni_handle.h"
#include "jni_callbacks.h"
#include <cstring>
#include <cstdlib>
#include <pthread.h>

// KP1.1 跨平台日志: Android 走 __android_log_print, 桌面 JVM 走 fprintf(stderr)
#define TAG "legado_qjs"
#ifdef __ANDROID__

#include <android/log.h>

#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)
#else
#include <cstdio>
#define LOGE(...) fprintf(stderr, "[ERROR][%s] ", TAG); fprintf(stderr, __VA_ARGS__); fprintf(stderr, "\n")
#endif

// 静态成员初始化
JSClassID JavaObjectClass::classId = 0;
JavaVM *JavaObjectClass::cachedJvm = nullptr;
JSRuntime **JavaObjectClass::registeredRuntimes = nullptr;
uint32_t JavaObjectClass::registeredCount = 0;
uint32_t JavaObjectClass::registeredCap = 0;
pthread_mutex_t JavaObjectClass::registryMutex = PTHREAD_MUTEX_INITIALIZER;

// 缓存的 Java 方法 ID (JavaObjectBridgeNative 静态方法)
namespace {
    jclass g_bridgeCls = nullptr;
    jmethodID g_hasProperty = nullptr;
    jmethodID g_getPropertyInfo = nullptr;
    jmethodID g_setProperty = nullptr;
    jmethodID g_getPropertyNames = nullptr;
    jmethodID g_newArrayLike = nullptr;
    // 哨兵单例 (JavaObjectBridge.METHOD_MARKER / NULL_FIELD_MARKER 的 global ref)
    // trap 用 IsSameObject 与之对比, 免掉原先 3 槽数组 + 装箱 2 Boolean 的开销。
    jobject g_methodMarker = nullptr;
    jobject g_nullFieldMarker = nullptr;
    // 双检锁 + __atomic (替代 std::call_once, 免 libc++ 依赖; pthread_once 无法安全传 env)。
    bool g_bridgeInited = false;
    pthread_mutex_t g_bridgeInitMutex = PTHREAD_MUTEX_INITIALIZER;

    // java.util.List 缓存 (用于 Symbol.iterator 检测: 让 JS for...of 能迭代 Java List)
    jclass g_listCls = nullptr;
    jmethodID g_listSize = nullptr;
    jmethodID g_listGet = nullptr;

    // java.lang.Class.isArray() 缓存 (用于检测 Java 数组, 配合 Symbol.iterator 支持)
    jclass g_objectCls = nullptr;     // java.lang.Object
    jclass g_classCls = nullptr;      // java.lang.Class
    jmethodID g_getClass = nullptr;    // Object.getClass()
    jmethodID g_isArray = nullptr;     // Class.isArray()

    // java.lang.Boolean 缓存 (getProperty 解包 fieldExists / hasMethod 用)
    // 原先两次 Boolean 解包都 FindClass + GetMethodID, 每次属性访问算两次, 极热
    jclass g_BooleanCls = nullptr;
    jmethodID g_BooleanValueOf = nullptr;
    jmethodID g_BooleanValue = nullptr;

    // java.lang.reflect.Array 缓存 (List/Array 索引 fast path 和 Symbol.iterator 用)
    // 原实现 Symbol.iterator 时每次都 FindClass + GetStaticMethodID, 索引 fast path
    // 未启用时无损耗, 但一旦启用就是 hot loop 里的关键调用 (每 list[i] 至少 1 次调用)
    jclass g_reflectArrayCls = nullptr;
    jmethodID g_reflectArrayGetLength = nullptr;
    jmethodID g_reflectArrayGet = nullptr;

    // 获取 JNIEnv (从 JS 执行线程)
    JNIEnv *getJniEnv() {
        if (!JavaObjectClass::cachedJvm) return nullptr;
        JNIEnv *env = nullptr;
        static char threadName[] = "quickjs-trap";
        JavaVMAttachArgs args = {JNI_VERSION_1_6, threadName, nullptr};
        jint ret = JavaObjectClass::cachedJvm->GetEnv((void **) &env, JNI_VERSION_1_6);
        if (ret == JNI_EDETACHED) {
            // 当前线程未 attached,尝试 attach
            // 注意: 如果 JS 在主线程执行,主线程已 attached,这里不会触发
            // Android NDK 27 的 AttachCurrentThread 严格要求 JNIEnv**,
            // 标准 JDK (桌面 JVM) 接收 void**, 用条件编译兼容两端
#ifdef __ANDROID__
            ret = JavaObjectClass::cachedJvm->AttachCurrentThread((JNIEnv **) &env, &args);
#else
            ret = JavaObjectClass::cachedJvm->AttachCurrentThread((void **) &env, &args);
#endif
            if (ret != JNI_OK) return nullptr;
        }
        return env;
    }

    // 初始化 JavaObjectBridgeNative 及相关缓存的方法 ID。
    void ensureBridgeInited(JNIEnv *env) {
        if (__atomic_load_n(&g_bridgeInited, __ATOMIC_ACQUIRE)) return;
        pthread_mutex_lock(&g_bridgeInitMutex);
        if (g_bridgeInited) {
            pthread_mutex_unlock(&g_bridgeInitMutex);
            return;
        }
        {
            jclass local = env->FindClass("com/script/quickjs/JavaObjectBridgeNative");
            if (!local) {
                LOGE("JavaObjectBridgeNative class not found");
                env->ExceptionClear();
                return;
            }
            g_bridgeCls = (jclass) env->NewGlobalRef(local);
            env->DeleteLocalRef(local);

            // hasProperty(obj, name, dangerousApi): Boolean
            g_hasProperty = env->GetStaticMethodID(g_bridgeCls, "hasProperty",
                                                   "(Ljava/lang/Object;Ljava/lang/String;Z)Z");
            // getPropertyInfo(obj, name, dangerousApi): Any? (哨兵协议, 见 g_methodMarker/g_nullFieldMarker)
            g_getPropertyInfo = env->GetStaticMethodID(g_bridgeCls, "getPropertyInfo",
                                                       "(Ljava/lang/Object;Ljava/lang/String;Z)Ljava/lang/Object;");
            // setProperty(obj, name, value, dangerousApi): Boolean
            g_setProperty = env->GetStaticMethodID(g_bridgeCls, "setProperty",
                                                   "(Ljava/lang/Object;Ljava/lang/String;Ljava/lang/Object;Z)Z");
            // getPropertyNames(obj, dangerousApi): Array<String>
            g_getPropertyNames = env->GetStaticMethodID(g_bridgeCls, "getPropertyNames",
                                                        "(Ljava/lang/Object;Z)[Ljava/lang/String;");
            g_newArrayLike = env->GetStaticMethodID(g_bridgeCls, "newArrayLike",
                    "(Ljava/lang/Object;[Ljava/lang/Object;)Ljava/lang/Object;");

            // java.util.List (用于 Symbol.iterator 检测: 让 JS for...of 能迭代 Java List)
            // JavaObjectBridge.kt 的 callHotTypeMethod 已对 List 做快速路径,
            // 但 JS for...of 需要对象实现 Symbol.iterator 协议, native 层在此补充。
            jclass localList = env->FindClass("java/util/List");
            if (localList) {
                g_listCls = (jclass) env->NewGlobalRef(localList);
                env->DeleteLocalRef(localList);
                g_listSize = env->GetMethodID(g_listCls, "size", "()I");
                g_listGet = env->GetMethodID(g_listCls, "get", "(I)Ljava/lang/Object;");
            } else {
                env->ExceptionClear();
            }

            // java.lang.Object.getClass() + java.lang.Class.isArray()
            // 用于检测 Java 数组 (配合 Symbol.iterator 支持 for...of 迭代数组)
            jclass localObject = env->FindClass("java/lang/Object");
            if (localObject) {
                g_objectCls = (jclass) env->NewGlobalRef(localObject);
                env->DeleteLocalRef(localObject);
                g_getClass = env->GetMethodID(g_objectCls, "getClass", "()Ljava/lang/Class;");
            } else {
                env->ExceptionClear();
            }
            jclass localClass = env->FindClass("java/lang/Class");
            if (localClass) {
                g_classCls = (jclass) env->NewGlobalRef(localClass);
                env->DeleteLocalRef(localClass);
                g_isArray = env->GetMethodID(g_classCls, "isArray", "()Z");
            } else {
                env->ExceptionClear();
            }

            // java.lang.Boolean 缓存 (setProperty 等分支仍会用到 Boolean.valueOf/booleanValue)
            jclass localBool = env->FindClass("java/lang/Boolean");
            if (localBool) {
                g_BooleanCls = (jclass) env->NewGlobalRef(localBool);
                env->DeleteLocalRef(localBool);
                g_BooleanValueOf = env->GetStaticMethodID(g_BooleanCls, "valueOf",
                                                          "(Z)Ljava/lang/Boolean;");
                g_BooleanValue = env->GetMethodID(g_BooleanCls, "booleanValue", "()Z");
            } else {
                env->ExceptionClear();
            }

            // java.lang.reflect.Array (整数索引 fast path 用 getLength/get)
            jclass localRefArr = env->FindClass("java/lang/reflect/Array");
            if (localRefArr) {
                g_reflectArrayCls = (jclass) env->NewGlobalRef(localRefArr);
                env->DeleteLocalRef(localRefArr);
                g_reflectArrayGetLength = env->GetStaticMethodID(
                        g_reflectArrayCls, "getLength", "(Ljava/lang/Object;)I");
                g_reflectArrayGet = env->GetStaticMethodID(
                        g_reflectArrayCls, "get", "(Ljava/lang/Object;I)Ljava/lang/Object;");
            } else {
                env->ExceptionClear();
            }

            // 读取 JavaObjectBridge 的哨兵单例并转 GlobalRef。
            // METHOD_MARKER / NULL_FIELD_MARKER 是 Kotlin object 里 @JvmField 的 val, 生成为 static 字段。
            jmethodID getMethodMarker = env->GetStaticMethodID(
                    g_bridgeCls, "getMethodMarker", "()Ljava/lang/Object;");
            jmethodID getNullFieldMarker = env->GetStaticMethodID(
                    g_bridgeCls, "getNullFieldMarker", "()Ljava/lang/Object;");
            if (getMethodMarker && getNullFieldMarker) {
                jobject methodLocal = env->CallStaticObjectMethod(g_bridgeCls, getMethodMarker);
                if (methodLocal) {
                    g_methodMarker = env->NewGlobalRef(methodLocal);
                    env->DeleteLocalRef(methodLocal);
                }
                jobject nullLocal = env->CallStaticObjectMethod(g_bridgeCls, getNullFieldMarker);
                if (nullLocal) {
                    g_nullFieldMarker = env->NewGlobalRef(nullLocal);
                    env->DeleteLocalRef(nullLocal);
                }
            }
            if (env->ExceptionCheck()) env->ExceptionClear();

            // 汇总检查放到末尾: Boolean 与 sentinel 在上面才初始化, 提前判会误报。
            if (!g_hasProperty || !g_getPropertyInfo || !g_setProperty || !g_getPropertyNames ||
                    !g_newArrayLike || !g_BooleanCls || !g_BooleanValue ||
                !g_methodMarker || !g_nullFieldMarker) {
                LOGE("JavaObjectBridgeNative methods/Boolean/sentinel not found");
                env->ExceptionClear();
            }
        }
        __atomic_store_n(&g_bridgeInited, true, __ATOMIC_RELEASE);
        pthread_mutex_unlock(&g_bridgeInitMutex);
    }

    // 检测 atom 是否为 Symbol.iterator (well-known symbol)。
    // 缓存在 CtxOpaqueData::symbolIteratorAtom (initCtxOpaque 时经 globalThis.Symbol.iterator
    // + JS_ValueToAtom 拿到真正的 SYMBOL 类型 well-known atom, 不能用 JS_NewAtom "Symbol.iterator"),
    // 后续属性访问只做一次 JSAtom (uint32_t) 数值比较, 免掉 JS_AtomToCString + strcmp + JS_FreeCString。
    inline bool isSymbolIterator(JSContext *ctx, JSAtom atom) {
        auto *data = (CtxOpaqueData *) JS_GetContextOpaque(ctx);
        // 排除 JS_ATOM_NULL: 初始化失败时 symbolIteratorAtom 保留 JS_ATOM_NULL, 别把
        // 传入的普通 null atom 误判为 Symbol.iterator。
        return data && data->symbolIteratorAtom != JS_ATOM_NULL &&
               atom == data->symbolIteratorAtom;
    }
    // 已知: hasProperty / getOwnProperty trap 未接同一分支, 所以
    // `'Symbol.iterator' in list` / `Reflect.has` / `getOwnPropertyDescriptor` 会返回 false。
    // for..of / [...list] / 解构 / Array.from 等真实迭代都走 getProperty, 不受影响;
    // 书源无人写此类自检, 暂不修。

    // atom 是否为 tagged int 编码 (数组索引): 最高位 (1<<31) 置位, 低 31 位即 uint32 索引。
    // 这是 quickjs-ng 内部约定 (__JS_AtomIsTaggedInt 是 static inline, 未公开导出),
    // 属性访问相关的字节码/JS_GetPropertyValue 全都基于此编码, 长期稳定。为省
    // atomToCString + NewStringUTF + JNI 往返 + Kotlin 侧 propertyInfoCache lookup + toIntOrNull
    // 一整套开销 (每 list[i] 访问都要跑一遍), 在 trap 入口做一次纯 CPU 位测试。
    // 若未来 quickjs-ng 改动 atom 编码, 这里会退化为"永远不走 fast path", 慢路径仍完整可用。
    inline bool isIndexAtom(JSAtom atom, uint32_t *out) {
        constexpr uint32_t kTagInt = 1U << 31;
        if (atom & kTagInt) {
            *out = atom & ~kTagInt;
            return true;
        }
        return false;
    }

    // 整数索引 fast path: 仅当 javaObj 是 List 或 Java 数组时命中, 否则返回 false 落慢路径。
    // 命中时 *out 已装入结果 (可能是 JS_UNDEFINED, 表示越界), 调用方直接 return。
    //
    // 安全性: JsSecurityPolicy 的 isObjectVisible 对 List / 数组 (非 protectedClasses) 永远返回 true,
    // 慢路径的 gate 也不会拦截 list[i], 这里跳过重复调用等价。
    bool tryFastIndexGet(JSContext *ctx, JNIEnv *env, jobject javaObj, uint32_t idx, JSValue *out) {
        // List: 走 List.size() / List.get(i)
        if (g_listCls && env->IsInstanceOf(javaObj, g_listCls)) {
            jint size = env->CallIntMethod(javaObj, g_listSize);
            if (env->ExceptionCheck()) {
                // 慢路径也会遇到并处理, 让上层继续
                env->ExceptionClear();
                return false;
            }
            if (idx >= (uint32_t) size) {
                *out = JS_UNDEFINED;
                return true;
            }
            jobject elem = env->CallObjectMethod(javaObj, g_listGet, (jint) idx);
            if (env->ExceptionCheck()) {
                env->ExceptionClear();
                if (elem) env->DeleteLocalRef(elem);
                return false;
            }
            *out = JniValueConvert::fromJavaObject(ctx, env, elem);
            if (elem) env->DeleteLocalRef(elem);
            return true;
        }
        // Java 数组: 走 reflect.Array.getLength / reflect.Array.get
        if (g_reflectArrayCls && g_getClass && g_isArray) {
            jobject classObj = env->CallObjectMethod(javaObj, g_getClass);
            if (env->ExceptionCheck()) {
                env->ExceptionClear();
                if (classObj) env->DeleteLocalRef(classObj);
                return false;
            }
            if (!classObj) return false;
            jboolean isArr = env->CallBooleanMethod(classObj, g_isArray);
            env->DeleteLocalRef(classObj);
            if (env->ExceptionCheck()) {
                env->ExceptionClear();
                return false;
            }
            if (isArr != JNI_TRUE) return false;
            jint size = env->CallStaticIntMethod(g_reflectArrayCls, g_reflectArrayGetLength,
                                                 javaObj);
            if (env->ExceptionCheck()) {
                env->ExceptionClear();
                return false;
            }
            if (idx >= (uint32_t) size) {
                *out = JS_UNDEFINED;
                return true;
            }
            jobject elem = env->CallStaticObjectMethod(
                    g_reflectArrayCls, g_reflectArrayGet, javaObj, (jint) idx);
            if (env->ExceptionCheck()) {
                env->ExceptionClear();
                if (elem) env->DeleteLocalRef(elem);
                return false;
            }
            *out = JniValueConvert::fromJavaObject(ctx, env, elem);
            if (elem) env->DeleteLocalRef(elem);
            return true;
        }
        return false;
    }

    // 判断 Java 对象是否为 List 或 Java 数组 (用于 Symbol.iterator 支持)
    bool isJavaListOrArray(JNIEnv *env, jobject javaObj) {
        if (!javaObj) return false;
        // 优先检测 List (最常见场景: getStringList 返回 List<String>)
        if (env->IsInstanceOf(javaObj, g_listCls)) return true;
        // 再检测 Java 数组 (通过 getClass().isArray() 反射)
        if (g_getClass && g_isArray) {
            jobject classObj = env->CallObjectMethod(javaObj, g_getClass);
            if (env->ExceptionCheck()) {
                env->ExceptionClear();
                if (classObj) env->DeleteLocalRef(classObj);
                return false;
            }
            jboolean isArray = env->CallBooleanMethod(classObj, g_isArray);
            if (env->ExceptionCheck()) {
                env->ExceptionClear();
                env->DeleteLocalRef(classObj);
                return false;
            }
            env->DeleteLocalRef(classObj);
            return isArray == JNI_TRUE;
        }
        return false;
    }

    // 判断 Java 对象是否为 Java 数组 (不含 List)
    // 对齐 rhino: NativeJavaArray (Java 数组) 的 prototype 是 Array.prototype,
    // NativeJavaList (List) 的 prototype 是 Object.prototype。设置 Array.prototype
    // 时必须只针对真正的 Java 数组, 避免给 List 引入 rhino 没有的 slice/map/filter 行为。
    bool isJavaArray(JNIEnv *env, jobject javaObj) {
        if (!javaObj) return false;
        if (!g_getClass || !g_isArray) return false;
        jobject classObj = env->CallObjectMethod(javaObj, g_getClass);
        if (env->ExceptionCheck()) {
            env->ExceptionClear();
            if (classObj) env->DeleteLocalRef(classObj);
            return false;
        }
        jboolean isArray = env->CallBooleanMethod(classObj, g_isArray);
        if (env->ExceptionCheck()) {
            env->ExceptionClear();
            env->DeleteLocalRef(classObj);
            return false;
        }
        env->DeleteLocalRef(classObj);
        return isArray == JNI_TRUE;
    }

    // 获取 Array.prototype (带 ctx 级缓存, 返回 DupValue, 调用方负责 FreeValue)
    // 对齐 rhino NativeJavaArray.getPrototype(): Java 数组包装对象的 prototype 设为
    // Array.prototype, 让 slice/map/filter/forEach/indexOf/join 等 Array.prototype
    // 方法通过原型链可用。这些方法内部通过 this.length 和 this[i] 访问元素,
    // 由 JavaObject exotic trap 处理 (getCollectionField 已支持 length/索引)。
    // 缓存到 CtxOpaqueData::arrayProto, 避免每次 wrap 数组都查找 global.Array.prototype。
    JSValue getArrayPrototype(JSContext *ctx) {
        auto *data = (CtxOpaqueData *) JS_GetContextOpaque(ctx);
        if (!data) return JS_UNDEFINED;
        if (JS_IsUndefined(data->arrayProto)) {
            JSValue global = JS_GetGlobalObject(ctx);
            JSValue arrayCtor = JS_GetPropertyStr(ctx, global, "Array");
            JS_FreeValue(ctx, global);
            if (JS_IsException(arrayCtor) || JS_IsNull(arrayCtor) || JS_IsUndefined(arrayCtor)) {
                return arrayCtor;
            }
            JSValue proto = JS_GetPropertyStr(ctx, arrayCtor, "prototype");
            JS_FreeValue(ctx, arrayCtor);
            if (JS_IsException(proto) || JS_IsNull(proto) || JS_IsUndefined(proto)) {
                return proto;
            }
            // 缓存 (DupValue 增加引用计数, ctx 销毁时 freeCtxOpaque 释放)
            data->arrayProto = JS_DupValue(ctx, proto);
            return proto;  // proto 本身是 +1 (JS_GetPropertyStr 返回 DupValue)
        }
        return JS_DupValue(ctx, data->arrayProto);
    }

    JSValue wrapArrayLikeValues(JSContext *ctx, JNIEnv *env, jobject source,
            jobjectArray values) {
        if (!g_newArrayLike) {
            return JS_ThrowInternalError(ctx, "JavaObjectBridgeNative.newArrayLike not bound");
        }
        jobject result = env->CallStaticObjectMethod(g_bridgeCls, g_newArrayLike, source, values);
        if (env->ExceptionCheck()) {
            jthrowable thr = env->ExceptionOccurred();
            env->ExceptionClear();
            if (thr) {
                // 异常穿桥根因修复: 不再 wrap JavaObject, 改标准 Error (见 jni_callbacks.h)
                throwJavaExceptionAsJsError(ctx, env, thr, "Creating Java array result failed");
                return JS_EXCEPTION;
            }
            return JS_ThrowInternalError(ctx, "Creating Java array result failed");
        }
        JSValue wrapped = JavaObjectClass::wrap(ctx, env, result);
        if (result) env->DeleteLocalRef(result);
        return wrapped;
    }

    JSValue jsJavaArraySlice(JSContext *ctx, JSValueConst this_val,
            int argc, JSValueConst *argv) {
        JNIEnv *env = getJniEnv();
        if (!env) return JS_ThrowInternalError(ctx, "JNI env unavailable for array slice");
        ensureBridgeInited(env);
        jobject source = JavaObjectClass::getJavaObject(ctx, this_val);
        if (!source || !isJavaArray(env, source)) {
            return JS_ThrowTypeError(ctx, "Array.slice called on non-Java array");
        }
        if (!g_reflectArrayCls || !g_reflectArrayGetLength || !g_reflectArrayGet || !g_objectCls) {
            return JS_ThrowInternalError(ctx, "Java array reflection not bound");
        }
        jint length = env->CallStaticIntMethod(g_reflectArrayCls, g_reflectArrayGetLength, source);
        if (env->ExceptionCheck()) {
            env->ExceptionClear();
            return JS_ThrowInternalError(ctx, "Array.getLength threw");
        }
        int64_t start = 0;
        int64_t end = length;
        if (argc > 0 && !JS_IsUndefined(argv[0]) && JS_ToInt64(ctx, &start, argv[0]) < 0) {
            return JS_EXCEPTION;
        }
        if (argc > 1 && !JS_IsUndefined(argv[1]) && JS_ToInt64(ctx, &end, argv[1]) < 0) {
            return JS_EXCEPTION;
        }
        start = start < 0 ? length + start : start;
        end = end < 0 ? length + end : end;
        if (start < 0) start = 0;
        if (start > length) start = length;
        if (end < 0) end = 0;
        if (end > length) end = length;
        if (end < start) end = start;

        jobjectArray values = env->NewObjectArray((jsize) (end - start), g_objectCls, nullptr);
        if (!values) return JS_ThrowOutOfMemory(ctx);
        for (int64_t i = start; i < end; ++i) {
            jobject value = env->CallStaticObjectMethod(g_reflectArrayCls, g_reflectArrayGet,
                    source, (jint) i);
            if (env->ExceptionCheck()) {
                env->ExceptionClear();
                if (value) env->DeleteLocalRef(value);
                env->DeleteLocalRef(values);
                return JS_ThrowInternalError(ctx, "Array.get threw");
            }
            env->SetObjectArrayElement(values, (jsize) (i - start), value);
            if (value) env->DeleteLocalRef(value);
        }
        JSValue result = wrapArrayLikeValues(ctx, env, source, values);
        env->DeleteLocalRef(values);
        return result;
    }

    JSValue jsJavaArrayMap(JSContext *ctx, JSValueConst this_val,
            int argc, JSValueConst *argv) {
        if (argc < 1 || !JS_IsFunction(ctx, argv[0])) {
            return JS_ThrowTypeError(ctx, "Array.map callback must be a function");
        }
        JNIEnv *env = getJniEnv();
        if (!env) return JS_ThrowInternalError(ctx, "JNI env unavailable for array map");
        ensureBridgeInited(env);
        jobject source = JavaObjectClass::getJavaObject(ctx, this_val);
        if (!source || !isJavaArray(env, source)) {
            return JS_ThrowTypeError(ctx, "Array.map called on non-Java array");
        }
        if (!g_reflectArrayCls || !g_reflectArrayGetLength || !g_reflectArrayGet || !g_objectCls) {
            return JS_ThrowInternalError(ctx, "Java array reflection not bound");
        }
        jint length = env->CallStaticIntMethod(g_reflectArrayCls, g_reflectArrayGetLength, source);
        if (env->ExceptionCheck()) {
            env->ExceptionClear();
            return JS_ThrowInternalError(ctx, "Array.getLength threw");
        }
        jobjectArray values = env->NewObjectArray(length, g_objectCls, nullptr);
        if (!values) return JS_ThrowOutOfMemory(ctx);
        JSValueConst callbackThis = argc > 1 ? argv[1] : JS_UNDEFINED;
        for (jint i = 0; i < length; ++i) {
            jobject element = env->CallStaticObjectMethod(g_reflectArrayCls, g_reflectArrayGet,
                    source, i);
            if (env->ExceptionCheck()) {
                env->ExceptionClear();
                if (element) env->DeleteLocalRef(element);
                env->DeleteLocalRef(values);
                return JS_ThrowInternalError(ctx, "Array.get threw");
            }
            JSValue args[3] = {
                    JniValueConvert::fromJavaObject(ctx, env, element),
                    JS_NewInt32(ctx, i),
                    JS_DupValue(ctx, this_val)
            };
            if (element) env->DeleteLocalRef(element);
            JSValue mapped = JS_Call(ctx, argv[0], callbackThis, 3, args);
            for (auto &arg: args) JS_FreeValue(ctx, arg);
            if (JS_IsException(mapped)) {
                env->DeleteLocalRef(values);
                return mapped;
            }
            jobject mappedValue = JniValueConvert::toJavaObject(ctx, env, mapped);
            JS_FreeValue(ctx, mapped);
            if (env->ExceptionCheck()) {
                env->ExceptionClear();
                if (mappedValue) env->DeleteLocalRef(mappedValue);
                env->DeleteLocalRef(values);
                return JS_ThrowTypeError(ctx, "Array.map result cannot be converted to Java");
            }
            env->SetObjectArrayElement(values, i, mappedValue);
            if (mappedValue) env->DeleteLocalRef(mappedValue);
        }
        JSValue result = wrapArrayLikeValues(ctx, env, source, values);
        env->DeleteLocalRef(values);
        return result;
    }

    // Symbol.iterator 工厂函数: 把 Java List/Array 转成 JS Array 并返回其迭代器
    // for...of 会调用 obj[Symbol.iterator]() 获取迭代器, 本函数即为此调用的返回值。
    // this_val 是 Java List/Array 对象 (JavaObject class 实例)
    // 实现: 拷贝 Java 集合元素到 JS Array, 返回 Array.prototype.values 的调用结果
    // (Array 的默认迭代器), 复用 QuickJS 内置 Array 迭代器逻辑。
    // 注意: 用 JSCFunction 签名 (而非 JSCFunctionData), 因为 this_val 已携带
    // Java 对象引用, 无需额外 func_data。
    JSValue jsJavaListSymbolIterator(JSContext *ctx, JSValueConst this_val,
                                     int argc, JSValueConst *argv) {
        JNIEnv *env = getJniEnv();
        if (!env) {
            // 契约: 返回 JS_EXCEPTION 必须先设置 ctx 异常 slot
            return JS_ThrowInternalError(ctx, "JNI env unavailable for Symbol.iterator");
        }
        ensureBridgeInited(env);
        if (!g_listCls) {
            return JS_ThrowInternalError(ctx, "java.util.List class not bound");
        }

        jobject javaObj = JavaObjectClass::getJavaObject(ctx, this_val);
        if (!javaObj) {
            return JS_ThrowTypeError(ctx, "Symbol.iterator on non-Java object");
        }

        // 创建 JS Array
        JSValue arr = JS_NewArray(ctx);
        uint32_t jsIndex = 0;

        // 判断是 List 还是 Java 数组
        bool isList = env->IsInstanceOf(javaObj, g_listCls);
        if (isList) {
            // Java List: 调用 size() 和 get(i)
            jint size = env->CallIntMethod(javaObj, g_listSize);
            if (env->ExceptionCheck()) {
                env->ExceptionClear();
                JS_FreeValue(ctx, arr);
                return JS_ThrowInternalError(ctx, "List.size() threw");
            }
            for (jint i = 0; i < size; i++) {
                jobject elem = env->CallObjectMethod(javaObj, g_listGet, i);
                if (env->ExceptionCheck()) {
                    env->ExceptionClear();
                    if (elem) env->DeleteLocalRef(elem);
                    continue;
                }
                JSValue elemVal = JniValueConvert::fromJavaObject(ctx, env, elem);
                JS_SetPropertyUint32(ctx, arr, jsIndex++, elemVal);
                if (elem) env->DeleteLocalRef(elem);
            }
        } else {
            // Java 数组: 用 java.lang.reflect.Array (类和方法 ID 已在 ensureBridgeInited 缓存)
            if (!g_reflectArrayCls || !g_reflectArrayGetLength || !g_reflectArrayGet) {
                JS_FreeValue(ctx, arr);
                return JS_ThrowInternalError(ctx, "java.lang.reflect.Array not bound");
            }
            jint size = env->CallStaticIntMethod(g_reflectArrayCls, g_reflectArrayGetLength,
                                                 javaObj);
            if (env->ExceptionCheck()) {
                env->ExceptionClear();
                JS_FreeValue(ctx, arr);
                return JS_ThrowInternalError(ctx, "Array.getLength threw");
            }
            for (jint i = 0; i < size; i++) {
                jobject elem = env->CallStaticObjectMethod(g_reflectArrayCls, g_reflectArrayGet,
                                                           javaObj, i);
                if (env->ExceptionCheck()) {
                    env->ExceptionClear();
                    if (elem) env->DeleteLocalRef(elem);
                    continue;
                }
                JSValue elemVal = JniValueConvert::fromJavaObject(ctx, env, elem);
                JS_SetPropertyUint32(ctx, arr, jsIndex++, elemVal);
                if (elem) env->DeleteLocalRef(elem);
            }
        }

        // 返回 Array 的 [Symbol.iterator]() 结果
        // Array.prototype.values 返回 Array 的默认迭代器 (value iterator)
        // for...of 通过此迭代器的 next() 方法逐个获取元素
        JSValue valuesFn = JS_GetPropertyStr(ctx, arr, "values");
        JSValue iter = JS_Call(ctx, valuesFn, arr, 0, nullptr);
        JS_FreeValue(ctx, valuesFn);
        JS_FreeValue(ctx, arr);
        return iter;
    }
}

JSClassID JavaObjectClass::init(JSRuntime *rt, JavaVM *jvm) {

    // nativeCreateContext 可能并发调用, 整段加锁。
    pthread_mutex_lock(&registryMutex);
    cachedJvm = jvm;

    // classId 是进程级 (一个数字), 首次分配后跨 runtime 复用。
    if (classId == 0) JS_NewClassID(rt, &classId);

    // JS_NewClass 是 runtime-scoped, 每个 rt 都要注册一次; 未注册的 rt 上 JS_NewObjectClass 会失败。
    bool alreadyRegistered = false;
    for (uint32_t i = 0; i < registeredCount; i++) {
        if (registeredRuntimes[i] == rt) {
            alreadyRegistered = true;
            break;
        }
    }
    if (!alreadyRegistered) {
        // exotic/def 必须 static: JSClassDef 只存指针。
        static JSClassExoticMethods exotic = {};
        exotic.has_property = &JavaObjectClass::hasProperty;
        exotic.get_property = &JavaObjectClass::getProperty;
        exotic.set_property = &JavaObjectClass::setProperty;
        exotic.delete_property = &JavaObjectClass::deleteProperty;
        exotic.get_own_property = &JavaObjectClass::getOwnProperty;
        exotic.get_own_property_names = &JavaObjectClass::getOwnPropertyNames;

        static JSClassDef def = {};
        def.class_name = "JavaObject";
        def.finalizer = &JavaObjectClass::finalizer;
        def.exotic = &exotic;

        int ret = JS_NewClass(rt, classId, &def);

        if (ret != 0) {
            LOGE("JS_NewClass failed for JavaObject: ret=%d", ret);
            pthread_mutex_unlock(&registryMutex);
            return 0;
        }
        if (registeredCount >= registeredCap) {
            uint32_t newCap = registeredCap == 0 ? 8 : registeredCap * 2;
            JSRuntime **nb = (JSRuntime **) std::realloc(registeredRuntimes,
                                                         newCap * sizeof(JSRuntime *));
            if (!nb) {
                LOGE("registeredRuntimes realloc failed");
                pthread_mutex_unlock(&registryMutex);
                return classId;
            }
            registeredRuntimes = nb;
            registeredCap = newCap;
        }
        registeredRuntimes[registeredCount++] = rt;
    }
    pthread_mutex_unlock(&registryMutex);
    return classId;
}

JSClassID JavaObjectClass::getClassId() { return classId; }

void JavaObjectClass::unregisterRuntime(JSRuntime *rt) {
    if (!rt) return;
    pthread_mutex_lock(&registryMutex);
    // swap-with-last 删除。
    for (uint32_t i = 0; i < registeredCount; i++) {
        if (registeredRuntimes[i] == rt) {
            registeredRuntimes[i] = registeredRuntimes[--registeredCount];
            break;
        }
    }
    pthread_mutex_unlock(&registryMutex);
}

void JavaObjectClass::initBridgeCache(JNIEnv *env) { ensureBridgeInited(env); }

JSValue JavaObjectClass::wrap(JSContext *ctx, JNIEnv *env, jobject javaObj) {
    if (classId == 0) {
        // 旧实现 return JS_NULL 会让所有调用方(trap/callback 的 JS_Throw(errObj))
        // 抛 null, JS catch(e) 拿到 null 二次抛 TypeError, 原始异常信息完全丢失。
        // 改用 JS_ThrowInternalError: 自建 Error 对象 + JS_Throw 设到 current_exception,
        // 返回 JS_EXCEPTION。调用方必须检查 JS_IsException 避免再 JS_Throw 覆盖
        // (JS_Throw 不检测 JS_EXCEPTION, 会 JS_FreeValue(current_exception) 后赋值 JS_EXCEPTION,
        // 清空已设的 Error 对象)。
        LOGE("JavaObjectClass not initialized");
        return JS_ThrowInternalError(ctx, "JavaObjectClass not initialized");
    }
    if (!javaObj) return JS_NULL;  // Java null 转 JS null (合理语义)

    // 创建全局引用,存入 opaque
    jobject globalRef = env->NewGlobalRef(javaObj);

    // 创建 JavaObject 类实例
    JSValue obj = JS_NewObjectClass(ctx, classId);
    if (JS_IsException(obj)) {
        env->DeleteGlobalRef(globalRef);
        // JS_NewObjectClass 失败(通常 OOM)时 ctx 异常 slot 可能已设,
        // JS_ThrowInternalError 会 JS_FreeValue 旧异常后设新 Error, 返回 JS_EXCEPTION。
        LOGE("JS_NewObjectClass failed for JavaObject");
        return JS_ThrowInternalError(ctx, "JS_NewObjectClass failed for JavaObject");
    }

    // 存全局引用到 opaque 槽
    JS_SetOpaque(obj, globalRef);

    // 对齐 rhino NativeJavaArray.getPrototype(): Java 数组的 prototype 设为 Array.prototype,
    // 让 slice/map/filter/forEach/indexOf/join 等数组方法通过原型链可用。
    // 仅对真正的 Java 数组 (getClass().isArray()), 不对 List 设 (rhino NativeJavaList
    // 的 prototype 是 Object.prototype, 不暴露 Array.prototype 方法)。
    // 例: this.java.base64DecodeToByteArray(...) 返回 byte[], JS 中 raw.slice(0,12) 可用。
    if (isJavaArray(env, javaObj)) {
        JSValue proto = getArrayPrototype(ctx);
        if (!JS_IsException(proto) && !JS_IsNull(proto) && !JS_IsUndefined(proto)) {
            // JS_SetPrototype 内部会 DupValue, 这里释放 getArrayPrototype 返回的本地引用
            JS_SetPrototype(ctx, obj, proto);
        }
        JS_FreeValue(ctx, proto);
    }

    return obj;
}

bool JavaObjectClass::isInstance(JSContext *ctx, JSValueConst val) {
    if (classId == 0) return false;
    return JS_GetOpaque(val, classId) != nullptr;
}

jobject JavaObjectClass::getJavaObject(JSContext *ctx, JSValueConst val) {
    if (classId == 0) return nullptr;
    return (jobject) JS_GetOpaque(val, classId);
}

const char *JavaObjectClass::atomToCString(JSContext *ctx, JSAtom atom) {
    // JS_AtomToCString 返回的字符串需要 JS_FreeCString 释放
    // 注意: 调用方负责释放
    return JS_AtomToCString(ctx, atom);
}

// ============ exotic trap 实现 ============
// 所有 trap 从 ctx opaque 读取 dangerousApi, 传递给 Java 侧

int JavaObjectClass::hasProperty(JSContext *ctx, JSValueConst obj, JSAtom prop) {

    JNIEnv *env = getJniEnv();
    if (!env) {
        JS_ThrowInternalError(ctx, "JNI env unavailable in hasProperty");
        return -1;
    }
    ensureBridgeInited(env);
    if (!g_hasProperty) {
        JS_ThrowInternalError(ctx, "JavaObjectBridgeNative.hasProperty not bound");
        return -1;
    }

    jobject javaObj = getJavaObject(ctx, obj);
    if (!javaObj) {
        JS_ThrowInternalError(ctx, "Java object opaque is null in hasProperty");
        return -1;
    }

    const char *name = atomToCString(ctx, prop);
    if (!name) {
        JS_ThrowInternalError(ctx, "atom to string failed in hasProperty");
        return -1;
    }

    jstring jname = env->NewStringUTF(name);
    JS_FreeCString(ctx, name);

    bool dangerousApi = getDangerousApi(ctx);
    jboolean result = env->CallStaticBooleanMethod(g_bridgeCls, g_hasProperty,
                                                   javaObj, jname,
                                                   dangerousApi ? JNI_TRUE : JNI_FALSE);
    env->DeleteLocalRef(jname);

    if (env->ExceptionCheck()) {
        // 异常穿桥根因修复: 不再 wrap JavaObject, 改标准 Error (见 jni_callbacks.h)
        jthrowable thr = env->ExceptionOccurred();
        env->ExceptionClear();
        if (thr) {
            throwJavaExceptionAsJsError(ctx, env, thr, "Java hasProperty threw");
            return -1;
        }
        JS_ThrowInternalError(ctx, "Java hasProperty threw (no throwable)");
        return -1;
    }
    if (!result) {
        // Java 侧属性不存在, 检查原型链 (对齐 rhino, 见 getProperty trap 中同类注释)
        // 例: byte[] 数组 raw 的 'slice' in raw → Java 侧无 slice → 沿原型链找 Array.prototype.slice
        JSValue proto = JS_GetPrototype(ctx, obj);
        if (JS_IsException(proto)) {
            return -1;
        }
        if (!JS_IsNull(proto)) {
            // proto 非 exotic, JS_HasProperty 走标准路径, 不递归触发本 trap
            int has = JS_HasProperty(ctx, proto, prop);
            JS_FreeValue(ctx, proto);
            return has;  // -1 异常, 0 不存在, 1 存在
        }
        JS_FreeValue(ctx, proto);
        return 0;
    }
    return result ? 1 : 0;
}

JSValue JavaObjectClass::getProperty(JSContext *ctx, JSValueConst obj, JSAtom atom,
                                     JSValueConst receiver) {
    JNIEnv *env = getJniEnv();
    if (!env) {
        return JS_ThrowInternalError(ctx, "JNI env unavailable in getProperty");
    }
    ensureBridgeInited(env);
    if (!g_getPropertyInfo) {
        return JS_ThrowInternalError(ctx, "JavaObjectBridgeNative.getPropertyInfo not bound");
    }

    // 检测 Symbol.iterator: 让 Java List/Array 可在 JS 中 for...of 迭代
    // 背景: QuickJS 的 for...of 会调用 obj[Symbol.iterator]() 获取迭代器,
    // 若返回 undefined 则报 "value is not iterable"。Java List 通过 JavaObject
    // exotic trap 暴露, 默认不实现 Symbol.iterator 协议, 需在此特殊处理。
    // 仅对 List/Array 返回迭代器工厂函数, 其他 Java 对象仍走原反射路径。
    if (isSymbolIterator(ctx, atom)) {
        jobject javaObjForCheck = getJavaObject(ctx, obj);
        if (javaObjForCheck && isJavaListOrArray(env, javaObjForCheck)) {
            // 返回一个 JS 函数, for...of 会调用它获取迭代器
            // JS_NewCFunction 返回的函数 this 绑定到调用者 (即 Java List/Array 对象)
            return JS_NewCFunction(ctx, jsJavaListSymbolIterator,
                                   "[Symbol.iterator]", 0);
        }
        // 非 List/Array 对象访问 Symbol.iterator 返回 undefined (走标准 JS 行为)
        return JS_UNDEFINED;
    }

    jobject javaObj = getJavaObject(ctx, obj);
    if (!javaObj) return JS_UNDEFINED;

    if (isJavaArray(env, javaObj)) {
        const char *methodName = atomToCString(ctx, atom);
        if (!methodName) return JS_EXCEPTION;
        JSValue arrayMethod = JS_UNDEFINED;
        if (std::strcmp(methodName, "slice") == 0) {
            arrayMethod = JS_NewCFunction(ctx, jsJavaArraySlice, "slice", 2);
        } else if (std::strcmp(methodName, "map") == 0) {
            arrayMethod = JS_NewCFunction(ctx, jsJavaArrayMap, "map", 1);
        }
        JS_FreeCString(ctx, methodName);
        if (!JS_IsUndefined(arrayMethod)) return arrayMethod;
    }

    // 整数索引 fast path: list[i] / arr[i] 是极热循环 (书源常见 pattern).
    // 直接从 atom 位测得到 uint32 索引, 若 javaObj 是 List/Array, 走 List.get(i) /
    // Array.get(arr,i), 省掉 atomToCString + NewStringUTF + JNI 往返 + Kotlin 侧
    // propertyInfoCache lookup + toIntOrNull. 慢路径 (getJavaPropertyRaw) 已通过
    // getCollectionField 处理这两类, fast path 是等价捷径。
    uint32_t fastIdx;
    if (isIndexAtom(atom, &fastIdx)) {
        JSValue fastOut;
        if (tryFastIndexGet(ctx, env, javaObj, fastIdx, &fastOut)) {
            return fastOut;
        }
        // 非 List/Array 的整数索引 (罕见): 落慢路径, 让 Kotlin 侧决定 (通常返回 null)
    }

    const char *name = atomToCString(ctx, atom);
    if (!name) {
        // JS_AtomToCString 失败 (通常 OOM) 时 quickjs 已设过 ctx 异常,
        // 这里若返回 JS_UNDEFINED, 调用方拿到"undefined + ctx 有异常"的
        // 矛盾状态, 后续 JS_GetException 拿到的就是这条 stale 异常,
        // 触发 ref_count 错乱。propagate 异常更安全。
        return JS_EXCEPTION;
    }

    jstring jname = env->NewStringUTF(name);
    JS_FreeCString(ctx, name);

    bool dangerousApi = getDangerousApi(ctx);
    // getPropertyInfo 返回单个 Any? (哨兵协议):
    //   null                    -> 属性不存在, 沿原型链查
    //   g_methodMarker (单例)   -> 有同名方法, 走 method callable
    //   g_nullFieldMarker (单例) -> field 存在但值为 null, 返回 JS_NULL
    //   其他 jobject            -> 该对象即 fieldValue, 直接 fromJavaObject
    // 用 IsSameObject 与 GlobalRef 单例对比, 免掉原先 3 槽数组 + 2 次 Boolean 装箱/解包。
    jobject info = env->CallStaticObjectMethod(g_bridgeCls,
                                               g_getPropertyInfo, javaObj,
                                               jname,
                                               dangerousApi ? JNI_TRUE : JNI_FALSE);
    env->DeleteLocalRef(jname);

    if (env->ExceptionCheck()) {

        // 异常穿桥根因修复: 不再 wrap JavaObject, 改标准 Error (见 jni_callbacks.h)
        jthrowable thr = env->ExceptionOccurred();
        env->ExceptionClear();
        if (info) env->DeleteLocalRef(info);
        if (thr) {

            throwJavaExceptionAsJsError(ctx, env, thr, "Java getPropertyInfo threw");

            return JS_EXCEPTION;
        }
        return JS_ThrowInternalError(ctx, "Java getPropertyInfo threw (no throwable)");
    }

    if (!info) {
        // Java 侧属性不存在, 沿原型链查找 (对齐 rhino NativeJavaObject/NativeJavaArray):
        // rhino 中 Java 对象的 prototype 是 Object.prototype (NativeJavaObject 默认)
        // 或 Array.prototype (NativeJavaArray, 见 wrap 中的 JS_SetPrototype), 属性找不到时
        // 沿原型链查找。QuickJS 的 exotic get_property trap 调用后直接返回, 不会自动沿
        // 原型链, 需在此手动处理。
        // 例: byte[] 数组 raw 的 raw.slice → Java 侧无 slice → 沿原型链找 Array.prototype.slice
        JSValue proto = JS_GetPrototype(ctx, obj);
        if (JS_IsException(proto)) {
            return proto;  // 异常已设到 ctx, 直接返回
        }
        if (!JS_IsNull(proto)) {
            // proto 是 Array.prototype (数组) 或 Object.prototype (普通对象),
            // JS_GetProperty 在 proto 上做标准属性查找 (proto 非 exotic, 不递归触发本 trap)
            JSValue val = JS_GetProperty(ctx, proto, atom);
            JS_FreeValue(ctx, proto);
            return val;
        }
        JS_FreeValue(ctx, proto);
        return JS_UNDEFINED;
    }

    // 决策 (对齐 rhino LiveConnect FieldAndMessages 行为):
    // rhino 中 field 和 method 同名时, method 优先 —— 因此 Kotlin 侧 getJavaPropertyRaw
    // 检出同名方法就直接返回 METHOD_MARKER, 不再回退看 field。
    // 注意: 不要在 exotic get_property trap 内用 JS_DefinePropertyValue 固化 callable。
    // trap 被引擎调用时会持有 obj->shape 指针, trap 内改 obj 会触发 shape 迁移,
    // 老 shape 被释放后引擎读到悬垂指针, 会在后续 JS_GetPrototype 里 SEGV。
    JSValue ret;
    if (env->IsSameObject(info, g_methodMarker)) {
        // 同名方法在所有实例间共享 callable, this 由调用点自动绑定 (rhino 一致)。
        ret = getOrCreateMethodCallable(ctx, atom);
    } else if (env->IsSameObject(info, g_nullFieldMarker)) {
        ret = JS_NULL;
    } else {
        ret = JniValueConvert::fromJavaObject(ctx, env, info);
    }
    env->DeleteLocalRef(info);
    return ret;
}

int JavaObjectClass::setProperty(JSContext *ctx, JSValueConst obj, JSAtom atom,
                                 JSValueConst value, JSValueConst receiver, int flags) {
    JNIEnv *env = getJniEnv();
    if (!env) {
        JS_ThrowInternalError(ctx, "JNI env unavailable in setProperty");
        return -1;
    }
    ensureBridgeInited(env);
    if (!g_setProperty) {
        JS_ThrowInternalError(ctx, "JavaObjectBridgeNative.setProperty not bound");
        return -1;
    }

    jobject javaObj = getJavaObject(ctx, obj);
    if (!javaObj) {
        JS_ThrowInternalError(ctx, "Java object opaque is null in setProperty");
        return -1;
    }

    const char *name = atomToCString(ctx, atom);
    if (!name) {
        JS_ThrowInternalError(ctx, "atom to string failed in setProperty");
        return -1;
    }

    jstring jname = env->NewStringUTF(name);
    JS_FreeCString(ctx, name);

    // JSValue -> jobject (基本类型直接转,对象走 wrap 或句柄)
    jobject javaValue = JniValueConvert::toJavaObject(ctx, env, value);

    // toJavaObject 抛 JsNativeException 后再调 CallStaticBooleanMethod 是 UB:
    // ART 的 JNI 内部表 (LocalReferenceTable / ExceptionState) 在 pending exc 下被写
    // 会破坏相邻分配 (sscudo 还会复用 freed slot 给 quickjs JSString), 表现为远处
    // strv() abort。这里把 pending exc 转成 trap 异常返回 -1, 沿用上面 -1 路径契约。
    if (env->ExceptionCheck()) {
        env->ExceptionClear();
        env->DeleteLocalRef(jname);
        if (javaValue) env->DeleteLocalRef(javaValue);
        JS_ThrowInternalError(ctx, "Java setProperty: value conversion threw");
        return -1;
    }

    bool dangerousApi = getDangerousApi(ctx);
    jboolean result = env->CallStaticBooleanMethod(g_bridgeCls, g_setProperty,
                                                   javaObj, jname, javaValue,
                                                   dangerousApi ? JNI_TRUE : JNI_FALSE);
    if (javaValue) env->DeleteLocalRef(javaValue);
    env->DeleteLocalRef(jname);

    if (env->ExceptionCheck()) {
        // 对齐 rhino WrappedException: 包装原始 Throwable 传给 JS catch
        // (见 jni_callbacks.cpp jsMethodCallable 同类处理)
        jthrowable thr = env->ExceptionOccurred();
        env->ExceptionClear();
        if (thr) {
            // 异常穿桥根因修复: 不再 wrap JavaObject, 改标准 Error (见 jni_callbacks.h)
            throwJavaExceptionAsJsError(ctx, env, thr, "Java setProperty threw");
            return -1;
        }
        JS_ThrowInternalError(ctx, "Java setProperty threw (no throwable)");
        return -1;
    }
    return result ? 1 : 0;
}

int JavaObjectClass::deleteProperty(JSContext *ctx, JSValueConst obj, JSAtom prop) {
    // 不支持删除 Java 对象属性
    return 0;
}

int JavaObjectClass::getOwnProperty(JSContext *ctx, JSPropertyDescriptor *desc,
                                    JSValueConst obj, JSAtom prop) {
    // 只查 Java 侧自有属性, 不沿原型链 (对齐 rhino: slice 等数组方法在原型链上, 不是自有属性)。
    // 原 impl 调 hasProperty + getProperty, 但这俩现在会在 Java 侧返回"不存在"时沿原型链查找
    // (对齐 rhino NativeJavaObject/NativeJavaArray), 导致 getOwnProperty 误把原型链属性当成
    // 自有属性 (如 raw.hasOwnProperty('slice') 错误返回 true)。改为直接调 Java 侧 getPropertyInfo,
    // info==null 时返回 0 (非自有属性), 不沿原型链。
    JNIEnv *env = getJniEnv();
    if (!env) {
        JS_ThrowInternalError(ctx, "JNI env unavailable in getOwnProperty");
        return -1;
    }
    ensureBridgeInited(env);
    if (!g_getPropertyInfo) {
        JS_ThrowInternalError(ctx, "JavaObjectBridgeNative.getPropertyInfo not bound");
        return -1;
    }

    jobject javaObj = getJavaObject(ctx, obj);
    if (!javaObj) return 0;

    const char *name = atomToCString(ctx, prop);
    if (!name) {
        // JS_AtomToCString 失败 (通常 OOM) 时 ctx 异常已设
        return -1;
    }
    jstring jname = env->NewStringUTF(name);
    JS_FreeCString(ctx, name);

    bool dangerousApi = getDangerousApi(ctx);
    // 哨兵协议: 同 getProperty 描述。
    jobject info = env->CallStaticObjectMethod(g_bridgeCls,
                                               g_getPropertyInfo, javaObj,
                                               jname,
                                               dangerousApi ? JNI_TRUE : JNI_FALSE);
    env->DeleteLocalRef(jname);

    if (env->ExceptionCheck()) {
        // 对齐 rhino WrappedException: 包装原始 Throwable 传给 JS catch
        jthrowable thr = env->ExceptionOccurred();
        env->ExceptionClear();
        if (info) env->DeleteLocalRef(info);
        if (thr) {
            // 异常穿桥根因修复: 不再 wrap JavaObject, 改标准 Error (见 jni_callbacks.h)
            throwJavaExceptionAsJsError(ctx, env, thr, "Java getPropertyInfo threw");
            return -1;
        }
        JS_ThrowInternalError(ctx, "Java getPropertyInfo threw (no throwable)");
        return -1;
    }

    if (!info) {
        // Java 侧无此属性, 不是自有属性 (不沿原型链, 对齐 rhino)
        return 0;
    }

    // info 非空: Java 侧有此属性 (field/method/getter/collection field), 是自有属性
    // 直接从 info 转换 value, 不再回调 getProperty 二次跑 JNI:
    // 原实现调 getProperty 会重复 atomToCString + NewStringUTF + CallStaticObjectMethod,
    // 一次属性枚举 (Object.keys / hasOwnProperty) 相当于两倍 JNI 开销。
    if (desc) {
        JSValue val;
        if (env->IsSameObject(info, g_methodMarker)) {
            val = getOrCreateMethodCallable(ctx, prop);
        } else if (env->IsSameObject(info, g_nullFieldMarker)) {
            val = JS_NULL;
        } else {
            val = JniValueConvert::fromJavaObject(ctx, env, info);
        }
        if (JS_IsException(val)) {
            // 不能把 JS_EXCEPTION 写进 desc->value 后返回 1 (success),
            // 调用方会把它当成普通 value 使用, 后续 JS_DupValue/JS_FreeValue
            // 会污染异常 slot, 触发远处 JS_ToCString -> strv abort。
            env->DeleteLocalRef(info);
            return -1;
        }
        desc->flags = JS_PROP_WRITABLE | JS_PROP_ENUMERABLE | JS_PROP_CONFIGURABLE;
        desc->getter = JS_UNDEFINED;
        desc->setter = JS_UNDEFINED;
        desc->value = val;
    }
    env->DeleteLocalRef(info);
    return 1;
}

int JavaObjectClass::getOwnPropertyNames(JSContext *ctx, JSPropertyEnum **ptab,
                                         uint32_t *plen, JSValueConst obj) {
    JNIEnv *env = getJniEnv();
    if (!env) {
        // 契约: trap 返回 -1 必须先在 ctx 设置异常 slot, 否则调用方 JS_GetException
        // 拿到的是上一次 stale 异常对象, 递增 refcount 后释放会导致已释放 JSString 被
        // 再次写入, 表现为远处线程 JS_ToCString -> strv abort (heap corruption)
        *ptab = nullptr;
        *plen = 0;
        JS_ThrowInternalError(ctx, "JNI env unavailable in getOwnPropertyNames");
        return -1;
    }
    ensureBridgeInited(env);
    if (!g_getPropertyNames) {
        *ptab = nullptr;
        *plen = 0;
        JS_ThrowInternalError(ctx, "JavaObjectBridgeNative.getPropertyNames not bound");
        return -1;
    }

    jobject javaObj = getJavaObject(ctx, obj);
    if (!javaObj) {
        *ptab = nullptr;
        *plen = 0;
        return 0;
    }

    bool dangerousApi = getDangerousApi(ctx);
    auto names = (jobjectArray) env->CallStaticObjectMethod(g_bridgeCls,
                                                            g_getPropertyNames, javaObj,
                                                            dangerousApi ? JNI_TRUE
                                                                         : JNI_FALSE);
    if (env->ExceptionCheck()) {
        // 异常穿桥根因修复: 不再 wrap JavaObject, 改标准 Error (见 jni_callbacks.h)
        jthrowable thr = env->ExceptionOccurred();
        env->ExceptionClear();
        if (names) env->DeleteLocalRef(names);
        *ptab = nullptr;
        *plen = 0;
        if (thr) {
            throwJavaExceptionAsJsError(ctx, env, thr, "Java getPropertyNames threw");
            return -1;
        }
        JS_ThrowInternalError(ctx, "Java getPropertyNames threw (no throwable)");
        return -1;
    }

    if (!names) {
        *ptab = nullptr;
        *plen = 0;
        return 0;
    }

    jsize len = env->GetArrayLength(names);
    // 分配 JSPropertyEnum 数组 (调用方负责 js_free)
    *ptab = (JSPropertyEnum *) js_malloc(ctx, sizeof(JSPropertyEnum) * (len > 0 ? len : 1));
    if (!*ptab) {
        env->DeleteLocalRef(names);
        *plen = 0;
        // js_malloc 失败时 quickjs 内部已抛 OOM 异常, 这里无需再 throw
        return -1;
    }
    *plen = len;

    // PushLocalFrame 批量回收循环内 GetObjectArrayElement 产生的 local ref,
    // 省掉每轮 DeleteLocalRef 调用; +16 冗余给 GetStringUTFChars 内部可能产生的
    // 短周期 local ref (Android JNI 实现细节, 保守值)
    if (len > 0 && env->PushLocalFrame(len + 16) == 0) {
        for (jsize i = 0; i < len; i++) {
            auto name = (jstring) env->GetObjectArrayElement(names, i);
            const char *cname = env->GetStringUTFChars(name, nullptr);
            (*ptab)[i].atom = JS_NewAtom(ctx, cname ? cname : "");
            (*ptab)[i].is_enumerable = true;
            env->ReleaseStringUTFChars(name, cname);
        }
        env->PopLocalFrame(nullptr);
    } else {
        // PushLocalFrame 失败退化到逐个 DeleteLocalRef (保持原语义)
        for (jsize i = 0; i < len; i++) {
            auto name = (jstring) env->GetObjectArrayElement(names, i);
            const char *cname = env->GetStringUTFChars(name, nullptr);
            (*ptab)[i].atom = JS_NewAtom(ctx, cname ? cname : "");
            (*ptab)[i].is_enumerable = true;
            env->ReleaseStringUTFChars(name, cname);
            env->DeleteLocalRef(name);
        }
    }
    env->DeleteLocalRef(names);
    return 0;
}

void JavaObjectClass::finalizer(JSRuntime *rt, JSValueConst val) {
    auto globalRef = (jobject) JS_GetOpaque(val, classId);
    if (!globalRef || !cachedJvm) return;

    JNIEnv *env = nullptr;
    jint ret = cachedJvm->GetEnv((void **) &env, JNI_VERSION_1_6);
    if (ret == JNI_EDETACHED) {
        // Android NDK 27 的 AttachCurrentThread 严格要求 JNIEnv**,
        // 标准 JDK (桌面 JVM) 要求 void**, 用条件编译兼容两端
#ifdef __ANDROID__
        ret = cachedJvm->AttachCurrentThread((JNIEnv **) &env, nullptr);
#else
        ret = cachedJvm->AttachCurrentThread((void **) &env, nullptr);
#endif
        if (ret != JNI_OK) return;
    }
    if (env) {
        env->DeleteGlobalRef(globalRef);
    }
}
