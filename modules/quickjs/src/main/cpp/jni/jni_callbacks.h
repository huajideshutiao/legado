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
    //
    // key 从 std::string 改为 JSAtom: 属性访问已经手上一份 atom, 免掉 atom→cstring→string 的转换,
    // 也不再有异构查找 (const char* find(std::string)) 的隐式临时构造。JS_DupAtom 只是刷 refcount。
    // 键在 freeCtxOpaque 里 JS_FreeAtom, 值 JS_FreeValue。
    std::unordered_map<JSAtom, JSValue> methodCallableCache;
    // Array.prototype 缓存 (lazy init): Java 数组 wrap 时设置 prototype 为 Array.prototype,
    // 对齐 rhino NativeJavaArray.getPrototype() 行为, 让 slice/map/filter/forEach/indexOf
    // 等 Array.prototype 方法通过原型链可用。JS_UNDEFINED 表示未初始化。
    // 释放在 freeCtxOpaque 里统一 JS_FreeValue。
    JSValue arrayProto;
    // Symbol.iterator atom 缓存: getProperty trap 需要判断当前 atom 是否为 well-known
    // Symbol.iterator (让 Java List/Array 支持 JS for...of)。原先每次属性访问都做
    // JS_AtomToCString + strcmp + JS_FreeCString, atom 比较是数值相等, 免掉字符串往返。
    // 注意: 必须通过 globalThis.Symbol.iterator → JS_ValueToAtom 拿, 不能用
    // JS_NewAtom("Symbol.iterator") — 后者只在 STRING atom 表里查, 与 well-known 的
    // SYMBOL 类型 atom 数值不等 (见 initCtxOpaque 注释)。
    // 释放在 freeCtxOpaque 里 JS_FreeAtom。
    JSAtom symbolIteratorAtom;
};

/**
 * JNI_OnLoad 阶段预热 callback 相关类 (BridgeNative / BindingHandler / Object)。
 * 幂等, 内部 std::call_once。
 */
void initJniCallbacksCache(JNIEnv *env);

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
 * 获取/创建 method callable JS 函数 (按 atom 缓存)。
 *
 * 调用约定: 创建的 callable 不持有 jobject, 调用时从 this_val (即调用点的接收对象)
 * 取出 jobject 传给 Java 侧。这让同名方法可在所有 Java 对象间共享同一个 JSFunction
 * 实例, 避免循环里反复分配 (例如 sb.append 这类 hot loop)。
 *
 * 与 rhino 对齐: `var fn = obj.method; fn(x)` 这种提取调用会丢失 this, 抛 TypeError,
 * 与 rhino NativeJavaMethod 语义一致 (rhino 也要求 LiveConnect 调用带正确 this)。
 *
 * atom 版本: 属性访问 trap 手上已有 atom, 免掉 atom → cstring → std::string 的转换,
 * 缓存 key 就是 atom (JSAtom 是 uint32_t, unordered_map 命中直接数值比较)。
 * 未命中时同样只做一次 atom → cstring → JS_NewString 用来存入 func_data。
 *
 * @param ctx JSContext
 * @param atom 方法名 atom (cache key, 内部 JS_DupAtom 保留)
 * @return JS 函数 (调用方负责 FreeValue)
 */
JSValue getOrCreateMethodCallable(JSContext *ctx, JSAtom atom);

/**
 * 注册 binding (JS 全局函数, 回调 Java BindingHandler.call)。
 *
 * @param ctx JSContext
 * @param name binding 名称 (如 "__loadJavaClass")
 * @return true 成功, false 失败
 */
bool defineBinding(JSContext *ctx, const char *name);

#endif // JNI_CALLBACKS_H
