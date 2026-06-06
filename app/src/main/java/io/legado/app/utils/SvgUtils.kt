package io.legado.app.utils

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.RectF
import android.util.Size
import com.caverock.androidsvg.SVG
import java.io.ByteArrayInputStream
import java.io.FileInputStream
import java.io.InputStream
import kotlin.math.max

@Suppress("WeakerAccess", "MemberVisibilityCanBePrivate")
object SvgUtils {

    /**
     * 从Svg中解码bitmap
     */
    
    fun createBitmap(filePath: String, width: Int, height: Int? = null): Bitmap? {
        return kotlin.runCatching {
            val inputStream = FileInputStream(filePath)
            createBitmap(inputStream, width, height)
        }.getOrNull()
    }

    fun createBitmap(inputStream: InputStream, width: Int, height: Int? = null): Bitmap? {
        return kotlin.runCatching {
            val svg = SVG.getFromInputStream(inputStream)
            createBitmap(svg, width, height)
        }.getOrNull()
    }

    /**
     * 在 width × height 矩形内等比拟合渲染 SVG，会按需放大或缩小
     * 与 createBitmap 不同：createBitmap 只缩小，文档较小时按原尺寸渲染
     */
    fun renderInto(bytes: ByteArray, width: Int, height: Int): Bitmap? {
        return kotlin.runCatching {
            renderInto(SVG.getFromInputStream(ByteArrayInputStream(bytes)), width, height)
        }.getOrNull()
    }

    fun renderInto(filePath: String, width: Int, height: Int): Bitmap? {
        return kotlin.runCatching {
            FileInputStream(filePath).use {
                renderInto(SVG.getFromInputStream(it), width, height)
            }
        }.getOrNull()
    }

    private fun renderInto(svg: SVG, width: Int, height: Int): Bitmap {
        val size = getSize(svg)
        val srcW = size.width.coerceAtLeast(1).toFloat()
        val srcH = size.height.coerceAtLeast(1).toFloat()
        val ratio = minOf(width / srcW, height / srcH)
        val bitmapWidth = (srcW * ratio).toInt().coerceAtLeast(1)
        val bitmapHeight = (srcH * ratio).toInt().coerceAtLeast(1)

        if (svg.documentViewBox == null && size.width > 0 && size.height > 0) {
            svg.setDocumentViewBox(0f, 0f, svg.documentWidth, svg.documentHeight)
        }
        svg.setDocumentWidth("100%")
        svg.setDocumentHeight("100%")

        val bitmap = Bitmap.createBitmap(bitmapWidth, bitmapHeight, Bitmap.Config.ARGB_8888)
        svg.renderToCanvas(Canvas(bitmap))
        return bitmap
    }

    //获取svg图片大小
    fun getSize(filePath: String): Size? {
        return kotlin.runCatching {
            val inputStream = FileInputStream(filePath)
            getSize(inputStream)
        }.getOrNull()
    }

    fun getSize(inputStream: InputStream): Size? {
        return kotlin.runCatching {
            val svg = SVG.getFromInputStream(inputStream)
            getSize(svg)
        }.getOrNull()
    }

    /////// private method
    private fun createBitmap(svg: SVG, width: Int? = null, height: Int? = null): Bitmap {
        val size = getSize(svg)
        val wRatio = width?.let { size.width / it } ?: -1
        val hRatio = height?.let { size.height / it } ?: -1
        //如果超出指定大小，则缩小相应的比例
        val ratio = when {
            wRatio > 1 && hRatio > 1 -> max(wRatio, hRatio)
            wRatio > 1 -> wRatio
            hRatio > 1 -> hRatio
            else -> 1
        }

        val viewBox: RectF? = svg.documentViewBox
        if (viewBox == null && size.width > 0 && size.height > 0) {
            svg.setDocumentViewBox(0f, 0f, svg.documentWidth, svg.documentHeight)
        }

        svg.setDocumentWidth("100%")
        svg.setDocumentHeight("100%")

        val bitmapWidth = size.width / ratio
        val bitmapHeight = size.height / ratio
        val bitmap = Bitmap.createBitmap(bitmapWidth, bitmapHeight, Bitmap.Config.ARGB_8888)

        svg.renderToCanvas(Canvas(bitmap))
        return bitmap
    }

    private fun getSize(svg: SVG): Size {
        val width = svg.documentWidth.toInt().takeIf { it > 0 }
            ?: (svg.documentViewBox.right - svg.documentViewBox.left).toInt()
        val height = svg.documentHeight.toInt().takeIf { it > 0 }
            ?: (svg.documentViewBox.bottom - svg.documentViewBox.top).toInt()
        return Size(width, height)      
    }

}
