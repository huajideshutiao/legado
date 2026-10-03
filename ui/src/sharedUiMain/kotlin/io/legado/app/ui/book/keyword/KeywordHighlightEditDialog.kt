package io.legado.app.ui.book.keyword

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.Surface
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.legado.app.data.entities.KeywordHighlight
import io.legado.app.ui.book.read.page.overlay.HighlightPalette
import io.legado.app.ui.compose.component.AppDialog
import io.legado.app.ui.compose.component.AppDialogSizes
import io.legado.app.ui.compose.component.AppSwitch
import io.legado.app.ui.compose.component.AppTextButton
import io.legado.app.ui.compose.component.AppUnderlineTextField
import io.legado.app.ui.compose.component.DialogTitleBar
import io.legado.app.ui.compose.component.appDialogSize
import io.legado.app.ui.compose.theme.AppTheme
import io.legado.app.ui.compose.theme.AppTheme.DesignTokens
import legado.ui.generated.resources.Res
import legado.ui.generated.resources.cancel
import legado.ui.generated.resources.delete
import legado.ui.generated.resources.keyword
import legado.ui.generated.resources.keyword_highlight_add
import legado.ui.generated.resources.keyword_highlight_exclude_scope
import legado.ui.generated.resources.keyword_highlight_scope
import legado.ui.generated.resources.keyword_highlight_underline
import legado.ui.generated.resources.ok
import org.jetbrains.compose.resources.stringResource

/**
 * 关键词高亮编辑对话框 (新增/编辑共用, 纯 Composable + 回调, 对齐 BookmarkDialog 形态)。
 *
 * 编辑字段 = 关键词 + 作用范围 + 色档 + 下划线开关; pattern/isRegex 为正则升级预埋列,
 * 第一阶段不暴露编辑入口。
 *
 * @param rule 新增传空 id 实例; 确定时以 copy 回传新实例, 调用方负责入库
 */
@Composable
fun KeywordHighlightEditDialog(
    rule: KeywordHighlight,
    showDelete: Boolean = false,
    onConfirm: (KeywordHighlight) -> Unit,
    onDismiss: () -> Unit,
    onDelete: (() -> Unit)? = null,
) {
    val colors = AppTheme.colors
    val titleText = stringResource(Res.string.keyword_highlight_add)
    val wordLabel = stringResource(Res.string.keyword)
    val scopeLabel = stringResource(Res.string.keyword_highlight_scope)
    val excludeScopeLabel = stringResource(Res.string.keyword_highlight_exclude_scope)
    val underlineLabel = stringResource(Res.string.keyword_highlight_underline)
    val deleteText = stringResource(Res.string.delete)
    val cancelText = stringResource(Res.string.cancel)
    val okText = stringResource(Res.string.ok)

    var word by remember { mutableStateOf(rule.word) }
    var scope by remember { mutableStateOf(rule.scope.orEmpty()) }
    var excludeScope by remember { mutableStateOf(rule.excludeScope.orEmpty()) }
    var colorIndex by remember { mutableStateOf(rule.colorIndex) }
    var underline by remember { mutableStateOf(rule.underline) }

    AppDialog(onDismissRequest = onDismiss, properties = AppDialogSizes.properties()) {
        Surface(
            shape = DesignTokens.dialogShape,
            color = colors.fillet,
            modifier = Modifier.appDialogSize(),
        ) {
            Column(Modifier.fillMaxWidth()) {
                DialogTitleBar(
                    title = titleText,
                    onBack = onDismiss,
                )

                Column(
                    Modifier
                        .fillMaxWidth()
                        .padding(horizontal = DesignTokens.spacingDefault),
                ) {
                    AppUnderlineTextField(
                        value = word,
                        onValueChange = { word = it },
                        label = wordLabel,
                        modifier = Modifier.fillMaxWidth(),
                    )

                    Spacer(Modifier.size(DesignTokens.spacingDefault))
                    // 作用范围: 形态对齐替换规则编辑页 scope 字段 (label 即说明文案, 无 placeholder)
                    AppUnderlineTextField(
                        value = scope,
                        onValueChange = { scope = it },
                        label = scopeLabel,
                        modifier = Modifier.fillMaxWidth(),
                    )

                    Spacer(Modifier.size(DesignTokens.spacingDefault))
                    // 排除范围: 形态对齐替换规则编辑页 excludeScope 字段 (优先级高于作用范围)
                    AppUnderlineTextField(
                        value = excludeScope,
                        onValueChange = { excludeScope = it },
                        label = excludeScopeLabel,
                        modifier = Modifier.fillMaxWidth(),
                    )

                    Spacer(Modifier.size(DesignTokens.spacingDefault))
                    // 色档选择: 五档圆点, 选中项描边 (色值与回显同源 HighlightPalette)
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        repeat(HighlightPalette.SIZE) { index ->
                            val selected = index == colorIndex
                            Box(
                                Modifier
                                    .padding(end = DesignTokens.spacingDefault)
                                    .size(24.dp)
                                    .background(HighlightPalette.colorOf(index), CircleShape)
                                    .border(
                                        width = if (selected) 2.dp else 0.dp,
                                        color = colors.accent,
                                        shape = CircleShape,
                                    )
                                    .clickable { colorIndex = index },
                            )
                        }
                    }

                    Spacer(Modifier.size(DesignTokens.spacingDefault))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = underlineLabel,
                            color = colors.primaryText,
                            fontSize = 15.sp,
                            modifier = Modifier.weight(1f),
                        )
                        AppSwitch(checked = underline, onCheckedChange = { underline = it })
                    }
                }

                // 底部按钮栏: 删除(可选) | 弹性间距 | 取消 | 确定
                Row(
                    Modifier
                        .fillMaxWidth()
                        .padding(horizontal = DesignTokens.spacingDefault),
                ) {
                    if (showDelete && onDelete != null) {
                        AppTextButton(text = deleteText, color = DesignTokens.arcoBlue6) { onDelete() }
                    }
                    Spacer(Modifier.weight(1f))
                    AppTextButton(text = cancelText, color = colors.secondaryText) { onDismiss() }
                    AppTextButton(text = okText, color = DesignTokens.arcoBlue6) {
                        val word0 = word.trim()
                        if (word0.isEmpty()) return@AppTextButton
                        // scope/excludeScope 存原样不 trim/归一, 对齐替换规则编辑端 (ReplaceEditScreen.buildReplaceRule);
                        // 空串与 null 在匹配端等价 (均为全局)
                        onConfirm(
                            rule.copy(
                                word = word0,
                                colorIndex = colorIndex,
                                underline = underline,
                                scope = scope,
                                excludeScope = excludeScope,
                            )
                        )
                    }
                }
            }
        }
    }
}
