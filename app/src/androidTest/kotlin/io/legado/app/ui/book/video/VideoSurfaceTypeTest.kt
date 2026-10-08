package io.legado.app.ui.book.video

import android.view.LayoutInflater
import android.view.TextureView
import androidx.media3.common.util.UnstableApi
import androidx.media3.ui.PlayerView
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.legado.app.R
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * 画面缩放是按图层变换施加的, 只有参与 View 合成的渲染面会跟随 (纹理视图);
 * 渲染面若是独立合成的 SurfaceView, 缩放会静默失效 —— 这条测试守住这个前提。
 */
@OptIn(UnstableApi::class)
@RunWith(AndroidJUnit4::class)
class VideoSurfaceTypeTest {

    @Test
    fun `视频渲染面是纹理视图`() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        // 媒体库的控件构造要求主线程 Looper
        var view: PlayerView? = null
        instrumentation.runOnMainSync {
            view = LayoutInflater.from(context)
                .inflate(R.layout.view_video_player, null, false) as PlayerView
        }
        val playerView = view ?: throw AssertionError("布局没有 inflate 出 PlayerView")
        val surface = playerView.videoSurfaceView
        assertTrue(
            "渲染面必须是纹理视图, 实际是 ${surface?.javaClass?.name}",
            surface is TextureView,
        )
    }
}
