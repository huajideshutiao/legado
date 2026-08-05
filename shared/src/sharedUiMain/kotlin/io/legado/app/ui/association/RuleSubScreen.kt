package io.legado.app.ui.association

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material.DropdownMenuItem
import androidx.compose.material.Icon
import androidx.compose.material.IconButton
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
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.legado.app.data.entities.RuleSub
import io.legado.app.ui.compose.component.AppDropdownMenu
import io.legado.app.ui.compose.component.AppTitleBar
import io.legado.app.ui.compose.component.RuleManageScaffold
import io.legado.app.ui.compose.platform.rememberPainter
import io.legado.app.ui.compose.reorderable.RuleItemScope
import io.legado.app.ui.compose.theme.AppTheme
import io.legado.app.ui.compose.theme.AppTheme.DesignTokens
import legado.shared.generated.resources.Res
import legado.shared.generated.resources.add
import legado.shared.generated.resources.book_source
import legado.shared.generated.resources.delete
import legado.shared.generated.resources.dict_rule
import legado.shared.generated.resources.edit
import legado.shared.generated.resources.ic_add
import legado.shared.generated.resources.ic_edit
import legado.shared.generated.resources.ic_more_vert
import legado.shared.generated.resources.more_menu
import legado.shared.generated.resources.replace_rule
import legado.shared.generated.resources.rss_source
import legado.shared.generated.resources.rule_sub_empty_msg
import legado.shared.generated.resources.rule_subscription
import legado.shared.generated.resources.to_bottom
import legado.shared.generated.resources.to_top
import legado.shared.generated.resources.tts
import legado.shared.generated.resources.txt_toc_rule
import org.jetbrains.compose.resources.getString
import org.jetbrains.compose.resources.painterResource
import org.jetbrains.compose.resources.stringResource
import androidx.compose.ui.tooling.preview.Preview
import io.legado.app.ui.preview.LegadoThemePreview

// ===== state / actions =====

/**
 * 规则订阅页展示状态 (immutable)。
 *
 * 宿主端 (app 端 [RuleSubActivity] / 桌面端) 持有 `mutableStateOf<RuleSubUiState>`,
 * 数据更新时 copy 出新实例以触发 Compose 重组。
 *
 * @param ruleSubs 当前订阅规则列表 (已按 customOrder 排序)
 */
data class RuleSubUiState(
    val ruleSubs: List<RuleSub> = emptyList(),
)

/**
 * 规则订阅页事件回调集合。
 *
 * app 端 [RuleSubActivity] 实现本接口, 平台相关依赖 (appDb / showDialogFragment /
 * `alert` 编辑弹窗 / `toastOnUi` 等) 在回调内桥接, shared 端不直接持有 Android 依赖。
 *
 * - [onBack]: 返回 (finish)
 * - [onAdd]: 点击标题栏 + 按钮, 弹出新增订阅编辑对话框
 * - [onEdit]: 点击列表项编辑按钮, 弹出编辑对话框 (回填 ruleSub 字段)
 * - [onOpenSubscription]: 点击列表项主体, 根据 ruleSub.type 打开对应导入对话框 (依赖 showDialogFragment)
 * - [onMove]: 拖拽换位即时更新内存列表 (不落库)
 * - [onPersistOrder]: 拖拽松手后将当前列表顺序落库 (调 viewModel.upOrder)
 * - [onToTop] / [onToBottom]: 单项置顶/置底 (调 viewModel.toTop/toBottom)
 * - [onDelete]: 单项删除 (含确认弹窗, 调 viewModel.delete)
 */
interface RuleSubUiActions {
    fun onBack()
    fun onAdd()
    fun onEdit(ruleSub: RuleSub)
    fun onOpenSubscription(ruleSub: RuleSub)
    fun onMove(from: Int, to: Int)
    fun onPersistOrder()
    fun onToTop(ruleSub: RuleSub)
    fun onToBottom(ruleSub: RuleSub)
    fun onDelete(ruleSub: RuleSub)
}

// ===== RuleSubScreen =====

/**
 * 规则订阅管理 Screen (KMP 版, 替代 app 端 `RuleSubActivity.Content` 下沉)。
 *
 * 三段式 API:
 * - [RuleSubUiState]: 不可变展示状态 (订阅列表), 由宿主端 (Activity/桌面) 持有 mutableStateOf 并 copy
 * - [RuleSubUiActions]: 交互回调集合 (返回/新增/编辑/打开订阅/拖拽/置顶置底/删除), 宿主端实现接口
 * - [RuleSubScreen]: 纯 Composable 渲染入口, 仅依赖 state + actions
 *
 * 下沉改动:
 * - 字符串资源 `stringResource(R.string.xxx)` → `stringResource(Res.string.xxx)` (key-based, 跨平台)
 * - 图标资源 `painterResource(R.drawable.xxx)` → `rememberPainter("xxx")` (key-based, 跨平台)
 * - 平台依赖 (appDb / showDialogFragment / alert 编辑弹窗) 通过 [RuleSubUiActions] 回调桥接,
 *   编辑弹窗 (`showEditDialog`) 与订阅打开 (`openSubscription`) 仍由 app 端 Activity 持有
 * - 复用 shared/compose/component 的 [RuleManageScaffold] 骨架 + [AppTitleBar] / [AppDropdownMenu]
 *
 * 注: 类型名称 (typeName) 在 shared 端用 [rememberString] 按 type 映射到对应字符串 key,
 * 与 app 端 Activity 的 `typeName(type)` (走 getString) 行为对齐。
 */
@Composable
fun RuleSubScreen(
    state: RuleSubUiState,
    actions: RuleSubUiActions,
    modifier: Modifier = Modifier,
) {
    RuleManageScaffold(
        items = state.ruleSubs,
        itemKey = { it.id },
        onMove = { from, to -> actions.onMove(from, to) },
        modifier = modifier,
        emptyText = stringResource(Res.string.rule_sub_empty_msg),
        titleBar = {
            AppTitleBar(
                title = stringResource(Res.string.rule_subscription),
                onBack = { actions.onBack() },
                actions = {
                    IconButton(onClick = { actions.onAdd() }) {
                        Icon(
                            painter = painterResource(Res.drawable.ic_add),
                            contentDescription = stringResource(Res.string.add),
                            tint = AppTheme.colors.primaryText,
                        )
                    }
                },
            )
        },
    ) { item ->
        RuleSubItem(
            item = item,
            onEdit = { actions.onEdit(item) },
            onOpen = { actions.onOpenSubscription(item) },
            onToTop = { actions.onToTop(item) },
            onToBottom = { actions.onToBottom(item) },
            onDelete = { actions.onDelete(item) },
            onPersistOrder = { actions.onPersistOrder() },
        )
    }
}

/**
 * 单条订阅项: 名称 + 类型徽标 + url + 编辑按钮 + 更多菜单 (置顶/置底/删除)。
 * 长按空白把手区触发拖拽排序 (longPressDraggableHandle 来自 reorderable 库)。
 *
 * 注: 对照原 `RuleSubActivity.RuleSubItem`, 宽高/边距/字号/圆角/颜色逻辑全部保持一致,
 * 仅将 viewModel 直调 / showEditDialog / openSubscription / delete 替换为外部回调。
 */
@Composable
private fun RuleItemScope.RuleSubItem(
    item: RuleSub,
    onEdit: () -> Unit,
    onOpen: () -> Unit,
    onToTop: () -> Unit,
    onToBottom: () -> Unit,
    onDelete: () -> Unit,
    onPersistOrder: () -> Unit,
) {
    val colors = AppTheme.colors
    var showMenu by remember { mutableStateOf(false) }
    Row(
        Modifier
            .fillMaxWidth()
            .longPressDraggableHandle(onDragStopped = { onPersistOrder() })
            .clickable { onOpen() }
            .padding(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                text = item.name,
                color = colors.primaryText,
                fontSize = 16.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Row(
                Modifier.padding(top = 2.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                TypeBadge(typeName(item.type))
                Spacer(Modifier.width(4.dp))
                Text(
                    text = item.url,
                    color = colors.secondaryText,
                    fontSize = 12.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
            }
        }
        IconButton(onClick = onEdit) {
            Icon(
                painter = painterResource(Res.drawable.ic_edit),
                contentDescription = stringResource(Res.string.edit),
                tint = colors.primaryText,
            )
        }
        Box {
            IconButton(onClick = { showMenu = true }) {
                Icon(
                    painter = painterResource(Res.drawable.ic_more_vert),
                    contentDescription = stringResource(Res.string.more_menu),
                    tint = colors.primaryText,
                )
            }
            AppDropdownMenu(expanded = showMenu, onDismissRequest = { showMenu = false }) {
                DropdownMenuItem(
                    onClick = { showMenu = false; onToTop() },
                ) {
                    Text(stringResource(Res.string.to_top), color = colors.primaryText)
                }
                DropdownMenuItem(
                    onClick = { showMenu = false; onToBottom() },
                ) {
                    Text(stringResource(Res.string.to_bottom), color = colors.primaryText)
                }
                DropdownMenuItem(
                    onClick = { showMenu = false; onDelete() },
                ) {
                    Text(stringResource(Res.string.delete), color = colors.primaryText)
                }
            }
        }
    }
}

// 对齐 AccentBgTextView：accent 底色 + 按亮度取黑/白字
@Composable
private fun TypeBadge(text: String) {
    val accent = AppTheme.colors.accent
    Text(
        text = text,
        color = if (accent.luminance() > 0.5f) Color.Black else Color.White,
        fontSize = 12.sp,
        modifier = Modifier
            .clip(DesignTokens.shapeSm)
            .background(accent)
            .padding(horizontal = 4.dp, vertical = 1.dp),
    )
}

/**
 * 订阅类型 → 显示名称: 0/1 书源 / 1 RSS源 / 2 替换规则 / 3 TXT目录规则 / 4 字典规则 / 5 TTS。
 *
 * 对照原 `RuleSubActivity.typeName(type)`, 仅将 `getString(R.string.xxx)` 替换为
 * [rememberString] ("xxx"), 跨平台按 key 命中 app 端 R.string。
 */
@Composable
private fun typeName(type: Int): String = when (type) {
    1 -> stringResource(Res.string.rss_source)
    2 -> stringResource(Res.string.replace_rule)
    3 -> stringResource(Res.string.txt_toc_rule)
    4 -> stringResource(Res.string.dict_rule)
    5 -> stringResource(Res.string.tts)
    else -> stringResource(Res.string.book_source)
}

// ===== @Preview 合并自 androidMain 的 association/AssociationPreviews.kt (RuleSubScreen) =====

// ===== RuleSubScreen =====

/**
 * association 模块下 [RuleSubScreen] 与 [ImportListScaffold] 的 @Preview。
 *
 * 假数据: [RuleSub] / 引擎列表用纯内存对象构造, 不依赖 DB / 网络。
 * RuleSubUiActions 用 noop 实现 (Preview 期不触发实际跳转)。
 */

private val previewRuleSubs = listOf(
    RuleSub(
        id = 1L,
        name = "起点书源订阅",
        url = "https://example.com/source1.json",
        type = 0,
        customOrder = 0,
    ),
    RuleSub(
        id = 2L,
        name = "RSS 源订阅",
        url = "https://example.com/rss.json",
        type = 1,
        customOrder = 1,
    ),
    RuleSub(
        id = 3L,
        name = "替换规则订阅",
        url = "https://example.com/replace.json",
        type = 2,
        customOrder = 2,
    ),
)

/** Preview 期 noop [RuleSubUiActions], 所有回调空实现。 */
private object NoopRuleSubActions : RuleSubUiActions {
    override fun onBack() {}
    override fun onAdd() {}
    override fun onEdit(ruleSub: RuleSub) {}
    override fun onOpenSubscription(ruleSub: RuleSub) {}
    override fun onMove(from: Int, to: Int) {}
    override fun onPersistOrder() {}
    override fun onToTop(ruleSub: RuleSub) {}
    override fun onToBottom(ruleSub: RuleSub) {}
    override fun onDelete(ruleSub: RuleSub) {}
}

@Preview
@Composable
fun RuleSubScreenPreview() = LegadoThemePreview {
    RuleSubScreen(
        state = RuleSubUiState(ruleSubs = previewRuleSubs),
        actions = NoopRuleSubActions,
    )
}

@Preview
@Composable
fun RuleSubScreenEmptyPreview() = LegadoThemePreview {
    RuleSubScreen(
        state = RuleSubUiState(ruleSubs = emptyList()),
        actions = NoopRuleSubActions,
    )
}

@Preview
@Composable
fun RuleSubScreenDarkPreview() = LegadoThemePreview(dark = true) {
    RuleSubScreen(
        state = RuleSubUiState(ruleSubs = previewRuleSubs),
        actions = NoopRuleSubActions,
    )
}

