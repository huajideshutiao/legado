package io.legado.app.model

/**
 * iOS/鸿蒙同 Android 压到短边 [BAKE_MAX_SHORT_SIDE]: 屏幕尺寸固定, 产物被拉伸的倍数是定值
 * 没有感知, 而按原图尺寸模糊的耗时与内存在移动端会卡。共用实现见 `WallpaperBaker.skiko.kt`。
 */
internal actual val blurWorkMaxShortSide: Int = BAKE_MAX_SHORT_SIDE
