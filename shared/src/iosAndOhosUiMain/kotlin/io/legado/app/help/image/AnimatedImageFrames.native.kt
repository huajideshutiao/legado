package io.legado.app.help.image

/**
 * [decodeAnimatedFrames] 的 iOS / 鸿蒙实现: 恒返回 null (消费点退化为静态首帧)。
 *
 * iOS 动图由系统 UIImage / Coil3 共享管线解码 (带磁盘缓存与防盗链 Interceptor,
 * 改走裸字节帧表会绕开缓存); 鸿蒙融合渲染不直接调 Skia Codec。两端都不接本帧表路径。
 */
internal actual fun decodeAnimatedFrames(bytes: ByteArray): AnimatedFrames? = null
