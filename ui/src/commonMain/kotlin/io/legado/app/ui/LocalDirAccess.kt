package io.legado.app.ui

import io.legado.app.constant.DirUsage

/**
 * 在本地目录/文件上执行 [block], 由平台实现决定是否需要先取得外部目录访问授权。
 *
 * iOS 与鸿蒙的用户授权目录都在应用容器之外: 两端的容器内路径 (应用私有目录) 不经目录授权直接执行,
 * 容器外路径必须按本次用途登记过才放行 (iOS 取 security-scoped 租约, 见 `IosSecurityScopedStorage`;
 * 鸿蒙激活持久化目录授权, 见 `OhosDirAuthorizations`), 未登记或授权失败抛 `SecurityException`。
 * 无需授权的端直接执行。
 *
 * [usage] 为本次访问的用途: 容器外路径只有同用途登记的目录才放行 —— 为字体选的目录不会因此
 * 放行书籍文件访问, 反之亦然 (见 [DirUsage])。无需授权的端 [usage] 不被使用。
 *
 * [block] 的入参是本次访问实际使用的路径: 受沙盒约束的端先把它规范化 (解析符号链接,
 * 见 `IosSecurityScopedStorage`/`OhosDirAuthorizations`) 再回传, iOS 还可能回传文件协调器给出的
 * 替代位置; 无需授权的端原样回传。块内的文件系统调用以该入参为准。
 *
 * 租约按调用粒度起停, 不跨调用持有。
 */
internal expect fun <T> withLocalDirAccess(
    path: String,
    usage: DirUsage = DirUsage.BookTree,
    block: (String) -> T,
): T
