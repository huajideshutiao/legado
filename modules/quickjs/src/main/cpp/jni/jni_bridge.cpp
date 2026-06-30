#include <jni.h>
#include <quickjs.h>
#include <android/log.h>
#include <cstring>
#include <string>
#include <vector>

#include "jni_handle.h"
#include "jni_object_class.h"
#include "jni_value_convert.h"
#include "jni_callbacks.h"

#define TAG "legado_qjs"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)

// 缓存的 JavaVM (JNI_OnLoad 时设置)
static JavaVM *g_jvm = nullptr;

// ============ JNI_OnLoad ============
extern "C" JNIEXPORT jint JNICALL JNI_OnLoad(JavaVM *vm, void *reserved) {
    g_jvm = vm;
    LOGI("legado_quickjs native loaded");
    return JNI_VERSION_1_6;
}

// ============ Runtime / Context 生命周期 ============

extern "C" JNIEXPORT jlong JNICALL
Java_com_script_quickjs_QuickJsNative_nativeCreateRuntime(JNIEnv *env, jobject clazz) {
    JSRuntime *rt = JS_NewRuntime();
    if (!rt) {
        LOGE("JS_NewRuntime failed");
        return 0;
    }
    // 设置内存限制 (256MB) 和栈大小 (1MB),防止恶意 JS OOM
    JS_SetMemoryLimit(rt, 256 * 1024 * 1024);
    JS_SetMaxStackSize(rt, 1024 * 1024);
    return (jlong) rt;
}

extern "C" JNIEXPORT void JNICALL
Java_com_script_quickjs_QuickJsNative_nativeFreeRuntime(JNIEnv *env, jobject clazz, jlong rtPtr) {
    if (!rtPtr) return;
    auto *rt = (JSRuntime *) rtPtr;
    // 必须在 JS_FreeRuntime 之前移除, 否则 rt 指针悬空后仍留在 registeredRuntimes,
    // 新 runtime 复用同一地址时误判为已注册, 跳过 JS_NewClass 导致 class 未注册
    JavaObjectClass::unregisterRuntime(rt);
    JS_FreeRuntime(rt);
}

extern "C" JNIEXPORT jlong JNICALL
Java_com_script_quickjs_QuickJsNative_nativeCreateContext(JNIEnv *env, jobject clazz, jlong rtPtr) {
    if (!rtPtr) return 0;
    auto *rt = (JSRuntime *) rtPtr;
    JSContext *ctx = JS_NewContext(rt);
    if (!ctx) {
        LOGE("JS_NewContext failed");
        return 0;
    }

    // 初始化 JavaObject 自定义类 (每个 runtime 一次)
    JavaObjectClass::init(rt, g_jvm);

    // 初始化 ctx opaque (存储 dangerousApi 等运行时状态)
    initCtxOpaque(ctx);

    return (jlong) ctx;
}

extern "C" JNIEXPORT void JNICALL
Java_com_script_quickjs_QuickJsNative_nativeFreeContext(JNIEnv *env, jobject clazz, jlong ctxPtr) {
    if (!ctxPtr) return;
    auto *ctx = (JSContext *) ctxPtr;
    // 释放 ctx opaque 数据
    freeCtxOpaque(ctx);
    // 释放此 ctx 的所有句柄 (防止 Java 侧漏 release)
    JsHandleTable::instance().releaseByCtx(ctx);
    JS_FreeContext(ctx);
}

// ============ JS 执行 ============

extern "C" JNIEXPORT jobject JNICALL
Java_com_script_quickjs_QuickJsNative_nativeEval(JNIEnv *env, jobject clazz,
                                                 jlong ctxPtr, jstring code) {
    if (!ctxPtr || !code) return nullptr;
    auto *ctx = (JSContext *) ctxPtr;

    const char *src = env->GetStringUTFChars(code, nullptr);
    if (!src) return nullptr;

    // 更新 stack_top 为当前线程栈指针: QuickJS 在 JS_NewRuntime 时记录创建线程的
    // stack_top,SharedJsScope 缓存的 ctx 被其他线程协程使用时,栈检查会基于错误的
    // stack_top (跨线程栈地址不可比较),导致误报 "Maximum call stack size exceeded"。
    // 每次进入 JS 执行前刷新为当前线程栈顶,确保栈检查基于当前线程。
    JS_UpdateStackTop(JS_GetRuntime(ctx));

    // 不使用 JS_EVAL_FLAG_STRICT: 兼容 Rhino sloppy mode,
    // 允许书源 JS 使用 with 语句 (JavaImporter 的常见用法)
    JSValue result = JS_Eval(ctx, src, strlen(src), "<eval>",
                             JS_EVAL_TYPE_GLOBAL);
    env->ReleaseStringUTFChars(code, src);

    // 转 Java 对象 (toJavaObject 内部会处理异常,抛出 JsNativeException)
    jobject ret = JniValueConvert::toJavaObject(ctx, env, result);
    JS_FreeValue(ctx, result);
    return ret;
}

// ============ 句柄管理 ============

extern "C" JNIEXPORT void JNICALL
Java_com_script_quickjs_QuickJsNative_nativeFreeHandle(JNIEnv *env, jobject clazz,
                                                       jlong ctxPtr, jlong handle) {
    if (!handle) return;
    JsHandleTable::instance().release(handle);
}

extern "C" JNIEXPORT jlong JNICALL
Java_com_script_quickjs_QuickJsNative_nativeDupHandle(JNIEnv *env, jobject clazz,
                                                      jlong ctxPtr, jlong handle) {
    if (!handle) return 0;
    auto *ctx = (JSContext *) ctxPtr;
    JSValue val = JsHandleTable::instance().get(handle);
    if (JS_IsNull(val)) return 0;
    JSValue dup = JS_DupValue(ctx, val);
    return JsHandleTable::instance().store(ctx, dup);
}

// ============ 全局对象 ============

extern "C" JNIEXPORT jobject JNICALL
Java_com_script_quickjs_QuickJsNative_nativeGetGlobalObject(JNIEnv *env, jobject clazz,
                                                            jlong ctxPtr) {
    if (!ctxPtr) return nullptr;
    auto *ctx = (JSContext *) ctxPtr;
    JSValue global = JS_GetGlobalObject(ctx);
    // global 是 object,转句柄返回
    JSValue dup = JS_DupValue(ctx, global);
    int64_t handle = JsHandleTable::instance().store(ctx, dup);
    JS_FreeValue(ctx, global);
    // 返回 Long 句柄 (复用 g_LongCls / g_LongValueOf 缓存)
    jobject result = env->CallStaticObjectMethod(g_LongCls, g_LongValueOf, (jlong) handle);
    return result;
}

// ============ 属性操作 ============

extern "C" JNIEXPORT jobject JNICALL
Java_com_script_quickjs_QuickJsNative_nativeGetProperty(JNIEnv *env, jobject clazz,
                                                        jlong ctxPtr, jlong objHandle,
                                                        jstring name) {
    if (!ctxPtr || !objHandle || !name) return nullptr;
    auto *ctx = (JSContext *) ctxPtr;
    JSValue obj = JsHandleTable::instance().get(objHandle);
    if (JS_IsNull(obj)) return nullptr;

    const char *cname = env->GetStringUTFChars(name, nullptr);
    JSValue val = JS_GetPropertyStr(ctx, obj, cname);
    env->ReleaseStringUTFChars(name, cname);

    jobject ret = JniValueConvert::toJavaObject(ctx, env, val);
    JS_FreeValue(ctx, val);
    return ret;
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_script_quickjs_QuickJsNative_nativeSetProperty(JNIEnv *env, jobject clazz,
                                                        jlong ctxPtr, jlong objHandle,
                                                        jstring name, jobject value) {
    if (!ctxPtr || !objHandle || !name) return JNI_FALSE;
    auto *ctx = (JSContext *) ctxPtr;
    JSValue obj = JsHandleTable::instance().get(objHandle);
    if (JS_IsNull(obj)) return JNI_FALSE;

    const char *cname = env->GetStringUTFChars(name, nullptr);
    JSValue jsVal = JniValueConvert::fromJavaObject(ctx, env, value);
    // JS_SetPropertyStr 会 consume jsVal (失败路径也会 JS_FreeValue),
    // 调用方不能再 JS_FreeValue, 否则 refcount 多扣一次:
    // String binding 时 jsVal 是 +1 的 JSString, consume 后属性槽持 1 引用,
    // 再 Free 会立刻把 refcount 归零并 list_del 释放 string,
    // 而属性槽指针仍指向已 free 的 string。下次 GC / 属性替换 / ctx 释放
    // 二次 list_del 触发 SIGSEGV (链表节点 prev 已被 fail-safe 置 NULL)。
    int ret = JS_SetPropertyStr(ctx, obj, cname, jsVal);
    env->ReleaseStringUTFChars(name, cname);
    return ret >= 0 ? JNI_TRUE : JNI_FALSE;
}

// 设置对象属性 (句柄版本)
// 与 nativeSetProperty 区别: valueHandle 直接从 JsHandleTable 取 JSValue,
// 避免 fromJavaObject 把 Long 句柄误当成普通数字 (修复 injectVariable 注入 Java 对象丢失问题)
extern "C" JNIEXPORT jboolean JNICALL
Java_com_script_quickjs_QuickJsNative_nativeSetPropertyHandle(JNIEnv *env, jobject clazz,
                                                              jlong ctxPtr, jlong objHandle,
                                                              jstring name, jlong valueHandle) {
    if (!ctxPtr || !objHandle || !name) return JNI_FALSE;
    auto *ctx = (JSContext *) ctxPtr;
    JSValue obj = JsHandleTable::instance().get(objHandle);
    if (JS_IsNull(obj)) return JNI_FALSE;

    // 从 JsHandleTable 取 valueHandle 对应的 JSValue
    JSValue jsVal = JsHandleTable::instance().get(valueHandle);
    if (JS_IsNull(jsVal)) return JNI_FALSE;

    // 重要: JS_SetPropertyStr 会 consume 传入的 val (不 DupValue, 调用后不需 Free val)
    // 但 jsVal 来自 JsHandleTable, 调用方之后会 nativeFreeHandle 释放 JsHandleTable 的引用
    // 如果不 DupValue, SetPropertyStr 和 JsHandleTable 共享同一引用,
    // nativeFreeHandle 会让引用计数归零, 对象被 GC 释放, 属性指向已释放内存 (use-after-free)
    // 因此必须 DupValue, 让属性持有独立引用
    JSValue dup = JS_DupValue(ctx, jsVal);

    const char *cname = env->GetStringUTFChars(name, nullptr);
    int ret = JS_SetPropertyStr(ctx, obj, cname, dup);  // consume dup
    env->ReleaseStringUTFChars(name, cname);
    return ret >= 0 ? JNI_TRUE : JNI_FALSE;
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_script_quickjs_QuickJsNative_nativeHasProperty(JNIEnv *env, jobject clazz,
                                                        jlong ctxPtr, jlong objHandle,
                                                        jstring name) {
    if (!ctxPtr || !objHandle || !name) return JNI_FALSE;
    auto *ctx = (JSContext *) ctxPtr;
    JSValue obj = JsHandleTable::instance().get(objHandle);
    if (JS_IsNull(obj)) return JNI_FALSE;

    const char *cname = env->GetStringUTFChars(name, nullptr);
    JSAtom atom = JS_NewAtom(ctx, cname);
    int ret = JS_HasProperty(ctx, obj, atom);
    JS_FreeAtom(ctx, atom);
    env->ReleaseStringUTFChars(name, cname);
    return ret > 0 ? JNI_TRUE : JNI_FALSE;
}

// ============ 类型查询与转换 ============

extern "C" JNIEXPORT jint JNICALL
Java_com_script_quickjs_QuickJsNative_nativeGetTypeTag(JNIEnv *env, jobject clazz,
                                                       jlong ctxPtr, jlong handle) {
    if (!ctxPtr || !handle) return 0;
    auto *ctx = (JSContext *) ctxPtr;
    JSValue val = JsHandleTable::instance().get(handle);
    if (JS_IsNull(val)) return 0;
    return JniValueConvert::getTypeTag(ctx, val);
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_script_quickjs_QuickJsNative_nativeToBoolean(JNIEnv *env, jobject clazz,
                                                      jlong ctxPtr, jlong handle) {
    if (!ctxPtr || !handle) return JNI_FALSE;
    auto *ctx = (JSContext *) ctxPtr;
    JSValue val = JsHandleTable::instance().get(handle);
    if (JS_IsNull(val)) return JNI_FALSE;
    return JS_ToBool(ctx, val) > 0 ? JNI_TRUE : JNI_FALSE;
}

extern "C" JNIEXPORT jint JNICALL
Java_com_script_quickjs_QuickJsNative_nativeToInt32(JNIEnv *env, jobject clazz,
                                                    jlong ctxPtr, jlong handle) {
    if (!ctxPtr || !handle) return 0;
    auto *ctx = (JSContext *) ctxPtr;
    JSValue val = JsHandleTable::instance().get(handle);
    if (JS_IsNull(val)) return 0;
    int32_t v = 0;
    JS_ToInt32(ctx, &v, val);
    return v;
}

extern "C" JNIEXPORT jdouble JNICALL
Java_com_script_quickjs_QuickJsNative_nativeToFloat64(JNIEnv *env, jobject clazz,
                                                      jlong ctxPtr, jlong handle) {
    if (!ctxPtr || !handle) return 0.0;
    auto *ctx = (JSContext *) ctxPtr;
    JSValue val = JsHandleTable::instance().get(handle);
    if (JS_IsNull(val)) return 0.0;
    double v = 0.0;
    JS_ToFloat64(ctx, &v, val);
    return v;
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_script_quickjs_QuickJsNative_nativeToString(JNIEnv *env, jobject clazz,
                                                     jlong ctxPtr, jlong handle) {
    if (!ctxPtr || !handle) return nullptr;
    auto *ctx = (JSContext *) ctxPtr;
    JSValue val = JsHandleTable::instance().get(handle);
    if (JS_IsNull(val)) return nullptr;
    const char *str = JS_ToCString(ctx, val);
    jstring jstr = env->NewStringUTF(str ? str : "");
    JS_FreeCString(ctx, str);
    return jstr;
}

// ============ 从 Java 值创建 JSValue ============

extern "C" JNIEXPORT jobject JNICALL
Java_com_script_quickjs_QuickJsNative_nativeNewBoolean(JNIEnv *env, jobject clazz,
                                                       jlong ctxPtr, jboolean value) {
    if (!ctxPtr) return nullptr;
    auto *ctx = (JSContext *) ctxPtr;
    JSValue val = JS_NewBool(ctx, value == JNI_TRUE);
    int64_t handle = JsHandleTable::instance().store(ctx, val);
    jclass longCls = env->FindClass("java/lang/Long");
    jmethodID valueOf = env->GetStaticMethodID(longCls, "valueOf", "(J)Ljava/lang/Long;");
    jobject result = env->CallStaticObjectMethod(longCls, valueOf, (jlong) handle);
    env->DeleteLocalRef(longCls);
    return result;
}

extern "C" JNIEXPORT jobject JNICALL
Java_com_script_quickjs_QuickJsNative_nativeNewInt32(JNIEnv *env, jobject clazz,
                                                     jlong ctxPtr, jint value) {
    if (!ctxPtr) return nullptr;
    auto *ctx = (JSContext *) ctxPtr;
    JSValue val = JS_NewInt32(ctx, value);
    int64_t handle = JsHandleTable::instance().store(ctx, val);
    jclass longCls = env->FindClass("java/lang/Long");
    jmethodID valueOf = env->GetStaticMethodID(longCls, "valueOf", "(J)Ljava/lang/Long;");
    jobject result = env->CallStaticObjectMethod(longCls, valueOf, (jlong) handle);
    env->DeleteLocalRef(longCls);
    return result;
}

extern "C" JNIEXPORT jobject JNICALL
Java_com_script_quickjs_QuickJsNative_nativeNewFloat64(JNIEnv *env, jobject clazz,
                                                       jlong ctxPtr, jdouble value) {
    if (!ctxPtr) return nullptr;
    auto *ctx = (JSContext *) ctxPtr;
    JSValue val = JS_NewFloat64(ctx, value);
    int64_t handle = JsHandleTable::instance().store(ctx, val);
    jclass longCls = env->FindClass("java/lang/Long");
    jmethodID valueOf = env->GetStaticMethodID(longCls, "valueOf", "(J)Ljava/lang/Long;");
    jobject result = env->CallStaticObjectMethod(longCls, valueOf, (jlong) handle);
    env->DeleteLocalRef(longCls);
    return result;
}

extern "C" JNIEXPORT jobject JNICALL
Java_com_script_quickjs_QuickJsNative_nativeNewString(JNIEnv *env, jobject clazz,
                                                      jlong ctxPtr, jstring value) {
    if (!ctxPtr) return nullptr;
    auto *ctx = (JSContext *) ctxPtr;
    const char *str = env->GetStringUTFChars(value, nullptr);
    JSValue val = JS_NewString(ctx, str ? str : "");
    env->ReleaseStringUTFChars(value, str);
    int64_t handle = JsHandleTable::instance().store(ctx, val);
    jclass longCls = env->FindClass("java/lang/Long");
    jmethodID valueOf = env->GetStaticMethodID(longCls, "valueOf", "(J)Ljava/lang/Long;");
    jobject result = env->CallStaticObjectMethod(longCls, valueOf, (jlong) handle);
    env->DeleteLocalRef(longCls);
    return result;
}

extern "C" JNIEXPORT jobject JNICALL
Java_com_script_quickjs_QuickJsNative_nativeNewArray(JNIEnv *env, jobject clazz, jlong ctxPtr) {
    if (!ctxPtr) return nullptr;
    auto *ctx = (JSContext *) ctxPtr;
    JSValue val = JS_NewArray(ctx);
    int64_t handle = JsHandleTable::instance().store(ctx, val);
    jclass longCls = env->FindClass("java/lang/Long");
    jmethodID valueOf = env->GetStaticMethodID(longCls, "valueOf", "(J)Ljava/lang/Long;");
    jobject result = env->CallStaticObjectMethod(longCls, valueOf, (jlong) handle);
    env->DeleteLocalRef(longCls);
    return result;
}

// ============ JavaObject 包装 ============

extern "C" JNIEXPORT jobject JNICALL
Java_com_script_quickjs_QuickJsNative_nativeWrapJavaObject(JNIEnv *env, jobject clazz,
                                                           jlong ctxPtr, jobject javaObj) {
    if (!ctxPtr || !javaObj) return nullptr;
    auto *ctx = (JSContext *) ctxPtr;
    JSValue val = JavaObjectClass::wrap(ctx, env, javaObj);
    if (JS_IsNull(val)) return nullptr;  // javaObj 为 null (理论不会, 开头已检查)
    if (JS_IsException(val)) {
        // wrap 失败 (classId==0/OOM): JS_ThrowInternalError 已设 Error 到 current_exception
        // 取出异常抛 JsNativeException, 对齐 nativeEval/nativeEvalBytecode/nativeCompile 模式;
        // 否则 Error 残留 ctx, 下次 eval 会误报, 且 Java 侧拿到 nullptr 不知是包装失败
        JSValue exc = JS_GetException(ctx);
        std::string msgStr = JniValueConvert::buildExceptionMessage(ctx, exc);
        JS_FreeValue(ctx, exc);
        jclass excCls = env->FindClass("com/script/quickjs/JsNativeException");
        if (excCls) {
            env->ThrowNew(excCls, msgStr.c_str());
            env->DeleteLocalRef(excCls);
        }
        return nullptr;
    }
    int64_t handle = JsHandleTable::instance().store(ctx, val);
    jclass longCls = env->FindClass("java/lang/Long");
    jmethodID valueOf = env->GetStaticMethodID(longCls, "valueOf", "(J)Ljava/lang/Long;");
    jobject result = env->CallStaticObjectMethod(longCls, valueOf, (jlong) handle);
    env->DeleteLocalRef(longCls);
    return result;
}

// ============ 异常处理 ============

extern "C" JNIEXPORT jobject JNICALL
Java_com_script_quickjs_QuickJsNative_nativeGetException(JNIEnv *env, jobject clazz, jlong ctxPtr) {
    if (!ctxPtr) return nullptr;
    auto *ctx = (JSContext *) ctxPtr;
    JSValue exc = JS_GetException(ctx);
    jobject ret = JniValueConvert::toJavaObject(ctx, env, exc);
    JS_FreeValue(ctx, exc);
    return ret;
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_script_quickjs_QuickJsNative_nativeIsError(JNIEnv *env, jobject clazz,
                                                    jlong ctxPtr, jlong handle) {
    if (!ctxPtr || !handle) return JNI_FALSE;
    // quickjs-ng: JS_IsError 只接受 JSValueConst 单参数,无需 ctx
    JSValue val = JsHandleTable::instance().get(handle);
    if (JS_IsNull(val)) return JNI_FALSE;
    return JS_IsError(val) ? JNI_TRUE : JNI_FALSE;
}

// ============ 函数调用 ============

extern "C" JNIEXPORT jobject JNICALL
Java_com_script_quickjs_QuickJsNative_nativeCallFunction(JNIEnv *env, jobject clazz,
                                                         jlong ctxPtr, jlong funcHandle,
                                                         jlong thisHandle, jlongArray argHandles) {
    if (!ctxPtr || !funcHandle) return nullptr;
    auto *ctx = (JSContext *) ctxPtr;
    JSValue func = JsHandleTable::instance().get(funcHandle);
    if (JS_IsNull(func)) return nullptr;

    JSValue thisVal = thisHandle ? JsHandleTable::instance().get(thisHandle) : JS_UNDEFINED;

    // 准备参数
    jsize argCount = argHandles ? env->GetArrayLength(argHandles) : 0;
    std::vector<JSValue> args;
    args.reserve(argCount);
    if (argHandles) {
        jlong *handles = env->GetLongArrayElements(argHandles, nullptr);
        for (jsize i = 0; i < argCount; i++) {
            JSValue arg = JsHandleTable::instance().get(handles[i]);
            args.push_back(arg);
        }
        env->ReleaseLongArrayElements(argHandles, handles, JNI_ABORT);
    }

    // 更新 stack_top 为当前线程栈指针 (跨线程使用 ctx 时栈检查需基于当前线程)
    JS_UpdateStackTop(JS_GetRuntime(ctx));

    JSValue result = JS_Call(ctx, func, thisVal, argCount, args.data());
    jobject ret = JniValueConvert::toJavaObject(ctx, env, result);
    JS_FreeValue(ctx, result);
    return ret;
}

// ============ bytecode 编译与执行 ============

extern "C" JNIEXPORT jbyteArray JNICALL
Java_com_script_quickjs_QuickJsNative_nativeCompile(JNIEnv *env, jobject clazz,
                                                    jlong ctxPtr, jstring code) {
    if (!ctxPtr || !code) return nullptr;
    auto *ctx = (JSContext *) ctxPtr;

    const char *src = env->GetStringUTFChars(code, nullptr);
    if (!src) return nullptr;

    // 更新 stack_top 为当前线程栈指针: compilerCtx 是全局单例, 可能被不同线程使用,
    // QuickJS 在 JS_NewRuntime 时记录创建线程的 stack_top, 跨线程使用时栈检查会基于
    // 错误的 stack_top (跨线程栈地址不可比较), 可能误报 "Maximum call stack size exceeded"
    // 或导致 native crash。与 nativeEval/nativeEvalBytecode 保持一致。
    JS_UpdateStackTop(JS_GetRuntime(ctx));

    // 编译为 bytecode (不执行)
    JSValue funVal = JS_Eval(ctx, src, strlen(src), "<compile>",
                             JS_EVAL_TYPE_GLOBAL | JS_EVAL_FLAG_COMPILE_ONLY);
    env->ReleaseStringUTFChars(code, src);

    if (JS_IsException(funVal)) {
        // 获取编译错误的实际信息 (如语法错误位置), 抛出 JsNativeException 携带原始错误,
        // 避免返回 null 让 Kotlin 侧只能抛 "Compile failed" 丢失错误信息无法排查
        JSValue exc = JS_GetException(ctx);
        // 用 buildExceptionMessage 获取含 stack 的完整消息
        // (编译错误的 SyntaxError message 已含 "at line X col Y", stack 可能没意义但保留)
        std::string msgStr = JniValueConvert::buildExceptionMessage(ctx, exc);
        JS_FreeValue(ctx, exc);
        JS_FreeValue(ctx, funVal);
        jclass excCls = env->FindClass("com/script/quickjs/JsNativeException");
        if (excCls) {
            env->ThrowNew(excCls, msgStr.c_str());
            env->DeleteLocalRef(excCls);
        }
        return nullptr;
    }

    // 序列化 bytecode (quickjs-ng 用 JS_WriteObject + JS_WRITE_OBJ_BYTECODE)
    size_t outLen = 0;
    uint8_t *buf = JS_WriteObject(ctx, &outLen, funVal, JS_WRITE_OBJ_BYTECODE);
    JS_FreeValue(ctx, funVal);

    if (!buf) return nullptr;

    jbyteArray result = env->NewByteArray((jsize) outLen);
    env->SetByteArrayRegion(result, 0, (jsize) outLen, (jbyte *) buf);
    js_free(ctx, buf);
    return result;
}

extern "C" JNIEXPORT jobject JNICALL
Java_com_script_quickjs_QuickJsNative_nativeEvalBytecode(JNIEnv *env, jobject clazz,
                                                         jlong ctxPtr, jbyteArray bytecode) {
    if (!ctxPtr || !bytecode) return nullptr;
    auto *ctx = (JSContext *) ctxPtr;

    jsize len = env->GetArrayLength(bytecode);
    jbyte *data = env->GetByteArrayElements(bytecode, nullptr);

    // 反序列化 bytecode (用 JS_ReadObject + JS_READ_OBJ_BYTECODE flag)
    JSValue funVal = JS_ReadObject(ctx, (uint8_t *) data, len, JS_READ_OBJ_BYTECODE);
    env->ReleaseByteArrayElements(bytecode, data, JNI_ABORT);

    if (JS_IsException(funVal)) {
        // 对齐 nativeCompile(行 464-479): JS_ReadObject 失败(bytecode 损坏/版本不兼容)时
        // ctx 异常 slot 已设, 获取异常信息抛 JsNativeException, 避免返回 null 让 Kotlin 侧
        // 只能抛通用 "Eval bytecode failed" 丢失原始错误信息(如 "bytecode header mismatch")
        JSValue exc = JS_GetException(ctx);
        std::string msgStr = JniValueConvert::buildExceptionMessage(ctx, exc);
        JS_FreeValue(ctx, exc);
        JS_FreeValue(ctx, funVal);
        jclass excCls = env->FindClass("com/script/quickjs/JsNativeException");
        if (excCls) {
            env->ThrowNew(excCls, msgStr.c_str());
            env->DeleteLocalRef(excCls);
        }
        return nullptr;
    }

    // 更新 stack_top 为当前线程栈指针 (跨线程使用 ctx 时栈检查需基于当前线程)
    JS_UpdateStackTop(JS_GetRuntime(ctx));

    // 执行
    JSValue result = JS_EvalFunction(ctx, funVal);
    jobject ret = JniValueConvert::toJavaObject(ctx, env, result);
    JS_FreeValue(ctx, result);
    return ret;
}

// ============ Binding 注册 ============

extern "C" JNIEXPORT jboolean JNICALL
Java_com_script_quickjs_QuickJsNative_nativeDefineBinding(JNIEnv *env, jobject clazz,
                                                          jlong ctxPtr, jstring name) {
    if (!ctxPtr || !name) return JNI_FALSE;
    auto *ctx = (JSContext *) ctxPtr;
    const char *cname = env->GetStringUTFChars(name, nullptr);
    if (!cname) return JNI_FALSE;
    bool ok = defineBinding(ctx, cname);
    env->ReleaseStringUTFChars(name, cname);
    return ok ? JNI_TRUE : JNI_FALSE;
}

// ============ dangerousApi 管理 ============

extern "C" JNIEXPORT void JNICALL
Java_com_script_quickjs_QuickJsNative_nativeSetDangerousApi(JNIEnv *env, jobject clazz,
                                                            jlong ctxPtr, jboolean dangerousApi) {
    if (!ctxPtr) return;
    auto *ctx = (JSContext *) ctxPtr;
    setDangerousApi(ctx, dangerousApi == JNI_TRUE);
}
