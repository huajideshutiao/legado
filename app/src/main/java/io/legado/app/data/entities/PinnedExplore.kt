package io.legado.app.data.entities

import android.os.Parcelable
import kotlinx.parcelize.Parcelize

@Parcelize
data class PinnedExplore(
    val sourceUrl: String,
    val sourceName: String,
    val categoryName: String,
    val categoryUrl: String
) : Parcelable
