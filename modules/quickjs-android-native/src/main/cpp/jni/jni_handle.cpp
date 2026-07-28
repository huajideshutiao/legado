#include "jni_handle.h"

#include <quickjs.h>
#include <cstdlib>
#include <cstring>

namespace {
    constexpr uint32_t kInitSize = 64;    // 2^6
    constexpr uint32_t kEmpty = 0;
    constexpr uint32_t kUsed = 1;
    constexpr uint32_t kTomb = 2;

    // 与 qjs hash_string8 (h * 263) 同款单乘散列: 自增 handle 走一次黄金比乘法散开低位。
    inline uint32_t hashKey(int64_t key) {
        return (uint32_t) ((uint64_t) key * 0x9E3779B97F4A7C15ULL >> 32);
    }
}

JsHandleTable::JsHandleTable()
        : mutex(PTHREAD_MUTEX_INITIALIZER),
          hash(nullptr), size(0), used(0), tombstones(0), nextHandle(1) {}

JsHandleTable::~JsHandleTable() {
    // 进程退出路径: ctx 可能已随 runtime 销毁, 不主动 FreeValue 避免 UAF。
    std::free(hash);
    pthread_mutex_destroy(&mutex);
}

JsHandleTable &JsHandleTable::instance() {
    static JsHandleTable instance;
    return instance;
}

uint32_t JsHandleTable::probeLocked(int64_t key, bool *outFound) const {
    const uint32_t mask = size - 1;
    uint32_t idx = hashKey(key) & mask;
    uint32_t firstTomb = UINT32_MAX;
    for (;;) {
        const Entry &e = hash[idx];
        if (e.state == kEmpty) {
            *outFound = false;
            return firstTomb != UINT32_MAX ? firstTomb : idx;
        }
        if (e.state == kUsed && e.key == key) {
            *outFound = true;
            return idx;
        }
        if (e.state == kTomb && firstTomb == UINT32_MAX) firstTomb = idx;
        idx = (idx + 1) & mask;
    }
}

void JsHandleTable::rehashLocked(uint32_t newSize) {
    Entry *oldHash = hash;
    uint32_t oldSize = size;
    Entry *nb = (Entry *) std::calloc(newSize, sizeof(Entry));
    if (!nb) return;
    hash = nb;
    size = newSize;
    used = 0;
    tombstones = 0;
    if (oldHash) {
        for (uint32_t i = 0; i < oldSize; i++) {
            const Entry &e = oldHash[i];
            if (e.state != kUsed) continue;
            bool found;
            uint32_t idx = probeLocked(e.key, &found);
            hash[idx] = e;
            used++;
        }
        std::free(oldHash);
    }
}

int64_t JsHandleTable::store(JSContext *ctx, JSValue value) {
    pthread_mutex_lock(&mutex);
    if (!hash) {
        rehashLocked(kInitSize);
        if (!hash) {
            pthread_mutex_unlock(&mutex);
            return 0;
        }
    }
    // load factor >= 0.75 触发 rehash; tombstone 占大头时同尺寸清扫。
    if ((used + tombstones) * 4 >= size * 3) {
        rehashLocked(tombstones * 2 > used ? size : size * 2);
    }
    int64_t handle = nextHandle++;
    bool found;
    uint32_t idx = probeLocked(handle, &found);
    if (hash[idx].state == kTomb) tombstones--;
    hash[idx] = {handle, ctx, value, kUsed};
    used++;
    pthread_mutex_unlock(&mutex);
    return handle;
}

JSValue JsHandleTable::get(int64_t handle) {
    pthread_mutex_lock(&mutex);
    if (!hash) {
        pthread_mutex_unlock(&mutex);
        return JS_NULL;
    }
    bool found;
    uint32_t idx = probeLocked(handle, &found);
    JSValue v = found ? hash[idx].value : JS_NULL;
    pthread_mutex_unlock(&mutex);
    return v;
}

JSContext *JsHandleTable::getCtx(int64_t handle) {
    pthread_mutex_lock(&mutex);
    if (!hash) {
        pthread_mutex_unlock(&mutex);
        return nullptr;
    }
    bool found;
    uint32_t idx = probeLocked(handle, &found);
    JSContext *c = found ? hash[idx].ctx : nullptr;
    pthread_mutex_unlock(&mutex);
    return c;
}

void JsHandleTable::release(int64_t handle) {
    pthread_mutex_lock(&mutex);
    if (!hash) {
        pthread_mutex_unlock(&mutex);
        return;
    }
    bool found;
    uint32_t idx = probeLocked(handle, &found);
    if (!found) {
        pthread_mutex_unlock(&mutex);
        return;
    }
    // 锁内 FreeValue: 避免其他线程读到已释放句柄。
    JS_FreeValue(hash[idx].ctx, hash[idx].value);
    hash[idx].state = kTomb;
    used--;
    tombstones++;
    pthread_mutex_unlock(&mutex);
}

void JsHandleTable::releaseByCtx(JSContext *ctx) {
    pthread_mutex_lock(&mutex);
    if (!hash) {
        pthread_mutex_unlock(&mutex);
        return;
    }
    for (uint32_t i = 0; i < size; i++) {
        Entry &e = hash[i];
        if (e.state == kUsed && e.ctx == ctx) {
            JS_FreeValue(e.ctx, e.value);
            e.state = kTomb;
            used--;
            tombstones++;
        }
    }
    pthread_mutex_unlock(&mutex);
}
