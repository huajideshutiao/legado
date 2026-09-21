package io.legado.app.model

/**
 * 桌面端按原图尺寸模糊 (0 = 不降采样): CPU/内存充裕, 且窗口尺寸随时在变 —— 产物分辨率不足
 * 会在大窗口/高 DPI 下被 Crop 放大成糊图。共用实现见 `WallpaperBaker.skiko.kt`。
 */
internal actual val blurWorkMaxShortSide: Int = 0
