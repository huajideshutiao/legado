#ifndef JNI_OBJECT_CLASS_H
#define JNI_OBJECT_CLASS_H

#include <quickjs.h>
#include <jni.h>
#include <mutex>
#include <unordered_set>

/**
 * JavaObject 自定义类。
 *
 * 用 JSClassExoticMethods 拦截属性访问,让 Java 对象在 JS 侧表现为原生对象。
 * opaque 槽存 jobject 的全局引用 (GlobalRef),GC 时 finalizer 自动释放。
 *
 * exotic trap 流程:
 *   JS 访问 javaObj.prop
 *     -> get_property trap
 *       -> JNI 回调 Java 侧 JavaObjectBridge.getProperty(obj, "prop")
 *         -> Java 反射返回结果
 *       -> 结果转 JSValue 返回
 *
 * 收益: 彻底消除 quickjs-kt 的 619 行 bootstrap JS 代码。
 */
class JavaObjectClass {
public:
    /**
     * 初始化 JavaObject 自定义类。
     *
     * classId (由 JS_NewClassID 分配) 进程级全局唯一,可跨 runtime 复用;
     * 但 JS_NewClass 是 runtime-specific 的,每个新 JSRuntime 都必须注册一次,
     * 否则 JS_NewObjectClass 在未注册的 runtime 上会返回 exception,
     * 导致 wrap() 返回 JS_NULL,nativeWrapJavaObject 返回 nullptr,
     * Java 侧 injectVariable 注入失败 (valueHandle==0L),JS 访问对象报
     * "not a function" / "cannot read property of undefined"。
     *
     * 内部用 registeredRuntimes 集合避免在同一 runtime 上重复注册。
     */
    static JSClassID init(JSRuntime *rt, JavaVM *jvm);

    /**
     * 从 registeredRuntimes 移除已释放的 runtime 指针。
     *
     * 必须在 JS_FreeRuntime 之前调用,否则 rt 指针悬空后仍留在集合中。
     * 若新 JSRuntime 分配在同一地址 (内存分配器可能复用), registeredRuntimes
     * 会误判为已注册而跳过 JS_NewClass, 导致新 runtime 上 JavaObject class
     * 未注册, 引发 "not a function" 和崩溃 (表现为单独运行通过、全部运行崩溃)。
     */
    static void unregisterRuntime(JSRuntime *rt);

    /**
     * 获取 class_id (init 后调用)。
     */
    static JSClassID getClassId();

    /**
     * 包装 Java 对象为 JSValue。
     * 创建 JavaObject 类实例,opaque 存 jobject GlobalRef。
     * 返回的 JSValue 已 DupValue,调用方可直接存入句柄表。
     */
    static JSValue wrap(JSContext *ctx, JNIEnv *env, jobject javaObj);

    /**
     * 判断 JSValue 是否为 JavaObject 实例。
     */
    static bool isInstance(JSContext *ctx, JSValueConst val);

    /**
     * 从 JavaObject 实例获取 jobject (不转移引用,调用方不应 DeleteGlobalRef)。
     */
    static jobject getJavaObject(JSContext *ctx, JSValueConst val);

    // 缓存的 JavaVM 指针 (init 时设置,供 trap 回调获取 JNIEnv)
    // public 以便匿名命名空间的 getJniEnv() 访问
    static JavaVM *cachedJvm;

private:
    static JSClassID classId;
    // 已注册 JavaObject class 的 runtime 集合
    // 避免在同一 runtime 上重复调用 JS_NewClass (JS_NewClassID 全局分配,JS_NewClass 是 runtime-specific)
    static std::unordered_set<JSRuntime *> registeredRuntimes;
    // 保护 registeredRuntimes 与 classId 的并发 find/insert/erase。
    // nativeCreateContext 可能从多个线程并发调用 init(), unordered_set 的并发
    // 读写是 UB, 在 heap 上踩到 rehash 中的临时状态会污染相邻分配块,
    // 表现为遥远 JSString header 被覆盖, 后续 JS_ToCString 调 strv 时 abort。
    static std::mutex registryMutex;

    // JSClassExoticMethods trap 实现
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

    // GC finalizer
    static void finalizer(JSRuntime *rt, JSValueConst val);

    // 辅助: JSAtom -> C 字符串
    static const char *atomToCString(JSContext *ctx, JSAtom atom);
};

#endif // JNI_OBJECT_CLASS_H
