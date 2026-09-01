package io.legado.app.utils

import kotlin.math.abs
import kotlin.math.cbrt
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * 纯 Kotlin 颜色工具，不依赖 android.graphics.Color / androidx.core.graphics.ColorUtils。
 *
 * 下沉 commonMain 后, app 端通过 shared 依赖直接 import 本类。
 * HSV↔RGB / sRGB↔XYZ↔Lab / parseColor 均用标准算法实现, 行为对齐 Android 原生 ColorUtils。
 */
object ColorUtils {

    /**
     * WCAG 2.x 相对亮度: L = 0.2126*R + 0.7152*G + 0.0722*B (R/G/B 为 gamma 解码后的线性值)。
     * 对齐 androidx.core.graphics.ColorUtils.calculateLuminance。
     */
    fun calculateLuminance(color: Int): Double {
        val r = channelLinear(red(color))
        val g = channelLinear(green(color))
        val b = channelLinear(blue(color))
        return 0.2126 * r + 0.7152 * g + 0.0722 * b
    }

    fun isColorLight(color: Int): Boolean {
        return calculateLuminance(color) >= 0.5
    }

    /**
     * 输出 #RRGGBB 6 位大写 (对齐原版 `String.format("#%06X", 0xFFFFFF and intColor)`;
     * String.format 是 JVM-only API, commonMain 用纯 Kotlin 等价实现, 输出完全一致)。
     * alpha 被屏蔽, 半透明色只显示 RGB 部分 (原版行为)。
     */
    fun intToString(intColor: Int): String {
        // 0xFFFFFF and 保证 6 位且无负数符号 (原 String.format %X 对负数补 F 的语义)
        val hex = (0xFFFFFF and intColor).toString(16).uppercase().padStart(6, '0')
        return "#$hex"
    }

    fun stripAlpha(color: Int): Int {
        return -0x1000000 or color
    }

    fun shiftColor(color: Int, by: Float): Int {
        if (by == 1f) return color
        val alpha = alpha(color)
        val hsv = FloatArray(3)
        colorToHSV(color, hsv)
        hsv[2] = (hsv[2] * by).coerceIn(0f, 1f) // value component
        return (alpha shl 24) + (0x00ffffff and HSVToColor(hsv))
    }

    fun darkenColor(color: Int): Int {
        return shiftColor(color, 0.9f)
    }

    fun lightenColor(color: Int): Int {
        return shiftColor(color, 1.1f)
    }

    fun invertColor(color: Int): Int {
        val r = 255 - red(color)
        val g = 255 - green(color)
        val b = 255 - blue(color)
        return argb(alpha(color), r, g, b)
    }

    fun adjustAlpha(color: Int, factor: Float): Int {
        val alpha = (alpha(color) * factor).roundToInt()
        val red = red(color)
        val green = green(color)
        val blue = blue(color)
        return argb(alpha, red, green, blue)
    }

    fun withAlpha(baseColor: Int, alpha: Float): Int {
        val a = min(255, max(0, (alpha * 255).toInt())) shl 24
        val rgb = 0x00ffffff and baseColor
        return a + rgb
    }

    /**
     * Taken from CollapsingToolbarLayout's CollapsingTextHelper class.
     */
    fun blendColors(color1: Int, color2: Int, ratio: Float): Int {
        val inverseRatio = 1f - ratio
        val a = alpha(color1) * inverseRatio + alpha(color2) * ratio
        val r = red(color1) * inverseRatio + red(color2) * ratio
        val g = green(color1) * inverseRatio + green(color2) * ratio
        val b = blue(color1) * inverseRatio + blue(color2) * ratio
        return argb(a.toInt(), r.toInt(), g.toInt(), b.toInt())
    }

    fun argb(r: Int, g: Int, b: Int): Int {
        return argb(Byte.MAX_VALUE.toInt(), r, g, b)
    }

    fun argb(alpha: Int, r: Int, g: Int, b: Int): Int {
        val a = alpha.coerceIn(0, 255)
        val red = r.coerceIn(0, 255)
        val green = g.coerceIn(0, 255)
        val blue = b.coerceIn(0, 255)
        return (a shl 24) or (red shl 16) or (green shl 8) or blue
    }

    fun rgb(argb: Int): IntArray {
        return intArrayOf(argb shr 16 and 0xFF, argb shr 8 and 0xFF, argb and 0xFF)
    }

    fun byteArrToInt(colorByteArr: ByteArray): Int {
        return ((colorByteArr[0].toInt() shl 24) + (colorByteArr[1].toInt() and 0xFF shl 16)
                + (colorByteArr[2].toInt() and 0xFF shl 8) + (colorByteArr[3].toInt() and 0xFF))
    }

    /**
     * Computes the difference between two RGB colors by converting them to the L*a*b scale and
     * comparing them using the CIE76 algorithm { http://en.wikipedia.org/wiki/Color_difference#CIE76}
     */
    fun getColorDifference(a: Int, b: Int): Double {
        val lab1 = DoubleArray(3)
        val lab2 = DoubleArray(3)
        colorToLAB(a, lab1)
        colorToLAB(b, lab2)
        return sqrt(
            (lab2[0] - lab1[0])
                .pow(2.0) + (lab2[1] - lab1[1])
                .pow(2.0) + (lab2[2] - lab1[2])
                .pow(2.0)
        )
    }

    // ---- 纯 Kotlin 通道拆分 (替代 android.graphics.Color.alpha/red/green/blue) ----

    fun alpha(color: Int): Int = color ushr 24

    fun red(color: Int): Int = color shr 16 and 0xFF

    fun green(color: Int): Int = color shr 8 and 0xFF

    fun blue(color: Int): Int = color and 0xFF

    // ---- 纯 Kotlin HSV↔RGB (替代 android.graphics.Color.colorToHSV / HSVToColor) ----

    /**
     * RGB → HSV, 对齐 android.graphics.Color.colorToHSV。
     * hsv[0]=Hue [0,360), hsv[1]=Saturation [0,1], hsv[2]=Value [0,1]。
     */
    fun colorToHSV(color: Int, hsv: FloatArray) {
        val r = red(color) / 255f
        val g = green(color) / 255f
        val b = blue(color) / 255f
        val max = max(r, max(g, b))
        val min = min(r, min(g, b))
        val delta = max - min
        val v = max
        val s = if (max == 0f) 0f else delta / max
        var h = 0f
        if (delta != 0f) {
            when (max) {
                r -> h = ((g - b) / delta) % 6f
                g -> h = (b - r) / delta + 2f
                b -> h = (r - g) / delta + 4f
            }
            h *= 60f
            if (h < 0f) h += 360f
        }
        hsv[0] = h
        hsv[1] = s
        hsv[2] = v
    }

    /**
     * HSV → RGB (alpha=255), 对齐 android.graphics.Color.HSVToColor(hsv)。
     */
    fun HSVToColor(hsv: FloatArray): Int {
        val h = hsv[0]
        val s = hsv[1].coerceIn(0f, 1f)
        val v = hsv[2].coerceIn(0f, 1f)
        val c = v * s
        val hp = h / 60f
        val x = c * (1 - abs(hp % 2f - 1f))
        val m = v - c
        val (r1, g1, b1) = when {
            hp < 1f -> Triple(c, x, 0f)
            hp < 2f -> Triple(x, c, 0f)
            hp < 3f -> Triple(0f, c, x)
            hp < 4f -> Triple(0f, x, c)
            hp < 5f -> Triple(x, 0f, c)
            else -> Triple(c, 0f, x)
        }
        val r = ((r1 + m) * 255).roundToInt().coerceIn(0, 255)
        val g = ((g1 + m) * 255).roundToInt().coerceIn(0, 255)
        val b = ((b1 + m) * 255).roundToInt().coerceIn(0, 255)
        return argb(255, r, g, b)
    }

    /**
     * 解析颜色字符串, 支持 #RRGGBB / #AARRGGBB / #RGB / #ARGB (对齐 android.graphics.Color.parseColor)。
     */
    fun parseColor(colorString: String): Int {
        if (colorString.isEmpty()) throw IllegalArgumentException("Empty color string")
        if (colorString[0] != '#') throw IllegalArgumentException("Unknown color format: $colorString")
        val hex = colorString.substring(1)
        return when (hex.length) {
            3 -> { // #RGB
                val r = hex[0].digitToInt(16) * 17
                val g = hex[1].digitToInt(16) * 17
                val b = hex[2].digitToInt(16) * 17
                argb(255, r, g, b)
            }
            4 -> { // #ARGB
                val a = hex[0].digitToInt(16) * 17
                val r = hex[1].digitToInt(16) * 17
                val g = hex[2].digitToInt(16) * 17
                val b = hex[3].digitToInt(16) * 17
                argb(a, r, g, b)
            }
            6 -> { // #RRGGBB
                val r = hex.substring(0, 2).toInt(16)
                val g = hex.substring(2, 4).toInt(16)
                val b = hex.substring(4, 6).toInt(16)
                argb(255, r, g, b)
            }
            8 -> { // #AARRGGBB
                val a = hex.substring(0, 2).toInt(16)
                val r = hex.substring(2, 4).toInt(16)
                val g = hex.substring(4, 6).toInt(16)
                val b = hex.substring(6, 8).toInt(16)
                argb(a, r, g, b)
            }
            else -> throw IllegalArgumentException("Unknown color format: $colorString")
        }
    }

    // ---- 纯 Kotlin sRGB↔XYZ↔Lab (替代 androidx.core.graphics.ColorUtils.colorToLAB/colorToXYZ) ----

    /**
     * sRGB → CIE-L*a*b* (D65 白点), 对齐 androidx.core.graphics.ColorUtils.colorToLAB。
     */
    private fun colorToLAB(color: Int, lab: DoubleArray) {
        val xyz = DoubleArray(3)
        colorToXYZ(color, xyz)
        // D65 白点参考: Xn=95.047, Yn=100.000, Zn=108.883
        val xn = 95.047
        val yn = 100.0
        val zn = 108.883
        val fx = labFunc(xyz[0] / xn)
        val fy = labFunc(xyz[1] / yn)
        val fz = labFunc(xyz[2] / zn)
        lab[0] = 116.0 * fy - 16.0
        lab[1] = 500.0 * (fx - fy)
        lab[2] = 200.0 * (fy - fz)
    }

    private fun labFunc(t: Double): Double {
        return if (t > 216.0 / 24389.0) cbrt(t) else (24389.0 / 27.0 * t + 16.0) / 116.0
    }

    /**
     * sRGB → XYZ (D65), 对齐 androidx.core.graphics.ColorUtils.colorToXYZ。
     */
    private fun colorToXYZ(color: Int, xyz: DoubleArray) {
        val r = channelLinear(red(color))
        val g = channelLinear(green(color))
        val b = channelLinear(blue(color))
        // sRGB → XYZ (D65) 转换矩阵
        xyz[0] = (0.4124 * r + 0.3576 * g + 0.1805 * b) * 100
        xyz[1] = (0.2126 * r + 0.7152 * g + 0.0722 * b) * 100
        xyz[2] = (0.0193 * r + 0.1192 * g + 0.9505 * b) * 100
    }

    /** sRGB 8-bit 通道 → [0,1] 线性值 (gamma 解码, sRGB IEC 61966-2-1)。 */
    private fun channelLinear(c8: Int): Double {
        val cs = c8 / 255.0
        return if (cs <= 0.03928) cs / 12.92 else ((cs + 0.055) / 1.055).pow(2.4)
    }
}
