#include "jni_callbacks.h"
#include "jni_value_convert.h"
#include "jni_object_class.h"
#include <android/log.h>
#include <cstring>
#include <string>
#include <vector>

#define TAG "legado_qjs"
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)

// 缓存的 Java 方法 ID (JavaObjectBridgeNative + BindingHandler)
namespace {
    // JavaObjectBridgeNative (method callable 回调)
    jclass g_bridgeNativeCls = nullptr;
    jmethodID g_callMethod = nullptr;

    // BindingHandler (binding 回调)
    jclass g_bindingHandlerCls = nullptr;
    jmethodID g_bindingCall = nullptr;

    bool g_callbacksInited = false;

    // 获取 JNIEnv (复用 JavaObjectClass::cachedJvm)
    JNIEnv *getJniEnv() {
        if (!JavaObjectClass::cachedJvm) return nullptr;
        JNIEnv *env = nullptr;
        JavaVMAttachArgs args = {JNI_VERSION_1_6, "quickjs-callback", nullptr};
        jint ret = JavaObjectClass::cachedJvm->GetEnv((void **) &env, JNI_VERSION_1_6);
        if (ret == JNI_EDETACHED) {
            ret = JavaObjectClass::cachedJvm->AttachCurrentThread(&env, &args);
            if (ret != JNI_OK) return nullptr;
        }
        return env;
    }

    // 初始化回调方法 ID
    void ensureCallbacksInited(JNIEnv *env) {
        if (g_callbacksInited) return;

        // JavaObjectBridgeNative (method callable 回调)
        jclass bridgeCls = env->FindClass("com/script/quickjs/JavaObjectBridgeNative");
        if (!bridgeCls) {
            LOGE("JavaObjectBridgeNative class not found");
            env->ExceptionClear();
            return;
        }
        g_bridgeNativeCls = (jclass) env->NewGlobalRef(bridgeCls);
        env->DeleteLocalRef(bridgeCls);
        // callMethod(objHandle, methodName, args, dangerousApi): Any?
        g_callMethod = env->GetStaticMethodID(g_bridgeNativeCls, "callMethod",
                                              "(JLjava/lang/String;[Ljava/lang/Object;Z)Ljava/lang/Object;");
        if (!g_callMethod) {
            LOGE("JavaObjectBridgeNative.callMethod not found");
            env->ExceptionClear();
        }

        // BindingHandler (binding 回调)
        jclass bindingCls = env->FindClass("com/script/quickjs/BindingHandler");
        if (!bindingCls) {
            LOGE("BindingHandler class not found");
            env->ExceptionClear();
            return;
        }
        g_bindingHandlerCls = (jclass) env->NewGlobalRef(bindingCls);
        env->DeleteLocalRef(bindingCls);
        // call(name, args): Any?
        g_bindingCall = env->GetStaticMethodID(g_bindingHandlerCls, "call",
                                               "(Ljava/lang/String;[Ljava/lang/Object;)Ljava/lang/Object;");
        if (!g_bindingCall) {
            LOGE("BindingHandler.call not found");
            env->ExceptionClear();
        }

        g_callbacksInited = true;
    }

    // 把 JS args 转换为 Java Object[] (用 JniValueConvert::toJavaObject)
    jobjectArray jsArgsToJavaArray(JSContext *ctx, JNIEnv *env, int argc, JSValueConst *argv) {
        jclass objCls = env->FindClass("java/lang/Object");
        jobjectArray args = env->NewObjectArray(argc, objCls, nullptr);
        env->DeleteLocalRef(objCls);
        if (!args) return nullptr;

        for (int i = 0; i < argc; i++) {
            jobject arg = JniValueConvert::toJavaObject(ctx, env, argv[i]);
            if (arg) {
                env->SetObjectArrayElement(args, i, arg);
                env->DeleteLocalRef(arg);
            }
            // 异常时 toJavaObject 会抛 JsNativeException, 这里不额外处理
        }
        return args;
    }
}

// ============ ctx opaque 管理 ============

void initCtxOpaque(JSContext *ctx) {
    if (!ctx) return;
    CtxOpaqueData *data = new CtxOpaqueData{false};
    JS_SetContextOpaque(ctx, data);
}

void freeCtxOpaque(JSContext *ctx) {
    if (!ctx) return;
    CtxOpaqueData *data = (CtxOpaqueData *) JS_GetContextOpaque(ctx);
    if (data) {
        delete data;
        JS_SetContextOpaque(ctx, nullptr);
    }
}

void setDangerousApi(JSContext *ctx, bool dangerousApi) {
    if (!ctx) return;
    CtxOpaqueData *data = (CtxOpaqueData *) JS_GetContextOpaque(ctx);
    if (!data) {
        data = new CtxOpaqueData{dangerousApi};
        JS_SetContextOpaque(ctx, data);
    } else {
        data->dangerousApi = dangerousApi;
    }
}

bool getDangerousApi(JSContext *ctx) {
    if (!ctx) return false;
    CtxOpaqueData *data = (CtxOpaqueData *) JS_GetContextOpaque(ctx);
    return data ? data->dangerousApi : false;
}

// ============ method callable ============

// method callable 的 C 回调
// func_data[0] = JS_NewInt64(objHandle) (int32 范围内用 int32 tag, 超出用 float64)
// func_data[1] = JS_NewString(methodName)
static JSValue jsMethodCallable(JSContext *ctx, JSValueConst this_val,
                                int argc, JSValueConst *argv, int magic,
                                JSValueConst *func_data) {
    JNIEnv *env = getJniEnv();
    if (!env) {
        // 契约: 返回 JS_EXCEPTION 必须先在 ctx 上设置异常 slot,
        // 否则调用方 JS_GetException 拿到 stale 值, 后续 with/作用域 unwind
        // 时 ref_count 跟踪可能错乱导致 JSString 提前释放 (heap corruption)
        return JS_ThrowInternalError(ctx, "JNI env unavailable in method callable");
    }
    ensureCallbacksInited(env);
    if (!g_callMethod) {
        return JS_ThrowInternalError(ctx, "JavaObjectBridgeNative.callMethod not bound");
    }

    // 从 func_data 提取 objHandle 和 methodName
    // 用 JS_ToInt64 统一读取 int32/float64 两种 tag, 避免超过 2^31 的句柄被截断
    int64_t objHandle = 0;
    if (JS_ToInt64(ctx, &objHandle, func_data[0])) {
        // JS_ToInt64 失败时已经在 ctx 设过异常, 直接 propagate
        return JS_EXCEPTION;
    }
    const char *methodName = JS_ToCString(ctx, func_data[1]);
    if (!methodName) {
        // JS_ToCString 失败时已经在 ctx 设过异常
        return JS_EXCEPTION;
    }

    // 读取 dangerousApi
    bool dangerousApi = getDangerousApi(ctx);

    // 转换 args 为 Java Object[]
    jobjectArray javaArgs = jsArgsToJavaArray(ctx, env, argc, argv);
    if (env->ExceptionCheck()) {
        env->ExceptionClear();
        JS_FreeCString(ctx, methodName);
        if (javaArgs) env->DeleteLocalRef(javaArgs);
        return JS_ThrowInternalError(ctx, "JNI exception while converting JS args");
    }

    // 调用 JavaObjectBridgeNative.callMethod(objHandle, methodName, args, dangerousApi)
    jstring jMethodName = env->NewStringUTF(methodName);
    jobject result = env->CallStaticObjectMethod(
            g_bridgeNativeCls,
            g_callMethod,
            (jlong) objHandle,
            jMethodName,
            javaArgs,
            dangerousApi ? JNI_TRUE : JNI_FALSE
    );
    env->DeleteLocalRef(jMethodName);
    if (javaArgs) env->DeleteLocalRef(javaArgs);

    if (env->ExceptionCheck()) {
        env->ExceptionClear();
        if (result) env->DeleteLocalRef(result);
        JSValue exc = JS_ThrowInternalError(ctx, "Java method '%s' threw", methodName);
        JS_FreeCString(ctx, methodName);
        return exc;
    }
    JS_FreeCString(ctx, methodName);

    // 结果转 JSValue
    JSValue ret = JniValueConvert::fromJavaObject(ctx, env, result);
    if (result) env->DeleteLocalRef(result);
    return ret;
}

JSValue createMethodCallable(JSContext *ctx, int64_t objHandle, const char *methodName) {
    if (!ctx || !methodName) return JS_UNDEFINED;

    // func_data 存 objHandle 和 methodName
    // 用 JS_NewInt64 存 int64 句柄 (int32 范围内自动用 int32 tag, 超出用 float64)
    JSValue data[2];
    data[0] = JS_NewInt64(ctx, objHandle);
    data[1] = JS_NewString(ctx, methodName);

    JSValue fn = JS_NewCFunctionData(ctx, jsMethodCallable, 0, 0, 2, data);

    // JS_NewCFunctionData 内部会 DupValue data, 这里释放引用
    JS_FreeValue(ctx, data[0]);
    JS_FreeValue(ctx, data[1]);

    return fn;
}

// ============ binding 注册 ============

// binding 的 C 回调
// func_data[0] = JS_NewString(name)
static JSValue jsBindingCall(JSContext *ctx, JSValueConst this_val,
                             int argc, JSValueConst *argv, int magic,
                             JSValueConst *func_data) {
    JNIEnv *env = getJniEnv();
    if (!env) {
        return JS_ThrowInternalError(ctx, "JNI env unavailable in binding call");
    }
    ensureCallbacksInited(env);
    if (!g_bindingCall) {
        return JS_ThrowInternalError(ctx, "BindingHandler.call not bound");
    }

    // 从 func_data 提取 binding name
    const char *name = JS_ToCString(ctx, func_data[0]);
    if (!name) {
        // JS_ToCString 失败已设过 ctx 异常
        return JS_EXCEPTION;
    }

    // 保存 binding name 副本, 供后续判断是否需要强制 wrap (newJavaInstance 等)
    std::string nameStr(name);
    JS_FreeCString(ctx, name);

    // 转换 args 为 Java Object[]
    jobjectArray javaArgs = jsArgsToJavaArray(ctx, env, argc, argv);
    if (env->ExceptionCheck()) {
        env->ExceptionClear();
        if (javaArgs) env->DeleteLocalRef(javaArgs);
        return JS_ThrowInternalError(ctx, "JNI exception while converting binding args");
    }

    // 调用 BindingHandler.call(name, args)
    jstring jName = env->NewStringUTF(nameStr.c_str());
    jobject result = env->CallStaticObjectMethod(
            g_bindingHandlerCls,
            g_bindingCall,
            jName,
            javaArgs
    );
    env->DeleteLocalRef(jName);
    if (javaArgs) env->DeleteLocalRef(javaArgs);

    if (env->ExceptionCheck()) {
        env->ExceptionClear();
        if (result) env->DeleteLocalRef(result);
        return JS_ThrowInternalError(ctx, "Java binding '%s' threw", nameStr.c_str());
    }

    // 结果转 JSValue
    // 对 __newJavaInstance / __newJavaAdapter 强制走 JavaObjectClass::wrap,
    // 不让 fromJavaObject 把 String/Integer 等转为 JS 基本类型。
    // 这样 `new java.lang.String('hello')` 返回 JavaObject (包装 String),
    // s.length() 能调用 String.length() 方法 (对齐 rhino LiveConnect 行为)。
    JSValue ret;
    if (nameStr == "__newJavaInstance" || nameStr == "__newJavaAdapter") {
        if (result == nullptr) {
            ret = JS_NULL;
        } else {
            ret = JavaObjectClass::wrap(ctx, env, result);
        }
    } else {
        ret = JniValueConvert::fromJavaObject(ctx, env, result);
    }
    if (result) env->DeleteLocalRef(result);
    return ret;
}

bool defineBinding(JSContext *ctx, const char *name) {
    if (!ctx || !name) return false;

    // func_data 存 binding name
    JSValue data[1];
    data[0] = JS_NewString(ctx, name);

    JSValue fn = JS_NewCFunctionData(ctx, jsBindingCall, 0, 0, 1, data);
    JS_FreeValue(ctx, data[0]); // JS_NewCFunctionData 内部 DupValue

    if (JS_IsException(fn)) {
        JS_FreeValue(ctx, fn);
        return false;
    }

    // 设置为全局变量
    JSValue global = JS_GetGlobalObject(ctx);
    int ret = JS_SetPropertyStr(ctx, global, name, fn);
    JS_FreeValue(ctx, global);

    return ret >= 0;
}
