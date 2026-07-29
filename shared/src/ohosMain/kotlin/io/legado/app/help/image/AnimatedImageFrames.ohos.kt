package io.legado.app.help.image

/**
 * 融合渲染下不直接调用 Skia Codec。鸿蒙图片仍由平台 ImageBridge/静态位图管线处理，
 * 动图能力在接入 CPF Coil OHOS 解码器前安全退化为静态首帧。
 */
internal actual fun decodeAnimatedFrames(bytes: ByteArray): AnimatedFrames? = null
