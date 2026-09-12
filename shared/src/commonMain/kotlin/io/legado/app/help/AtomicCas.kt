package io.legado.app.help

import kotlinx.atomicfu.AtomicRef

/**
 * 无锁 CAS 更新环 (只依赖 [AtomicRef.value] / [AtomicRef.compareAndSet] 这两个确定存在的 API)。
 *
 * 用途: 热路径上的「不可变快照 + copy-on-write」容器 —— 读侧一次 volatile 取值即返回,
 * 完全不加锁; 写侧在 CAS 重试环里生成新快照。
 *
 * 约束: [transform] 必须**纯函数**且幂等 (它可能被并发执行多次), 无副作用;
 * 返回 `=== old` 表示"无需变更", 直接结束, 不做多余的发布。
 *
 * 不使用 `AtomicRef.update { }`: 该扩展在各 atomicfu 版本上的存在性未经本仓库实测,
 * 而本文件用到的成员在 `kotlinx.atomicfu.AtomicRef` 上均可确认存在。
 */
internal inline fun <T> AtomicRef<T>.casUpdate(transform: (T) -> T) {
    while (true) {
        val old = value
        val next = transform(old)
        if (next === old) return
        if (compareAndSet(old, next)) return
    }
}
