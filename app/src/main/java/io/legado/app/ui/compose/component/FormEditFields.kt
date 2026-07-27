package io.legado.app.ui.compose.component

import android.view.ViewGroup
import android.widget.LinearLayout
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
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.widget.doAfterTextChanged
import com.google.android.material.textfield.TextInputLayout
import io.legado.app.lib.theme.applyThemeTree
import io.legado.app.lib.theme.space
import io.legado.app.ui.widget.code.CodeView
import io.legado.app.ui.widget.code.addJsPattern
import io.legado.app.ui.widget.code.addJsonPattern
import io.legado.app.ui.widget.code.addLegadoPattern
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

/** CodeView 输入行：AndroidView 白名单包裹（构造一次，无 update），按 codePatterns 配置语法高亮 */
@Composable
private fun FormCodeField(entity: EditEntity) {
    AndroidView(
        factory = { ctx ->
            val root = TextInputLayout(ctx).apply {
                layoutParams = ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                )
                setPadding(0, ctx.space.xs, 0, 0)
                hint = entity.hint
            }
            val codeView = CodeView(ctx).apply {
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                )
            }
            root.addView(codeView)
            entity.codePatterns.let { patterns ->
                if (patterns and EditEntity.CodePattern.legado != 0) codeView.addLegadoPattern()
                if (patterns and EditEntity.CodePattern.json != 0) codeView.addJsonPattern()
                if (patterns and EditEntity.CodePattern.js != 0) codeView.addJsPattern()
            }
            codeView.setText(entity.value)
            // 视图单实例常驻(无复用)，同步写回
            codeView.doAfterTextChanged { entity.value = it?.toString() }
            // 动态构造的 View 不走 Factory2, 整树着色
            root.applyThemeTree()
            root
        },
        modifier = Modifier.fillMaxWidth(),
    )
}
