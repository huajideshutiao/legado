package io.legado.app.data.entities.rule

import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonNames

/**
 * 正文处理规则
 *
 * subContent 字段挂 KS @SerialName + @JsonNames:
 * 旧书源 JSON 用 "lrcRule" 作为键时, KS (经 RulePolymorphicSerializer) 能正确反序列化到 subContent。
 * (Gson @SerializedName(alternate) 路径已随 Gson 移除, 仅保留 KS 路径)
 */
@OptIn(ExperimentalSerializationApi::class)
@Serializable
data class ContentRule(
    var content: String? = null,
    var title: String? = null, //有些网站只能在正文中获取标题
    var nextContentUrl: String? = null,
    var webJs: String? = null,
    var sourceRegex: String? = null,
    var replaceRegex: String? = null, //替换规则
    var imageStyle: String? = null,   //默认大小居中,FULL最大宽度
    var imageDecode: String? = null, //图片bytes二次解密js, 返回解密后的bytes
    var payAction: String? = null,    //购买操作,js或者包含{{js}}的url
    // KS 路径: @SerialName + @JsonNames (替代原 Gson @SerializedName(alternate = ["lrcRule"]))
    @SerialName("subContent")
    @JsonNames("lrcRule")
    var subContent: String? = null, //附加内容规则
    var musicCover: String? = null, //音乐封面
    var shouldOverrideUrlLoading: String? = null, // 拦截网页内的跳转, 用于订阅源迁移为书源后
) {

    companion object {
        // 原 Gson jsonDeserializer 已移除, 反序列化由 RulePolymorphicSerializer 复刻
    }

}
