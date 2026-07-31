package io.legado.app.ui.association

/*
 * 下沉所需资源 key 清单 (供 ResourceProvider 各平台 actual 补全)
 *
 * Painter key (drawable):
 *   - ic_arrow_back  (返回箭头, 已存在)
 *   - ic_save        (保存图标, 已存在)
 *
 * String key (string):
 *   - js_edit        (标题: JS 编辑)
 *   - file_name      (文件名输入框 label)
 *   - code           (代码输入框 label)
 *   - action_save    (保存按钮 contentDescription, 已存在)
 *   - run            (运行按钮文案)
 *   - result         (运行结果区标题)
 *   - empty          (空态文案, 已存在)
 */

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.Icon
import androidx.compose.material.IconButton
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.legado.app.ui.compose.component.AppTextField
import io.legado.app.ui.compose.component.AppTitleBar
import io.legado.app.ui.compose.component.AppTextButton
import io.legado.app.ui.compose.theme.AppTheme
import io.legado.app.ui.compose.theme.AppTheme.DesignTokens
import legado.shared.generated.resources.Res
import legado.shared.generated.resources.action_save
import legado.shared.generated.resources.code
import legado.shared.generated.resources.file_name
import legado.shared.generated.resources.ic_save
import legado.shared.generated.resources.js_edit
import legado.shared.generated.resources.result
import legado.shared.generated.resources.run
import org.jetbrains.compose.resources.painterResource
import org.jetbrains.compose.resources.stringResource

/**
 * JS 编辑页用户交互回调。
 *
 * 平台相关能力 (文件持久化) 由宿主实现:
 * - app 端可写沙盒/SAF, desktop 端可走 java.io, iOS 端走 FileManager
 *
 * 保存成功后宿主应调用 [JsEditScreenModel.markSaved] 同步状态。
 */
interface JsEditUiActions {
    fun onBack()

    /** 持久化 JS 代码到文件 (宿主实现), 成功后调 [JsEditScreenModel.markSaved] */
    fun onSave(code: String, fileName: String)
}

/**
 * JS 编辑页 Screen (shared, 平台无关)。
 *
 * app 端无独立 JsEditActivity (JS 编辑分散在 BookSourceEdit / CodeDialog),
 * 本 Screen 作为通用 JS 代码编辑器, 桌面/iOS 端可直接复用。
 *
 * - 标题栏: 返回 + 保存图标 (复用 [AppTitleBar] actions 槽)
 * - 文件名: 单行 [AppTextField]
 * - 代码区: 多行 [AppTextField] (普通 TextField, 不依赖 app 端 CodeView 平台库)
 * - 运行结果: 可滚动文本区, 显示 JS eval 返回值或异常栈
 * - 运行按钮: [AppTextButton], 触发 [JsEditUiEvent.Run] (由 ScreenModel 走 JsEngines eval)
 *
 * @param state 当前 UI 状态 (由 [JsEditScreenModel] 暴露)
 * @param actions 平台交互回调 (返回/保存)
 * @param onRun 运行按钮回调 (由 Screen 调 dispatch(JsEditUiEvent.Run))
 * @param onCodeChange 代码变更回调 (由 Screen 调 dispatch(JsEditUiEvent.CodeChange))
 * @param onFileNameChange 文件名变更回调 (由 Screen 调 dispatch(JsEditUiEvent.FileNameChange))
 */
@Composable
fun JsEditScreen(
    state: JsEditUiState,
    actions: JsEditUiActions,
    onRun: () -> Unit,
    onCodeChange: (String) -> Unit,
    onFileNameChange: (String) -> Unit,
) {
    val colors = AppTheme.colors
    Column(Modifier.fillMaxSize()) {
        AppTitleBar(
            title = stringResource(Res.string.js_edit),
            onBack = { actions.onBack() },
            actions = {
                IconButton(onClick = { actions.onSave(state.code, state.fileName) }) {
                    Icon(
                        painter = painterResource(Res.drawable.ic_save),
                        contentDescription = stringResource(Res.string.action_save),
                        tint = colors.primaryText,
                    )
                }
            },
        )
        // 文件名输入
        AppTextField(
            value = state.fileName,
            onValueChange = onFileNameChange,
            label = stringResource(Res.string.file_name),
            singleLine = true,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 4.dp),
        )
        // 代码编辑区 (普通 TextField, 多行填满主区域)
        AppTextField(
            value = state.code,
            onValueChange = onCodeChange,
            label = stringResource(Res.string.code),
            singleLine = false,
            maxLines = Int.MAX_VALUE,
            textStyle = TextStyle(
                color = colors.primaryText,
                fontSize = 14.sp,
                fontFamily = FontFamily.Monospace,
            ),
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .padding(horizontal = 12.dp, vertical = 4.dp),
        )
        // 运行结果区 (有结果或错误时显示, 可滚动)
        val resultText = state.result
        val errorText = state.error?.stackTraceToString()
        if (resultText != null || errorText != null) {
            ResultSection(
                title = stringResource(Res.string.result),
                content = resultText ?: errorText ?: "",
                isError = resultText == null,
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 80.dp, max = 160.dp),
            )
        }
        // 底部运行按钮
        Row(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.End,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            AppTextButton(
                text = stringResource(Res.string.run),
                enabled = !state.isRunning && state.code.isNotBlank(),
                onClick = onRun,
            )
        }
    }
}

/** 运行结果展示区: 标题 + 可滚动正文, error 时正文着 danger 色 */
@Composable
private fun ResultSection(
    title: String,
    content: String,
    isError: Boolean,
    modifier: Modifier = Modifier,
) {
    val colors = AppTheme.colors
    Column(
        modifier
            .background(colors.bottomBackground)
            .padding(horizontal = 12.dp, vertical = 8.dp),
    ) {
        Text(
            text = title,
            color = colors.secondaryText,
            fontSize = 12.sp,
        )
        Spacer(Modifier.height(4.dp))
        Box(
            Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState()),
        ) {
            Text(
                text = content,
                color = if (isError) DesignTokens.arcoDanger else colors.primaryText,
                fontSize = 12.sp,
                fontFamily = FontFamily.Monospace,
            )
        }
    }
}
