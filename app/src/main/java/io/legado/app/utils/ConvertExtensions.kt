@file:Suppress("unused")
@file:JvmName("ConvertExtensionsAndroid")

package io.legado.app.utils

import android.content.res.Resources
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import java.io.BufferedReader
import java.io.InputStream
import java.io.InputStreamReader

/**
 * ConvertExtensions Android 部分 (Bitmap/Drawable/Resources/dpPx/spPx 等 Android 依赖).
 *
 * 纯 Kotlin 部分 (toInt/toFloat/toString/formatFileSize/Int.hexString) 已下沉至
 * modules/shared/src/commonMain/kotlin/io/legado/app/utils/ConvertExtensions.kt.
 *
 * 本文件原 object ConvertUtils 成员中 Android 依赖部分改为 [ConvertUtils] 扩展函数,
 * 调用方式 (`ConvertUtils.toBitmap(...)` 等) 与原一致, 兼容现有调用方。
 */

/**
 * Bitmap 解码扩展: 保留 app 端原签名, 内部委托 BitmapFactory。
 */
@JvmOverloads
fun ConvertUtils.toBitmap(bytes: ByteArray, width: Int = -1, height: Int = -1): Bitmap? {
    var bitmap: Bitmap? = null
    if (bytes.isNotEmpty()) {
        kotlin.runCatching {
            val options = BitmapFactory.Options()
            // 设置让解码器以最佳方式解码
            options.inPreferredConfig = null
            if (width > 0 && height > 0) {
                options.outWidth = width
                options.outHeight = height
            }
            bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)
            bitmap!!.density = 96// 96 dpi
        }
    }
    return bitmap
}

private fun toDrawable(bitmap: Bitmap?): Drawable? {
    return if (bitmap == null) null else BitmapDrawable(Resources.getSystem(), bitmap)
}

fun ConvertUtils.toDrawable(bytes: ByteArray): Drawable? {
    return toDrawable(toBitmap(bytes))
}

@JvmOverloads
fun ConvertUtils.toString(`is`: InputStream, charset: String = "utf-8"): String {
    val sb = StringBuilder()
    kotlin.runCatching {
        val reader = BufferedReader(InputStreamReader(`is`, charset))
        while (true) {
            val line = reader.readLine()
            if (line == null) {
                break
            } else {
                sb.append(line).append("\n")
            }
        }
        reader.close()
        `is`.close()
    }
    return sb.toString()
}

fun Int.dpToPx(): Int = this.toFloat().dpToPx().toInt()

fun Int.spToPx(): Int = this.toFloat().spToPx().toInt()

fun Float.dpToPx(): Float = android.util.TypedValue.applyDimension(
    android.util.TypedValue.COMPLEX_UNIT_DIP, this, Resources.getSystem().displayMetrics
)

fun Float.spToPx(): Float = android.util.TypedValue.applyDimension(
    android.util.TypedValue.COMPLEX_UNIT_SP, this, Resources.getSystem().displayMetrics
)
