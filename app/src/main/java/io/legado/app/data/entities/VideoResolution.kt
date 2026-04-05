package io.legado.app.data.entities

import android.os.Parcelable
import kotlinx.parcelize.Parcelize

@Parcelize
data class VideoResolution(
    val name: String = "",
    val url: String = "",
    val width: Int = 0,
    val height: Int = 0,
    val bitrate: Int = 0
) : Parcelable

@Parcelize
data class VideoSource(
    val resolutions: List<VideoResolution> = emptyList(),
    val defaultIndex: Int = 0,
    val headers: Map<String, String>? = null
) : Parcelable {
    fun getResolution(index: Int = defaultIndex): VideoResolution? {
        return resolutions.getOrNull(index)
    }

}
