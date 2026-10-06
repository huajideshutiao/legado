package io.legado.app.data.entities

import kotlinx.serialization.Serializable

@Serializable
data class PinnedExplore(
    val sourceUrl: String,
    val sourceName: String,
    val categoryName: String,
    val categoryUrl: String,
    /** 非空 = 搜索类收藏 (单源搜索结果页, categoryUrl 存书源 searchUrl): 发现页点击转搜索模式; 主页展示项候选时一并写入 searchKey */
    val searchKey: String? = null,
)
