// I18N KEYS (新增, 待 ResourceProvider.jvm.kt 补全桌面端字面量):
// - custom_page_key: "自定义翻页按键" (已存在 jvmMain)
// - prev_page_key: "上一页按键"
// - next_page_key: "下一页按键"
// - reset: "重置"
// - ok: "确定" (已存在 jvmMain)
//
// PAINTER KEYS: 本 Dialog 不使用 painter key (无图标)

package io.legado.app.ui.book.read.config

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import io.legado.app.ui.compose.component.AppDialog
import io.legado.app.ui.compose.component.AppDialogSizes
import io.legado.app.ui.compose.component.AppOutlinedTextField
import io.legado.app.ui.compose.component.AppTextButton
import io.legado.app.ui.compose.component.DialogTitleBar
import io.legado.app.ui.compose.component.appDialogSize
import io.legado.app.ui.compose.theme.AppTheme
import io.legado.app.ui.compose.theme.AppTheme.DesignTokens
import legado.shared.generated.resources.Res
import legado.shared.generated.resources.custom_page_key
import legado.shared.generated.resources.next_page_key
import legado.shared.generated.resources.ok
import legado.shared.generated.resources.prev_page_key
import legado.shared.generated.resources.reset
import org.jetbrains.compose.resources.stringResource
import androidx.compose.ui.tooling.preview.Preview
import io.legado.app.ui.preview.LegadoThemePreview

/**
 * 自定义翻页按键对话框 (KMP 共享, app + desktop 复用)。
 *
 * 对应 app 端 `io.legado.app.ui.book.read.config.PageKeyDialog`,
 * 但去掉对 Android Fragment / Dialog / KeyEvent / hideSoftInput 的依赖,
 * 改为纯 @Composable + 回调形式:
 * - 调用方传入 [keyMappings] (keyCode → 动作名映射), 解析为 prev/next 两个输入框初值
 * - 用户编辑后, 通过 [onConfirm] 回传更新后的 Map (keyCode → "prev_page"/"next_page")
 * - [onDismiss] 关闭回调 (与原版 dismiss 对齐)
 *
 * # 原业务逻辑保留
 *
 * - 两个输入框: 上一页按键 / 下一页按键 (与原版 prev / next 输入框对齐)
 * - 输入框内是 keyCode 数字逗号分隔串 (与原版 `appendKeyCode` 拼接逻辑对齐)
 * - 重置按钮: 清空两个输入框 (与原版 `prev = ""; next = ""` 对齐)
 * - 确定按钮: 回传更新后的配置 (与原版 `AppConfig.prevKeys = prev; AppConfig.nextKeys = next` 对齐)
 *
 * # 与原版的差异 (KMP 限制)
 *
 * - 原版 `dialog.setOnKeyListener` 监听物理键按下自动追加 keyCode, 依赖 Android Dialog KeyEvent
 *   (桌面端无对应 API, 改为用户手动输入 keyCode 数字)
 * - 原版 `onDismiss` 调用 `hideSoftInput`, 依赖 Android InputMethodManager
 *   (Compose Multiplatform 由平台焦点机制自动处理, 不需要手动隐藏)
 *
 * # 数据转换
 *
 * - 入参 [keyMappings]: `Map<Int, String>` (keyCode → 动作名, 动作名 ∈ {"prev_page", "next_page"})
 * - 内部状态: 两个 String (prevKeys / nextKeys), 数字逗号分隔
 * - 出参: 重新组装为 `Map<Int, String>` (与入参格式对齐, 便于调用方持久化)
 *
 * @param keyMappings 当前 keyCode → 动作名映射 (动作名: "prev_page" / "next_page")
 * @param onConfirm 确认回调, 携带更新后的映射
 * @param onDismiss 关闭回调
 */
@Composable
fun PageKeyDialog(
    keyMappings: Map<Int, String>,
    onConfirm: (Map<Int, String>) -> Unit,
    onDismiss: () -> Unit,
) {
    val colors = AppTheme.colors

    // 从入参解析 prev/next 两个输入框初值
    // 与原版 `prev = AppConfig.prevKeys.orEmpty()` 对齐, 这里从 Map 反向序列化
    val initialPrev = remember(keyMappings) {
        keyMappings.entries
            .filter { it.value == "prev_page" }
            .joinToString(",") { it.key.toString() }
    }
    val initialNext = remember(keyMappings) {
        keyMappings.entries
            .filter { it.value == "next_page" }
            .joinToString(",") { it.key.toString() }
    }

    var prev by remember(keyMappings) { mutableStateOf(initialPrev) }
    var next by remember(keyMappings) { mutableStateOf(initialNext) }

    AppDialog(
        onDismissRequest = onDismiss,
        properties = AppDialogSizes.properties(),
    ) {
        Surface(
            shape = DesignTokens.dialogShape,
            color = colors.fillet,
            modifier = Modifier.appDialogSize().padding(16.dp),
        ) {
            Column(Modifier.fillMaxWidth()) {
                DialogTitleBar(
                    title = stringResource(Res.string.custom_page_key),
                    onBack = onDismiss,
                )
                Column(
                    Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    AppOutlinedTextField(
                        value = prev,
                        onValueChange = { prev = it },
                        label = stringResource(Res.string.prev_page_key),
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    AppOutlinedTextField(
                        value = next,
                        onValueChange = { next = it },
                        label = stringResource(Res.string.next_page_key),
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }

                // 底部按钮行 (与原版 Row + Spacer(weight) + Reset + OK 对齐)
                Row(
                    Modifier
                        .fillMaxWidth()
                        .padding(8.dp),
                    horizontalArrangement = Arrangement.End,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    AppTextButton(
                        text = stringResource(Res.string.reset),
                        color = colors.secondaryText,
                        onClick = {
                            prev = ""
                            next = ""
                        },
                    )
                    Spacer(Modifier.padding(horizontal = 4.dp))
                    AppTextButton(
                        text = stringResource(Res.string.ok),
                        onClick = {
                            onConfirm(buildKeyMappings(prev, next))
                        },
                    )
                }
            }
        }
    }
}

/**
 * 把 prev/next 两个输入框内容组装为 `Map<Int, String>`。
 *
 * - prev 输入框中的 keyCode → "prev_page"
 * - next 输入框中的 keyCode → "next_page"
 * - 解析时容忍空串 / 多余逗号 / 非数字 (与原版 `AppConfig.prevKeys` 直接存字符串的宽松语义对齐)
 */
private fun buildKeyMappings(prev: String, next: String): Map<Int, String> {
    val result = mutableMapOf<Int, String>()
    prev.split(",")
        .mapNotNull { it.trim().toIntOrNull() }
        .forEach { result[it] = "prev_page" }
    next.split(",")
        .mapNotNull { it.trim().toIntOrNull() }
        .forEach { result[it] = "next_page" }
    return result
}

// ===== @Preview 合并自 androidMain 的 book/read/config/ReadConfigDialogPreviews.kt (PageKeyDialog) =====

// ===== PageKeyDialog =====

private val previewKeyMappings = mapOf(
    21 to "prev_page",  // KEYCODE_DPAD_LEFT
    22 to "next_page",  // KEYCODE_DPAD_RIGHT
    19 to "prev_page",  // KEYCODE_DPAD_UP
    20 to "next_page",  // KEYCODE_DPAD_DOWN
)

@Preview
@Composable
fun PageKeyDialogPreview() = LegadoThemePreview {
    PageKeyDialog(
        keyMappings = previewKeyMappings,
        onConfirm = {},
        onDismiss = {},
    )
}

@Preview
@Composable
fun PageKeyDialogEmptyPreview() = LegadoThemePreview {
    PageKeyDialog(
        keyMappings = emptyMap(),
        onConfirm = {},
        onDismiss = {},
    )
}

@Preview
@Composable
fun PageKeyDialogDarkPreview() = LegadoThemePreview(dark = true) {
    PageKeyDialog(
        keyMappings = previewKeyMappings,
        onConfirm = {},
        onDismiss = {},
    )
}

