package io.legado.app.data.entities

import kotlinx.serialization.Serializable

@Serializable
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
    val coverVideo: Boolean = false,
    /** 非空 = 搜索类展示项 (exploreUrl 存书源 searchUrl, 加载走搜索模式 key=searchKey, 与发现类同链路分流) */
    val searchKey: String? = null
) {
    companion object {
        const val STYLE_COVER_ROW = 0
        const val STYLE_RANK_LIST = 1
        const val STYLE_INFINITE_GRID = 2
        const val STYLE_FOUR_ROW = 3
    }
}
