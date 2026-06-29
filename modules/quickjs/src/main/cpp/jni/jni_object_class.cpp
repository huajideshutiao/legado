#include "jni_object_class.h"
#include "jni_value_convert.h"
#include "jni_handle.h"
#include "jni_callbacks.h"
#include <android/log.h>
#include <cstring>
#include <mutex>

#define TAG "legado_qjs"
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)

// 静态成员初始化
JSClassID JavaObjectClass::classId = 0;
JavaVM *JavaObjectClass::cachedJvm = nullptr;
std::unordered_set<JSRuntime *> JavaObjectClass::registeredRuntimes;
std::mutex JavaObjectClass::registryMutex;

// 缓存的 Java 方法 ID (JavaObjectBridgeNative 静态方法)
namespace {
    jclass g_bridgeCls = nullptr;
    jmethodID g_hasProperty = nullptr;
    jmethodID g_getPropertyInfo = nullptr;
    jmethodID g_setProperty = nullptr;
    jmethodID g_getPropertyNames = nullptr;
    // Tier 2: method callable 改为共享 + 从 this_val 取对象后, 不再需要 getHandle (已删)
    // 用 std::once_flag 保证 init body 只执行一次, 且执行完成后对其它线程可见
    // (call_once 内部 release/acquire 屏障替代了原裸 bool 的 publish-after-write 隐患:
    //  原先 thread A 还在赋值 g_hasProperty/g_getHandle, thread B 已观察到 g_bridgeInited==true
    //  并取走零值 method ID, 走入"调用未初始化方法 ID"路径, 后续返回 JS_EXCEPTION 时
    //  ctx 异常 slot 为空, 触发 JS_GetException 拿 stale 值的契约违反)
    std::once_flag g_bridgeInitFlag;

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

    // 获取 JNIEnv (从 JS 执行线程)
    JNIEnv *getJniEnv() {
        if (!JavaObjectClass::cachedJvm) return nullptr;
        JNIEnv *env = nullptr;
        JavaVMAttachArgs args = {JNI_VERSION_1_6, "quickjs-trap", nullptr};
        jint ret = JavaObjectClass::cachedJvm->GetEnv((void **) &env, JNI_VERSION_1_6);
        if (ret == JNI_EDETACHED) {
            // 当前线程未 attached,尝试 attach
            // 注意: 如果 JS 在主线程执行,主线程已 attached,这里不会触发
            ret = JavaObjectClass::cachedJvm->AttachCurrentThread(&env, &args);
            if (ret != JNI_OK) return nullptr;
        }
        return env;
    }

    // 初始化 JavaObjectBridgeNative 的方法 ID
    // 所有方法签名都带 dangerousApi 参数 (从 ctx opaque 读取)
    void ensureBridgeInited(JNIEnv *env) {
        std::call_once(g_bridgeInitFlag, [env]() {
            // JavaObjectBridgeNative 是 Kotlin object,编译后类名 com.script.quickjs.JavaObjectBridgeNative
            // 提供 @JvmStatic 静态方法供 native 回调,实现 Java 对象的属性访问
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
            // getPropertyInfo(obj, name, dangerousApi): Array<Any?>?  (返回 [fieldValue, fieldExists, hasMethod])
            g_getPropertyInfo = env->GetStaticMethodID(g_bridgeCls, "getPropertyInfo",
                                                       "(Ljava/lang/Object;Ljava/lang/String;Z)[Ljava/lang/Object;");
            // setProperty(obj, name, value, dangerousApi): Boolean
            g_setProperty = env->GetStaticMethodID(g_bridgeCls, "setProperty",
                                                   "(Ljava/lang/Object;Ljava/lang/String;Ljava/lang/Object;Z)Z");
            // getPropertyNames(obj, dangerousApi): Array<String>
            g_getPropertyNames = env->GetStaticMethodID(g_bridgeCls, "getPropertyNames",
                                                        "(Ljava/lang/Object;Z)[Ljava/lang/String;");

            if (!g_hasProperty || !g_getPropertyInfo || !g_setProperty || !g_getPropertyNames) {
                LOGE("JavaObjectBridgeNative methods not found");
                env->ExceptionClear();
            }

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

            // java.lang.Boolean 缓存 (getProperty 解包 Boolean / 调用 valueOf)
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
        });
    }

    // 检测 atom 是否为 Symbol.iterator (well-known symbol)
    // JS_AtomToCString 对 well-known symbol 返回其描述字符串 "Symbol.iterator"
    // 用字符串比较避免依赖 quickjs-ng 内部 atom enum (未在公开头文件导出)
    bool isSymbolIterator(JSContext *ctx, JSAtom atom) {
        const char *name = JS_AtomToCString(ctx, atom);
        if (!name) return false;
        bool result = (strcmp(name, "Symbol.iterator") == 0);
        JS_FreeCString(ctx, name);
        return result;
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

    // Symbol.iterator 工厂函数: 把 Java List/Array 转成 JS Array 并返回其迭代器
    // for...of 会调用 obj[Symbol.iterator]() 获取迭代器, 本函数即为此调用的返回值。
    // this_val 是 Java List/Array 对象 (JavaObject class 实例)
    // 实现: 拷贝 Java 集合元素到 JS Array, 返回 Array.prototype.values 的调用结果
    // (Array 的默认迭代器), 复用 QuickJS 内置 Array 迭代器逻辑。
    // 注意: 用 JSCFunction 签名 (而非 JSCFunctionData), 因为 this_val 已携带
    // Java 对象引用, 无需额外 func_data。
    static JSValue jsJavaListSymbolIterator(JSContext *ctx, JSValueConst this_val,
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
            // Java 数组: 用 java.lang.reflect.Array
            jclass arrayCls = env->FindClass("java/lang/reflect/Array");
            if (!arrayCls) {
                env->ExceptionClear();
                JS_FreeValue(ctx, arr);
                return JS_ThrowInternalError(ctx, "java.lang.reflect.Array class not found");
            }
            jmethodID arrayGetLength = env->GetStaticMethodID(
                    arrayCls, "getLength", "(Ljava/lang/Object;)I");
            jmethodID arrayGet = env->GetStaticMethodID(
                    arrayCls, "get", "(Ljava/lang/Object;I)Ljava/lang/Object;");
            if (!arrayGetLength || !arrayGet) {
                env->ExceptionClear();
                env->DeleteLocalRef(arrayCls);
                JS_FreeValue(ctx, arr);
                return JS_ThrowInternalError(ctx, "Array.getLength/get method not found");
            }
            jint size = env->CallStaticIntMethod(arrayCls, arrayGetLength, javaObj);
            if (env->ExceptionCheck()) {
                env->ExceptionClear();
                env->DeleteLocalRef(arrayCls);
                JS_FreeValue(ctx, arr);
                return JS_ThrowInternalError(ctx, "Array.getLength threw");
            }
            for (jint i = 0; i < size; i++) {
                jobject elem = env->CallStaticObjectMethod(arrayCls, arrayGet, javaObj, i);
                if (env->ExceptionCheck()) {
                    env->ExceptionClear();
                    if (elem) env->DeleteLocalRef(elem);
                    continue;
                }
                JSValue elemVal = JniValueConvert::fromJavaObject(ctx, env, elem);
                JS_SetPropertyUint32(ctx, arr, jsIndex++, elemVal);
                if (elem) env->DeleteLocalRef(elem);
            }
            env->DeleteLocalRef(arrayCls);
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
    // 并发安全: nativeCreateContext 可能从多个 Java 线程同时调用,
    // 这里对 classId 分配 + JS_NewClass + registeredRuntimes.insert 整体加锁。
    // 在 quickjs-ng 多 runtime 场景下, 这是少数能跨 runtime 触发的 native 路径。
    std::lock_guard<std::mutex> lock(registryMutex);

    cachedJvm = jvm;

    // 首次调用: 用 JS_NewClassID 分配进程级全局唯一的 classId
    // classId 可跨 runtime 复用 (只是一个数字 ID)
    if (classId == 0) {
        JS_NewClassID(rt, &classId);
    }

    // JS_NewClass 是 runtime-specific 的: 每个新 JSRuntime 都必须注册一次,
    // 否则 JS_NewObjectClass 在未注册的 runtime 上返回 exception,
    // wrap() 会返回 JS_NULL,导致 Java 对象注入失败。
    // 用 registeredRuntimes 集合避免在同一 runtime 上重复注册。
    if (registeredRuntimes.find(rt) == registeredRuntimes.end()) {
        // 定义类: finalizer 释放 jobject GlobalRef,exotic 拦截属性访问
        // exotic 必须是 static (JSClassDef 内部只存指针,调用方需保证生命周期)
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
        def.gc_mark = nullptr;
        def.call = nullptr;
        def.exotic = &exotic;

        int ret = JS_NewClass(rt, classId, &def);
        if (ret != 0) {
            LOGE("JS_NewClass failed for JavaObject: ret=%d", ret);
            // 不清零 classId (可能其他 runtime 在用),此 runtime 上 class 不可用
            return 0;
        }
        registeredRuntimes.insert(rt);
    }
    return classId;
}

JSClassID JavaObjectClass::getClassId() {
    return classId;
}

void JavaObjectClass::unregisterRuntime(JSRuntime *rt) {
    if (!rt) return;
    std::lock_guard<std::mutex> lock(registryMutex);
    registeredRuntimes.erase(rt);
}

JSValue JavaObjectClass::wrap(JSContext *ctx, JNIEnv *env, jobject javaObj) {
    if (classId == 0) {
        LOGE("JavaObjectClass not initialized");
        return JS_NULL;
    }
    if (!javaObj) return JS_NULL;

    // 创建全局引用,存入 opaque
    jobject globalRef = env->NewGlobalRef(javaObj);

    // 创建 JavaObject 类实例
    JSValue obj = JS_NewObjectClass(ctx, classId);
    if (JS_IsException(obj)) {
        env->DeleteGlobalRef(globalRef);
        return JS_NULL;
    }

    // 存全局引用到 opaque 槽
    JS_SetOpaque(obj, globalRef);
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
        // 对齐 rhino WrappedException: 包装原始 Throwable 传给 JS catch
        // (见 jni_callbacks.cpp jsMethodCallable 同类处理)
        jthrowable thr = env->ExceptionOccurred();
        env->ExceptionClear();
        if (thr) {
            JSValue errObj = JavaObjectClass::wrap(ctx, env, thr);
            env->DeleteLocalRef(thr);
            // JS_Throw 偷走 errObj 引用 (不 DupValue), 不再 JS_FreeValue,
            // 否则 refcount 归 0 立即释放, rt->current_exception 悬空,
            // JS catch(e) 拿到已释放的 JavaObject → use-after-free → SIGSEGV fault addr 0x8
            JS_Throw(ctx, errObj);
            return -1;
        }
        JS_ThrowInternalError(ctx, "Java hasProperty threw (no throwable)");
        return -1;
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
    // getPropertyInfo 返回 [fieldValue, fieldExists, hasMethod] 或 null
    jobjectArray info = (jobjectArray) env->CallStaticObjectMethod(g_bridgeCls,
                                                                   g_getPropertyInfo, javaObj,
                                                                   jname,
                                                                   dangerousApi ? JNI_TRUE
                                                                                : JNI_FALSE);
    env->DeleteLocalRef(jname);

    if (env->ExceptionCheck()) {
        // 对齐 rhino WrappedException: 包装原始 Throwable 传给 JS catch
        // (见 jni_callbacks.cpp jsMethodCallable 同类处理)
        jthrowable thr = env->ExceptionOccurred();
        env->ExceptionClear();
        if (info) env->DeleteLocalRef(info);
        if (thr) {
            JSValue errObj = JavaObjectClass::wrap(ctx, env, thr);
            env->DeleteLocalRef(thr);
            // JS_Throw 偷走 errObj 引用 (不 DupValue), 不再 JS_FreeValue (否则 UAF, 见 hasProperty 注释)
            JS_Throw(ctx, errObj);
            return JS_EXCEPTION;
        }
        return JS_ThrowInternalError(ctx, "Java getPropertyInfo threw (no throwable)");
    }

    if (!info) {
        // 属性不存在
        return JS_UNDEFINED;
    }

    // 解析 [fieldValue, fieldExists, hasMethod]
    jsize len = env->GetArrayLength(info);
    if (len < 3) {
        LOGE("getProperty: info len=%d < 3", len);
        env->DeleteLocalRef(info);
        return JS_UNDEFINED;
    }

    jobject fieldValue = env->GetObjectArrayElement(info, 0);
    jobject fieldExistsObj = env->GetObjectArrayElement(info, 1);
    jobject hasMethodObj = env->GetObjectArrayElement(info, 2);

    // fieldExists 和 hasMethod 是 Boolean, 解包 (用缓存的 g_BooleanValue, 避免热路径 FindClass)
    bool fieldExists = false;
    bool hasMethod = false;
    if (fieldExistsObj) {
        fieldExists = env->CallBooleanMethod(fieldExistsObj, g_BooleanValue) == JNI_TRUE;
        env->DeleteLocalRef(fieldExistsObj);
    }
    if (hasMethodObj) {
        hasMethod = env->CallBooleanMethod(hasMethodObj, g_BooleanValue) == JNI_TRUE;
        env->DeleteLocalRef(hasMethodObj);
    }

    // 决策逻辑 (对齐 rhino LiveConnect FieldAndMessages 行为):
    // rhino 中 field 和 method 同名时, method 优先 (返回 method callable)。
    // 例: ArrayList 有 private int size 字段, 也有 int size() 方法,
    //     rhino 返回 method callable, 因此 list.size() 调用方法返回 int,
    //     list.size 也返回 method callable (而非字段值)。
    //
    // 1. hasMethod=true: 返回 method callable (即使 field 存在且值非 null)
    // 2. hasMethod=false 且 fieldValue!=null: 返回 field 值
    // 3. hasMethod=false, fieldValue=null, fieldExists=true: 返回 JS_NULL
    // 4. 都不存在: 返回 undefined
    JSValue ret = JS_UNDEFINED;
    if (hasMethod) {
        // method 优先 (对齐 rhino FieldAndMessages)
        // 优化: 创建 callable 后用 JS_DefinePropertyValue 固化为对象自有属性,
        // 后续访问直接走属性路径 (不再触发 trap), 避免每次都 hash 查 methodName。
        const char *methodName = atomToCString(ctx, atom);
        if (methodName) {
            ret = getOrCreateMethodCallable(ctx, methodName);
            // 把 callable 写为对象自有属性 (JS_PROP_CONFIGURABLE 允许 delete)
            JS_DefinePropertyValue(ctx, obj, atom, JS_DupValue(ctx, ret), JS_PROP_CONFIGURABLE);
            JS_FreeCString(ctx, methodName);
        }
        // fieldValue 不再需要, 释放
        if (fieldValue) env->DeleteLocalRef(fieldValue);
    } else if (fieldValue != nullptr) {
        // hasMethod=false 且 field 值非 null, 返回转换后的 JSValue
        ret = JniValueConvert::fromJavaObject(ctx, env, fieldValue);
        env->DeleteLocalRef(fieldValue);
    } else if (fieldExists) {
        // hasMethod=false, field 存在但值为 null, 返回 JS_NULL
        ret = JS_NULL;
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
            JSValue errObj = JavaObjectClass::wrap(ctx, env, thr);
            env->DeleteLocalRef(thr);
            // JS_Throw 偷走 errObj 引用 (不 DupValue), 不再 JS_FreeValue (否则 UAF, 见 hasProperty 注释)
            JS_Throw(ctx, errObj);
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
    // 简化: 用 has_property + get_property 填充 descriptor
    int has = hasProperty(ctx, obj, prop);
    if (has <= 0) return has;

    if (desc) {
        JSValue val = getProperty(ctx, obj, prop, JS_UNDEFINED);
        if (JS_IsException(val)) {
            // 不能把 JS_EXCEPTION 写进 desc->value 后返回 1 (success),
            // 调用方会把它当成普通 value 使用, 后续 JS_DupValue/JS_FreeValue
            // 会污染异常 slot, 触发远处 JS_ToCString -> strv abort。
            return -1;
        }
        desc->flags = JS_PROP_WRITABLE | JS_PROP_ENUMERABLE | JS_PROP_CONFIGURABLE;
        desc->getter = JS_UNDEFINED;
        desc->setter = JS_UNDEFINED;
        desc->value = val;
    }
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
    jobjectArray names = (jobjectArray) env->CallStaticObjectMethod(g_bridgeCls,
                                                                    g_getPropertyNames, javaObj,
                                                                    dangerousApi ? JNI_TRUE
                                                                                 : JNI_FALSE);
    if (env->ExceptionCheck()) {
        // 对齐 rhino WrappedException: 包装原始 Throwable 传给 JS catch
        // (见 jni_callbacks.cpp jsMethodCallable 同类处理)
        jthrowable thr = env->ExceptionOccurred();
        env->ExceptionClear();
        if (names) env->DeleteLocalRef(names);
        *ptab = nullptr;
        *plen = 0;
        if (thr) {
            JSValue errObj = JavaObjectClass::wrap(ctx, env, thr);
            env->DeleteLocalRef(thr);
            // JS_Throw 偷走 errObj 引用 (不 DupValue), 不再 JS_FreeValue (否则 UAF, 见 hasProperty 注释)
            JS_Throw(ctx, errObj);
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

    for (jsize i = 0; i < len; i++) {
        jstring name = (jstring) env->GetObjectArrayElement(names, i);
        const char *cname = env->GetStringUTFChars(name, nullptr);
        (*ptab)[i].atom = JS_NewAtom(ctx, cname ? cname : "");
        (*ptab)[i].is_enumerable = 1;
        env->ReleaseStringUTFChars(name, cname);
        env->DeleteLocalRef(name);
    }
    env->DeleteLocalRef(names);
    return 0;
}

void JavaObjectClass::finalizer(JSRuntime *rt, JSValueConst val) {
    jobject globalRef = (jobject) JS_GetOpaque(val, classId);
    if (!globalRef || !cachedJvm) return;

    JNIEnv *env = nullptr;
    jint ret = cachedJvm->GetEnv((void **) &env, JNI_VERSION_1_6);
    if (ret == JNI_EDETACHED) {
        ret = cachedJvm->AttachCurrentThread(&env, nullptr);
        if (ret != JNI_OK) return;
    }
    if (env) {
        env->DeleteGlobalRef(globalRef);
    }
}
