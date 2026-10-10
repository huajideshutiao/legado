package io.legado.app.ui

import io.legado.app.constant.DirUsage
import io.legado.app.utils.IosSecurityScopedStorage

/**
 * iOS 实现: 访问包在 security-scoped 授权租约内 (沙盒内路径自动放行)。
 *
 * 授权按 [usage] 匹配: 只有用户经同一用途入口选定的目录才放行, 租约失败抛
 * `SecurityException` 由调用方处理。块内收到的是授权层实际使用的路径 (规范化后的目标,
 * 或文件协调器给出的替代位置)。
 *
 * 租约按调用粒度起停, 不跨调用持有 —— 导入浏览器可能长时间停留在某目录, 长期持 scope
 * 等于把授权挂到进程生命周期上。
 */
internal actual fun <T> withLocalDirAccess(path: String, usage: DirUsage, block: (String) -> T): T =
    IosSecurityScopedStorage.withAccess(path, usage, block = block)
