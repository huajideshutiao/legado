package io.legado.app.help.image

/**
 * [decodeAnimatedFrames] 的 Android 实现: 恒返回 null。
 *
 * Android 端动图由 coil3-gif 解码器承担 (AnimatedImageDecoder API28+ / GifDecoder),
 * 消费点走 Coil3 painter 即自带逐帧播放, 无需本帧表路径; 且 skiko 不在 Android
 * classpath 上 (Android Compose 映射 androidx.compose, 非 skiko 后端)。
 */
internal actual fun decodeAnimatedFrames(bytes: ByteArray): AnimatedFrames? = null

/**
 * [decodeImageAuto] 的 Android 实现: 回落 [decodeBytesSampled] 静态解码。
 *
 * Android 漫画页走 coil3-gif + MangaPageImageView, 不经本路径; 这里只为满足 expect。
 */
internal actual fun decodeImageAuto(bytes: ByteArray): DecodedImageResult? =
    decodeBytesSampled(bytes, 0)?.let { DecodedImageResult.Static(it) }
