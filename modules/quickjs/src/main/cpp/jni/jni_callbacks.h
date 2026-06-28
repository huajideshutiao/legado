#ifndef JNI_CALLBACKS_H
#define JNI_CALLBACKS_H

#include <quickjs.h>
#include <jni.h>
#include <cstdint>

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
 * 创建 method callable JS 函数。
 *
 * @param ctx JSContext
 * @param objHandle Java 对象句柄
 * @param methodName 方法名
 * @return JS 函数 (调用方负责 FreeValue)
 */
JSValue createMethodCallable(JSContext *ctx, int64_t objHandle, const char *methodName);

/**
 * 注册 binding (JS 全局函数, 回调 Java BindingHandler.call)。
 *
 * @param ctx JSContext
 * @param name binding 名称 (如 "__loadJavaClass")
 * @return true 成功, false 失败
 */
bool defineBinding(JSContext *ctx, const char *name);

#endif // JNI_CALLBACKS_H
