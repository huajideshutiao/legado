package io.legado.app.ui.compose.component

import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import io.legado.app.ui.preview.LegadoThemePreview

/** 数字输入框：等价 inputType=number 的 EditText（数字键盘 + 过滤非数字 + 长度上限），MD2 视觉 */
@Composable
fun AppNumberField(
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    label: String? = null,
    maxLength: Int = 5,
) {
    AppTextField(
        value = value,
        onValueChange = { input ->
            onValueChange(input.filter { it.isDigit() }.take(maxLength))
        },
        modifier = modifier,
        label = label,
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
    )
}

// ===== @Preview 合并自 androidMain 的 compose/component/SmallComponentsPreviews.kt (AppNumberField) =====

// ---- AppNumberField ----

@Preview
@Composable
fun AppNumberFieldPreview() = LegadoThemePreview {
    var value by remember { mutableStateOf("123") }
    Box(Modifier.padding(16.dp)) {
        AppNumberField(
            value = value,
            onValueChange = { value = it },
            label = "数字",
            modifier = Modifier.width(160.dp),
        )
    }
}

