package io.legado.app.ui.book.video

import io.legado.app.ui.compose.component.zoomMaxPanX
import io.legado.app.ui.compose.component.zoomMaxPanY
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * 视频画面缩放手势状态机的参数化验证 (双指捏合 / Ctrl+滚轮)。
 *
 * 覆盖一类行为而非单个用例: 倍率钳制、质心锚点、平移归一化与贴边边界、无效坐标防护、
 * 容器与画面几何变化后的重钳、默认视图判据。
 */
class VideoZoomGestureTest {

    /** 可控的缩放接入点 (替代平台渲染层)。 */
    private class FakeZoom(
        scale: Float = 1f,
        panX: Float = 0f,
        panY: Float = 0f,
        var aspect: Float? = 1f,
    ) {
        var value = VideoZoom(scale = scale, panX = panX, panY = panY)

        fun handle() = VideoZoomHandle(
            current = { value },
            set = { value = it },
            aspectRatio = { aspect },
        )
    }

    private fun controller(zoom: FakeZoom?): VideoGestureController = VideoGestureController(
        isPlaying = { true },
        positionMs = { 0L },
        durationMs = { 600_000L },
        speed = { 1f },
        setSpeed = {},
        onPlayPause = {},
        seekTo = {},
        readBrightness = { null },
        writeBrightness = {},
        readVolume = { null },
        writeVolume = {},
        onToggleControls = {},
        onGestureText = {},
        zoom = zoom?.handle(),
    )

    /** 1000x1000 容器 + 同比例内容 (FIT 内容 = 容器), 便于手算边界。 */
    private fun VideoGestureController.pinch(
        factor: Float,
        centroidX: Float = 500f,
        centroidY: Float = 500f,
    ) = onZoomGesture(
        zoomFactor = factor,
        panDeltaX = 0f,
        panDeltaY = 0f,
        centroidX = centroidX,
        centroidY = centroidY,
        width = 1000f,
        height = 1000f,
    )

    @Test
    fun `倍率按步进累积并钳制在 50-400 百分比`() {
        for (start in listOf(1f, 2f, 3.9f, 0.6f)) {
            for (factor in listOf(0.01f, 0.5f, 1.5f, 2f, 100f)) {
                val zoom = FakeZoom(scale = start)
                controller(zoom).pinch(factor)
                // 边界用字面量: 期望值引生产常量会让“常量被改动”失去守护
                val expected = (start * factor).coerceIn(0.5f, 4f)
                assertTrue(
                    abs(zoom.value.scale - expected) < 1e-4f,
                    "start=$start factor=$factor 期望 $expected 实际 ${zoom.value.scale}",
                )
            }
        }
    }

    @Test
    fun `缩放范围契约是 50-400 百分比`() {
        assertEquals(0.5f, VideoZoomMinScale, 0f)
        assertEquals(4f, VideoZoomMaxScale, 0f)
        assertEquals(50, VideoZoom(scale = VideoZoomMinScale).percent)
        assertEquals(400, VideoZoom(scale = VideoZoomMaxScale).percent)
    }

    @Test
    fun `异常几何下平移范围不产生非有限值`() {
        val ratios = listOf(null, 0f, -1f, Float.NaN, Float.POSITIVE_INFINITY)
        val sizes = listOf(0f, 500f, 1000f)
        for (ratio in ratios) {
            for (width in sizes) {
                for (height in sizes) {
                    for (scale in listOf(0.5f, 1f, 4f)) {
                        val maxX = zoomMaxPanX(width, height, ratio, scale)
                        val maxY = zoomMaxPanY(width, height, ratio, scale)
                        assertTrue(
                            maxX.isFinite() && maxX >= 0f,
                            "maxPanX=$maxX (比例=$ratio, ${width}x$height, 倍率=$scale)",
                        )
                        assertTrue(
                            maxY.isFinite() && maxY >= 0f,
                            "maxPanY=$maxY (比例=$ratio, ${width}x$height, 倍率=$scale)",
                        )
                    }
                }
            }
        }
    }

    @Test
    fun `以左上角为锚放大时画面贴到右边界`() {
        val zoom = FakeZoom()
        controller(zoom).pinch(factor = 2f, centroidX = 0f, centroidY = 0f)
        assertEquals(2f, zoom.value.scale, 1e-4f)
        assertEquals(1f, zoom.value.panX, 1e-3f)
        assertEquals(1f, zoom.value.panY, 1e-3f)
    }

    @Test
    fun `平移量夹在贴边范围内`() {
        val zoom = FakeZoom(scale = 2f)
        val handler = controller(zoom)
        // 连续拖动远超边界: 归一化后恒为 ±1 (可平移范围的满量程)
        repeat(20) { handler.onZoomGesture(1f, 500f, 500f, 500f, 500f, 1000f, 1000f) }
        assertEquals(1f, zoom.value.panX, 1e-3f)
        assertEquals(1f, zoom.value.panY, 1e-3f)
        repeat(40) { handler.onZoomGesture(1f, -500f, -500f, 500f, 500f, 1000f, 1000f) }
        assertEquals(-1f, zoom.value.panX, 1e-3f)
        assertEquals(-1f, zoom.value.panY, 1e-3f)
    }

    @Test
    fun `缩小到不超出容器时平移归零`() {
        val zoom = FakeZoom(scale = 2f, panX = 1f, panY = 1f)
        controller(zoom).pinch(factor = 0.1f)
        assertEquals(0.5f, zoom.value.scale, 1e-4f)
        assertEquals(0f, zoom.value.panX, 1e-4f)
        assertEquals(0f, zoom.value.panY, 1e-4f)
    }

    @Test
    fun `质心取不到时不把无效坐标写进状态`() {
        // 无效质心由手势层拦下 (换算成容器中心) 后才进锚点计算, 这里守住的就是那道防线
        for (bad in listOf(Float.NaN, Float.POSITIVE_INFINITY, Float.NEGATIVE_INFINITY)) {
            val zoom = FakeZoom()
            controller(zoom).pinch(factor = 1.5f, centroidX = bad, centroidY = bad)
            assertEquals(1.5f, zoom.value.scale, 1e-4f)
            assertTrue(zoom.value.panX.isFinite() && zoom.value.panY.isFinite(), "质心 $bad 污染了状态")
            assertEquals(0f, zoom.value.panX, 1e-4f)
            assertEquals(0f, zoom.value.panY, 1e-4f)
        }
    }

    @Test
    fun `滚轮上滚放大下滚缩小且互为逆操作`() {
        val zoom = FakeZoom()
        val handler = controller(zoom)
        handler.onWheelZoom(scrollDeltaY = -1f, x = 500f, y = 500f, width = 1000f, height = 1000f)
        val enlarged = zoom.value.scale
        assertTrue(enlarged > 1f, "上滚应放大, 实际 $enlarged")
        handler.onWheelZoom(scrollDeltaY = 1f, x = 500f, y = 500f, width = 1000f, height = 1000f)
        assertTrue(abs(zoom.value.scale - 1f) < 1e-3f, "下滚一格应回到原倍率, 实际 ${zoom.value.scale}")
    }

    @Test
    fun `容器变化到该轴不再超出时位置归零`() {
        val zoom = FakeZoom(scale = 2f, panX = 1f, panY = 1f)
        val handler = controller(zoom)
        handler.onContainerSizeChanged(1000f, 1000f)
        assertEquals(1f, zoom.value.panX, 1e-3f)
        // 容器变宽 (4000x1000) 且画面为方形: 横向内容不再超出 → 横向位置归零, 纵向仍可平移
        handler.onContainerSizeChanged(4000f, 1000f)
        assertEquals(0f, zoom.value.panX, 1e-4f)
        assertEquals(1f, zoom.value.panY, 1e-3f)
    }

    @Test
    fun `画面比例变化后只把不再溢出的轴归零`() {
        val zoom = FakeZoom(scale = 2f, panX = 1f, panY = 1f)
        val handler = controller(zoom)
        handler.onContainerSizeChanged(1000f, 1000f)
        zoom.aspect = 4f // 画面变宽: 纵向不再溢出, 横向仍可平移
        handler.onVideoGeometryChanged()
        assertEquals(0f, zoom.value.panY, 1e-4f)
        assertEquals(1f, zoom.value.panX, 1e-3f)
    }

    @Test
    fun `默认视图判据与显示百分比同一口径`() {
        assertTrue(VideoZoom(scale = 1f).isDefault)
        // 四舍五入后仍显示 100% 的微小偏差按默认视图处理 (与百分比文案显隐一致)
        assertTrue(VideoZoom(scale = 1.004f).isDefault)
        assertTrue(!VideoZoom(scale = 1.02f).isDefault)
        assertTrue(!VideoZoom(scale = 1f, panX = 0.5f).isDefault)
        assertTrue(!VideoZoom(scale = 0.5f).isDefault)
        assertEquals(150, VideoZoom(scale = 1.5f).percent)
        assertEquals(50, VideoZoom(scale = 0.5f).percent)
    }

    @Test
    fun `微小位移残留仍按默认视图处理`() {
        // 极轻的捏合会留下远小于容差的归一化位移: 不能因此常驻一个显示 100% 的重置入口
        assertTrue(VideoZoom(scale = 1.004f, panX = 0.001f, panY = -0.001f).isDefault)
        assertTrue(!VideoZoom(scale = 1f, panX = 0.02f).isDefault)
    }

    @Test
    fun `未接入缩放的端不响应双指与滚轮`() {
        val handler = controller(null)
        assertTrue(!handler.supportsZoom)
        // 没有缩放接入点就没有可写的状态, 这道验证只能守到“不抛异常、不碰其他手势”
        handler.pinch(factor = 2f)
        handler.onWheelZoom(-1f, 500f, 500f, 1000f, 1000f)
        handler.onContainerSizeChanged(1000f, 1000f)
        handler.onVideoGeometryChanged()
    }
}
