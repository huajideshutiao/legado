// I18N KEYS (新增, 待 ResourceProvider.jvm.kt 补全桌面端字面量):
// - custom_page_key: "自定义翻页按键" (已存在 jvmMain)
// - prev_page_key: "上一页按键"
// - next_page_key: "下一页按键"
// - reset: "重置"
// - ok: "确认" (已存在 jvmMain)
//
// PAINTER KEYS: 本 Dialog 不使用 painter key (无图标)

package io.legado.app.ui.book.read.config

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material.Surface
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.unit.dp
import io.legado.app.ui.compose.component.AppDialog
import io.legado.app.ui.compose.component.AppDialogSizes
import io.legado.app.ui.compose.component.AppUnderlineTextField
import io.legado.app.ui.compose.component.DialogTitleBar
import io.legado.app.ui.compose.component.appDialogSize
import io.legado.app.ui.compose.platform.keyToPageKeyCode
import io.legado.app.ui.compose.theme.AppTheme
import io.legado.app.ui.compose.theme.AppTheme.DesignTokens
import legado.shared.generated.resources.Res
import legado.shared.generated.resources.custom_page_key
import legado.shared.generated.resources.next_page_key
import legado.shared.generated.resources.ok
import legado.shared.generated.resources.prev_page_key
import legado.shared.generated.resources.reset
import org.jetbrains.compose.resources.stringResource

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
 * - 聚焦的输入框按物理键自动录入 keyCode (与原版 `dialog.setOnKeyListener` + `hasFocus` 对齐,
 *   见 [appendCapturedKey])
 * - 重置按钮: 清空两个输入框 (与原版 `prev = ""; next = ""` 对齐)
 * - 确定按钮: 回传更新后的配置 (与原版 `AppConfig.prevKeys = prev; AppConfig.nextKeys = next` 对齐)
 *
 * # 与原版的差异 (KMP 限制)
 *
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
            shape = DesignTokens.shapeDefault,
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
                    AppUnderlineTextField(
                        value = prev,
                        onValueChange = { prev = it },
                        label = stringResource(Res.string.prev_page_key),
                        singleLine = true,
                        modifier = Modifier
                            .fillMaxWidth()
                            .onPreviewKeyEvent { event ->
                                val captured = appendCapturedKey(prev, event)
                                    ?: return@onPreviewKeyEvent false
                                prev = captured
                                true
                            },
                    )
                    AppUnderlineTextField(
                        value = next,
                        onValueChange = { next = it },
                        label = stringResource(Res.string.next_page_key),
                        singleLine = true,
                        modifier = Modifier
                            .fillMaxWidth()
                            .onPreviewKeyEvent { event ->
                                val captured = appendCapturedKey(next, event)
                                    ?: return@onPreviewKeyEvent false
                                next = captured
                                true
                            },
                    )
                }

                // 底部按钮行 (原版 dialog_page_key.xml: 重置/确定两个 48dp 高 weight1 均分方块,
                // selector_fillet_btn_bg = arco_radius_default 8dp 圆角 + btn_bg(#100e0e0e) 实底
                // + primaryText 居中, 两钮无间隔)
                Row(
                    Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 8.dp),
                ) {
                    FilletButton(
                        text = stringResource(Res.string.reset),
                        modifier = Modifier.weight(1f),
                    ) {
                        prev = ""
                        next = ""
                    }
                    FilletButton(
                        text = stringResource(Res.string.ok),
                        modifier = Modifier.weight(1f),
                    ) {
                        onConfirm(buildKeyMappings(prev, next))
                    }
                }
            }
        }
    }
}

/**
 * 物理键捕获追加 (对照原版 `dialog.setOnKeyListener`): 只认 KeyDown, 返回键/退格放行
 * (原版排除 KEYCODE_BACK/KEYCODE_DEL, 留给关闭对话框与删除字符); 空串或尾逗号直接追加,
 * 否则先补逗号。返回 null = 不录入 (放行给正常输入)。
 *
 * 存的恒是 Android keyCode (偏好格式与原版一致): Android 端取 Key.nativeKeyCode,
 * 桌面/iOS/鸿蒙经映射表反解, 表外键无对应 keyCode 故放行, 可手工输入数字。
 */
private fun appendCapturedKey(current: String, event: KeyEvent): String? {
    if (event.type != KeyEventType.KeyDown) return null
    if (event.key == Key.Back || event.key == Key.Escape || event.key == Key.Backspace) return null
    val code = keyToPageKeyCode(event.key) ?: return null
    return if (current.isEmpty() || current.endsWith(",")) "$current$code" else "$current,$code"
}

/**
 * 原版 selector_fillet_btn_bg 方块按钮: arco_radius_default 8dp 圆角 + btn_bg(#100e0e0e) 实底
 * + primaryText 居中; 高度 48dp (arco_view_height_xl), 点击区随 modifier 均分。
 */
@Composable
private fun FilletButton(
    text: String,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    val colors = AppTheme.colors
    Box(
        modifier
            .height(48.dp)
            .clip(DesignTokens.shapeDefault)
            .background(Color(0x100E0E0E))
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(text, color = colors.primaryText)
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
