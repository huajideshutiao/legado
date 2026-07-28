package com.script.quickjs

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.util.Base64
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream

/**
 * `image` 别名端到端测试（KJ1-b）。
 *
 * 验证 quickjs 桥对 app 侧 `bindings["image"] = BitmapImageOps` 注入模式的完整链路:
 * - Java 对象注入 + 方法重载分派 (decode(ByteArray) vs decode(String))
 * - ImageRef 句柄透传 (decode 返回值再传回 split/encode)
 * - List 双向透传 (split 返回 List<ImageRef>; stitch 接收 JS Array 与 Java List)
 * - Map 返回值属性访问 (size().w / .h)
 * - ByteArray 返回值解包 (encode)
 * - platform 变量注入 (String)
 *
 * [TestImageOps] 镜像 app/help/image/BitmapImageOps 的函数面
 * (模块不能依赖 app, 测试对象为桥接层而非 Bitmap 实现本身)。
 */
@RunWith(AndroidJUnit4::class)
class ImageOpsAliasTest {

    /** 镜像 io.legado.app.help.image.ImageOps 的函数面, ImageRef 用内部句柄类。 */
    @Suppress("unused")
    class TestImageOps {

        class Ref(val bitmap: Bitmap)

        fun decode(bytes: ByteArray): Ref {
            val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                ?: throw IllegalArgumentException("decode failed (${bytes.size} bytes)")
            return Ref(bitmap)
        }

        fun decode(base64: String): Ref {
            val payload = base64.substringAfter("base64,", base64)
            return decode(Base64.decode(payload, Base64.DEFAULT))
        }

        /** 解码输入流（封面解密路径下 `result` 为流）。JVM 专属重载，对齐 BitmapImageOps.decode(InputStream)。 */
        fun decode(input: InputStream): Ref {
            return decode(input.readBytes())
        }

        fun encode(img: Ref, format: String, quality: Int): ByteArray {
            val compressFormat = when (format.lowercase()) {
                "png" -> Bitmap.CompressFormat.PNG
                "jpg", "jpeg" -> Bitmap.CompressFormat.JPEG
                else -> throw IllegalArgumentException("format $format")
            }
            val out = ByteArrayOutputStream()
            img.bitmap.compress(compressFormat, quality.coerceIn(0, 100), out)
            return out.toByteArray()
        }

        fun split(img: Ref, rows: Int, cols: Int): List<Ref> {
            val bitmap = img.bitmap
            val cellW = bitmap.width / cols
            val cellH = bitmap.height / rows
            val out = ArrayList<Ref>(rows * cols)
            for (r in 0 until rows) {
                for (c in 0 until cols) {
                    val w = if (c == cols - 1) bitmap.width - cellW * c else cellW
                    val h = if (r == rows - 1) bitmap.height - cellH * r else cellH
                    out.add(Ref(Bitmap.createBitmap(bitmap, cellW * c, cellH * r, w, h)))
                }
            }
            return out
        }

        fun stitch(imgs: List<Ref>, direction: String): Ref {
            val bitmaps = (imgs as List<*>).map {
                (it as? Ref)?.bitmap
                    ?: throw IllegalArgumentException("not a Ref: ${it?.javaClass?.name}")
            }
            val horizontal = direction.lowercase() == "h"
            val width = if (horizontal) bitmaps.sumOf { it.width } else bitmaps.maxOf { it.width }
            val height =
                if (horizontal) bitmaps.maxOf { it.height } else bitmaps.sumOf { it.height }
            val result = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(result)
            var offset = 0f
            for (b in bitmaps) {
                if (horizontal) {
                    canvas.drawBitmap(b, offset, 0f, null)
                    offset += b.width
                } else {
                    canvas.drawBitmap(b, 0f, offset, null)
                    offset += b.height
                }
            }
            return Ref(result)
        }

        fun crop(img: Ref, x: Int, y: Int, w: Int, h: Int): Ref {
            return Ref(Bitmap.createBitmap(img.bitmap, x, y, w, h))
        }

        fun size(img: Ref): Map<String, Int> {
            return mapOf("w" to img.bitmap.width, "h" to img.bitmap.height)
        }
    }

    /**
     * 40x40 四象限色块图的 PNG 字节 (行优先: 左上红/右上绿/左下蓝/右下黄)。
     * PNG 无损, 解码后像素可精确断言。
     */
    private fun quadrantPngBytes(): ByteArray {
        val bitmap = Bitmap.createBitmap(40, 40, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val paint = Paint()
        paint.color = Color.RED
        canvas.drawRect(0f, 0f, 20f, 20f, paint)
        paint.color = Color.GREEN
        canvas.drawRect(20f, 0f, 40f, 20f, paint)
        paint.color = Color.BLUE
        canvas.drawRect(0f, 20f, 20f, 40f, paint)
        paint.color = Color.YELLOW
        canvas.drawRect(20f, 20f, 40f, 40f, paint)
        val out = ByteArrayOutputStream()
        bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
        return out.toByteArray()
    }

    /**
     * 模拟 app 侧 JsBindings 注入 (JsEngine.kt JsBindings.init):
     * platform 纯 String + image 单例。
     * dangerousApi=true: 测试类在 com.script 包下被安全名单拦截, 真实
     * BitmapImageOps 在 io.legado.app.help.image 下不受此限制 (对齐 AnalyzeRulePathTest)。
     */
    private fun evalWithImageAlias(js: String, result: Any? = null): Any? {
        val bindings = buildScriptBindings { b ->
            b["platform"] = "android"
            b["image"] = TestImageOps()
            b["result"] = result
            b.dangerousApi = true
        }
        return QuickJsEngine.eval(js, bindings)
    }

    /** 核心链路: decode → size → split(2,2) → JS Array 重排 → stitch('h') → encode('png')。 */
    @Test
    fun testSplitStitchEncodeRoundTrip() {
        val encoded = evalWithImageAlias(
            """
            var img = image.decode(result);
            var s = image.size(img);
            if (s.w != 40 || s.h != 40) throw 'size mismatch: ' + s.w + 'x' + s.h;
            var parts = image.split(img, 2, 2);
            if (parts.length != 4) throw 'split count: ' + parts.length;
            var order = [3, 2, 1, 0];
            var reordered = [];
            for (var i = 0; i < order.length; i++) {
                reordered.push(parts.get(order[i]));
            }
            image.encode(image.stitch(reordered, 'h'), 'png', 100);
            """.trimIndent(),
            result = quadrantPngBytes()
        )
        assertTrue("encode 应返回 ByteArray, 实际 ${encoded?.javaClass}", encoded is ByteArray)
        encoded as ByteArray
        assertTrue("encode 字节应非空", encoded.isNotEmpty())
        val stitched = BitmapFactory.decodeByteArray(encoded, 0, encoded.size)
        assertEquals("拼接后宽度", 80, stitched.width)
        assertEquals("拼接后高度", 20, stitched.height)
        // 重排 [3,2,1,0] → 黄/蓝/绿/红
        assertEquals(Color.YELLOW, stitched.getPixel(10, 10))
        assertEquals(Color.BLUE, stitched.getPixel(30, 10))
        assertEquals(Color.GREEN, stitched.getPixel(50, 10))
        assertEquals(Color.RED, stitched.getPixel(70, 10))
    }

    /** stitch 直接吃 split 返回的 Java List (不经 JS Array), 纵切纵拼应还原原尺寸。 */
    @Test
    fun testStitchAcceptsJavaListFromSplit() {
        val encoded = evalWithImageAlias(
            """
            var img = image.decode(result);
            var out = image.stitch(image.split(img, 2, 1), 'v');
            var s = image.size(out);
            if (s.w != 40 || s.h != 40) throw 'restored size: ' + s.w + 'x' + s.h;
            image.encode(out, 'png', 100);
            """.trimIndent(),
            result = quadrantPngBytes()
        )
        assertTrue(encoded is ByteArray)
        val restored = BitmapFactory.decodeByteArray(
            encoded as ByteArray, 0, encoded.size
        )
        assertEquals(40, restored.width)
        assertEquals(40, restored.height)
        assertEquals(Color.RED, restored.getPixel(10, 10))
        assertEquals(Color.YELLOW, restored.getPixel(30, 30))
    }

    /** decode 重载分派: base64 String 入参与 ByteArray 入参等价。 */
    @Test
    fun testDecodeBase64StringOverload() {
        val b64 = Base64.encodeToString(quadrantPngBytes(), Base64.NO_WRAP)
        val result = evalWithImageAlias(
            """
            var img = image.decode(result);
            var s = image.size(img);
            s.w + 'x' + s.h;
            """.trimIndent(),
            result = b64
        )
        assertEquals("40x40", result.toString())
    }

    /** decode 重载分派: InputStream 入参与 ByteArray 入参等价 (封面解密路径下 `result` 为流)。 */
    @Test
    fun testDecodeInputStreamOverload() {
        val pngBytes = quadrantPngBytes()
        val streamResult = evalWithImageAlias(
            """
            var img = image.decode(result);
            var s = image.size(img);
            s.w + 'x' + s.h;
            """.trimIndent(),
            result = ByteArrayInputStream(pngBytes)
        )
        val bytesResult = evalWithImageAlias(
            """
            var img = image.decode(result);
            var s = image.size(img);
            s.w + 'x' + s.h;
            """.trimIndent(),
            result = pngBytes
        )
        assertEquals("40x40", streamResult.toString())
        assertEquals(
            "InputStream 路径应与 ByteArray 路径等价",
            bytesResult.toString(), streamResult.toString()
        )
    }

    /** crop 区域像素正确 (裁右上象限应为纯绿)。 */
    @Test
    fun testCropQuadrant() {
        val encoded = evalWithImageAlias(
            """
            var img = image.decode(result);
            image.encode(image.crop(img, 20, 0, 20, 20), 'png', 100);
            """.trimIndent(),
            result = quadrantPngBytes()
        )
        val cropped = BitmapFactory.decodeByteArray(
            encoded as ByteArray, 0, encoded.size
        )
        assertEquals(20, cropped.width)
        assertEquals(20, cropped.height)
        assertEquals(Color.GREEN, cropped.getPixel(10, 10))
    }

    /** platform 注入为纯 String, 值 android (KJ1-a)。 */
    @Test
    fun testPlatformBindingIsAndroidString() {
        val result = evalWithImageAlias("typeof platform + ':' + platform;")
        assertEquals("string:android", result.toString())
    }
}
