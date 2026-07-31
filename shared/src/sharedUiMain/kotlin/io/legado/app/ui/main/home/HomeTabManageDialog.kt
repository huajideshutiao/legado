package io.legado.app.ui.main.home

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material.Icon
import androidx.compose.material.IconButton
import androidx.compose.material.Surface
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
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import io.legado.app.constant.EventBus
import io.legado.app.help.HomeTabHelpShared
import io.legado.app.ui.compose.component.DialogTitleBar
import io.legado.app.ui.compose.component.RuleManageScaffold
import io.legado.app.ui.compose.reorderable.RuleItemScope
import io.legado.app.ui.compose.theme.AppTheme
import io.legado.app.ui.compose.theme.AppTheme.DesignTokens
import io.legado.app.utils.FlowBus
import io.legado.app.utils.postEvent
import legado.shared.generated.resources.Res
import legado.shared.generated.resources.edit
import legado.shared.generated.resources.home_tab_add
import legado.shared.generated.resources.home_tab_manage
import legado.shared.generated.resources.home_tab_section_count
import legado.shared.generated.resources.ic_add
import legado.shared.generated.resources.ic_baseline_sort_24
import legado.shared.generated.resources.sort
import org.jetbrains.compose.resources.painterResource
import org.jetbrains.compose.resources.stringResource

/**
 * 主页分组管理：item 点击进入该分组的展示项管理；编辑按钮改标题/删除；+ 添加分组；拖动排序。
 * 下沉自 app 端同名 DialogFragment (原用 dialog_recycler_view + HomeTabManageAdapter)。
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

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        BoxWithConstraints(Modifier.fillMaxSize()) {
            // isFullHeight=true → 高度锁定 0.8 屏高 (对照 BaseDialogFragment.onStart)
            Surface(
                modifier = Modifier
                    .fillMaxWidth(0.9f)
                    .widthIn(max = 800.dp)
                    .heightIn(min = maxHeight * 0.8f, max = maxHeight * 0.8f)
                    .align(Alignment.Center),
                shape = DesignTokens.shapeDefault,
                color = colors.fillet,
            ) {
                RuleManageScaffold(
                    items = tabs,
                    itemKey = { it.title },
                    onMove = { from, to ->
                        tabs = tabs.toMutableList().apply { add(to, removeAt(from)) }
                    },
                    titleBar = {
                        DialogTitleBar(
                            title = stringResource(Res.string.home_tab_manage),
                            onBack = onDismiss,
                            actions = {
                                IconButton(onClick = { addingTab = true }) {
                                    Icon(
                                        painter = painterResource(Res.drawable.ic_add),
                                        contentDescription = stringResource(Res.string.home_tab_add),
                                        tint = colors.primaryText,
                                    )
                                }
                            },
                        )
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
internal fun RuleItemScope.HomeManageItem(
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
            .padding(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            painter = painterResource(Res.drawable.ic_baseline_sort_24),
            contentDescription = stringResource(Res.string.sort),
            tint = colors.secondaryText,
            modifier = Modifier.size(24.dp),
        )
        Spacer(Modifier.width(12.dp))
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
                .padding(8.dp),
        )
    }
}
