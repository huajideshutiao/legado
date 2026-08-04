package io.legado.app.utils

/**
 * 读取 shared 模块 composeResources `files/` 目录下的内置资源字节。
 *
 * 单一数据源: `shared/src/commonMain/composeResources/files/` (bg_preview 缩略图等),
 * 各端按自己的打包方式读取 (Android 打进 assets / JVM 打进 classpath);
 * iOS/鸿蒙暂未接入本通道, actual 返回 null (无调用方, 消费方已直接走 Res.readBytes)。
 * 供 [RemoteAssetsUtils.getBgPreviewBytes] 读内置背景预览图。
 */
internal expect fun readSharedResourceBytes(path: String): ByteArray?
