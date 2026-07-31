package io.legado.app.utils

/**
 * 读取宿主内置资产 (Android `assets/`) 字节。
 *
 * 供 [RemoteAssetsUtils.getBgPreviewBytes] 读内置背景预览图; 桌面/iOS/鸿蒙无此资产目录,
 * actual 返回 null (调用方按"未就绪"处理, 与原版读取失败分支一致)。
 */
internal expect fun readAppAssetBytes(path: String): ByteArray?
