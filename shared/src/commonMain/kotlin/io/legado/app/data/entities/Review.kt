package io.legado.app.data.entities

import kotlinx.serialization.Serializable


/**
 * 单条段评数据
 * 仅用于网络抓取后的内存传递，不入库。
 *
 * @Serializable: 供跨端 Overlay payload (段评列表对话框 [io.legado.app.ui.route.ReviewListDialogHost])
 * 用 KS_JSON 序列化透传 (app 端 Gson 反射不受该注解影响, 字段/默认值不变)。
 */
@Serializable
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
)
