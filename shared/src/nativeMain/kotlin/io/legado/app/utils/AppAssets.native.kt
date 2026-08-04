package io.legado.app.utils

/**
 * iOS/鸿蒙未接入本通道 (RemoteAssetsUtils 仅在 jvmAndAndroidMain, native 无调用方);
 * 内置背景缩略图由 sharedUiMain 消费方直接走 composeResources Res.readBytes, 无需平台实现。
 */
internal actual fun readSharedResourceBytes(path: String): ByteArray? = null
