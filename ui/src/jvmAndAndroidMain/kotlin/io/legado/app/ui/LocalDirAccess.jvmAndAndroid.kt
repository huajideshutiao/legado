package io.legado.app.ui

import io.legado.app.constant.DirUsage

/**
 * 安卓/桌面实现: 选定目录无需二次授权, 直接执行。
 *
 * 安卓的 SAF 授权按树 URI 授予 (选定即 takePersistableUriPermission, content:// 访问走
 * DocumentFile / contentResolver), 桌面为普通文件路径; 两端读取选定目录本身都不按用途二次授权,
 * 安卓侧另按用途登记选定目录供本地书文件访问校验。
 */
internal actual fun <T> withLocalDirAccess(path: String, usage: DirUsage, block: () -> T): T =
    block()
