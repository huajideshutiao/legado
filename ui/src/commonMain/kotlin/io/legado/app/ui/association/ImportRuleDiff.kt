package io.legado.app.ui.association

import io.legado.app.data.entities.DictRule
import io.legado.app.data.entities.ReplaceRule
import io.legado.app.data.entities.SourceFilterRule

/**
 * 三类规则"本地已有但内容有变化"的唯一判据。
 *
 * 列表里的"更新"标记、导入对话框的默认勾选、规则订阅的每日自动更新都读这一份, 三处不会再各自漂移;
 * 判据要增删字段只改这里。
 *
 * 不参与比对的字段: 主键 (定位用)、本地排序 (DictRule.sortNumber / SourceFilterRule.order, 属本地偏好)、
 * 创建时间 (SourceFilterRule.createTime, 元数据)。
 */
internal fun replaceRuleChanged(item: ReplaceRule, local: ReplaceRule): Boolean =
    item.name != local.name ||
        item.group != local.group ||
        item.pattern != local.pattern ||
        item.replacement != local.replacement ||
        item.scope != local.scope ||
        item.scopeTitle != local.scopeTitle ||
        item.scopeContent != local.scopeContent ||
        item.excludeScope != local.excludeScope ||
        item.isEnabled != local.isEnabled ||
        item.isRegex != local.isRegex

internal fun dictRuleChanged(item: DictRule, local: DictRule): Boolean =
    item.urlRule != local.urlRule ||
        item.showRule != local.showRule ||
        item.enabled != local.enabled

internal fun sourceFilterRuleChanged(item: SourceFilterRule, local: SourceFilterRule): Boolean =
    item.name != local.name ||
        item.enabled != local.enabled ||
        item.pattern != local.pattern ||
        item.fields != local.fields ||
        item.scope != local.scope
