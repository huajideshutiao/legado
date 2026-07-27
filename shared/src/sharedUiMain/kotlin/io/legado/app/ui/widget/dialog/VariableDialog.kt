package io.legado.app.ui.widget.dialog

// I18N KEYS (need to register in ResourceProvider.jvm.kt):
//   "source_variable_tab" to "源变量",
//   "book_variable_tab" to "书籍变量",
//   "variable_key" to "变量名",
//   "variable_value" to "变量值",
//   "variable_edit_title" to "编辑变量",
//   "variable_empty" to "暂无变量"

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.Icon
import androidx.compose.material.IconButton
import androidx.compose.material.Surface
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import io.legado.app.ui.compose.component.AppOutlinedTextField
import io.legado.app.ui.compose.component.AppScrollTabRow
import io.legado.app.ui.compose.component.AppTextButton
import io.legado.app.ui.compose.component.DialogTitleBar
import io.legado.app.ui.compose.platform.rememberPainter
import io.legado.app.ui.compose.platform.rememberString
import io.legado.app.ui.compose.theme.AppTheme
import io.legado.app.ui.compose.theme.AppTheme.DesignTokens

/**
 * 变量编辑对话框 (KMP 共享, app + desktop 复用)。
 *
 * 对应 app 端 `io.legado.app.ui.widget.dialog.VariableDialog`, 但去掉对
 * AppCompatActivity / ComposeDialog / WindowManager 的依赖, 改为纯 @Composable + 回调形式:
 * - 两个 Tab: 源变量 / 书籍变量, 通过 [AppScrollTabRow] 切换
 * - 每个 Tab 展示变量键值对 (key 只读 + value 可编辑), 支持删除和新增
 * - 确认时把两个 Map 通过 [onConfirm] 回传
 *
 * 原 app 端是单变量编辑器 (一次编辑一个变量值), KMP 版改为 Map 批量编辑器
 * (一次查看/编辑全部变量), 更适合桌面端批量操作场景。
 *
 * @param sourceVariables 当前的书源变量 Map
 * @param bookVariables 当前的书籍变量 Map
 * @param onConfirm 用户点击确定, 参数为 (源变量Map, 书籍变量Map)
 * @param onDismiss 用户取消 (返回按钮 / 点击对话框外部 / 取消按钮)
 */
@Composable
fun VariableDialog(
    sourceVariables: Map<String, String>,
    bookVariables: Map<String, String>,
    onConfirm: (Map<String, String>, Map<String, String>) -> Unit,
    onDismiss: () -> Unit,
) {
    val colors = AppTheme.colors
    val titleText = rememberString("variable_edit_title")
    val sourceTabText = rememberString("source_variable_tab")
    val bookTabText = rememberString("book_variable_tab")
    val keyLabelText = rememberString("variable_key")
    val valueLabelText = rememberString("variable_value")
    val emptyText = rememberString("variable_empty")
    val addText = rememberString("add")
    val cancelText = rememberString("cancel")
    val okText = rememberString("ok")

    // 可变变量 Map (初始化自参数)
    val sourceVars = remember { mutableStateMapOf<String, String>().apply { putAll(sourceVariables) } }
    val bookVars = remember { mutableStateMapOf<String, String>().apply { putAll(bookVariables) } }

    // 当前选中的 Tab (0=源变量, 1=书籍变量)
    var selectedTab by remember { mutableStateOf(0) }

    Dialog(onDismissRequest = onDismiss) {
        Surface(
            shape = DesignTokens.dialogShape,
            color = colors.background,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Column(Modifier.fillMaxWidth()) {
                DialogTitleBar(
                    title = titleText,
                    onBack = onDismiss,
                )

                // Tab 切换行 (任务要求用 AppTabRow, shared 仅有 AppScrollTabRow, 行为等价)
                AppScrollTabRow(
                    tabCount = 2,
                    selectedIndex = selectedTab,
                    indicatorColor = DesignTokens.arcoBlue6,
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(colors.bottomBackground),
                ) { index ->
                    val tabText = if (index == 0) sourceTabText else bookTabText
                    Text(
                        text = tabText,
                        color = if (index == selectedTab) DesignTokens.arcoBlue6 else colors.secondaryText,
                        fontSize = 14.sp,
                        fontWeight = if (index == selectedTab) FontWeight.Medium else FontWeight.Normal,
                        modifier = Modifier
                            .clickable { selectedTab = index }
                            .padding(horizontal = 24.dp, vertical = 12.dp),
                    )
                }

                // Tab 内容区: 变量列表 + 新增输入
                val currentMap = if (selectedTab == 0) sourceVars else bookVars
                VariableTabContent(
                    variables = currentMap,
                    keyLabel = keyLabelText,
                    valueLabel = valueLabelText,
                    emptyText = emptyText,
                    addText = addText,
                )

                // 底部按钮栏
                Row(
                    Modifier
                        .fillMaxWidth()
                        .background(colors.bottomBackground)
                        .padding(horizontal = 8.dp, vertical = 4.dp),
                    horizontalArrangement = Arrangement.End,
                ) {
                    AppTextButton(text = cancelText, color = colors.secondaryText) { onDismiss() }
                    AppTextButton(text = okText, color = DesignTokens.arcoBlue6) {
                        onConfirm(
                            sourceVars.toMap(),
                            bookVars.toMap(),
                        )
                    }
                }
            }
        }
    }
}

/**
 * 单个 Tab 的变量列表内容: 已有变量 (key 只读 + value 可编辑 + 删除) + 新增行。
 */
@Composable
private fun VariableTabContent(
    variables: MutableMap<String, String>,
    keyLabel: String,
    valueLabel: String,
    emptyText: String,
    addText: String,
) {
    val colors = AppTheme.colors
    // 新增行的 key/value 输入
    var newKey by remember { mutableStateOf("") }
    var newValue by remember { mutableStateOf("") }

    Column(
        Modifier
            .fillMaxWidth()
            .heightIn(max = 400.dp)
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
    ) {
        if (variables.isEmpty()) {
            Text(
                text = emptyText,
                color = colors.secondaryText,
                fontSize = 14.sp,
                modifier = Modifier.padding(vertical = 8.dp),
            )
        } else {
            // 已有变量列表 (toList 避免遍历时并发修改)
            variables.toList().forEach { (key, value) ->
                Row(
                    Modifier
                        .fillMaxWidth()
                        .padding(vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    // key 只读展示 (对齐原 VariableDialog: 变量名由调用方指定, 用户只编辑值)
                    Text(
                        text = key,
                        color = colors.primaryText,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Medium,
                        modifier = Modifier
                            .width(100.dp)
                            .padding(end = 8.dp),
                    )
                    // value 可编辑
                    AppOutlinedTextField(
                        value = value,
                        onValueChange = { variables[key] = it },
                        modifier = Modifier
                            .weight(1f),
                        singleLine = true,
                    )
                    // 删除按钮
                    IconButton(
                        onClick = { variables.remove(key) },
                        modifier = Modifier.padding(start = 4.dp),
                    ) {
                        Icon(
                            painter = rememberPainter("ic_baseline_close"),
                            contentDescription = null,
                            tint = colors.secondaryText,
                        )
                    }
                }
            }
        }

        Spacer(Modifier.heightIn(min = 8.dp))

        // 新增变量行: key 输入 + value 输入 + 添加按钮
        Row(
            Modifier
                .fillMaxWidth()
                .padding(top = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            AppOutlinedTextField(
                value = newKey,
                onValueChange = { newKey = it },
                label = keyLabel,
                modifier = Modifier
                    .width(120.dp)
                    .padding(end = 8.dp),
                singleLine = true,
            )
            AppOutlinedTextField(
                value = newValue,
                onValueChange = { newValue = it },
                label = valueLabel,
                modifier = Modifier.weight(1f),
                singleLine = true,
            )
            IconButton(
                onClick = {
                    // key 非空才添加 (对齐原 VariableDialog 的非空校验)
                    if (newKey.isNotBlank()) {
                        variables[newKey.trim()] = newValue
                        newKey = ""
                        newValue = ""
                    }
                },
                modifier = Modifier.padding(start = 4.dp),
            ) {
                Icon(
                    painter = rememberPainter("ic_add"),
                    contentDescription = addText,
                    tint = DesignTokens.arcoBlue6,
                )
            }
        }
    }
}
