package io.legado.app.data.entities

import kotlinx.serialization.Serializable

@Serializable
data class PinnedExplore(
    val sourceUrl: String,
    val sourceName: String,
    val categoryName: String,
    val categoryUrl: String
)
