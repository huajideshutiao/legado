package io.legado.app.data.entities


data class VideoResolution(
    val name: String = "",
    val url: String = "",
    val width: Int = 0,
    val height: Int = 0,
    val bitrate: Int = 0
)

data class VideoSource(
    val resolutions: List<VideoResolution> = emptyList(),
    val defaultIndex: Int = 0,
    val headers: Map<String, String>? = null
) {
    fun getResolution(index: Int = defaultIndex): VideoResolution? {
        return resolutions.getOrNull(index)
    }

}
