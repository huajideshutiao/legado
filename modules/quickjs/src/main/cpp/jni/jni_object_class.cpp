#include "jni_object_class.h"
#include "jni_value_convert.h"
#include "jni_handle.h"
#include "jni_callbacks.h"
#include <android/log.h>
#include <cstring>

#define TAG "legado_qjs"
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)

// 静态成员初始化
JSClassID JavaObjectClass::classId = 0;
JavaVM *JavaObjectClass::cachedJvm = nullptr;
std::unordered_set<JSRuntime *> JavaObjectClass::registeredRuntimes;

// 缓存的 Java 方法 ID (JavaObjectBridgeNative 静态方法)
namespace {
    jclass g_bridgeCls = nullptr;
    jmethodID g_hasProperty = nullptr;
    jmethodID g_getPropertyInfo = nullptr;
    jmethodID g_setProperty = nullptr;
    jmethodID g_getPropertyNames = nullptr;
    jmethodID g_getHandle = nullptr;  // method callable 创建时用
    bool g_bridgeInited = false;

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
        if (g_bridgeInited) return;
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
        // getHandle(obj): Long  (method callable 创建时获取对象句柄)
        g_getHandle = env->GetStaticMethodID(g_bridgeCls, "getHandle",
                                             "(Ljava/lang/Object;)J");

        if (!g_hasProperty || !g_getPropertyInfo || !g_setProperty || !g_getPropertyNames ||
            !g_getHandle) {
            LOGE("JavaObjectBridgeNative methods not found");
            env->ExceptionClear();
        }
        g_bridgeInited = true;
    }
}

JSClassID JavaObjectClass::init(JSRuntime *rt, JavaVM *jvm) {
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
    if (!env) return 0;
    ensureBridgeInited(env);
    if (!g_hasProperty) return 0;

    jobject javaObj = getJavaObject(ctx, obj);
    if (!javaObj) return 0;

    const char *name = atomToCString(ctx, prop);
    if (!name) return 0;

    jstring jname = env->NewStringUTF(name);
    JS_FreeCString(ctx, name);

    bool dangerousApi = getDangerousApi(ctx);
    jboolean result = env->CallStaticBooleanMethod(g_bridgeCls, g_hasProperty,
                                                   javaObj, jname,
                                                   dangerousApi ? JNI_TRUE : JNI_FALSE);
    env->DeleteLocalRef(jname);

    if (env->ExceptionCheck()) {
        env->ExceptionClear();
        return 0;
    }
    return result ? 1 : 0;
}

JSValue JavaObjectClass::getProperty(JSContext *ctx, JSValueConst obj, JSAtom atom,
                                     JSValueConst receiver) {
    JNIEnv *env = getJniEnv();
    if (!env) return JS_EXCEPTION;
    ensureBridgeInited(env);
    if (!g_getPropertyInfo) return JS_EXCEPTION;

    jobject javaObj = getJavaObject(ctx, obj);
    if (!javaObj) return JS_UNDEFINED;

    const char *name = atomToCString(ctx, atom);
    if (!name) return JS_UNDEFINED;

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
        LOGE("getProperty: JNI exception");
        env->ExceptionClear();
        if (info) env->DeleteLocalRef(info);
        return JS_EXCEPTION;
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

    // fieldExists 和 hasMethod 是 Boolean, 解包
    bool fieldExists = false;
    bool hasMethod = false;
    if (fieldExistsObj) {
        jclass boolCls = env->FindClass("java/lang/Boolean");
        jmethodID boolValue = env->GetMethodID(boolCls, "booleanValue", "()Z");
        fieldExists = env->CallBooleanMethod(fieldExistsObj, boolValue) == JNI_TRUE;
        env->DeleteLocalRef(fieldExistsObj);
        env->DeleteLocalRef(boolCls);
    }
    if (hasMethodObj) {
        jclass boolCls = env->FindClass("java/lang/Boolean");
        jmethodID boolValue = env->GetMethodID(boolCls, "booleanValue", "()Z");
        hasMethod = env->CallBooleanMethod(hasMethodObj, boolValue) == JNI_TRUE;
        env->DeleteLocalRef(hasMethodObj);
        env->DeleteLocalRef(boolCls);
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
        // method 优先 (对齐 rhino FieldAndMessages), 创建 method callable
        const char *methodName = atomToCString(ctx, atom);
        if (methodName) {
            // 通过缓存的 g_getHandle 获取 Java 对象的句柄
            jlong objHandle = env->CallStaticLongMethod(g_bridgeCls, g_getHandle, javaObj);
            if (!env->ExceptionCheck() && objHandle != 0) {
                ret = createMethodCallable(ctx, (int64_t) objHandle, methodName);
            } else if (env->ExceptionCheck()) {
                LOGE("getProperty: getHandle exception");
                env->ExceptionClear();
            }
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
    if (!env) return -1;
    ensureBridgeInited(env);
    if (!g_setProperty) return -1;

    jobject javaObj = getJavaObject(ctx, obj);
    if (!javaObj) return -1;

    const char *name = atomToCString(ctx, atom);
    if (!name) return -1;

    jstring jname = env->NewStringUTF(name);
    JS_FreeCString(ctx, name);

    // JSValue -> jobject (基本类型直接转,对象走 wrap 或句柄)
    jobject javaValue = JniValueConvert::toJavaObject(ctx, env, value);

    bool dangerousApi = getDangerousApi(ctx);
    jboolean result = env->CallStaticBooleanMethod(g_bridgeCls, g_setProperty,
                                                   javaObj, jname, javaValue,
                                                   dangerousApi ? JNI_TRUE : JNI_FALSE);
    if (javaValue) env->DeleteLocalRef(javaValue);
    env->DeleteLocalRef(jname);

    if (env->ExceptionCheck()) {
        env->ExceptionClear();
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
        desc->flags = JS_PROP_WRITABLE | JS_PROP_ENUMERABLE | JS_PROP_CONFIGURABLE;
        desc->getter = JS_UNDEFINED;
        desc->setter = JS_UNDEFINED;
        desc->value = getProperty(ctx, obj, prop, JS_UNDEFINED);
    }
    return 1;
}

int JavaObjectClass::getOwnPropertyNames(JSContext *ctx, JSPropertyEnum **ptab,
                                         uint32_t *plen, JSValueConst obj) {
    JNIEnv *env = getJniEnv();
    if (!env) return -1;
    ensureBridgeInited(env);
    if (!g_getPropertyNames) {
        *ptab = nullptr;
        *plen = 0;
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
        env->ExceptionClear();
        if (names) env->DeleteLocalRef(names);
        *ptab = nullptr;
        *plen = 0;
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
