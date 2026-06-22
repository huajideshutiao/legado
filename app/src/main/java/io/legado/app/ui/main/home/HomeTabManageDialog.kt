package io.legado.app.ui.main.home

import android.os.Bundle
import android.view.View
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import io.legado.app.R
import io.legado.app.base.BaseDialogFragment
import io.legado.app.constant.EventBus
import io.legado.app.data.entities.HomeTab
import io.legado.app.databinding.DialogHomeSectionManageBinding
import io.legado.app.help.HomeTabHelp
import io.legado.app.lib.theme.primaryColor
import io.legado.app.ui.widget.recycler.ItemTouchCallback
import io.legado.app.utils.applyTint
import io.legado.app.utils.observeEvent
import io.legado.app.utils.postEvent
import io.legado.app.utils.setEdgeEffectColor
import io.legado.app.utils.showDialogFragment
import io.legado.app.utils.viewbindingdelegate.viewBinding

/**
 * 主页分组管理：item 点击进入该分组的展示项管理；编辑按钮改标题/删除；+ 添加分组；拖动排序。
 * 复用 dialog_home_section_manage 的 layout 和 menu（同构）。
 */
class HomeTabManageDialog : BaseDialogFragment(R.layout.dialog_home_section_manage) {

    override val isFullHeight: Boolean = true

    private val binding by viewBinding(DialogHomeSectionManageBinding::bind)

    private val adapter by lazy {
        HomeTabManageAdapter(requireContext(), object : HomeTabManageAdapter.Callback {
            override fun onOpenSections(tab: HomeTab) {
                showDialogFragment(HomeSectionManageDialog.newInstance(tab.title))
            }

            override fun onEdit(tab: HomeTab) {
                showDialogFragment(HomeTabEditDialog.newInstance(tab.title))
            }
        })
    }

    override fun onFragmentCreated(view: View, savedInstanceState: Bundle?) {
        binding.toolBar.setTitle(R.string.home_tab_manage)
        binding.toolBar.inflateMenu(R.menu.dialog_home_section_manage)
        binding.toolBar.menu.findItem(R.id.menu_add_section).setTitle(R.string.home_tab_add)
        binding.toolBar.menu.applyTint(requireContext())
        binding.toolBar.setOnMenuItemClickListener {
            if (it.itemId == R.id.menu_add_section) {
                showDialogFragment(HomeTabEditDialog.newInstance(null))
            }
            true
        }
        binding.recyclerView.setEdgeEffectColor(primaryColor)
        binding.recyclerView.layoutManager = LinearLayoutManager(requireContext())
        binding.recyclerView.adapter = adapter
        initDragSort()
        upData()
        observeEvent<HomeTabEvent>(EventBus.HOME_TAB) { event ->
            if (event.action != HomeTabEvent.REORDER) upData()
        }
    }

    private fun initDragSort() {
        val touchCallback = ItemTouchCallback(object : ItemTouchCallback.Callback {
            override fun swap(srcPosition: Int, targetPosition: Int): Boolean {
                adapter.swap(srcPosition, targetPosition)
                return true
            }

            override fun onClearView(
                recyclerView: RecyclerView,
                viewHolder: RecyclerView.ViewHolder
            ) {
                HomeTabHelp.saveTabsOrder(adapter.getItems())
                postEvent(EventBus.HOME_TAB, HomeTabEvent(HomeTabEvent.REORDER))
            }
        })
        touchCallback.isCanDrag = true
        ItemTouchHelper(touchCallback).attachToRecyclerView(binding.recyclerView)
    }

    private fun upData() {
        adapter.setItems(HomeTabHelp.getTabs())
    }
}
