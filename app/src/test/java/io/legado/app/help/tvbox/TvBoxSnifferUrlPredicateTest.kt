package io.legado.app.help.tvbox

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 嗅探 URL 判据的 JVM 单测 (纯函数, 无 Android 依赖): 钉住 FongMi Sniffer.isVideoFormat
 * 的 URL 形态过滤 —— 真实 m3u8/mp4 直链必须命中, 解析页/播放页自身形态必须排除。
 * 真机只跑冒烟 (WebView 侧), 判据逻辑在此覆盖。
 */
class TvBoxSnifferUrlPredicateTest {

    @Test
    fun m3u8_directUrl_matches() {
        assertTrue(
            isVideoUrl(
                "https://cdn.example.com/playlist/2024/12345678901234567890/index.m3u8?sign=abc123",
            ),
        )
        assertTrue(isVideoUrl("https://a.example.com/1234567890123456/video.m3u8"))
    }

    @Test
    fun mp4_directUrl_matches() {
        assertTrue(isVideoUrl("https://cdn.example.com/movies/12345678901234.mp4"))
        assertTrue(isVideoUrl("https://cdn.example.com/file/12345678901234.mkv?token=x"))
    }

    @Test
    fun rtmp_matches() {
        assertTrue(isVideoUrl("rtmp://live.example.com/live/stream123456789"))
    }

    @Test
    fun dash_mpd_matches() {
        assertTrue(isVideoUrl("https://cdn.example.com/dash/1234567890123456/manifest.mpd"))
    }

    @Test
    fun parsePageUrlForm_excluded() {
        // 解析站/播放页形态: url=http / v=http / .html 必须排除 (否则把解析页自己当视频)
        assertFalse(isVideoUrl("https://jx.example.com/?url=https%3A%2F%2Fv.qq.com%2Fx%2Fpage.html"))
        assertFalse(isVideoUrl("https://jx.example.com/index.php?url=https://v.qq.com/x/cover/1.html"))
        assertFalse(isVideoUrl("https://example.com/play?v=https://v.youku.com/v_show/abc.html"))
        assertFalse(isVideoUrl("https://v.qq.com/x/cover/mzc00200v4pnq4n/x0044wzcpcj.html"))
        assertFalse(isVideoUrl("https://www.iqiyi.com/v_19rr7nb0x0.html"))
    }

    @Test
    fun htmlPlayPage_excluded_evenWithMediaPath() {
        // .html 结尾的播放页: 即使路径里带 video 字样也排除
        assertFalse(isVideoUrl("https://site.com/video/play/123456789012.html"))
    }

    @Test
    fun shortOrGarbage_notMatched() {
        assertFalse(isVideoUrl("https://x.m3u8"))
        assertFalse(isVideoUrl("not a url"))
        assertFalse(isVideoUrl(""))
    }

    @Test
    fun youkuTosUrl_matches() {
        // 优酷 video/tos 形态: FongMi SNIFFER 的独立分支
        assertTrue(isVideoUrl("https://v6-web.douyinvod.com/video/tos/cn/tos-cn-ve-15/abc1234567890/video.m3u8"))
    }
}
