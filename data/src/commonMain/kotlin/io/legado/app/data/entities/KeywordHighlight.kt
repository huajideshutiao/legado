package io.legado.app.data.entities

import androidx.room3.Entity
import androidx.room3.PrimaryKey
import kotlinx.serialization.Serializable

/**
 * 关键词高亮规则。
 *
 * 匹配与渲染见阅读层 KeywordMatcher / HighlightOverlay; [pattern]/[isRegex] 为正则
 * 升级预埋列, 本次迁移一次到位, 第一阶段 UI 与匹配逻辑均不使用。
 *
 * [scope] 作用范围语义对齐 [ReplaceRule.scope] (空 = 全局, 非空 = 书名/书源 origin
 * 子串命中即生效), 按书过滤在阅读层 ChapterHighlightState 跑 KeywordMatcher 前完成。
 *
 * 声明序即建表列序: [scope] 追加在既有列之后, 与 87→88 自动迁移的
 * ALTER TABLE ADD COLUMN 追加序保持一致。
 */
@Serializable
@Entity(tableName = "keywordHighlights")
data class KeywordHighlight(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    /** 匹配关键词(字面量, 阶段一); [pattern] 启用后此字段仅作显示名 */
    val word: String = "",
    /** 色档索引(见阅读层 HighlightPalette) */
    val colorIndex: Int = 0,
    /** true = 半透明背景之外在行底补一条同色下划线 */
    val underline: Boolean = false,
    val isEnabled: Boolean = true,
    /** 列表展示与匹配先后顺序, 新增取 maxOrder+1 */
    val sortOrder: Int = 0,
    /** 正则升级预埋: 模式串, 第一阶段恒空串 */
    val pattern: String = "",
    /** 正则升级预埋: 是否按正则匹配, 第一阶段恒 false */
    val isRegex: Boolean = false,
    /** 作用范围, 语义对齐 [ReplaceRule.scope]: null/空串 = 全局, 非空 = 书名或书源 origin 子串命中即生效 */
    val scope: String? = null,
    /** 排除范围, 语义对齐 [ReplaceRule.excludeScope]: null = 不排除, 非空 = 书名或书源 origin 子串命中即本条不生效 (优先于 [scope]) */
    val excludeScope: String? = null,
)
