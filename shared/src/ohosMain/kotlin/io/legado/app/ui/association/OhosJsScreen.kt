package io.legado.app.ui.association

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.Button
import androidx.compose.material.MaterialTheme
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.legado.app.help.toast.Toasters
import io.legado.app.ui.compose.component.AppTitleBar
import io.legado.app.ui.compose.component.AppTextField
import io.legado.app.ui.compose.platform.rememberString

/**
 * 鸿蒙端 JS 代码编辑运行 Screen 入口 (对照 app 端 `JsActivity`)。
 *
 * # 背景
 *
 * app 端 `JsActivity` 是 BottomSheet 宿主, 内容由 JS 侧经 `dialog`/`dialogView` 动态填充,
 * 用于书源调试中运行 JS 代码 (通过 `JsEngines` + `JsFn` + `runWithAuth` 授权执行)。
 * 宿主自身无 Compose UI, 仅提供 JS 执行上下文与生命周期关联。
 *
 * 鸿蒙端无 Android BottomSheet/Fragment 体系, 本 Screen 作为 `OhosNavHost.JS_EDIT` 路由入口,
 * 用标准 Compose 提供代码编辑区 + 运行按钮 + 结果展示区。
 *
 * # 简化项
 *
 * - 代码编辑区用 [AppTextField] 替代 CodeView (与 [OhosBookSourceEditScreen] 一致,
 *   CodeView 是 app 端 Android 专属控件, KMP 版已移除)
 * - JS 运行暂 toast 提示待接入: 实际执行需 `JsEngines` + `JsFn` + `runWithAuth` 授权上下文
 *   (依赖 `IntentData`/`JsScope` 桥接, 后续 KP)
 * - 自动缩进/语法高亮: 依赖 CodeView, 不支持
 *
 * @param jsCode 初始 JS 代码 (由调用方传入, 如书源调试场景的待运行 JS 片段)
 * @param onBack 返回回调 (由 OhosNavHost 注入)
 */
@Composable
fun OhosJsScreen(
    jsCode: String,
    onBack: () -> Unit,
) {
    // 待接入提示文案 (回调 lambda 非 @Composable, 需预先缓存)
    val notImplementedText = rememberString("ohos_js_run_not_implemented")
    val runLabel = rememberString("run")
    val resultLabel = rememberString("result")

    var code by remember { mutableStateOf(jsCode) }
    var result by remember { mutableStateOf("") }

    Column(Modifier.fillMaxSize()) {
        AppTitleBar(
            title = rememberString("js_edit"),
            onBack = onBack,
        )
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            // 代码编辑区 (对照 OhosBookSourceEditScreen.codeEditorSlot 的 AppTextField 用法)
            AppTextField(
                value = code,
                onValueChange = { code = it },
                modifier = Modifier.fillMaxWidth(),
                textStyle = TextStyle(
                    fontSize = 13.sp,
                    fontFamily = FontFamily.Monospace,
                ),
                minLines = 8,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Text),
            )

            // 运行按钮
            Button(
                onClick = {
                    // TODO: 接入 JsEngines + JsFn + runWithAuth 授权执行 (后续 KP)
                    Toasters.get().toast(notImplementedText)
                },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(runLabel)
            }

            // 结果展示区
            Text(
                text = resultLabel,
                color = MaterialTheme.colors.primary,
                fontSize = 14.sp,
            )
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(160.dp),
            ) {
                if (result.isNotEmpty()) {
                    Text(
                        text = result,
                        color = MaterialTheme.colors.onSurface,
                        fontSize = 13.sp,
                        fontFamily = FontFamily.Monospace,
                        modifier = Modifier
                            .fillMaxSize()
                            .verticalScroll(rememberScrollState()),
                    )
                } else {
                    Spacer(Modifier.fillMaxSize())
                }
            }
        }
    }
}
