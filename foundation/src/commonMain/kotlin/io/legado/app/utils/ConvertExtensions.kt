@file:Suppress("unused")

package io.legado.app.utils

import kotlin.math.log10
import kotlin.math.pow

/**
 * 数据类型转换、单位转换 (纯 Kotlin 部分下沉 commonMain 供多端复用).
 *
 * 原 app 端实现迁移说明:
 * - [toInt][obj][Any] 原 `Integer.parseInt(obj.toString())` + runCatching.getOrDefault(-1),
 *   commonMain 改用 [String.toIntOrNull], 行为等价 (解析失败均返回 -1).
 * - [toFloat][Any] 原 `java.lang.Float.parseFloat(...)` + runCatching.getOrDefault(-1f),
 *   commonMain 改用 [String.toFloatOrNull], 行为等价 (解析失败均返回 -1f).
 * - [Int.hexString] 原 `Integer.toHexString(this)`, commonMain 改用 [UInt.toString] (16),
 *   行为等价 (同为无符号十六进制).
 * - [ConvertUtils.formatFileSize] 原 `java.text.DecimalFormat("#,##0.##")`,
 *   commonMain 经 [formatFileSizeDecimal] expect 委托 jvmAndAndroidMain actual,
 *   DecimalFormat 行为不变 (千位分隔符 + 最多 2 位小数).
 *
 * Android 依赖部分 (toBitmap/toDrawable/toString(InputStream)/dpToPx/spToPx) 留 app 端原文件,
 * 作为 [ConvertUtils] 扩展函数保留, 调用方式不变.
 *
 * @author 李玉江[QQ:1023694760]
 * @since 2014-4-18
 */
@Suppress("MemberVisibilityCanBePrivate")
object ConvertUtils {
    const val GB: Long = 1073741824
    const val MB: Long = 1048576
    const val KB: Long = 1024

    fun toInt(obj: Any): Int {
        // 等价原 Integer.parseInt(obj.toString()) + runCatching.getOrDefault(-1)
        return obj.toString().toIntOrNull() ?: -1
    }

    fun toInt(bytes: ByteArray): Int {
        var result = 0
        var byte: Byte
        for (i in bytes.indices) {
            byte = bytes[i]
            result += (byte.toInt() and 0xFF).shl(8 * i)
        }
        return result
    }

    fun toFloat(obj: Any): Float {
        // 等价原 java.lang.Float.parseFloat(obj.toString()) + runCatching.getOrDefault(-1f)
        return obj.toString().toFloatOrNull() ?: -1f
    }

    fun toString(objects: Array<Any>, tag: String): String {
        val sb = StringBuilder()
        for (`object` in objects) {
            sb.append(`object`)
            sb.append(tag)
        }
        return sb.toString()
    }

    fun formatFileSize(length: Long): String {
        if (length <= 0) return "0"
        val units = arrayOf("b", "kb", "M", "G", "T")
        //计算单位的，原理是利用lg,公式是 lg(1024^n) = nlg(1024)，最后 nlg(1024)/lg(1024) = n。
        val digitGroups = (log10(length.toDouble()) / log10(1024.0)).toInt()
        //计算原理是，size/单位值。单位值指的是:比如说b = 1024,KB = 1024^2
        return formatFileSizeDecimal(length / 1024.0.pow(digitGroups.toDouble())) + " " + units[digitGroups]
    }
}

val Int.hexString: String
    // 等价原 Integer.toHexString(this); UInt.toString(16) 同为无符号十六进制
    get() = this.toUInt().toString(16)

/**
 * 文件大小数值格式化 expect 门面 (千位分隔符 + 最多 2 位小数).
 *
 * actual 在 jvmAndAndroidMain 委托 `java.text.DecimalFormat("#,##0.##")`, 行为不变.
 * commonMain 不能直接引用 java.text.DecimalFormat, 故经 expect/actual 桥接.
 */
internal expect fun formatFileSizeDecimal(value: Double): String
