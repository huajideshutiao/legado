package io.legado.app.ui

import io.legado.app.constant.DirUsage

/**
 * 在本地目录/文件上执行 [block], 由平台实现决定是否需要先取得外部目录访问授权。
 *
 * iOS 用户经"文件"面板授权的目录在应用容器之外, 文件系统调用必须包在 security-scoped
 * 授权租约内 (见 `IosSecurityScopedStorage`); 鸿蒙须先按需激活已持久化的目录授权
 * (见 `OhosDirAuthorizations`); 沙盒内路径与无需授权的端直接执行。
 *
 * [usage] 为本次访问的用途: 受沙盒约束的端只放行同用途登记的授权 —— 为字体选的目录不会因此
 * 放行书籍文件访问, 反之亦然 (见 [DirUsage])。无需授权的端 [usage] 不被使用。
 *
 * 租约按调用粒度起停, 不跨调用持有。
 */
internal expect fun <T> withLocalDirAccess(
    path: String,
    usage: DirUsage = DirUsage.BookTree,
    block: () -> T,
): T
