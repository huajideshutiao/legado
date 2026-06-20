package io.legado.app.data.entities

import android.os.Parcelable
import kotlinx.parcelize.Parcelize

/**
 * 单条段评数据
 * 仅用于网络抓取后的内存传递，不入库。
 */
@Parcelize
data class Review(
    var id: String? = null,
    var avatar: String? = null,
    var name: String? = null,
    var content: String = "",
    var postTime: String? = null,
    var extra: String? = null,
    var voteUpCount: Int = 0,
    var replyCount: Int = 0,
    var images: List<String> = emptyList(),
    var voted: Boolean = false,        // 当前用户是否已点赞，由 voteUpSelectedRule 解析
    var votedDown: Boolean = false,    // 当前用户是否已点踩，由 voteDownSelectedRule 解析
) : Parcelable
