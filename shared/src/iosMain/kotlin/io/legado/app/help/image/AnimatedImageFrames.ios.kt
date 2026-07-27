package io.legado.app.help.image

/**
 * [decodeAnimatedFrames] 的 iOS 实现: 恒返回 null。
 *
 * iOS 端动图由系统 UIImage / Coil3 管线解码, 消费点已具备动图能力, 无需本帧表路径。
 * (技术上 iOS 也坐在 skiko 上可复用 skikoUiMain 实现, 但 iOS 走 Coil3 共享管线
 * 有磁盘缓存与防盗链 Interceptor, 改走裸字节帧表会绕开缓存, 故不接入。)
 */
internal actual fun decodeAnimatedFrames(bytes: ByteArray): AnimatedFrames? = null
