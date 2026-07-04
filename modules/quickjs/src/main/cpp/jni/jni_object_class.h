#ifndef JNI_OBJECT_CLASS_H
#define JNI_OBJECT_CLASS_H

#include <quickjs.h>
#include <jni.h>
#include <pthread.h>
#include <cstdint>

/**
 * JavaObject 自定义类: 用 exotic trap 让 Java 对象在 JS 侧像原生对象一样访问,
 * opaque 槽存 jobject GlobalRef, GC finalizer 释放。
 */
class JavaObjectClass {
public:
    /**
     * 每个 JSRuntime 都要注册一次 class (JS_NewClass 是 runtime-scoped);
     * classId 由 JS_NewClassID 分配, 进程级唯一。
     */
    static JSClassID init(JSRuntime *rt, JavaVM *jvm);

    /**
     * JS_FreeRuntime 之前必须调用: 否则悬空的 rt 指针可能被新 runtime 复用地址,
     * 导致本表误判为已注册而跳过 JS_NewClass。
     */
    static void unregisterRuntime(JSRuntime *rt);

    static JSClassID getClassId();

    // 包装 Java 对象为 JSValue; 返回值已 DupValue。
    static JSValue wrap(JSContext *ctx, JNIEnv *env, jobject javaObj);

    static bool isInstance(JSContext *ctx, JSValueConst val);

    // 取出 jobject (不转移引用)。
    static jobject getJavaObject(JSContext *ctx, JSValueConst val);

    // 供匿名命名空间的 getJniEnv() 使用。
    static JavaVM *cachedJvm;

    // JNI_OnLoad 阶段预热 BridgeNative / List / Class.isArray / Boolean 缓存。
    static void initBridgeCache(JNIEnv *env);

private:
    static JSClassID classId;
    // 已注册 runtime 列表: runtime 通常个位数, 线性数组即可, 免掉 unordered_set 模板膨胀。
    static JSRuntime **registeredRuntimes;
    static uint32_t registeredCount;
    static uint32_t registeredCap;
    static pthread_mutex_t registryMutex;

    // JSClassExoticMethods trap.
    static int hasProperty(JSContext *ctx, JSValueConst obj, JSAtom prop);
    static JSValue getProperty(JSContext *ctx, JSValueConst obj, JSAtom atom,
                               JSValueConst receiver);
    static int setProperty(JSContext *ctx, JSValueConst obj, JSAtom atom,
                           JSValueConst value, JSValueConst receiver, int flags);
    static int deleteProperty(JSContext *ctx, JSValueConst obj, JSAtom prop);
    static int getOwnProperty(JSContext *ctx, JSPropertyDescriptor *desc,
                              JSValueConst obj, JSAtom prop);
    static int getOwnPropertyNames(JSContext *ctx, JSPropertyEnum **ptab,
                                   uint32_t *plen, JSValueConst obj);
    static void finalizer(JSRuntime *rt, JSValueConst val);
    static const char *atomToCString(JSContext *ctx, JSAtom atom);
};

#endif // JNI_OBJECT_CLASS_H
