package io.legado.app.ui.book.manga

import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ColorMatrix
import io.legado.app.ui.book.manga.config.MangaColorFilterConfig
import io.legado.app.ui.book.manga.config.isNoOp
import io.legado.app.ui.book.manga.config.toColorMatrix

/**
 * 标准 ITU-R BT.601 灰度矩阵 (对照 Coil3 GrayscaleTransformation / app 端 Coil3 灰度变换)。
 */
private val GRAYSCALE_MATRIX = floatArrayOf(
    0.299f, 0.587f, 0.114f, 0f, 0f,
    0.299f, 0.587f, 0.114f, 0f, 0f,
    0.299f, 0.587f, 0.114f, 0f, 0f,
    0f, 0f, 0f, 1f, 0f,
)

/**
 * 合并颜色滤镜 (colorFilterConfig + grayEnabled) 为单个 [ColorFilter]，Compose 渲染路径共用
 * (desktop/iOS/鸿蒙)，对照 app 端 view.colorFilter + loadPageImage 的 Coil3 灰度变换。
 *
 * - 仅 grayEnabled: 灰度矩阵 (对照 app 端 Coil3 GrayscaleTransformation)
 * - 仅 colorFilterConfig: 配置矩阵 (对照 app 端 ColorMatrixColorFilter)
 * - 两者均启用: 配置矩阵 × 灰度矩阵。app 端灰度在解码期变换像素, 调色在绘制期作用于
 *   已灰度的图, 故顺序是先灰度后调色
 * - 均不启用: null
 */
fun mangaColorFilter(
    config: MangaColorFilterConfig,
    grayEnabled: Boolean,
): ColorFilter? {
    val cfgNoOp = config.isNoOp()
    if (cfgNoOp && !grayEnabled) return null
    if (cfgNoOp && grayEnabled) return ColorFilter.colorMatrix(ColorMatrix(GRAYSCALE_MATRIX))
    if (!grayEnabled) return ColorFilter.colorMatrix(ColorMatrix(config.toColorMatrix()))
    // 两者均启用: 先灰度后调色 = 配置矩阵 × 灰度矩阵
    return ColorFilter.colorMatrix(
        ColorMatrix(
            mergeMatrices(
                config.toColorMatrix(),
                GRAYSCALE_MATRIX
            )
        )
    )
}

/** 4x5 矩阵合成 (result = a × b, 即先 b 后 a), 第 5 列为平移项 */
private fun mergeMatrices(a: FloatArray, b: FloatArray): FloatArray {
    val out = FloatArray(20)
    for (row in 0..3) {
        for (col in 0..3) {
            var sum = 0f
            for (k in 0..3) {
                sum += a[row * 5 + k] * b[k * 5 + col]
            }
            out[row * 5 + col] = sum
        }
        // 平移列: a 的线性部分作用于 b 的平移量, 再叠加 a 自身平移
        var offset = a[row * 5 + 4]
        for (k in 0..3) {
            offset += a[row * 5 + k] * b[k * 5 + 4]
        }
        out[row * 5 + 4] = offset
    }
    return out
}
