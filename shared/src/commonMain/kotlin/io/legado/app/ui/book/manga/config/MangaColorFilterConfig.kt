package io.legado.app.ui.book.manga.config

import io.legado.app.utils.GSON
import io.legado.app.utils.toJson

data class MangaColorFilterConfig(
    var r: Int = 0,
    var g: Int = 0,
    var b: Int = 0,
    var ct: Int = 0
) {
    fun toJson(): String {
        if (r == 0 && g == 0 && b == 0 && ct == 0) {
            return ""
        }
        return GSON.toJson(this)
    }
}

/**
 * 计算 4x5 ColorMatrix: RGB 反相分量 × 对比度 + 亮度补偿 (对照 app 端 MangaRenderScreen.toColorFilter)。
 * shared 端用 FloatArray 表示, 平台 actual 包装为各自 ColorMatrixColorFilter。
 */
fun MangaColorFilterConfig.toColorMatrix(): FloatArray {
    val rF = (255 - r) / 255f
    val gF = (255 - g) / 255f
    val bF = (255 - b) / 255f
    val contrast = 1f + ct / 50f
    val brightness = (1f - contrast) * 128f
    val m = FloatArray(20)
    m[0] = rF * contrast
    m[4] = brightness
    m[6] = gF * contrast
    m[9] = brightness
    m[12] = bF * contrast
    m[14] = brightness
    m[18] = 1f
    return m
}

/** 全 0 配置视为不启用滤镜 */
fun MangaColorFilterConfig.isNoOp(): Boolean = r == 0 && g == 0 && b == 0 && ct == 0
