// 本回归须覆盖应用现有的兼容媒体会话通道。
@file:Suppress("DEPRECATION")

package io.legado.app.model

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Rect
import android.media.MediaMetadata
import android.os.Build
import android.support.v4.media.MediaMetadataCompat
import android.support.v4.media.session.MediaSessionCompat
import androidx.annotation.RequiresApi
import androidx.core.graphics.drawable.toBitmap
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import coil3.BitmapImage
import coil3.SingletonImageLoader
import coil3.decode.DataSource
import coil3.request.ImageRequest
import coil3.request.SuccessResult
import coil3.request.allowHardware
import coil3.toBitmap
import io.legado.app.App
import io.legado.app.constant.PreferKey
import io.legado.app.help.config.AppConfig
import io.legado.app.utils.defaultSharedPreferences
import io.legado.app.utils.removePref
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import kotlin.math.abs

/**
 * 封面位图交给媒体系统的仪器化回归。
 *
 * 外部约束: 朗读与音频播放服务把封面经 MediaSessionCompat.setMetadata 交给系统; 系统开关
 * scale_bitmap_to_ashmem 启用且封面宽或高超过框架上限 (config_mediaMetadataBitmapMaxSize) 时,
 * 系统在软件 Canvas 上缩放封面 (MediaMetadata.Builder.scaleBitmap →
 * Bitmap.createScaledAshmemBitmap), 硬件位图不能被软件 Canvas 绘制。
 *
 * 断言全部走真实 BookCover.loadCoverBitmap + 真实 SingletonImageLoader + 原生 Bitmap/Canvas +
 * 真实 MediaSessionCompat 与控制器回读, 不替换图像引擎。
 */
@RunWith(AndroidJUnit4::class)
class BookCoverBitmapInstrumentedTest {

    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val context: Context get() = instrumentation.targetContext

    private val tempFiles = mutableListOf<File>()
    private var useDefaultCoverKeyExisted = false
    private var originalUseDefaultCover = false

    @Before
    fun setUp() {
        useDefaultCoverKeyExisted =
            App.instance.defaultSharedPreferences.contains(PreferKey.useDefaultCover)
        originalUseDefaultCover = AppConfig.useDefaultCover
        AppConfig.useDefaultCover = false
    }

    @After
    fun tearDown() {
        AppConfig.useDefaultCover = originalUseDefaultCover
        if (!useDefaultCoverKeyExisted) {
            App.instance.removePref(PreferKey.useDefaultCover)
        }
        tempFiles.forEach { it.delete() }
        tempFiles.clear()
        SingletonImageLoader.get(context).memoryCache?.clear()
    }

    /**
     * 多尺寸 × 多格式的冷缓存加载: 每个用例用新文件 (磁盘缓存无条目) 并清空内存缓存。
     * 同一会话连续更新标题、同一封面与另一颜色的真实封面, 每次都从控制器回读元数据。
     */
    @Test
    fun realCoverLoadsSoftwareBitmapForEverySizeAndFormat() = runBlocking {
        withContext(Dispatchers.Main) {
            val session = MediaSessionCompat(context, SESSION_TAG)
            try {
                for (format in listOf(
                    Bitmap.CompressFormat.PNG,
                    Bitmap.CompressFormat.JPEG,
                    webpFormat,
                )) {
                    for ((width, height) in listOf(320 to 480, 1080 to 1440, 2048 to 2048)) {
                        val file = withContext(Dispatchers.IO) {
                            writeSolidImage(width, height, SOURCE_COLOR, format)
                        }
                        SingletonImageLoader.get(context).memoryCache?.clear()

                        val bitmap = BookCover.loadCoverBitmap(context, file.absolutePath)
                        val label = "${format.name} ${width}x$height"
                        assertSoftwareBitmap(label, bitmap)
                        assertEquals("$label: 宽度", width, bitmap.width)
                        assertEquals("$label: 高度", height, bitmap.height)
                        assertPixels(label, bitmap, SOURCE_COLOR)

                        session.setMetadata(coverMetadata(label, bitmap))
                        assertSessionMetadata(label, session, label, SOURCE_COLOR, bitmap)
                    }
                }

                val coverA = loadSolidCover(360, 480, SOURCE_COLOR)
                val coverB = loadSolidCover(360, 480, ALT_COLOR)
                session.setMetadata(coverMetadata("连续更新 1", coverA))
                assertSessionMetadata("连续更新 1", session, "连续更新 1", SOURCE_COLOR, coverA)
                session.setMetadata(coverMetadata("连续更新 2", coverA))
                assertSessionMetadata("连续更新 2", session, "连续更新 2", SOURCE_COLOR, coverA)
                session.setMetadata(coverMetadata("连续更新 3", coverB))
                assertSessionMetadata("连续更新 3", session, "连续更新 3", ALT_COLOR, coverB)

                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    val limit = sessionBitmapDimensionLimit(session)
                    assertTrue("系统封面尺寸上限应为正数: $limit", limit > 0)

                    val oversizedSide = limit + 256
                    val oversized = loadSolidCover(oversizedSide, oversizedSide, SOURCE_COLOR)
                    assertSoftwareBitmap("超限封面", oversized)
                    assertEquals("超限封面: 宽度", oversizedSide, oversized.width)

                    session.setMetadata(coverMetadata("超限封面", oversized))
                    val art = session.controller.metadata
                        ?.getBitmap(MediaMetadataCompat.METADATA_KEY_ART)
                    assertNotNull("超限封面: 控制器未读到封面", art)
                    assertSoftwareBitmap("超限封面: 控制器封面", art!!)
                    assertTrue(
                        "回读封面 ${art.width}x${art.height} 应在系统上限 $limit 内",
                        art.width <= limit && art.height <= limit,
                    )
                    assertTrue(
                        "超限封面应被系统缩小 (源 ${oversized.width}x${oversized.height})",
                        art.width < oversized.width || art.height < oversized.height,
                    )
                    assertPixels("超限封面回读", art, SOURCE_COLOR)
                }
            } finally {
                session.release()
            }
        }
    }

    /**
     * 真实硬件位图占据内存缓存后再次加载: 缓存键取自预热结果, 预热项必须是 HARDWARE,
     * 封面请求后同一缓存键必须换成软件位图。
     */
    @Test
    fun hardwareMemoryCacheEntryIsNotHandedToCoverRequest() = runBlocking {
        assumeTrue("硬件位图需要 Android 8.0 及以上", Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
        withContext(Dispatchers.Main) {
            val session = MediaSessionCompat(context, SESSION_TAG)
            try {
                val file = withContext(Dispatchers.IO) {
                    writeSolidImage(1024, 1024, SOURCE_COLOR, Bitmap.CompressFormat.PNG)
                }
                val loader = SingletonImageLoader.get(context)
                assertNotNull("ImageLoader 未启用内存缓存", loader.memoryCache)
                loader.memoryCache?.clear()

                val warmUp = loader.execute(
                    ImageRequest.Builder(context).data(file.absolutePath).allowHardware(true).build()
                ) as? SuccessResult
                assertNotNull("预热请求未成功", warmUp)
                val warmUpBitmap = (warmUp?.image as? BitmapImage)?.bitmap
                assertNotNull("预热结果不是位图", warmUpBitmap)
                assumeTrue(
                    "当前平台未分配硬件位图 (config=${warmUpBitmap!!.config})",
                    warmUpBitmap.config == Bitmap.Config.HARDWARE,
                )

                val warmUpKey = warmUp.memoryCacheKey
                assertNotNull("预热请求未写入内存缓存", warmUpKey)
                val cacheKey = warmUpKey!!
                val warmUpImage = loader.memoryCache?.get(cacheKey)?.image as? BitmapImage
                assertNotNull("预热缓存项不是位图", warmUpImage)
                assertEquals(
                    "预热缓存项应为硬件位图",
                    Bitmap.Config.HARDWARE,
                    warmUpImage?.bitmap?.config,
                )

                val cover = BookCover.loadCoverBitmap(context, file.absolutePath)
                assertSoftwareBitmap("硬件缓存预热后加载", cover)
                assertPixels("硬件缓存预热后加载", cover, SOURCE_COLOR)

                val replacedImage = loader.memoryCache?.get(cacheKey)?.image as? BitmapImage
                assertNotNull("同一缓存键应存有封面结果", replacedImage)
                assertSoftwareBitmap("同一缓存键的封面结果", replacedImage!!.bitmap)
                assertSame("同一缓存键应复用封面加载得到的位图", cover, replacedImage.bitmap)

                val again = loader.execute(
                    ImageRequest.Builder(context).data(file.absolutePath).allowHardware(true).build()
                ) as? SuccessResult
                assertNotNull("再次请求未成功", again)
                assertEquals("再次请求应命中预热时的缓存键", cacheKey, again?.memoryCacheKey)
                assertEquals("再次请求应来自内存缓存", DataSource.MEMORY_CACHE, again?.dataSource)
                assertSame("再次请求应复用软件位图", cover, again?.image?.toBitmap())

                session.setMetadata(coverMetadata("硬件缓存预热后加载", cover))
                assertSessionMetadata(
                    "硬件缓存预热后加载",
                    session,
                    "硬件缓存预热后加载",
                    SOURCE_COLOR,
                    cover,
                )
            } finally {
                session.release()
            }
        }
    }

    /** 默认封面 (开关开启 / 空路径) 同样是软件位图, 且真实 MediaSession 更新不抛异常。 */
    @Test
    fun defaultCoverStaysSoftwareBitmap() = runBlocking {
        withContext(Dispatchers.Main) {
            val session = MediaSessionCompat(context, SESSION_TAG)
            try {
                val file = withContext(Dispatchers.IO) {
                    writeSolidImage(480, 640, SOURCE_COLOR, Bitmap.CompressFormat.PNG)
                }
                val seed = "book-cover-bitmap-test"

                AppConfig.useDefaultCover = true
                val forced = BookCover.loadCoverBitmap(context, file.absolutePath, seed = seed)
                assertSoftwareBitmap("开关开启时", forced)
                assertDefaultCover("开关开启时", forced, seed)
                session.setMetadata(coverMetadata("开关开启时", forced))
                assertSessionArt("开关开启时", session, forced)

                AppConfig.useDefaultCover = false
                val blankPath = BookCover.loadCoverBitmap(context, "  ", seed = seed)
                assertSoftwareBitmap("空路径", blankPath)
                assertDefaultCover("空路径", blankPath, seed)
                session.setMetadata(coverMetadata("空路径", blankPath))
                assertSessionArt("空路径", session, blankPath)
            } finally {
                session.release()
            }
        }
    }

    /** 位图必须是软件位图 (API 26 起才有硬件位图配置), 并能被软件 Canvas 绘制。 */
    private fun assertSoftwareBitmap(label: String, bitmap: Bitmap) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            assertNotEquals("$label: 不能是硬件位图", Bitmap.Config.HARDWARE, bitmap.config)
        }
        val probe = Bitmap.createBitmap(16, 16, Bitmap.Config.ARGB_8888)
        try {
            Canvas(probe).drawBitmap(bitmap, null, Rect(0, 0, 16, 16), null)
        } finally {
            probe.recycle()
        }
    }

    /** 抽样像素必须等于源图颜色: 证明加载到的是真实源图, 不是默认封面。 */
    private fun assertPixels(label: String, bitmap: Bitmap, expectedColor: Int) {
        for ((x, y) in samplePoints(bitmap)) {
            val pixel = bitmap.getPixel(x, y)
            assertTrue(
                "$label: ($x,$y) 期望源图颜色 ${expectedColor.toHex()} 实际 ${pixel.toHex()}",
                colorDistance(pixel, expectedColor) <= COLOR_TOLERANCE,
            )
        }
    }

    /** 抽样像素必须与同一 seed 的默认封面一致: 证明走的是默认封面分支。 */
    private fun assertDefaultCover(label: String, bitmap: Bitmap, seed: String) {
        val expected = BookCover.newDefaultDrawable(CoverRatio.NOVEL, seed).toBitmap()
        assertEquals("$label: 默认封面宽度", expected.width, bitmap.width)
        assertEquals("$label: 默认封面高度", expected.height, bitmap.height)
        for ((x, y) in samplePoints(bitmap)) {
            assertEquals(
                "$label: 默认封面像素 ($x,$y)",
                expected.getPixel(x, y),
                bitmap.getPixel(x, y),
            )
        }
    }

    /** 控制器回读: 标题与封面像素都要与本次更新一致。 */
    private fun assertSessionMetadata(
        label: String,
        session: MediaSessionCompat,
        expectedTitle: String,
        expectedColor: Int,
        expected: Bitmap,
    ) {
        val metadata = session.controller.metadata
        assertNotNull("$label: 控制器未读到元数据", metadata)
        assertEquals(
            "$label: 控制器标题",
            expectedTitle,
            metadata?.getText(MediaMetadataCompat.METADATA_KEY_TITLE)?.toString(),
        )
        assertSessionArt(label, session, expected, expectedColor)
    }

    /** 控制器回读的封面位图 (系统缩放后) 必须仍可被软件绘制。 */
    private fun assertSessionArt(
        label: String,
        session: MediaSessionCompat,
        expected: Bitmap,
        expectedColor: Int? = null,
    ) {
        val art = session.controller.metadata?.getBitmap(MediaMetadataCompat.METADATA_KEY_ART)
        assertNotNull("$label: 控制器未读到封面", art)
        assertSoftwareBitmap("$label: 控制器封面", art!!)
        assertTrue(
            "$label: 控制器封面 ${art.width}x${art.height} 不应大于源图 " +
                "${expected.width}x${expected.height}",
            art.width <= expected.width && art.height <= expected.height,
        )
        expectedColor?.let { assertPixels("$label: 控制器封面", art, it) }
    }

    /** 系统实际生效的封面尺寸上限 (框架 MediaMetadata 自 API 31 提供该 getter)。 */
    @RequiresApi(Build.VERSION_CODES.S)
    private fun sessionBitmapDimensionLimit(session: MediaSessionCompat): Int {
        val frameworkMetadata = session.controller.metadata?.getMediaMetadata() as? MediaMetadata
        assertNotNull("控制器未读到框架元数据", frameworkMetadata)
        return frameworkMetadata!!.getBitmapDimensionLimit()
    }

    private fun coverMetadata(title: String, bitmap: Bitmap): MediaMetadataCompat =
        MediaMetadataCompat.Builder()
            .putBitmap(MediaMetadataCompat.METADATA_KEY_ART, bitmap)
            .putText(MediaMetadataCompat.METADATA_KEY_TITLE, title)
            .build()

    private suspend fun loadSolidCover(width: Int, height: Int, color: Int): Bitmap {
        val file = withContext(Dispatchers.IO) {
            writeSolidImage(width, height, color, Bitmap.CompressFormat.PNG)
        }
        return BookCover.loadCoverBitmap(context, file.absolutePath)
    }

    private fun writeSolidImage(
        width: Int,
        height: Int,
        color: Int,
        format: Bitmap.CompressFormat,
    ): File {
        val file = File(context.cacheDir, "book_cover_${System.nanoTime()}.${format.suffix()}")
        tempFiles += file
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        try {
            bitmap.eraseColor(color)
            file.outputStream().use { out ->
                assertTrue("图片编码失败: $format", bitmap.compress(format, 100, out))
            }
            return file
        } finally {
            bitmap.recycle()
        }
    }

    private fun samplePoints(bitmap: Bitmap): List<Pair<Int, Int>> = listOf(
        bitmap.width / 2 to bitmap.height / 2,
        bitmap.width / 4 to bitmap.height / 3,
        bitmap.width * 3 / 4 to bitmap.height * 2 / 3,
    )

    private fun colorDistance(a: Int, b: Int): Int = maxOf(
        abs((a shr 16 and 0xFF) - (b shr 16 and 0xFF)),
        abs((a shr 8 and 0xFF) - (b shr 8 and 0xFF)),
        abs((a and 0xFF) - (b and 0xFF)),
    )

    private fun Int.toHex(): String = "#%08X".format(this)

    private fun Bitmap.CompressFormat.suffix(): String = when (this) {
        Bitmap.CompressFormat.PNG -> "png"
        Bitmap.CompressFormat.JPEG -> "jpg"
        else -> "webp"
    }

    @Suppress("DEPRECATION")
    private val webpFormat: Bitmap.CompressFormat
        get() = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            Bitmap.CompressFormat.WEBP_LOSSY
        } else {
            Bitmap.CompressFormat.WEBP
        }

    private companion object {
        const val SESSION_TAG = "bookCoverBitmapTest"

        /** 源图纯色 (magenta)。 */
        val SOURCE_COLOR = 0xFFFF00FF.toInt()

        /** 另一颜色封面 (green), 用于连续更新。 */
        val ALT_COLOR = 0xFF00FF00.toInt()

        /** 有损格式 (JPEG/WebP) 对纯色的量化误差。 */
        const val COLOR_TOLERANCE = 12
    }
}
