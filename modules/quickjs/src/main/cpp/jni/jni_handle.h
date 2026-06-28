#ifndef JNI_HANDLE_H
#define JNI_HANDLE_H

#include <quickjs.h>
#include <cstdint>
#include <mutex>
#include <unordered_map>

/**
 * JSValue 句柄表。
 *
 * 设计目的: JSValue 是 native 层的栈值(按值拷贝),不能直接传给 Java。
 * 用 long 句柄包装已 DupValue 的 JSValue,Java 侧只持有 8 字节句柄。
 *
 * 线程模型: 句柄表操作加锁(Java 侧 Cleaner 可能在其他线程调用 release)。
 * JS API 调用本身由调用方保证在同一线程(JSRuntime 单线程约束)。
 *
 * 生命周期: 句柄由 Java 侧 Cleaner/release 显式释放,或 context 销毁时批量释放。
 */
class JsHandleTable {
public:
    static JsHandleTable &instance();

    /**
     * 存储 JSValue (调用方需先 DupValue),返回句柄。
     * ctx 用于记录所属 context,release 时用同一 ctx 调用 JS_FreeValue。
     */
    int64_t store(JSContext *ctx, JSValue value);

    /**
     * 获取 JSValue (不转移所有权,调用方不应 FreeValue)。
     * 返回 JS_UNDEFINED 表示句柄不存在。
     */
    JSValue get(int64_t handle);

    /**
     * 获取句柄对应的 ctx。
     */
    JSContext *getCtx(int64_t handle);

    /**
     * 释放句柄 (JS_FreeValue)。
     */
    void release(int64_t handle);

    /**
     * 释放某 ctx 的所有句柄 (context 销毁时调用)。
     */
    void releaseByCtx(JSContext *ctx);

private:
    JsHandleTable();

    struct Entry {
        JSContext *ctx;
        JSValue value;
    };
    std::mutex mutex;
    std::unordered_map<int64_t, Entry> table;
    int64_t nextHandle;
};

#endif // JNI_HANDLE_H
