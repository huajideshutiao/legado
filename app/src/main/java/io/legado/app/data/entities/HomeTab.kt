package io.legado.app.data.entities

import android.os.Parcelable
import kotlinx.parcelize.Parcelize

/**
 * 主页分组。title 即唯一标识（同名即同一个），重命名 = 改 title。
 * sections 在分组内按 sortOrder 排序，无限流强制排到最后。
 */
@Parcelize
data class HomeTab(
    val title: String,
    val sortOrder: Int = 0,
    val sections: List<HomeSection> = emptyList()
) : Parcelable
