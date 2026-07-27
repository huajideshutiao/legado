package io.legado.app.ui.main.home

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.legado.app.R
import io.legado.app.base.BaseComposeDialogFragment
import io.legado.app.constant.EventBus
import io.legado.app.data.entities.HomeTab
import io.legado.app.help.HomeTabHelp
import io.legado.app.ui.compose.component.AppTextButton
import io.legado.app.ui.compose.component.DialogTitleBar
import io.legado.app.ui.compose.component.RuleManageScaffold
import io.legado.app.ui.compose.theme.AppTheme
import io.legado.app.utils.observeEvent
import io.legado.app.utils.postEvent
import io.legado.app.utils.showDialogFragment
import sh.calvin.reorderable.ReorderableCollectionItemScope

/**
 * 主页分组管理(Compose)：item 点击进入该分组的展示项管理；编辑改标题/删除；+ 添加分组；长按拖动排序。
 */
class HomeTabManageDialog : BaseComposeDialogFragment() {

    override val isFullHeight: Boolean = true

    private var tabList by mutableStateOf<List<HomeTab>>(emptyList())

    @Composable
    override fun Content() {
        LaunchedEffect(Unit) {
            upData()
            observeEvent<HomeTabEvent>(EventBus.HOME_TAB) { event ->
                if (event.action != HomeTabEvent.REORDER) upData()
            }
        }
        RuleManageScaffold(
            items = tabList,
            itemKey = { it.title },
            onMove = { from, to ->
                tabList = tabList.toMutableList().apply { add(to, removeAt(from)) }
            },
            titleBar = {
                DialogTitleBar(
                    title = getString(R.string.home_tab_manage),
                    onBack = { dismissAllowingStateLoss() },
                    actions = {
                        IconButton(onClick = { showHomeTabEditDialog(requireContext()) }) {
                            Icon(
                                painter = rememberPainter("ic_add"),
                                contentDescription = getString(R.string.home_tab_add),
                                tint = AppTheme.colors.primaryText,
                            )
                        }
                    },
                )
            },
        ) { item ->
            TabItem(item)
        }
    }

    @Composable
    private fun ReorderableCollectionItemScope.TabItem(item: HomeTab) {
        val colors = AppTheme.colors
        Row(
            Modifier
                .fillMaxWidth()
                .longPressDraggableHandle(onDragStopped = { persistOrder() })
                .clickable {
                    showDialogFragment(HomeSectionManageDialog.newInstance(item.title))
                }
                .padding(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                painter = rememberPainter("ic_baseline_sort_24"),
                contentDescription = stringResource(R.string.sort),
                tint = colors.secondaryText,
                modifier = Modifier.size(24.dp),
            )
            Column(Modifier.weight(1f).padding(start = 12.dp)) {
                Text(
                    text = item.title,
                    color = colors.primaryText,
                    fontSize = 15.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = stringResource(R.string.home_tab_section_count, item.sections.size),
                    color = colors.secondaryText,
                    fontSize = 12.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = 2.dp),
                )
            }
            Spacer(Modifier.width(8.dp))
            AppTextButton(text = stringResource(R.string.edit)) {
                showHomeTabEditDialog(requireContext(), item.title)
            }
        }
    }

    private fun upData() {
        tabList = HomeTabHelp.getTabs()
    }

    private fun persistOrder() {
        HomeTabHelp.saveTabsOrder(tabList)
        postEvent(EventBus.HOME_TAB, HomeTabEvent(HomeTabEvent.REORDER))
    }
}
