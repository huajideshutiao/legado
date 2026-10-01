package io.legado.app.ui

import io.legado.app.utils.IosSecurityScopedStorage

/**
 * iOS 实现: 目录/文件访问包在 security-scoped 授权租约内 (沙盒内路径自动放行)。
 *
 * 租约按调用粒度起停, 不跨调用持有 —— 导入浏览器可能长时间停留在某目录, 长期持 scope
 * 等于把授权挂到进程生命周期上。
 */
internal actual fun <T> withLocalDirAccess(path: String, block: () -> T): T =
    IosSecurityScopedStorage.withAccess(path) { block() }
