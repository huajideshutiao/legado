#ifndef JNI_CALLBACKS_H
#define JNI_CALLBACKS_H

#include <quickjs.h>
#include <jni.h>
#include <cstdint>
#include <string>
#include <unordered_map>

/**
 * JS 回调函数管理 (method callable + binding 注册)。
 *
 * 设计目的: 用 JS_NewCFunctionData 创建带闭包数据的 C 函数,
 * 实现 method callable 和 binding 回调, 避免 ES6 Proxy 的多层桥接开销。
 *
 * 1. method callable: JS 访问 javaObj.methodName 时, native trap 创建 JS 函数,
 *    func_data 存 objHandle 和 methodName, 调用时回调 JavaObjectBridgeNative.callMethod。
 *
 * 2. binding 注册: bootstrap 中的 __loadJavaClass 等 binding 通过 defineBinding 注册,
 *    func_data 存 binding name, 调用时回调 BindingHandler.call(name, args)。
 *
 * 3. dangerousApi 管理: 通过 ctx opaque 存储, trap 和回调时读取。
 */

// ctx opaque 数据 (存储 dangerousApi 等运行时状态)
struct CtxOpaqueData {
    bool dangerousApi;
    // method callable JSValue 缓存: methodName -> CFunctionData
    // 同名方法在所有 Java 对象间共享一个 callable, 调用时从 this_val 取 jobject。
    // 原先每次 obj.method 属性访问都 JS_NewCFunctionData 新建 + DupValue/FreeValue,
    // 循环里 sb.append 这样的写法每轮都要分配新 callable; 缓存后 JS 端命中走 JS_DupValue
    // (引用计数 +1) 即可。释放在 freeCtxOpaque 里统一 JS_FreeValue。
    std::unordered_map<std::string, JSValue> methodCallableCache;
};

/**
 * 初始化 ctx opaque (nativeCreateContext 时调用)。
 */
void initCtxOpaque(JSContext *ctx);

/**
 * 释放 ctx opaque (nativeFreeContext 时调用)。
 */
void freeCtxOpaque(JSContext *ctx);

/**
 * 设置 ctx 的 dangerousApi 标志。
 */
void setDangerousApi(JSContext *ctx, bool dangerousApi);

/**
 * 获取 ctx 的 dangerousApi 标志。
 */
bool getDangerousApi(JSContext *ctx);

/**
 * 获取/创建 method callable JS 函数。
 *
 * 调用约定: 创建的 callable 不持有 jobject, 调用时从 this_val (即调用点的接收对象)
 * 取出 jobject 传给 Java 侧。这让同名方法可在所有 Java 对象间共享同一个 JSFunction
 * 实例, 避免循环里反复分配 (例如 sb.append 这类 hot loop)。
 *
 * 命中 [CtxOpaqueData::methodCallableCache] 时返回 cached value 的 DupValue;
 * 未命中则新建并写入 cache。
 *
 * @param ctx JSContext
 * @param methodName 方法名 (cache key)
 * @return JS 函数 (调用方负责 FreeValue)
 */
JSValue getOrCreateMethodCallable(JSContext *ctx, const char *methodName);

/**
 * 注册 binding (JS 全局函数, 回调 Java BindingHandler.call)。
 *
 * @param ctx JSContext
 * @param name binding 名称 (如 "__loadJavaClass")
 * @return true 成功, false 失败
 */
bool defineBinding(JSContext *ctx, const char *name);

#endif // JNI_CALLBACKS_H
