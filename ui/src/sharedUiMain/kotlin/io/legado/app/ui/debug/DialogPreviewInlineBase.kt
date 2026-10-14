package io.legado.app.ui.debug

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.items
import androidx.compose.material.Icon
import androidx.compose.material.IconButton
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import io.legado.app.ui.compose.component.AppAutoCompleteField
import io.legado.app.ui.compose.component.AppCheckbox
import io.legado.app.ui.compose.component.AppChipRow
import io.legado.app.ui.compose.component.AppDialog
import io.legado.app.ui.compose.component.AppDialogScaffold
import io.legado.app.ui.compose.component.AppDialogSurface
import io.legado.app.ui.compose.component.AppDropdownField
import io.legado.app.ui.compose.component.AppNumberField
import io.legado.app.ui.compose.component.AppRadioButton
import io.legado.app.ui.compose.component.AppSearchField
import io.legado.app.ui.compose.component.AppSlider
import io.legado.app.ui.compose.component.AppSwitch
import io.legado.app.ui.compose.component.AppTextButton
import io.legado.app.ui.compose.component.AppTextField
import io.legado.app.ui.compose.component.AppTitleBar
import io.legado.app.ui.compose.component.DialogTitleBar
import io.legado.app.ui.compose.component.FastScrollLazyColumn
import io.legado.app.ui.compose.component.OverflowMenu
import io.legado.app.ui.compose.component.appDialogSize
import io.legado.app.ui.compose.theme.AppTheme
import io.legado.app.ui.compose.theme.AppTheme.DesignTokens
import legado.ui.generated.resources.Res
import legado.ui.generated.resources.ic_add
import org.jetbrains.compose.resources.painterResource

/**
 * 基础形制预览清单: 对话框壳体与基础控件。
 *
 * 改对话框外观时最常动的是这些壳体与控件, 故单列一组; 每一条都是一个真实壳体的最小实例,
 * 不含业务语义。
 *
 * 这些条目的弹窗标题本身是占位文字 (标题/对话框壳/底板形制…), 故名字用用途说明写死。
 */
internal fun buildBaseEntries(): List<DialogPreviewEntry.Inline> = listOf(
    DialogPreviewEntry.Inline(
        DialogPreviewGroup.BASE, "base_scaffold", nameText("对话框壳 (自适应高)"), "AppDialogScaffold",
    ) { onDismiss -> ScaffoldSample(onDismiss, fullHeight = false) },
    DialogPreviewEntry.Inline(
        DialogPreviewGroup.BASE, "base_scaffold_full", nameText("对话框壳 (全高列表)"), "AppDialogScaffold",
    ) { onDismiss -> ScaffoldSample(onDismiss, fullHeight = true) },
    DialogPreviewEntry.Inline(
        DialogPreviewGroup.BASE, "base_surface", nameText("对话框底板 (AppDialogSurface)"), "AppDialogSurface",
    ) { onDismiss -> SurfaceSample(onDismiss) },
    DialogPreviewEntry.Inline(
        DialogPreviewGroup.BASE, "base_controls", nameText("基础控件陈列"), "AppTextField 等基础控件",
    ) { onDismiss -> ControlsSample(onDismiss) },
    DialogPreviewEntry.Inline(
        DialogPreviewGroup.BASE, "base_title_bar", nameText("顶栏 (AppTitleBar + 搜索框 + 溢出菜单)"), "AppTitleBar",
    ) { onDismiss -> TitleBarSample(onDismiss) },
)

/** 标准三槽壳体: 顶栏 + 正文 + 底栏。 */
@Composable
private fun ScaffoldSample(onDismiss: () -> Unit, fullHeight: Boolean) {
    var switchOn by remember { mutableStateOf(true) }
    var text by remember { mutableStateOf("") }
    AppDialogScaffold(
        onDismissRequest = onDismiss,
        title = "对话框壳",
        subtitle = "顶栏 + 正文 + 底栏",
        fullHeight = fullHeight,
        titleActions = {
            IconButton(onClick = {}) {
                Icon(painterResource(Res.drawable.ic_add), contentDescription = null, tint = AppTheme.colors.accent)
            }
        },
        bottomBar = {
            AppTextButton(text = "取消") { onDismiss() }
            AppTextButton(text = "确定") { onDismiss() }
        },
    ) {
        if (fullHeight) {
            // 长内容必须自带滚动: 壳只给正文一个 weight, 底栏才能恒可见
            FastScrollLazyColumn(
                modifier = Modifier.fillMaxWidth(),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(0.dp),
            ) {
                items((1..30).toList()) { index ->
                    Text(
                        text = "列表项 $index",
                        color = AppTheme.colors.primaryText,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = DesignTokens.spacingDefault),
                    )
                }
            }
        } else {
            AppTextField(
                value = text,
                onValueChange = { text = it },
                label = "输入框",
                singleLine = true,
            )
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = DesignTokens.spacingDefault),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("开关", color = AppTheme.colors.primaryText)
                AppSwitch(checked = switchOn, onCheckedChange = { switchOn = it })
            }
        }
    }
}

/** 仅底板: 标题栏 + 自排正文 (无标准底栏的弹窗形制)。 */
@Composable
private fun SurfaceSample(onDismiss: () -> Unit) {
    AppDialog(onDismissRequest = onDismiss) {
        AppDialogSurface(
            modifier = Modifier.appDialogSize(),
        ) {
            Column(Modifier.fillMaxWidth()) {
                DialogTitleBar(title = "底板形制", onBack = onDismiss)
                Column(Modifier.padding(DesignTokens.spacingDefault)) {
                    Text(
                        text = "正文槽 (无标准底栏, 按钮由正文自排)",
                        color = AppTheme.colors.primaryText,
                    )
                }
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(DesignTokens.spacingDefault),
                    horizontalArrangement = Arrangement.End,
                ) {
                    AppTextButton(text = "关闭") { onDismiss() }
                }
            }
        }
    }
}

/** 基础控件陈列: 改控件外观时对照用。 */
@Composable
private fun ControlsSample(onDismiss: () -> Unit) {
    var text by remember { mutableStateOf("文本内容") }
    var number by remember { mutableStateOf("12") }
    var autoComplete by remember { mutableStateOf("") }
    var dropdownIndex by remember { mutableIntStateOf(0) }
    var switchOn by remember { mutableStateOf(true) }
    var checkOn by remember { mutableStateOf(true) }
    var radioOn by remember { mutableStateOf(true) }
    var sliderValue by remember { mutableIntStateOf(30) }

    AppDialogScaffold(
        onDismissRequest = onDismiss,
        title = "基础控件",
        fullHeight = true,
        bottomBar = {
            AppTextButton(text = "关闭") { onDismiss() }
        },
    ) {
        AppTextField(value = text, onValueChange = { text = it }, label = "单行文本", singleLine = true)
        AppTextField(
            value = text,
            onValueChange = { text = it },
            label = "多行文本",
            maxLines = 3,
            modifier = Modifier.padding(top = DesignTokens.spacingDefault),
        )
        AppNumberField(
            value = number,
            onValueChange = { number = it },
            label = "数字",
            modifier = Modifier.padding(top = DesignTokens.spacingDefault),
        )
        AppAutoCompleteField(
            value = autoComplete,
            onValueChange = { autoComplete = it },
            label = "补全文本",
            values = listOf("补全候选一", "补全候选二"),
            modifier = Modifier.padding(top = DesignTokens.spacingDefault),
        )
        AppDropdownField(
            options = listOf("选项一", "选项二", "选项三"),
            selectedIndex = dropdownIndex,
            onSelect = { dropdownIndex = it },
            label = "下拉",
            modifier = Modifier.padding(top = DesignTokens.spacingDefault),
        )
        AppSlider(
            value = sliderValue,
            max = 100,
            onValueChange = { sliderValue = it },
            modifier = Modifier.padding(top = DesignTokens.spacingDefault),
        )
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = DesignTokens.spacingDefault),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("开关", color = AppTheme.colors.primaryText)
            AppSwitch(checked = switchOn, onCheckedChange = { switchOn = it })
            Text("复选", color = AppTheme.colors.primaryText)
            AppCheckbox(checked = checkOn, onCheckedChange = { checkOn = it })
            Text("单选", color = AppTheme.colors.primaryText)
            AppRadioButton(selected = radioOn, onClick = { radioOn = !radioOn })
        }
        AppChipRow(modifier = Modifier.padding(top = DesignTokens.spacingDefault)) {
            listOf("标签一", "标签二", "标签三").forEach { label ->
                Text(
                    text = label,
                    color = AppTheme.colors.accent,
                    modifier = Modifier.padding(end = DesignTokens.spacingDefault),
                )
            }
        }
    }
}

/** 页面级顶栏形制: 返回 + 标题 + 搜索框 + 溢出菜单。 */
@Composable
private fun TitleBarSample(onDismiss: () -> Unit) {
    var searchKey by remember { mutableStateOf("") }
    AppDialog(onDismissRequest = onDismiss) {
        AppDialogSurface(modifier = Modifier.appDialogSize()) {
            Column(Modifier.fillMaxWidth()) {
                AppTitleBar(
                    title = "页面顶栏",
                    subtitle = "副标题",
                    onBack = onDismiss,
                    actions = {
                        OverflowMenu { dismiss ->
                            Text(
                                text = "菜单项",
                                color = AppTheme.colors.menuText,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(DesignTokens.spacingDefault),
                            )
                            AppTextButton(text = "关闭菜单") { dismiss() }
                        }
                    },
                )
                AppSearchField(
                    value = searchKey,
                    onValueChange = { searchKey = it },
                    hint = "搜索",
                    modifier = Modifier.padding(DesignTokens.spacingDefault),
                )
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(DesignTokens.spacingDefault),
                    horizontalArrangement = Arrangement.End,
                ) {
                    AppTextButton(text = "关闭") { onDismiss() }
                }
            }
        }
    }
}
