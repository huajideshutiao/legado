// I18N KEYS (新增, 待 ResourceProvider.jvm.kt 补全桌面端字面量):
// - click_regional_config: "点击区域设置"
// - select_action: "选择动作"
// - close: "关闭" (与 EffectiveReplacesScreen 复用, 已存在 jvmMain)
// - menu: "菜单"
// - next_page: "下一页"
// - prev_page: "上一页"
// - next_chapter: "下一章" (已存在 jvmMain)
// - previous_chapter: "上一章" (已存在 jvmMain)
// - read_aloud_prev_paragraph: "朗读上一段"
// - read_aloud_next_paragraph: "朗读下一段"
// - bookmark_add: "添加书签"
// - replace_state_change: "替换状态切换"
// - chapter_list: "目录" (已存在 jvmMain)
// - search_content: "全文搜索" (已存在 jvmMain)
// - read_aloud_pause_resume: "朗读暂停/继续"
//
// PAINTER KEYS (新增, 待 ResourceProvider.jvm.kt 补全桌面端图标):
// - ic_baseline_close: 关闭按钮 (已存在 jvmMain)

package io.legado.app.ui.book.read.config

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.Icon
import androidx.compose.material.Surface
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import io.legado.app.ui.compose.component.AppDialogSizes
import io.legado.app.ui.compose.component.AppSelectorDialog
import io.legado.app.ui.compose.component.appDialogSize
import io.legado.app.ui.compose.theme.AppTheme
import io.legado.app.ui.compose.theme.AppTheme.DesignTokens
import legado.shared.generated.resources.Res
import legado.shared.generated.resources.bookmark_add
import legado.shared.generated.resources.chapter_list
import legado.shared.generated.resources.click_regional_config
import legado.shared.generated.resources.close
import legado.shared.generated.resources.ic_baseline_close
import legado.shared.generated.resources.menu
import legado.shared.generated.resources.next_chapter
import legado.shared.generated.resources.next_page
import legado.shared.generated.resources.prev_page
import legado.shared.generated.resources.previous_chapter
import legado.shared.generated.resources.read_aloud_next_paragraph
import legado.shared.generated.resources.read_aloud_pause_resume
import legado.shared.generated.resources.read_aloud_prev_paragraph
import legado.shared.generated.resources.replace_state_change
import legado.shared.generated.resources.search_content
import legado.shared.generated.resources.select_action
import org.jetbrains.compose.resources.painterResource
import org.jetbrains.compose.resources.stringResource

/**
 * 点击区域动作配置 (9 个区域 × 单击动作)。
 *
 * 对齐 app 端 [io.legado.app.help.config.AppConfig] 中 9 个 clickActionXX 字段:
 * TL/TC/TR (Top-Left/Center/Right), ML/MC/MR (Middle), BL/BC/BR (Bottom)。
 * 默认值与 AppConfig cachedIntPref 默认值一致, 用于未配置时回退。
 *
 * 动作值含义见 [ClickActionDialog.actions] (0=菜单, 1=下一页, 2=上一页, ...)。
 */
data class ClickActionConfig(
    val tl: Int = 2,
    val tc: Int = 2,
    val tr: Int = 1,
    val ml: Int = 2,
    val mc: Int = 0,
    val mr: Int = 1,
    val bl: Int = 2,
    val bc: Int = 1,
    val br: Int = 1,
)

/**
 * 点击区域配置对话框 (KMP 共享, app + desktop 复用)。
 *
 * 对应 app 端 `io.legado.app.ui.book.read.config.ClickActionConfigDialog`,
 * 但去掉对 Android Fragment / Window / WindowManager / ComposeView Provider 注入的依赖,
 * 改为纯 @Composable + 回调形式:
 * - 调用方传入 [clickActionConfig] (9 个区域当前动作值), 用户点击格子弹出动作选择
 * - 用户每次切换动作, 立即通过 [onConfirm] 回传更新后的 [ClickActionConfig] (与原版
 *   `AppConfig.clickActionXX = it` 即时写入语义对齐)
 * - [onDismiss] 关闭对话框 (与原版 dismissAllowingStateLoss 对齐)
 *
 * # 原业务逻辑保留
 *
 * - 3x3 网格 9 个区域, 每格点击弹 selector 选动作 (与原版 `context.selector(...)` 对齐)
 * - 13 个可选动作 (0=菜单/1=下一页/2=上一页/3=下一章/4=上一章/5=朗读上一段/6=朗读下一段/
 *   7=添加书签/9=替换状态切换/10=目录/11=全文搜索/13=朗读暂停/继续)
 * - 顶部标题栏 + 关闭按钮 (与原版 `R.string.click_regional_config` + ic_baseline_close 对齐)
 *
 * # 样式 (Arco Design 规范)
 *
 * - 主色 arcoblue-6 (#165DFF): 当前选中动作文字色
 * - 圆角 arco_radius_lg = 16dp: Dialog Surface 与格子圆角
 * - 无阴影 (Surface 默认无阴影)
 *
 * @param clickActionConfig 9 个区域当前动作值
 * @param onConfirm 用户切换动作时回传更新后的配置 (即时写入语义, 与原版 AppConfig 写入对齐)
 * @param onDismiss 关闭回调
 */
@Composable
fun ClickActionDialog(
    clickActionConfig: ClickActionConfig,
    onConfirm: (ClickActionConfig) -> Unit,
    onDismiss: () -> Unit,
) {
    val colors = AppTheme.colors

    // 13 个可选动作 (key=动作值, value=动作名), 顺序与原版 actions linkedMapOf 完全一致
    // rememberString 是 @Composable, 不能在 remember{} 内调用, 直接每次重组时构建
    // (rememberString 内部已有缓存, 不会重复查资源)
    val actionValues = remember {
        listOf(0, 1, 2, 3, 4, 5, 6, 7, 9, 10, 11, 13)
    }
    val actionNames = listOf(
        stringResource(Res.string.menu),
        stringResource(Res.string.next_page),
        stringResource(Res.string.prev_page),
        stringResource(Res.string.next_chapter),
        stringResource(Res.string.previous_chapter),
        stringResource(Res.string.read_aloud_prev_paragraph),
        stringResource(Res.string.read_aloud_next_paragraph),
        stringResource(Res.string.bookmark_add),
        stringResource(Res.string.replace_state_change),
        stringResource(Res.string.chapter_list),
        stringResource(Res.string.search_content),
        stringResource(Res.string.read_aloud_pause_resume),
    )
    // 动作值 → 动作名映射, 用于格子显示当前动作
    val actionMap = remember(actionNames) {
        actionValues.zip(actionNames).toMap()
    }

    // 9 个区域当前值 (mutableIntStateOf 用于 Compose 重组)
    val states = remember(clickActionConfig) {
        listOf(
            mutableIntStateOf(clickActionConfig.tl),
            mutableIntStateOf(clickActionConfig.tr),
            mutableIntStateOf(clickActionConfig.tc),
            mutableIntStateOf(clickActionConfig.ml),
            mutableIntStateOf(clickActionConfig.mc),
            mutableIntStateOf(clickActionConfig.mr),
            mutableIntStateOf(clickActionConfig.bl),
            mutableIntStateOf(clickActionConfig.bc),
            mutableIntStateOf(clickActionConfig.br),
        )
    }

    // 当前展开 selector 的格子索引 (null=未展开), 与原版 context.selector 单弹窗语义对齐
    var selectorTargetIndex by remember { mutableStateOf<Int?>(null) }

    Dialog(
        onDismissRequest = onDismiss,
        properties = AppDialogSizes.properties(),
    ) {
        Surface(
            shape = DesignTokens.dialogShape,
            color = colors.background,
            modifier = Modifier.appDialogSize().padding(16.dp),
        ) {
            Column(Modifier.fillMaxWidth()) {
                // 顶部标题栏: 标题 + 关闭按钮 (与原版 R.string.click_regional_config + ic_baseline_close 对齐)
                Row(
                    Modifier
                        .fillMaxWidth()
                        .background(colors.bottomBackground)
                        .padding(horizontal = 16.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = stringResource(Res.string.click_regional_config),
                        color = colors.primaryText,
                        fontSize = 18.sp,
                        fontWeight = FontWeight.Medium,
                        modifier = Modifier.weight(1f),
                    )
                    Icon(
                        painter = painterResource(Res.drawable.ic_baseline_close),
                        contentDescription = stringResource(Res.string.close),
                        tint = colors.primaryText,
                        modifier = Modifier
                            .clickable(onClick = onDismiss)
                            .padding(4.dp),
                    )
                }

                // 3x3 网格 9 个区域, 与原版 repeat(3) { Row { repeat(3) { ... } } } 对齐
                // 行顺序: Top / Middle / Bottom; 列顺序: Left / Center / Right
                val rowOrders = listOf(
                    listOf(0, 1, 2), // TL, TC, TR
                    listOf(3, 4, 5), // ML, MC, MR
                    listOf(6, 7, 8), // BL, BC, BR
                )
                Column(
                    Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    rowOrders.forEach { rowIndices ->
                        Row(
                            Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            rowIndices.forEach { index ->
                                val state = states[index]
                                val value by state
                                Box(
                                    Modifier
                                        .weight(1f)
                                        .aspectRatio(1f)
                                        .background(
                                            color = colors.bottomBackground,
                                            shape = DesignTokens.shapeDefault,
                                        )
                                        .clickable {
                                            // 点击格子弹出动作选择 (与原版 context.selector 对齐)
                                            selectorTargetIndex = index
                                        },
                                    contentAlignment = Alignment.Center,
                                ) {
                                    Text(
                                        text = actionMap[value].orEmpty(),
                                        color = DesignTokens.arcoBlue6,
                                        fontSize = 14.sp,
                                        textAlign = TextAlign.Center,
                                        fontWeight = FontWeight.Medium,
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    // 动作选择 selector (与原版 context.selector 单弹窗对齐)
    val targetIndex = selectorTargetIndex
    if (targetIndex != null) {
        AppSelectorDialog(
            onDismissRequest = { selectorTargetIndex = null },
            title = stringResource(Res.string.select_action),
            items = actionNames,
            onItemSelected = { i ->
                val newValue = actionValues[i]
                states[targetIndex].intValue = newValue
                // 即时回传更新后的配置 (与原版 AppConfig.clickActionXX = it + onDestroy detectClickArea 对齐)
                onConfirm(
                    ClickActionConfig(
                        tl = states[0].intValue,
                        tc = states[1].intValue,
                        tr = states[2].intValue,
                        ml = states[3].intValue,
                        mc = states[4].intValue,
                        mr = states[5].intValue,
                        bl = states[6].intValue,
                        bc = states[7].intValue,
                        br = states[8].intValue,
                    )
                )
            },
        )
    }
}
