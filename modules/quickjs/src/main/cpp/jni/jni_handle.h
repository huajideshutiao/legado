#ifndef JNI_HANDLE_H
#define JNI_HANDLE_H

#include <quickjs.h>
#include <cstdint>
#include <pthread.h>

/**
 * JSValue 句柄表: 用 long 句柄包装 DupValue 过的 JSValue 交给 Java 持有。
 * Cleaner 可能跨线程 release, 因此加锁; 其他 JS API 由调用方保证同线程。
 * 内部是 power-of-two 开放寻址表 (splitmix64 hash + 线性探测 + tombstone)。
 */
class JsHandleTable {
public:
    static JsHandleTable &instance();

    // 存入已 DupValue 的 JSValue, 返回句柄; ctx 用于 release 时匹配。
    int64_t store(JSContext *ctx, JSValue value);

    // 读取 (不转移所有权)。无效句柄返回 JS_NULL。
    JSValue get(int64_t handle);
    JSContext *getCtx(int64_t handle);

    // 单个 / 批量释放 (JS_FreeValue)。
    void release(int64_t handle);
    void releaseByCtx(JSContext *ctx);

private:
    JsHandleTable();

    ~JsHandleTable();

    // state: 0 空, 1 占用, 2 tombstone (删除后仍占位以维持探测链)。
    struct Entry {
        int64_t key;
        JSContext *ctx;
        JSValue value;
        uint32_t state;
    };

    void rehashLocked(uint32_t newSize);

    uint32_t probeLocked(int64_t key, bool *outFound) const;

    pthread_mutex_t mutex;
    Entry *hash;        // 数组; size 为 2 的幂
    uint32_t size;
    uint32_t used;
    uint32_t tombstones;
    int64_t nextHandle;
};

#endif // JNI_HANDLE_H
