package io.legado.app.ui.compose.component

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import io.legado.app.ui.compose.platform.rememberPainter
import io.legado.app.ui.compose.theme.AppTheme
import io.legado.app.ui.preview.LegadoThemePreview

/**
 * [Md2TextField] 的 @Preview: 浅色/深色/错误态/禁用态/带图标/占位符/多行。
 */

@Preview
@Composable
fun Md2TextFieldLightPreview() = LegadoThemePreview {
    var value by remember { mutableStateOf("输入内容") }
    Box(Modifier.padding(16.dp)) {
        Md2TextField(
            value = value,
            onValueChange = { value = it },
            label = "标签",
            placeholder = "请输入",
            singleLine = true,
            modifier = Modifier.width(240.dp),
        )
    }
}

@Preview
@Composable
fun Md2TextFieldDarkPreview() = LegadoThemePreview(dark = true) {
    var value by remember { mutableStateOf("输入内容") }
    Box(Modifier.padding(16.dp)) {
        Md2TextField(
            value = value,
            onValueChange = { value = it },
            label = "标签",
            placeholder = "请输入",
            singleLine = true,
            modifier = Modifier.width(240.dp),
        )
    }
}

@Preview
@Composable
fun Md2TextFieldPlaceholderPreview() = LegadoThemePreview {
    var value by remember { mutableStateOf("") }
    Box(Modifier.padding(16.dp)) {
        Md2TextField(
            value = value,
            onValueChange = { value = it },
            label = "标签",
            placeholder = "请输入内容",
            singleLine = true,
            modifier = Modifier.width(240.dp),
        )
    }
}

@Preview
@Composable
fun Md2TextFieldErrorPreview() = LegadoThemePreview {
    var value by remember { mutableStateOf("bad") }
    Box(Modifier.padding(16.dp)) {
        Md2TextField(
            value = value,
            onValueChange = { value = it },
            label = "标签",
            isError = true,
            errorMessage = "内容不合法, 请重新输入",
            singleLine = true,
            modifier = Modifier.width(240.dp),
        )
    }
}

@Preview
@Composable
fun Md2TextFieldDisabledPreview() = LegadoThemePreview {
    Box(Modifier.padding(16.dp)) {
        Md2TextField(
            value = "不可编辑",
            onValueChange = {},
            enabled = false,
            label = "标签",
            singleLine = true,
            modifier = Modifier.width(240.dp),
        )
    }
}

@Preview
@Composable
fun Md2TextFieldWithIconsPreview() = LegadoThemePreview {
    var value by remember { mutableStateOf("关键字") }
    val colors = AppTheme.colors
    Box(Modifier.padding(16.dp)) {
        Md2TextField(
            value = value,
            onValueChange = { value = it },
            label = "搜索",
            placeholder = "请输入关键字",
            singleLine = true,
            leadingIcon = {
                Icon(
                    painter = rememberPainter("ic_search"),
                    contentDescription = null,
                    tint = colors.secondaryText,
                )
            },
            trailingIcon = {
                Icon(
                    painter = rememberPainter("ic_baseline_close"),
                    contentDescription = null,
                    tint = colors.secondaryText,
                )
            },
            modifier = Modifier.width(280.dp),
        )
    }
}

@Preview
@Composable
fun Md2TextFieldMultilinePreview() = LegadoThemePreview {
    var value by remember { mutableStateOf("第一行\n第二行") }
    Box(Modifier.padding(16.dp)) {
        Md2TextField(
            value = value,
            onValueChange = { value = it },
            label = "多行输入",
            placeholder = "请输入",
            maxLines = 4,
            modifier = Modifier.width(280.dp),
        )
    }
}

@Preview
@Composable
fun Md2TextFieldGalleryPreview() = LegadoThemePreview {
    var normal by remember { mutableStateOf("普通") }
    var empty by remember { mutableStateOf("") }
    Column(
        Modifier.padding(16.dp).width(280.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Md2TextField(
            value = normal,
            onValueChange = { normal = it },
            label = "普通",
            placeholder = "请输入",
            singleLine = true,
        )
        Md2TextField(
            value = empty,
            onValueChange = { empty = it },
            label = "占位符",
            placeholder = "请输入内容",
            singleLine = true,
        )
        Md2TextField(
            value = "错误内容",
            onValueChange = {},
            label = "错误",
            isError = true,
            errorMessage = "校验失败",
            singleLine = true,
        )
        Md2TextField(
            value = "禁用",
            onValueChange = {},
            enabled = false,
            label = "禁用",
            singleLine = true,
        )
    }
}
