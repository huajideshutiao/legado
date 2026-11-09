package io.legado.app.help.image

import androidx.compose.ui.graphics.ImageBitmap

/**
 * 位图的真实底层资源身份。
 *
 * 同一个解码结果被多次包装成新的 [ImageBitmap] 时, 本函数返回的底层对象不变 (平台接口
 * 直接取底层对象, 不做像素拷贝)。CPU 侧派生结果 (如背景预模糊) 以它作为缓存身份:
 * 同一个底层图换了包装就不重算, 底层图真的变了才重算。
 *
 * 各端实际实现见同包 `ImageBitmapIdentity.*.kt` (Android 是 `Bitmap`, 其余三端是 Skia Bitmap)。
 */
internal expect fun ImageBitmap.resourceIdentity(): Any
