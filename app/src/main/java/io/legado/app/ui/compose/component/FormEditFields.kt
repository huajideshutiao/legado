package io.legado.app.ui.compose.component

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import io.legado.app.ui.compose.component.code.CodeTextField
import io.legado.app.ui.compose.component.code.rememberCodeSyntax
import io.legado.app.ui.widget.text.EditEntity

/**
 * Compose 版通用表单字段列表，替代 View 版 [io.legado.app.ui.widget.form.FormAdapter]
 * 在 form-edit 弹窗中的 text/code 两种字段（checkBox/spinner 这三处弹窗未用到）。
 * 值仍写回 [EditEntity.value]，保存/复制逻辑保持从 editEntities 取值的契约不变。
 */
@Composable
fun FormEditFields(entities: List<EditEntity>) {
    entities.forEach { entity ->
        key(entity.key, entity) {
            // code 类型无 pattern 时降级为 text，对齐 FormAdapter.getItemViewType
            if (entity.viewType == EditEntity.ViewType.code && entity.codePatterns != 0) {
                FormCodeField(entity)
            } else {
                FormTextField(entity)
            }
        }
    }
}

@Composable
private fun FormTextField(entity: EditEntity) {
    var value by remember(entity) { mutableStateOf(entity.value.orEmpty()) }
    AppOutlinedTextField(
        value = value,
        onValueChange = {
            value = it
            entity.value = it
        },
        label = entity.hint,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 4.dp),
    )
}

/** 代码输入行：共享 [CodeTextField]，按 [EditEntity.codePatterns] 位掩码开对应着色组 */
@Composable
private fun FormCodeField(entity: EditEntity) {
    val patterns = entity.codePatterns
    val syntax = rememberCodeSyntax(
        legado = patterns and EditEntity.CodePattern.legado != 0,
        json = patterns and EditEntity.CodePattern.json != 0,
        js = patterns and EditEntity.CodePattern.js != 0,
    )
    var value by remember(entity) { mutableStateOf(entity.value.orEmpty()) }
    CodeTextField(
        value = value,
        onValueChange = {
            value = it
            entity.value = it
        },
        syntax = syntax,
        label = entity.hint,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 4.dp),
    )
}
