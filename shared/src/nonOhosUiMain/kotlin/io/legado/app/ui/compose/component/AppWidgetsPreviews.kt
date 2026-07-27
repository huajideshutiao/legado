package io.legado.app.ui.compose.component

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.PasswordVisualTransformation
import io.legado.app.ui.preview.AppPreview
import androidx.compose.ui.unit.dp
import io.legado.app.ui.preview.LegadoThemePreview

/**
 * [AppWidgets.kt] 中各 Composable 的 @Preview。
 * - [AppOutlinedButton]: 描边按钮
 * - [AppFilletTextButton]: 圆角填充小按钮 (chip 形态)
 * - [AppTextButton]: 文本按钮
 * - [AppSwitch]: 自绘开关
 * - [AppCheckbox]: 复选框
 * - [AppMenuCheckbox]: 菜单勾选框 (DropdownMenu 内用)
 * - [AppOutlinedTextField]: 输入框
 */

@AppPreview
@Composable
fun AppOutlinedButtonPreview() = LegadoThemePreview {
    Box(Modifier.padding(16.dp)) {
        AppOutlinedButton(text = "描边按钮", onClick = {})
    }
}

@AppPreview
@Composable
fun AppOutlinedButtonDisabledPreview() = LegadoThemePreview {
    Box(Modifier.padding(16.dp)) {
        AppOutlinedButton(text = "禁用按钮", enabled = false, onClick = {})
    }
}

@AppPreview
@Composable
fun AppFilletTextButtonPreview() = LegadoThemePreview {
    Box(Modifier.padding(16.dp)) {
        AppFilletTextButton(text = "圆角小按钮", onClick = {})
    }
}

@AppPreview
@Composable
fun AppTextButtonPreview() = LegadoThemePreview {
    Box(Modifier.padding(16.dp)) {
        AppTextButton(text = "文本按钮", onClick = {})
    }
}

@AppPreview
@Composable
fun AppSwitchPreview() = LegadoThemePreview {
    var checked by remember { mutableStateOf(true) }
    Box(Modifier.padding(16.dp)) {
        AppSwitch(checked = checked, onCheckedChange = { checked = it })
    }
}

@AppPreview
@Composable
fun AppSwitchUncheckedPreview() = LegadoThemePreview {
    Box(Modifier.padding(16.dp)) {
        AppSwitch(checked = false, onCheckedChange = {})
    }
}

@AppPreview
@Composable
fun AppSwitchDisabledPreview() = LegadoThemePreview {
    Box(Modifier.padding(16.dp)) {
        AppSwitch(checked = true, onCheckedChange = null, enabled = false)
    }
}

@AppPreview
@Composable
fun AppCheckboxPreview() = LegadoThemePreview {
    var checked by remember { mutableStateOf(true) }
    Box(Modifier.padding(16.dp)) {
        AppCheckbox(checked = checked, onCheckedChange = { checked = it })
    }
}

@AppPreview
@Composable
fun AppMenuCheckboxCheckedPreview() = LegadoThemePreview {
    Box(Modifier.padding(16.dp)) {
        AppMenuCheckbox(checked = true)
    }
}

@AppPreview
@Composable
fun AppMenuCheckboxUncheckedPreview() = LegadoThemePreview {
    Box(Modifier.padding(16.dp)) {
        AppMenuCheckbox(checked = false)
    }
}

@AppPreview
@Composable
fun AppOutlinedTextFieldPreview() = LegadoThemePreview {
    var value by remember { mutableStateOf("输入内容") }
    Box(Modifier.padding(16.dp)) {
        AppOutlinedTextField(
            value = value,
            onValueChange = { value = it },
            label = "标签",
            singleLine = true,
            modifier = Modifier.width(200.dp),
        )
    }
}

@AppPreview
@Composable
fun AppOutlinedTextFieldPasswordPreview() = LegadoThemePreview {
    var value by remember { mutableStateOf("password") }
    Box(Modifier.padding(16.dp)) {
        AppOutlinedTextField(
            value = value,
            onValueChange = { value = it },
            label = "密码",
            singleLine = true,
            visualTransformation = PasswordVisualTransformation(),
            modifier = Modifier.width(200.dp),
        )
    }
}

@AppPreview
@Composable
fun AppWidgetsGalleryPreview() = LegadoThemePreview {
    Column(
        Modifier.padding(16.dp).fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        AppOutlinedButton(text = "描边按钮", onClick = {})
        AppTextButton(text = "文本按钮", onClick = {})
        AppFilletTextButton(text = "圆角按钮", onClick = {})
        Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
            AppSwitch(checked = true, onCheckedChange = {})
            Spacer(Modifier.width(16.dp))
            AppSwitch(checked = false, onCheckedChange = {})
        }
        Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
            AppCheckbox(checked = true, onCheckedChange = {})
            Spacer(Modifier.width(16.dp))
            AppMenuCheckbox(checked = true)
            Spacer(Modifier.width(16.dp))
            AppMenuCheckbox(checked = false)
        }
    }
}
