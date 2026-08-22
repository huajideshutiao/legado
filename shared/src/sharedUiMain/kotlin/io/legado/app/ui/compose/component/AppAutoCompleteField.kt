package io.legado.app.ui.compose.component

import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.Icon
import androidx.compose.material.IconButton
import androidx.compose.material.Surface
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
import io.legado.app.ui.compose.theme.AppTheme
import legado.shared.generated.resources.Res
import legado.shared.generated.resources.ic_clear_all
import org.jetbrains.compose.resources.painterResource

/**
 * 复刻 widget.text.AutoCompleteTextView（DialogEditTextBinding 的历史下拉输入框）：
 * 聚焦/输入时弹历史候选下拉，每项可选删除按钮（onDelete != null 时显示，删除后从下拉移除并回调）。
 * 下拉不抢焦点, 锚在输入框下划线下方并与其同宽, 输入可持续过滤（contains，对齐历史建议语义）。
 */
@Composable
fun AppAutoCompleteField(
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    label: String? = null,
    values: List<String> = emptyList(),
    onDelete: ((String) -> Unit)? = null,
    singleLine: Boolean = true,
    autoFocus: Boolean = false,
) {
    val colors = AppTheme.colors
    val density = LocalDensity.current
    val history = remember { mutableStateListOf<String>().apply { addAll(values) } }
    LaunchedEffect(values) {
        history.clear(); history.addAll(values)
    }
    // 下拉展开态: 聚焦或继续输入即展开, 失焦/收起/点选候选后关闭
    // (对齐原版 enoughToFilter()=true 与 ACTION_DOWN 时 showDropDown)。
    // 不能直接用 isFocused: 收起时焦点没变, 只跟焦点回调就再也回不到展开态
    var expanded by remember { mutableStateOf(false) }
    // 输入框实测尺寸: 下拉锚在下划线正下方并与输入框同宽 (原版 ListPopupWindow 语义)
    var fieldSize by remember { mutableStateOf(IntSize.Zero) }
    val suggestions =
        history.filter { expanded && (value.isEmpty() || it.contains(value, ignoreCase = true)) }
    val focusRequester = remember { androidx.compose.ui.focus.FocusRequester() }
    if (autoFocus) {
        LaunchedEffect(Unit) { runCatching { focusRequester.requestFocus() } }
    }
    Box(modifier) {
        AppUnderlineTextField(
            value = value,
            onValueChange = {
                expanded = true
                onValueChange(it)
            },
            modifier = Modifier
                .fillMaxWidth()
                // 按下即展开, 对齐原版 onTouchEvent 里 ACTION_DOWN 就 showDropDown():
                // 选过候选或点过弹层外面后焦点未变, 只靠 onFocusChanged 回不到展开态。
                // requireUnconsumed=false 是必需的 —— 输入框自身会消费这个 down
                .pointerInput(Unit) {
                    awaitEachGesture {
                        awaitFirstDown(requireUnconsumed = false)
                        expanded = true
                    }
                }
                .onSizeChanged { fieldSize = it }
                .focusRequester(focusRequester)
                .onFocusChanged { expanded = it.isFocused },
            label = label,
            singleLine = singleLine,
        )
        if (suggestions.isNotEmpty()) {
            Popup(
                alignment = Alignment.TopStart,
                offset = IntOffset(0, fieldSize.height),
                properties = PopupProperties(focusable = false),
                onDismissRequest = { expanded = false },
            ) {
                Surface(
                    color = colors.fillet,
                    elevation = 4.dp,
                    modifier = Modifier.width(with(density) { fieldSize.width.toDp() }),
                ) {
                    LazyColumn(Modifier.heightIn(max = 200.dp)) {
                        // 用下标作 key: 历史缓存里可能有重复串 (原版 ArrayAdapter 容忍重复),
                        // 字符串作 key 会因重复抛 IllegalArgumentException
                        itemsIndexed(suggestions) { _, item ->
                            Row(
                                Modifier
                                    .fillMaxWidth()
                                    .clickable {
                                        onValueChange(item)
                                        expanded = false
                                    }
                                    .padding(start = 12.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Text(
                                    item,
                                    color = colors.primaryText,
                                    fontSize = 14.sp,
                                    modifier = Modifier
                                        .weight(1f)
                                        .padding(vertical = 12.dp),
                                )
                                if (onDelete != null) {
                                    IconButton(onClick = {
                                        history.remove(item)
                                        onDelete(item)
                                    }) {
                                        Icon(
                                            painter = painterResource(Res.drawable.ic_clear_all),
                                            contentDescription = null,
                                            tint = colors.secondaryText,
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
