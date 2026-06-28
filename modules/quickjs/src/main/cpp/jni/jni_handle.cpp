#include "jni_handle.h"

// JS_NULL 等常量在 quickjs.h 中定义为宏,需要包含
#include <quickjs.h>

JsHandleTable::JsHandleTable() : nextHandle(1) {}

JsHandleTable &JsHandleTable::instance() {
    static JsHandleTable instance;
    return instance;
}

int64_t JsHandleTable::store(JSContext *ctx, JSValue value) {
    std::lock_guard<std::mutex> lock(mutex);
    int64_t handle = nextHandle++;
    table[handle] = Entry{ctx, value};
    return handle;
}

JSValue JsHandleTable::get(int64_t handle) {
    std::lock_guard<std::mutex> lock(mutex);
    auto it = table.find(handle);
    if (it == table.end()) {
        // 句柄不存在,返回 NULL(不是 undefined,以便区分)
        return JS_NULL;
    }
    return it->second.value;
}

JSContext *JsHandleTable::getCtx(int64_t handle) {
    std::lock_guard<std::mutex> lock(mutex);
    auto it = table.find(handle);
    if (it == table.end()) {
        return nullptr;
    }
    return it->second.ctx;
}

void JsHandleTable::release(int64_t handle) {
    std::lock_guard<std::mutex> lock(mutex);
    auto it = table.find(handle);
    if (it == table.end()) {
        return;
    }
    // 在锁外 FreeValue 可能更安全,但 quickjs-ng 的 FreeValue 本身是线程安全的
    // (只要 ctx 有效)。这里在锁内释放,避免释放后其他线程拿到悬空句柄。
    JS_FreeValue(it->second.ctx, it->second.value);
    table.erase(it);
}

void JsHandleTable::releaseByCtx(JSContext *ctx) {
    std::lock_guard<std::mutex> lock(mutex);
    auto it = table.begin();
    while (it != table.end()) {
        if (it->second.ctx == ctx) {
            JS_FreeValue(it->second.ctx, it->second.value);
            it = table.erase(it);
        } else {
            ++it;
        }
    }
}
