#include "jni_callbacks.h"
#include "jni_value_convert.h"
#include "jni_object_class.h"
#include <android/log.h>
#include <cstring>
#include <mutex>
#include <string>
#include <vector>

#define TAG "legado_qjs"
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)

// 缓存的 Java 方法 ID (JavaObjectBridgeNative + BindingHandler)
namespace {
    // JavaObjectBridgeNative (method callable 回调)
    jclass g_bridgeNativeCls = nullptr;
    jmethodID g_callMethod = nullptr;
    jmethodID g_callMethodByObj = nullptr;  // Tier 2: 不走 handle, 直接传 jobject

    // BindingHandler (binding 回调)
    jclass g_bindingHandlerCls = nullptr;
    jmethodID g_bindingCall = nullptr;

    // java.lang.Object class 缓存 (jsArgsToJavaArray 用 NewObjectArray)
    // 原先每次方法调用都 FindClass("java/lang/Object"), 会锁 ClassLoader, 是热路径开销大头之一
    jclass g_objectCls = nullptr;

    // 用 std::once_flag 替代裸 bool: call_once 内置 release/acquire 屏障,
    // 保证 g_bridgeNativeCls / g_callMethod / g_bindingHandlerCls / g_bindingCall
    // 这些写入对其它线程 happen-before 可见。原裸 bool 可能被 thread A 先观察到 true,
    // 但 method ID 仍是 nullptr, 走入"未绑定"分支 return JS_EXCEPTION 时 ctx 异常 slot
    // 未设, 后续 JS_GetException 拿 stale 引发的 ref_count 错乱表现为 JSString header
    // 被覆盖, JS_ToCString -> strv -> abort。
    std::once_flag g_callbacksInitFlag;

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
        std::call_once(g_callbacksInitFlag, [env]() {
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
            // callMethodByObj(obj, methodName, args, dangerousApi): Any?
            // Tier 2: 全局 method callable 共享 (按 methodName 缓存), 不再持有 objHandle,
            // 改为从 this_val 取 jobject 直接传 Kotlin, 省掉 getHandle JNI 往返与 identityHandles 查询。
            g_callMethodByObj = env->GetStaticMethodID(g_bridgeNativeCls, "callMethodByObj",
                                                       "(Ljava/lang/Object;Ljava/lang/String;[Ljava/lang/Object;Z)Ljava/lang/Object;");
            if (!g_callMethodByObj) {
                LOGE("JavaObjectBridgeNative.callMethodByObj not found");
                env->ExceptionClear();
            }

            // java.lang.Object 缓存 (jsArgsToJavaArray 用)
            jclass objCls = env->FindClass("java/lang/Object");
            if (objCls) {
                g_objectCls = (jclass) env->NewGlobalRef(objCls);
                env->DeleteLocalRef(objCls);
            } else {
                LOGE("java.lang.Object class not found");
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
        });
    }

    // 把 JS args 转换为 Java Object[] (用 JniValueConvert::toJavaObject)
    jobjectArray jsArgsToJavaArray(JSContext *ctx, JNIEnv *env, int argc, JSValueConst *argv) {
        if (!g_objectCls) return nullptr;
        jobjectArray args = env->NewObjectArray(argc, g_objectCls, nullptr);
        if (!args) return nullptr;

        for (int i = 0; i < argc; i++) {
            jobject arg = JniValueConvert::toJavaObject(ctx, env, argv[i]);
            // toJavaObject 失败会抛 JsNativeException, 此后 JNI 处于 pending exception
            // 状态。必须立刻 return, 否则 SetObjectArrayElement / DeleteLocalRef /
            // 下一轮 toJavaObject 内的 FindClass 都属于"在 pending exc 下调 JNI", 会
            // 破坏 JNI 内部状态, 远处堆上 JSString header 被踩 -> strv abort。
            // 调用方 (jsMethodCallable / jsBindingCall) 已会 ExceptionCheck 处理。
            if (env->ExceptionCheck()) {
                if (arg) env->DeleteLocalRef(arg);
                return args;
            }
            if (arg) {
                env->SetObjectArrayElement(args, i, arg);
                env->DeleteLocalRef(arg);
            }
        }
        return args;
    }
}

// ============ ctx opaque 管理 ============

void initCtxOpaque(JSContext *ctx) {
    if (!ctx) return;
    CtxOpaqueData *data = new CtxOpaqueData();
    data->dangerousApi = false;
    JS_SetContextOpaque(ctx, data);
}

void freeCtxOpaque(JSContext *ctx) {
    if (!ctx) return;
    CtxOpaqueData *data = (CtxOpaqueData *) JS_GetContextOpaque(ctx);
    if (data) {
        // 释放缓存的 method callable JSValue (callable 是 ctx 持有的 +1 引用)
        for (auto &kv: data->methodCallableCache) {
            JS_FreeValue(ctx, kv.second);
        }
        data->methodCallableCache.clear();
        delete data;
        JS_SetContextOpaque(ctx, nullptr);
    }
}

void setDangerousApi(JSContext *ctx, bool dangerousApi) {
    if (!ctx) return;
    CtxOpaqueData *data = (CtxOpaqueData *) JS_GetContextOpaque(ctx);
    if (!data) {
        data = new CtxOpaqueData();
        data->dangerousApi = dangerousApi;
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
//
// 共享设计 (Tier 2 优化):
// - 每个 methodName 只创建一个 JSCFunctionData, 跨所有 Java 对象共享
// - func_data[0] = JS_NewString(methodName), 不再持有 objHandle
// - this_val 由 JS 调用点 (obj.method(...)) 自动绑定到接收对象, 是真正的 Java 对象 wrapper
// - 从 this_val 取 jobject -> 直接传给 callMethodByObj, 省去 getHandle / identityHandle 查询
//
// 与 rhino 对齐: rhino 的 NativeJavaMethod 也是按 (class, methodName) 共享, this 由 callsite 提供。
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
    if (!g_callMethodByObj) {
        return JS_ThrowInternalError(ctx, "JavaObjectBridgeNative.callMethodByObj not bound");
    }

    // this_val 必须是 JavaObject 实例 (由 obj.method(...) 调用点绑定)
    // 用户写 `var f = sb.append; f("x")` 这种丢失 this 的写法会拿到 undefined this_val,
    // 这时报错而不是猜对象 — 与 rhino 行为基本对齐 (rhino 也要求 LiveConnect 调用带正确 this)。
    jobject javaObj = JavaObjectClass::getJavaObject(ctx, this_val);
    if (!javaObj) {
        return JS_ThrowTypeError(ctx, "Java method called without Java object as 'this'");
    }

    const char *methodName = JS_ToCString(ctx, func_data[0]);
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

    // 调用 JavaObjectBridgeNative.callMethodByObj(obj, methodName, args, dangerousApi)
    jstring jMethodName = env->NewStringUTF(methodName);
    jobject result = env->CallStaticObjectMethod(
            g_bridgeNativeCls,
            g_callMethodByObj,
            javaObj,
            jMethodName,
            javaArgs,
            dangerousApi ? JNI_TRUE : JNI_FALSE
    );
    env->DeleteLocalRef(jMethodName);
    if (javaArgs) env->DeleteLocalRef(javaArgs);

    if (env->ExceptionCheck()) {
        // 对齐 rhino WrappedException: 拿原始 Throwable, 用 JavaObjectClass::wrap 包装成
        // JavaObject 后 JS_Throw。JS catch(e) 拿到 JavaObject (持有 Throwable), e 传回
        // Java 时 toJavaObject 走 isInstance 分支还原原始 Throwable, AppLog.put(e,e,false)
        // 第二参数能匹配 Throwable。原先 JS_ThrowInternalError 只保留方法名, 丢原始异常。
        jthrowable thr = env->ExceptionOccurred();
        env->ExceptionClear();
        if (result) env->DeleteLocalRef(result);
        if (thr) {
            JSValue errObj = JavaObjectClass::wrap(ctx, env, thr);
            env->DeleteLocalRef(thr);
            JS_FreeCString(ctx, methodName);
            // JS_Throw 偷走 errObj 引用 (不 DupValue), 不再 JS_FreeValue,
            // 否则 refcount 归 0 立即释放, rt->current_exception 悬空,
            // JS catch(e) 拿到已释放的 JavaObject → use-after-free → SIGSEGV fault addr 0x8
            JS_Throw(ctx, errObj);
            return JS_EXCEPTION;
        }
        // thr 为 null 的兜底 (理论不会发生): 先用 methodName 构造错误再释放
        JSValue exc = JS_ThrowInternalError(ctx, "Java method '%s' threw (no throwable)",
                                            methodName);
        JS_FreeCString(ctx, methodName);
        return exc;
    }
    JS_FreeCString(ctx, methodName);

    // return-this 复用 (Tier 2b):
    // sb.append / sb.insert / list.add 等"返回 this"的方法非常多, 原先每次都 wrap
    // 出新 JavaObject (NewGlobalRef + JS_NewObjectClass + SetOpaque)。
    // 这里检测 result 与 this_val 关联的是否同一 jobject, 是则直接 DupValue(this_val),
    // 省一次 GlobalRef + 一次 JS_NewObject。
    if (result && env->IsSameObject(result, javaObj)) {
        env->DeleteLocalRef(result);
        return JS_DupValue(ctx, this_val);
    }

    // 结果转 JSValue
    JSValue ret = JniValueConvert::fromJavaObject(ctx, env, result);
    if (result) env->DeleteLocalRef(result);
    return ret;
}

JSValue getOrCreateMethodCallable(JSContext *ctx, const char *methodName) {
    if (!ctx || !methodName) return JS_UNDEFINED;

    CtxOpaqueData *opaque = (CtxOpaqueData *) JS_GetContextOpaque(ctx);
    if (opaque) {
        auto it = opaque->methodCallableCache.find(methodName);
        if (it != opaque->methodCallableCache.end()) {
            // 命中: 调用方负责 FreeValue, 这里 DupValue
            return JS_DupValue(ctx, it->second);
        }
    }

    // func_data 只存 methodName, objHandle 通过 this_val 传递
    JSValue data[1];
    data[0] = JS_NewString(ctx, methodName);
    JSValue fn = JS_NewCFunctionData(ctx, jsMethodCallable, 0, 0, 1, data);
    JS_FreeValue(ctx, data[0]);  // JS_NewCFunctionData 内部 DupValue data

    if (JS_IsException(fn)) {
        return fn;
    }

    // 写入 cache (cache 持 1 引用, 调用方再 DupValue 拿到独立的 +1)
    if (opaque) {
        opaque->methodCallableCache.emplace(std::string(methodName), JS_DupValue(ctx, fn));
    }
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
        // 对齐 rhino WrappedException: 包装原始 Throwable 传给 JS catch
        // (见 jsMethodCallable 同类处理)
        jthrowable thr = env->ExceptionOccurred();
        env->ExceptionClear();
        if (result) env->DeleteLocalRef(result);
        if (thr) {
            JSValue errObj = JavaObjectClass::wrap(ctx, env, thr);
            env->DeleteLocalRef(thr);
            // JS_Throw 偷走 errObj 引用 (不 DupValue), 不再 JS_FreeValue (否则 UAF, 见 jsMethodCallable 注释)
            JS_Throw(ctx, errObj);
            return JS_EXCEPTION;
        }
        return JS_ThrowInternalError(ctx, "Java binding '%s' threw (no throwable)",
                                     nameStr.c_str());
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
