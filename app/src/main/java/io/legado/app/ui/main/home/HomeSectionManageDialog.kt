package io.legado.app.ui.main.home

import android.os.Bundle
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
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.legado.app.R
import io.legado.app.base.BaseComposeDialogFragment
import io.legado.app.constant.EventBus
import io.legado.app.data.entities.HomeSection
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
 * 管理某个分组(tabTitle)下的展示项(Compose)。列表、长按拖序、增删全部限定在该 tab 内。
 */
class HomeSectionManageDialog : BaseComposeDialogFragment() {

    override val isFullHeight: Boolean = true

    private val tabTitle: String get() = arguments?.getString(ARG_TAB_TITLE).orEmpty()

    private var sectionList by mutableStateOf<List<HomeSection>>(emptyList())

    @Composable
    override fun Content() {
        LaunchedEffect(Unit) {
            upData()
            observeEvent<HomeSectionEvent>(EventBus.HOME_SECTION) { event ->
                if (event.tabTitle == tabTitle && event.action != HomeSectionEvent.REORDER) {
                    upData()
                }
            }
        }
        RuleManageScaffold(
            items = sectionList,
            itemKey = { it.id },
            onMove = { from, to ->
                sectionList = sectionList.toMutableList().apply { add(to, removeAt(from)) }
            },
            emptyText = getString(R.string.home_manage_empty),
            titleBar = {
                DialogTitleBar(
                    title = getString(R.string.home_manage_for_tab, tabTitle),
                    onBack = { dismissAllowingStateLoss() },
                    actions = {
                        IconButton(onClick = {
                            showDialogFragment(HomeSectionEditDialog.newInstance(tabTitle))
                        }) {
                            Icon(
                                painter = painterResource(R.drawable.ic_add),
                                contentDescription = getString(R.string.home_add_section),
                                tint = AppTheme.colors.primaryText,
                            )
                        }
                    },
                )
            },
        ) { item ->
            SectionItem(item)
        }
    }

    @Composable
    private fun ReorderableCollectionItemScope.SectionItem(item: HomeSection) {
        val colors = AppTheme.colors
        Row(
            Modifier
                .fillMaxWidth()
                .longPressDraggableHandle(onDragStopped = { persistOrder() })
                .clickable {
                    showDialogFragment(HomeSectionEditDialog.newInstance(tabTitle, item))
                }
                .padding(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                painter = painterResource(R.drawable.ic_baseline_sort_24),
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
                    text = "${styleName(item.style)} · ${item.sourceName}",
                    color = colors.secondaryText,
                    fontSize = 12.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = 2.dp),
                )
            }
            Spacer(Modifier.width(8.dp))
            AppTextButton(text = stringResource(R.string.delete)) { deleteSection(item) }
        }
    }

    private fun styleName(style: Int): String = getString(
        when (style) {
            HomeSection.STYLE_RANK_LIST -> R.string.home_style_rank_list
            HomeSection.STYLE_FOUR_ROW -> R.string.home_style_four_row
            HomeSection.STYLE_INFINITE_GRID -> R.string.home_style_infinite_grid
            else -> R.string.home_style_cover_row
        }
    )

    private fun deleteSection(section: HomeSection) {
        HomeTabHelp.removeSection(tabTitle, section.id)
        postEvent(EventBus.HOME_SECTION, HomeSectionEvent(HomeSectionEvent.REMOVE, tabTitle, section))
        upData()
    }

    private fun upData() {
        sectionList = HomeTabHelp.getSections(tabTitle)
    }

    private fun persistOrder() {
        HomeTabHelp.saveSectionsOrder(tabTitle, sectionList)
        postEvent(EventBus.HOME_SECTION, HomeSectionEvent(HomeSectionEvent.REORDER, tabTitle))
    }

    companion object {
        private const val ARG_TAB_TITLE = "tabTitle"

        fun newInstance(tabTitle: String) = HomeSectionManageDialog().apply {
            arguments = Bundle().apply { putString(ARG_TAB_TITLE, tabTitle) }
        }
    }
}
