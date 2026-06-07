package io.legado.app.data.entities.rule

import android.os.Parcelable
import com.google.gson.JsonDeserializer
import io.legado.app.utils.INITIAL_GSON
import kotlinx.parcelize.Parcelize

@Parcelize
data class ReviewRule(
    var reviewUrl: String? = null,          // 评论列表URL，支持{{paragraphIndex}}占位符：-1=书籍级, 0=章节级, >0=段评级
    var reviewList: String? = null,         // 评论列表节点选择器
    var reviewCountRule: String? = null,    // 整章段评数规则，返回 {paragraphIndex: count}，仅 paragraphIndex>0 生效
    var reviewIdRule: String? = null,       // 段评ID规则（每条 item 内解析）
    var avatarRule: String? = null,         // 段评发布者头像
    var nameRule: String? = null,           // 段评发布者用户名
    var contentRule: String? = null,        // 段评内容
    var postTimeRule: String? = null,       // 段评发布时间
    var extraRule: String? = null,          // 段评附加信息（楼层/等级/地区等，单行小字）
    var imagesRule: String? = null,         // 段评图片列表（多图URL列表）
    var voteUpCountRule: String? = null,    // 点赞数
    var replyCountRule: String? = null,     // 一级回复数
    var totalCountRule: String? = null,     // 段评总数规则（请求级，result 为整页响应 body），用于列表头部"全部评论·N"
    var replyListUrl: String? = null,       // 回复列表URL规则（JS），变量：paragraphIndex / reviewId，返回回复列表 URL，仅在用户查看回复时执行
    var hasMoreRule: String? = null,        // 段评是否有下一页（请求级，result 为整页响应 body）

    var voteUpRule: String? = null,         // 点赞规则（JS），变量：paragraphIndex / reviewId / selected
    var voteDownRule: String? = null,       // 点踩规则（JS），变量：paragraphIndex / reviewId / selected
    var replyRule: String? = null,          // 回复评论规则（JS），变量：paragraphIndex / content / reviewId(顶层段评为空)
    var deleteRule: String? = null,         // 删除规则（JS），变量：paragraphIndex / reviewId
) : Parcelable {

    companion object {

        val jsonDeserializer = JsonDeserializer<ReviewRule?> { json, _, _ ->
            when {
                json.isJsonObject -> INITIAL_GSON.fromJson(json, ReviewRule::class.java)
                json.isJsonPrimitive -> INITIAL_GSON.fromJson(json.asString, ReviewRule::class.java)
                else -> null
            }
        }

    }

}
