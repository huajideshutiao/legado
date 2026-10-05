package io.legado.app.data.entities

import kotlinx.serialization.Serializable

/**
 * 单档流: 清晰度(本地书分辨率)档位。
 * [headers] 为该档请求头 (取数时随档携带, 播放装载时经 AnalyzeUrlCore headerMapF 注入)。
 * [mime] 为源声明的媒体类型 (TVBox playerContent 的 format 字段, FongMi PlaySpec.format
 * 同语义): 无后缀代理地址 (如 DASH 的 proxy?do=bili&type=mpd) 靠它首装即正确路由,
 * 不依赖解析失败后的格式重试。
 */
@Serializable
data class VideoResolution(
    val name: String = "",
    val url: String = "",
    val width: Int = 0,
    val height: Int = 0,
    val bitrate: Int = 0,
    val headers: Map<String, String> = emptyMap(),
    val mime: String = "",
)

@Serializable
data class VideoSource(
    /** 清晰度(本地书分辨率)档列表 */
    val resolutions: List<VideoResolution> = emptyList(),
    val defaultIndex: Int = 0,
    val headers: Map<String, String>? = null,
) {
    fun getResolution(index: Int = defaultIndex): VideoResolution? {
        return resolutions.getOrNull(index)
    }

}
