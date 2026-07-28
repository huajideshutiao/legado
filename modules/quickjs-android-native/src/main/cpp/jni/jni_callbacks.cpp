#include "jni_callbacks.h"
#include "jni_value_convert.h"
#include "jni_object_class.h"
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

namespace {
    constexpr uint32_t kMethodCacheInitSize = 16;   // 2^4
    constexpr uint32_t kMethodEmpty = 0;
    constexpr uint32_t kMethodUsed = 1;

    // 与 qjs hash_string8 (h * 263) 同款单乘散列, 免多次 shift+xor 的代码占用。
    inline uint32_t hashAtom(JSAtom a) {
        return (uint32_t) ((uint64_t) a * 0x9E3779B97F4A7C15ULL >> 32);
    }
}

// 缓存的 Java 方法 ID (JavaObjectBridgeNative + BindingHandler)
namespace {
    jclass g_bridgeNativeCls = nullptr;
    jmethodID g_callMethodByObj = nullptr;   // 共享 callable, 从 this_val 取 jobject
    jclass g_bindingHandlerCls = nullptr;
    jmethodID g_bindingCall = nullptr;
    jclass g_objectCls = nullptr;            // jsArgsToJavaArray 的 NewObjectArray 用

    // 双检锁 + __atomic 屏障 (替代 std::call_once, 免 libc++ 依赖; pthread_once 无法传 env)。
    // RELEASE/ACQUIRE 保证初始化写入 happen-before fast path 的读取。
    bool g_callbacksInited = false;
    pthread_mutex_t g_callbacksInitMutex = PTHREAD_MUTEX_INITIALIZER;

    // 获取 JNIEnv (复用 JavaObjectClass::cachedJvm)
    JNIEnv *getJniEnv() {
        if (!JavaObjectClass::cachedJvm) return nullptr;
        JNIEnv *env = nullptr;
        static char threadName[] = "quickjs-callback";
        JavaVMAttachArgs args = {JNI_VERSION_1_6, threadName, nullptr};
        jint ret = JavaObjectClass::cachedJvm->GetEnv((void **) &env, JNI_VERSION_1_6);
        if (ret == JNI_EDETACHED) {
            // Android NDK 27 的 AttachCurrentThread 严格要求 JNIEnv**,
            // 标准 JDK (桌面 JVM) 要求 void**, 用条件编译兼容两端
#ifdef __ANDROID__
            ret = JavaObjectClass::cachedJvm->AttachCurrentThread((JNIEnv **) &env, &args);
#else
            ret = JavaObjectClass::cachedJvm->AttachCurrentThread((void **) &env, &args);
#endif
            if (ret != JNI_OK) return nullptr;
        }
        return env;
    }

    // 初始化回调方法 ID
    void ensureCallbacksInited(JNIEnv *env) {
        if (__atomic_load_n(&g_callbacksInited, __ATOMIC_ACQUIRE)) return;
        pthread_mutex_lock(&g_callbacksInitMutex);
        if (g_callbacksInited) {
            pthread_mutex_unlock(&g_callbacksInitMutex);
            return;
        }
        // do{...}while(0) 包装以便 break 早退, 无论成败都刷 g_callbacksInited (对齐 call_once 语义)。
        do {
            jclass bridgeCls = env->FindClass("com/script/quickjs/JavaObjectBridgeNative");
            if (!bridgeCls) {
                LOGE("JavaObjectBridgeNative class not found");
                env->ExceptionClear();
                break;
            }
            g_bridgeNativeCls = (jclass) env->NewGlobalRef(bridgeCls);
            env->DeleteLocalRef(bridgeCls);
            // callMethodByObj(obj, methodName, args, dangerousApi): Any?
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
                break;
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
        } while (0);
        __atomic_store_n(&g_callbacksInited, true, __ATOMIC_RELEASE);
        pthread_mutex_unlock(&g_callbacksInitMutex);
    }
}

// JNI_OnLoad 阶段预热调用。
void initJniCallbacksCache(JNIEnv *env) { ensureCallbacksInited(env); }

namespace {

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
    // calloc 而非 new: 免 -fno-exceptions 下 bad_alloc 相关 stub。
    auto *data = (CtxOpaqueData *) std::calloc(1, sizeof(CtxOpaqueData));
    if (!data) return;
    data->arrayProto = JS_UNDEFINED;   // lazy: 首次 wrap Java 数组时填
    // Symbol.iterator 是 well-known symbol, JS_NewAtom 只查 STRING 表拿不到内部数值,
    // 必须走 globalThis.Symbol.iterator -> JS_ValueToAtom。
    data->symbolIteratorAtom = JS_ATOM_NULL;
    JSValue global = JS_GetGlobalObject(ctx);
    JSValue symbolObj = JS_GetPropertyStr(ctx, global, "Symbol");
    JS_FreeValue(ctx, global);
    if (JS_IsObject(symbolObj)) {
        JSValue iterSymbol = JS_GetPropertyStr(ctx, symbolObj, "iterator");
        if (JS_VALUE_GET_TAG(iterSymbol) == JS_TAG_SYMBOL) {
            data->symbolIteratorAtom = JS_ValueToAtom(ctx, iterSymbol);
        }
        JS_FreeValue(ctx, iterSymbol);
    }
    JS_FreeValue(ctx, symbolObj);
    JS_SetContextOpaque(ctx, data);
}

void freeCtxOpaque(JSContext *ctx) {
    if (!ctx) return;
    auto *data = (CtxOpaqueData *) JS_GetContextOpaque(ctx);
    if (data) {
        if (data->methodCallableHash) {
            for (uint32_t i = 0; i < data->methodCallableSize; i++) {
                MethodCallableEntry &e = data->methodCallableHash[i];
                if (e.state == kMethodUsed) {
                    JS_FreeAtom(ctx, e.key);
                    JS_FreeValue(ctx, e.value);
                }
            }
            std::free(data->methodCallableHash);
        }
        if (!JS_IsUndefined(data->arrayProto)) JS_FreeValue(ctx, data->arrayProto);
        if (data->symbolIteratorAtom != JS_ATOM_NULL) JS_FreeAtom(ctx, data->symbolIteratorAtom);
        std::free(data);
        JS_SetContextOpaque(ctx, nullptr);
    }
}

void setDangerousApi(JSContext *ctx, bool dangerousApi) {
    if (!ctx) return;
    auto *data = (CtxOpaqueData *) JS_GetContextOpaque(ctx);
    if (!data) {
        // 走 initCtxOpaque 补全所有字段 (symbolIteratorAtom / arrayProto), 避免留半初始化数据。
        initCtxOpaque(ctx);
        data = (CtxOpaqueData *) JS_GetContextOpaque(ctx);
        if (!data) return;
    }
    data->dangerousApi = dangerousApi;
}

bool getDangerousApi(JSContext *ctx) {
    if (!ctx) return false;
    auto *data = (CtxOpaqueData *) JS_GetContextOpaque(ctx);
    return data != nullptr && data->dangerousApi;
}

// ============ method callable ============

// 每个 methodName 只创建一个 JSCFunctionData, 跨 Java 对象共享; jobject 从 this_val 取。
static JSValue jsMethodCallable(JSContext *ctx, JSValueConst this_val,
                                int argc, JSValueConst *argv, int magic,
                                JSValueConst *func_data) {
    JNIEnv *env = getJniEnv();
    if (!env) return JS_ThrowInternalError(ctx, "JNI env unavailable in method callable");
    ensureCallbacksInited(env);
    if (!g_callMethodByObj) {
        return JS_ThrowInternalError(ctx, "JavaObjectBridgeNative.callMethodByObj not bound");
    }

    // 与 rhino NativeJavaMethod 一致: `var fn = obj.method; fn(x)` 丢 this 抛 TypeError。
    jobject javaObj = JavaObjectClass::getJavaObject(ctx, this_val);
    if (!javaObj) {
        return JS_ThrowTypeError(ctx, "Java method called without Java object as 'this'");
    }

    const char *methodName = JS_ToCString(ctx, func_data[0]);
    if (!methodName) return JS_EXCEPTION;

    bool dangerousApi = getDangerousApi(ctx);

    jobjectArray javaArgs = jsArgsToJavaArray(ctx, env, argc, argv);
    if (env->ExceptionCheck()) {
        env->ExceptionClear();
        JS_FreeCString(ctx, methodName);
        if (javaArgs) env->DeleteLocalRef(javaArgs);
        return JS_ThrowInternalError(ctx, "JNI exception while converting JS args");
    }

    jstring jMethodName = env->NewStringUTF(methodName);
    jobject result = env->CallStaticObjectMethod(
            g_bridgeNativeCls, g_callMethodByObj,
            javaObj, jMethodName, javaArgs, dangerousApi ? JNI_TRUE : JNI_FALSE);
    env->DeleteLocalRef(jMethodName);
    if (javaArgs) env->DeleteLocalRef(javaArgs);

    if (env->ExceptionCheck()) {
        // 对齐 rhino WrappedException: 把原始 Throwable wrap 成 JavaObject 后 JS_Throw,
        // 让 JS catch(e) 能拿回原始异常, e 传回 Java 时 isInstance 还原。
        jthrowable thr = env->ExceptionOccurred();
        env->ExceptionClear();
        if (result) env->DeleteLocalRef(result);
        if (thr) {
            JSValue errObj = JavaObjectClass::wrap(ctx, env, thr);
            env->DeleteLocalRef(thr);
            JS_FreeCString(ctx, methodName);
            if (JS_IsException(errObj)) return JS_EXCEPTION;
            // JS_Throw 偷走引用, 不能再 FreeValue, 否则 current_exception 悬空 → UAF。
            JS_Throw(ctx, errObj);
            return JS_EXCEPTION;
        }
        JSValue exc = JS_ThrowInternalError(ctx, "Java method '%s' threw (no throwable)",
                                            methodName);
        JS_FreeCString(ctx, methodName);
        return exc;
    }
    JS_FreeCString(ctx, methodName);

    // "返回 this" 复用: sb.append/list.add 这类方法 result 与 javaObj 同一对象时,
    // 直接 dup this_val, 省一次 wrap (NewGlobalRef + JS_NewObjectClass)。
    if (result && env->IsSameObject(result, javaObj)) {
        env->DeleteLocalRef(result);
        return JS_DupValue(ctx, this_val);
    }

    JSValue ret = JniValueConvert::fromJavaObject(ctx, env, result);
    if (result) env->DeleteLocalRef(result);
    return ret;
}

// 线性探测: hash_size 为 2 的幂, 未命中返回首个空桶。
static uint32_t methodCacheProbe(const MethodCallableEntry *hash, uint32_t hashSize,
                                 JSAtom key, bool *outFound) {
    const uint32_t mask = hashSize - 1;
    uint32_t idx = hashAtom(key) & mask;
    for (;;) {
        const MethodCallableEntry &e = hash[idx];
        if (e.state == kMethodEmpty) {
            *outFound = false;
            return idx;
        }
        if (e.key == key) {
            *outFound = true;
            return idx;
        }
        idx = (idx + 1) & mask;
    }
}

// newSize 必须是 2 的幂。
static void methodCacheRehash(CtxOpaqueData *opq, uint32_t newSize) {
    MethodCallableEntry *oldH = opq->methodCallableHash;
    uint32_t oldSize = opq->methodCallableSize;
    auto *nb = (MethodCallableEntry *) std::calloc(newSize, sizeof(MethodCallableEntry));
    if (!nb) return;
    opq->methodCallableHash = nb;
    opq->methodCallableSize = newSize;
    if (oldH) {
        for (uint32_t i = 0; i < oldSize; i++) {
            const MethodCallableEntry &e = oldH[i];
            if (e.state != kMethodUsed) continue;
            bool found;
            uint32_t idx = methodCacheProbe(nb, newSize, e.key, &found);
            nb[idx] = e;
        }
        std::free(oldH);
    }
}

JSValue getOrCreateMethodCallable(JSContext *ctx, JSAtom atom) {
    if (!ctx || atom == JS_ATOM_NULL) return JS_UNDEFINED;

    auto *opq = (CtxOpaqueData *) JS_GetContextOpaque(ctx);
    if (opq && opq->methodCallableHash) {
        bool found;
        uint32_t idx = methodCacheProbe(opq->methodCallableHash,
                                        opq->methodCallableSize, atom, &found);
        if (found) {
            // 命中只加 refcount, 免掉 JS_NewCFunctionData + JS_NewString 分配。
            return JS_DupValue(ctx, opq->methodCallableHash[idx].value);
        }
    }

    const char *methodName = JS_AtomToCString(ctx, atom);
    if (!methodName) return JS_EXCEPTION;
    JSValue data[1];
    data[0] = JS_NewString(ctx, methodName);
    JS_FreeCString(ctx, methodName);
    JSValue fn = JS_NewCFunctionData(ctx, jsMethodCallable, 0, 0, 1, data);
    JS_FreeValue(ctx, data[0]);

    if (opq && !JS_IsException(fn)) {
        if (!opq->methodCallableHash) methodCacheRehash(opq, kMethodCacheInitSize);
        // load factor >= 0.75 → 2x rehash
        if (opq->methodCallableHash &&
            opq->methodCallableUsed * 4 >= opq->methodCallableSize * 3) {
            methodCacheRehash(opq, opq->methodCallableSize * 2);
        }
        if (opq->methodCallableHash) {
            bool found;
            uint32_t idx = methodCacheProbe(opq->methodCallableHash,
                                            opq->methodCallableSize, atom, &found);
            if (!found) {
                MethodCallableEntry &e = opq->methodCallableHash[idx];
                e.key = JS_DupAtom(ctx, atom);
                e.value = JS_DupValue(ctx, fn);
                e.state = kMethodUsed;
                opq->methodCallableUsed++;
            }
        }
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

    // __getDangerousApi 直接读 ctx opaque, 免 JNI 往返 (Proxy get trap 热路径)。
    if (std::strcmp(name, "__getDangerousApi") == 0) {
        JS_FreeCString(ctx, name);
        return JS_NewBool(ctx, getDangerousApi(ctx));
    }

    // 强制 wrap 分支: 让 `new java.lang.String('x')` 返回 JavaObject 而不是 JS string。
    bool forceWrap = (std::strcmp(name, "__newJavaInstance") == 0 ||
                      std::strcmp(name, "__newJavaAdapter") == 0);

    jobjectArray javaArgs = jsArgsToJavaArray(ctx, env, argc, argv);
    if (env->ExceptionCheck()) {
        env->ExceptionClear();
        if (javaArgs) env->DeleteLocalRef(javaArgs);
        JS_FreeCString(ctx, name);
        return JS_ThrowInternalError(ctx, "JNI exception while converting binding args");
    }

    jstring jName = env->NewStringUTF(name);
    jobject result = env->CallStaticObjectMethod(g_bindingHandlerCls, g_bindingCall,
                                                 jName, javaArgs);
    env->DeleteLocalRef(jName);
    if (javaArgs) env->DeleteLocalRef(javaArgs);

    if (env->ExceptionCheck()) {
        // 对齐 rhino WrappedException, 详见 jsMethodCallable。
        jthrowable thr = env->ExceptionOccurred();
        env->ExceptionClear();
        if (result) env->DeleteLocalRef(result);
        if (thr) {
            JSValue errObj = JavaObjectClass::wrap(ctx, env, thr);
            env->DeleteLocalRef(thr);
            JS_FreeCString(ctx, name);
            if (JS_IsException(errObj)) return JS_EXCEPTION;
            JS_Throw(ctx, errObj);
            return JS_EXCEPTION;
        }
        JSValue exc = JS_ThrowInternalError(ctx, "Java binding '%s' threw (no throwable)", name);
        JS_FreeCString(ctx, name);
        return exc;
    }

    JSValue ret;
    if (forceWrap) {
        if (result == nullptr) {
            // 安全拦截或 adapter 创建失败时到这里; 不静默返回 null。
            ret = JS_ThrowTypeError(ctx,
                                    "Java instantiation failed (security blocked or no matching constructor)");
        } else {
            ret = JavaObjectClass::wrap(ctx, env, result);
        }
    } else {
        ret = JniValueConvert::fromJavaObject(ctx, env, result);
    }
    if (result) env->DeleteLocalRef(result);
    JS_FreeCString(ctx, name);
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
