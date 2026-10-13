// 本 Dialog 引用的字符串均取自 Compose Resources 按 key 索引的映射表
// (ui/src/commonMain/composeResources 为四端单一数据源, 无需各端补字面量):
// custom_page_key / prev_page_key / next_page_key / reset / ok; 本 Dialog 无图标资源

package io.legado.app.ui.book.read.config

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.unit.dp
import io.legado.app.constant.PreferKey
import io.legado.app.help.config.PreferenceProviders
import io.legado.app.ui.compose.component.AppDialogScaffold
import io.legado.app.ui.compose.component.AppDialogSizes
import io.legado.app.ui.compose.component.AppTextButton
import io.legado.app.ui.compose.component.AppUnderlineTextField
import io.legado.app.ui.compose.platform.keyToPageKeyCode
import io.legado.app.ui.compose.theme.AppTheme.DesignTokens
import legado.ui.generated.resources.Res
import legado.ui.generated.resources.custom_page_key
import legado.ui.generated.resources.next_page_key
import legado.ui.generated.resources.ok
import legado.ui.generated.resources.prev_page_key
import legado.ui.generated.resources.reset
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

    AppDialogScaffold(
        onDismissRequest = onDismiss,
        title = stringResource(Res.string.custom_page_key),
        // 底部按钮行: 对话框标准文字按钮 (不自行切分宽度); 重置走中性靠左, 确认靠右
        bottomBar = {
            AppTextButton(text = stringResource(Res.string.reset)) {
                prev = ""
                next = ""
            }
            Spacer(Modifier.weight(1f))
            AppTextButton(text = stringResource(Res.string.ok)) {
                onConfirm(buildKeyMappings(prev, next))
            }
        },
        contentPadding = PaddingValues(0.dp),
        properties = AppDialogSizes.properties(),
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = DesignTokens.spacingDefault, vertical = DesignTokens.spacingDefault),
            verticalArrangement = Arrangement.spacedBy(DesignTokens.spacingDefault),
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

/**
 * 自定义翻页按键对话框宿主: 读当前 prevKeys/nextKeys 偏好 → [PageKeyDialog] → 确认后回写两个键。
 *
 * 由"其它设置"的自定义翻页按键入口与阅读页首启引导(电视形态)共用, 两处读写同一对偏好键。
 * 确认即关闭 (对照原版 PageKeyDialog 的 tvOk 写入后 dismiss)。
 */
@Composable
fun CustomPageKeyDialogHost(
    onDismiss: () -> Unit,
) {
    val pref = remember { PreferenceProviders.get() }
    val keyMappings = remember {
        parsePageKeyMappings(
            pref.getStringOrNull(PreferKey.prevKeys) ?: "",
            pref.getStringOrNull(PreferKey.nextKeys) ?: "",
        )
    }
    PageKeyDialog(
        keyMappings = keyMappings,
        onConfirm = { mappings ->
            val (prev, next) = splitPageKeyMappings(mappings)
            pref.putString(PreferKey.prevKeys, prev)
            pref.putString(PreferKey.nextKeys, next)
            onDismiss()
        },
        onDismiss = onDismiss,
    )
}

// 解析 prevKeys/nextKeys 字符串为 PageKeyDialog 需要的 Map<Int, String>
// 与 PageKeyDialog 内部 buildKeyMappings 反向逻辑对齐
private fun parsePageKeyMappings(prevKeys: String, nextKeys: String): Map<Int, String> {
    val map = mutableMapOf<Int, String>()
    prevKeys.split(",").mapNotNull { it.trim().toIntOrNull() }.forEach { map[it] = "prev_page" }
    nextKeys.split(",").mapNotNull { it.trim().toIntOrNull() }.forEach { map[it] = "next_page" }
    return map
}

// 将 Map<Int, String> 反序列化为 prevKeys/nextKeys 字符串写回 prefs
private fun splitPageKeyMappings(mappings: Map<Int, String>): Pair<String, String> {
    val prev = mappings.filter { it.value == "prev_page" }.keys.joinToString(",") { it.toString() }
    val next = mappings.filter { it.value == "next_page" }.keys.joinToString(",") { it.toString() }
    return prev to next
}
