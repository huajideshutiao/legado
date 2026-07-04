#ifndef JNI_CALLBACKS_H
#define JNI_CALLBACKS_H

#include <quickjs.h>
#include <jni.h>
#include <cstdint>

/**
 * JS 回调函数管理 (method callable + binding 注册)。
 *
 * 用 JS_NewCFunctionData 创建带 closure 的 C 函数, 替代 ES6 Proxy 的多层桥接:
 *   - method callable: obj.method 时 trap 返回同名共享 callable, 调用时从 this_val 取 jobject
 *   - binding: 全局函数 (如 __loadJavaClass), 回调 Java BindingHandler.call
 *   - dangerousApi 开关放 ctx opaque, trap 直接读
 */

// method callable 缓存桶。state 详见 .cpp。
struct MethodCallableEntry {
    JSAtom key;
    JSValue value;
    uint32_t state;     // 0 空, 1 占用
};

// 存放 ctx-scoped 运行时状态。
struct CtxOpaqueData {
    bool dangerousApi;
    // 同名方法在所有 Java 对象间共享一个 callable, 避免 hot loop 中反复分配。
    // power-of-two 开放寻址表 (splitmix32 + 线性探测); 只 insert 不 delete, 无 tombstone。
    MethodCallableEntry *methodCallableHash;
    uint32_t methodCallableSize;
    uint32_t methodCallableUsed;
    // Array.prototype 缓存 (lazy): Java 数组 wrap 时挂上, 让 slice/map/... 通过原型链可用。
    JSValue arrayProto;
    // well-known Symbol.iterator atom, 用于 for...of trap 判断; 免掉 atom->cstring->strcmp。
    // 必须从 globalThis.Symbol.iterator -> JS_ValueToAtom 拿, JS_NewAtom("Symbol.iterator")
    // 只查 STRING atom 表, 与 SYMBOL 型 well-known atom 数值不等。
    JSAtom symbolIteratorAtom;
};

// JNI_OnLoad 阶段预热 callback 相关类。幂等。
void initJniCallbacksCache(JNIEnv *env);

void initCtxOpaque(JSContext *ctx);
void freeCtxOpaque(JSContext *ctx);

void setDangerousApi(JSContext *ctx, bool dangerousApi);
bool getDangerousApi(JSContext *ctx);

/**
 * 按 atom 缓存 method callable。callable 不持有 jobject, 调用时从 this_val 取,
 * 所以同名方法可在所有 Java 对象间共享一份 JSFunction。
 *
 * 与 rhino 语义一致: `var fn = obj.method; fn(x)` 会丢 this 抛 TypeError。
 * 调用方需 FreeValue 返回值。
 */
JSValue getOrCreateMethodCallable(JSContext *ctx, JSAtom atom);

// 注册全局 binding (回调 Java BindingHandler.call)。
bool defineBinding(JSContext *ctx, const char *name);

#endif // JNI_CALLBACKS_H
