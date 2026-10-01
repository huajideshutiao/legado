package io.legado.app.ui

/**
 * 在本地目录/文件上执行 [block], 由平台实现决定是否需要先取得外部目录访问授权。
 *
 * iOS 用户经"文件"面板授权的目录在应用容器之外, 文件系统调用必须包在 security-scoped
 * 授权租约内 (见 `IosSecurityScopedStorage`); 沙盒内路径与鸿蒙端无需授权, 直接执行。
 *
 * 租约按调用粒度起停, 不跨调用持有。
 */
internal expect fun <T> withLocalDirAccess(path: String, block: () -> T): T
