package io.legado.app.ui.main.home

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.Icon
import androidx.compose.material.IconButton
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.legado.app.constant.EventBus
import io.legado.app.help.HomeTabHelpShared
import io.legado.app.ui.compose.component.AppDialogScaffold
import io.legado.app.ui.compose.component.AppDialogSizes
import io.legado.app.ui.compose.component.RuleManageScaffold
import io.legado.app.ui.compose.theme.AppTheme
import io.legado.app.ui.compose.theme.AppTheme.DesignTokens
import io.legado.app.utils.FlowBus
import io.legado.app.utils.postEvent
import legado.ui.generated.resources.Res
import legado.ui.generated.resources.edit
import legado.ui.generated.resources.home_tab_add
import legado.ui.generated.resources.home_tab_manage
import legado.ui.generated.resources.home_tab_section_count
import legado.ui.generated.resources.ic_add
import legado.ui.generated.resources.ic_baseline_sort_24
import legado.ui.generated.resources.sort
import org.jetbrains.compose.resources.painterResource
import org.jetbrains.compose.resources.stringResource
import sh.calvin.reorderable.ReorderableCollectionItemScope

/**
 * 主页分组管理：item 点击进入该分组的展示项管理；编辑按钮改标题/删除；+ 添加分组；拖动排序。
 * 下沉自 app 端同名 DialogFragment (原用 dialog_recycler_view + HomeTabManageAdapter)。
 *
 * 顶栏进标准壳体 ([AppDialogScaffold] 的 title/titleActions), 列表骨架 [RuleManageScaffold]
 * 只承载列表本身, 无底栏。
 */
@Composable
fun HomeTabManageDialog(onDismiss: () -> Unit) {
    val colors = AppTheme.colors
    var tabs by remember { mutableStateOf(HomeTabHelpShared.getTabs()) }
    // 点 item 进入该分组的展示项管理 (原 showDialogFragment(HomeSectionManageDialog))
    var openSectionsOf by remember { mutableStateOf<String?>(null) }
    // 编辑/新增分组 (原 showHomeTabEditDialog); addingTab 与 editingTab 互斥
    var editingTab by remember { mutableStateOf<String?>(null) }
    var addingTab by remember { mutableStateOf(false) }

    fun upData() {
        tabs = HomeTabHelpShared.getTabs()
    }

    // 对照原版 observeEvent<HomeTabEvent>: REORDER 以外的变更都重拉列表
    LaunchedEffect(Unit) {
        FlowBus.with(EventBus.HOME_TAB).collect { event ->
            if (event is HomeTabEvent && event.action != HomeTabEvent.REORDER) upData()
        }
    }

    // 全高型: 顶栏 (标题 + 新增) 进标准壳体, 列表骨架只承载列表, 无底栏
    AppDialogScaffold(
        onDismissRequest = onDismiss,
        title = stringResource(Res.string.home_tab_manage),
        titleActions = {
            IconButton(onClick = { addingTab = true }) {
                Icon(
                    painter = painterResource(Res.drawable.ic_add),
                    contentDescription = stringResource(Res.string.home_tab_add),
                    tint = colors.primaryText,
                )
            }
        },
        properties = AppDialogSizes.properties(),
        contentPadding = PaddingValues(0.dp),
        // 原 appDialogSize(fullHeight = true): 高度锁定 0.8 屏高
        fullHeight = true,
    ) {
        RuleManageScaffold(
            items = tabs,
            itemKey = { it.title },
            onMove = { from, to ->
                tabs = tabs.toMutableList().apply { add(to, removeAt(from)) }
            },
        ) { item ->
            HomeManageItem(
                title = item.title,
                desc = stringResource(
                    Res.string.home_tab_section_count,
                    item.sections.size,
                ),
                actionText = stringResource(Res.string.edit),
                onClick = { openSectionsOf = item.title },
                onAction = { editingTab = item.title },
                onPersistOrder = {
                    HomeTabHelpShared.saveTabsOrder(tabs)
                    postEvent(EventBus.HOME_TAB, HomeTabEvent(HomeTabEvent.REORDER))
                },
            )
        }
    }

    openSectionsOf?.let { tabTitle ->
        HomeSectionManageDialog(tabTitle = tabTitle, onDismiss = { openSectionsOf = null })
    }

    if (addingTab || editingTab != null) {
        HomeTabEditDialog(
            oldTitle = editingTab,
            onDismiss = {
                addingTab = false
                editingTab = null
            },
        )
    }
}

/**
 * 管理列表单项 (对照 item_home_manage.xml)：拖拽把手 + 标题/描述两行 + 右侧文本操作按钮。
 * 分组管理里操作按钮是"编辑", 展示项管理里是"删除" (原版两个 Adapter 共用该布局)。
 */
@Composable
internal fun ReorderableCollectionItemScope.HomeManageItem(
    title: String,
    desc: String,
    actionText: String,
    onClick: () -> Unit,
    onAction: () -> Unit,
    onPersistOrder: () -> Unit,
) {
    val colors = AppTheme.colors
    Row(
        Modifier
            .fillMaxWidth()
            .longPressDraggableHandle(onDragStopped = onPersistOrder)
            .clickable(onClick = onClick)
            .padding(DesignTokens.spacingDefault),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            painter = painterResource(Res.drawable.ic_baseline_sort_24),
            contentDescription = stringResource(Res.string.sort),
            tint = colors.secondaryText,
            modifier = Modifier.size(24.dp),
        )
        Spacer(Modifier.width(DesignTokens.spacingMd))
        Column(Modifier.weight(1f)) {
            Text(
                text = title,
                color = colors.primaryText,
                fontSize = 15.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = desc,
                color = colors.secondaryText,
                fontSize = 12.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = 2.dp),
            )
        }
        Text(
            text = actionText,
            color = colors.accent,
            fontSize = 14.sp,
            modifier = Modifier
                .clickable(onClick = onAction)
                .padding(DesignTokens.spacingDefault),
        )
    }
}
