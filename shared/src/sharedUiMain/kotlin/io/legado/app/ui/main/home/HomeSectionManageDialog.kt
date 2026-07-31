package io.legado.app.ui.main.home

import androidx.compose.material.Icon
import androidx.compose.material.IconButton
import androidx.compose.material.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import io.legado.app.constant.EventBus
import io.legado.app.data.entities.HomeSection
import io.legado.app.help.HomeTabHelpShared
import io.legado.app.ui.compose.component.AppDialog
import io.legado.app.ui.compose.component.AppDialogSizes
import io.legado.app.ui.compose.component.DialogTitleBar
import io.legado.app.ui.compose.component.RuleManageScaffold
import io.legado.app.ui.compose.component.appDialogSize
import io.legado.app.ui.compose.theme.AppTheme
import io.legado.app.ui.compose.theme.AppTheme.DesignTokens
import io.legado.app.utils.FlowBus
import io.legado.app.utils.postEvent
import legado.shared.generated.resources.Res
import legado.shared.generated.resources.delete
import legado.shared.generated.resources.home_add_section
import legado.shared.generated.resources.home_manage_empty
import legado.shared.generated.resources.home_manage_for_tab
import legado.shared.generated.resources.home_style_cover_row
import legado.shared.generated.resources.home_style_four_row
import legado.shared.generated.resources.home_style_infinite_grid
import legado.shared.generated.resources.home_style_rank_list
import legado.shared.generated.resources.ic_add
import org.jetbrains.compose.resources.painterResource
import org.jetbrains.compose.resources.stringResource

/**
 * 管理某个分组（tabTitle）下的展示项。列表、拖序、增删全部限定在该 tab 内。
 * 下沉自 app 端同名 DialogFragment (原用 dialog_recycler_view + HomeSectionManageAdapter)。
 */
@Composable
fun HomeSectionManageDialog(
    tabTitle: String,
    onDismiss: () -> Unit,
) {
    val colors = AppTheme.colors
    var sections by remember(tabTitle) {
        mutableStateOf(HomeTabHelpShared.getSections(tabTitle))
    }
    // 点 item 编辑该展示项; addingSection 表示新增 (原 HomeSectionEditDialog.newInstance)
    var editingSection by remember { mutableStateOf<HomeSection?>(null) }
    var addingSection by remember { mutableStateOf(false) }

    fun upData() {
        sections = HomeTabHelpShared.getSections(tabTitle)
    }

    // 对照原版 observeEvent<HomeSectionEvent>: 同 tab 且非 REORDER 的变更重拉列表
    LaunchedEffect(tabTitle) {
        FlowBus.with(EventBus.HOME_SECTION).collect { event ->
            if (event is HomeSectionEvent &&
                event.tabTitle == tabTitle &&
                event.action != HomeSectionEvent.REORDER
            ) {
                upData()
            }
        }
    }

    val styleNames = listOf(
        stringResource(Res.string.home_style_cover_row),
        stringResource(Res.string.home_style_rank_list),
        stringResource(Res.string.home_style_infinite_grid),
        stringResource(Res.string.home_style_four_row),
    )
    val deleteText = stringResource(Res.string.delete)

    AppDialog(
        onDismissRequest = onDismiss,
        properties = AppDialogSizes.properties(),
    ) {
        // 不能套 fillMaxSize: 撑满窗口会让整窗都算"框内", 点外部永远关不掉; 居中由 RootMeasurePolicy 负责。
        // 全高型: 高度锁定 0.8 屏高
        Surface(
            modifier = Modifier.appDialogSize(fullHeight = true),
            shape = DesignTokens.shapeDefault,
            color = colors.fillet,
        ) {
            RuleManageScaffold(
                items = sections,
                itemKey = { it.id },
                onMove = { from, to ->
                    sections = sections.toMutableList().apply { add(to, removeAt(from)) }
                },
                emptyText = stringResource(Res.string.home_manage_empty),
                titleBar = {
                    DialogTitleBar(
                        title = stringResource(Res.string.home_manage_for_tab, tabTitle),
                        onBack = onDismiss,
                        actions = {
                            IconButton(onClick = { addingSection = true }) {
                                Icon(
                                    painter = painterResource(Res.drawable.ic_add),
                                    contentDescription = stringResource(Res.string.home_add_section),
                                    tint = colors.primaryText,
                                )
                            }
                        },
                    )
                },
            ) { item ->
                HomeManageItem(
                    title = item.title,
                    desc = "${styleNames.getOrElse(item.style) { styleNames[0] }} · ${item.sourceName}",
                    actionText = deleteText,
                    onClick = { editingSection = item },
                    onAction = {
                        HomeTabHelpShared.removeSection(tabTitle, item.id)
                        postEvent(
                            EventBus.HOME_SECTION,
                            HomeSectionEvent(HomeSectionEvent.REMOVE, tabTitle, item)
                        )
                        upData()
                    },
                    onPersistOrder = {
                        HomeTabHelpShared.saveSectionsOrder(tabTitle, sections)
                        postEvent(
                            EventBus.HOME_SECTION,
                            HomeSectionEvent(HomeSectionEvent.REORDER, tabTitle)
                        )
                    },
                )
            }
        }
    }

    if (addingSection || editingSection != null) {
        HomeSectionEditDialog(
            tabTitle = tabTitle,
            editing = editingSection,
            onDismiss = {
                addingSection = false
                editingSection = null
            },
        )
    }
}
