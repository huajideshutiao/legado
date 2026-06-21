package io.legado.app.data.entities

import android.os.Parcelable
import kotlinx.parcelize.Parcelize

@Parcelize
data class HomeSection(
    val id: String,
    val title: String,
    val sourceUrl: String,
    val sourceName: String,
    val exploreUrl: String,
    val exploreName: String,
    val style: Int,
    val sortOrder: Int = 0,
    /** 封面比例：false=小说(3:4)，true=视频(16:9) */
    val coverVideo: Boolean = false
) : Parcelable {
    companion object {
        const val STYLE_COVER_ROW = 0
        const val STYLE_RANK_LIST = 1
        const val STYLE_INFINITE_GRID = 2
        const val STYLE_FOUR_ROW = 3
    }
}
