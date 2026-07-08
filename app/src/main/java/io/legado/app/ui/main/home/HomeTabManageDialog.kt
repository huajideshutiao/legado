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
import io.legado.app.databinding.DialogRecyclerViewBinding
import io.legado.app.help.HomeTabHelp
import io.legado.app.ui.widget.recycler.ItemTouchCallback
import io.legado.app.utils.observeEvent
import io.legado.app.utils.postEvent
import io.legado.app.utils.showDialogFragment
import io.legado.app.utils.viewbindingdelegate.viewBinding

/**
 * 主页分组管理：item 点击进入该分组的展示项管理；编辑按钮改标题/删除；+ 添加分组；拖动排序。
 * 复用 dialog_recycler_view 的 layout（同构）。
 */
class HomeTabManageDialog : BaseDialogFragment(R.layout.dialog_recycler_view) {

    override val isFullHeight: Boolean = true

    private val binding by viewBinding(DialogRecyclerViewBinding::bind)

    private val adapter by lazy {
        HomeTabManageAdapter(requireContext(), object : HomeTabManageAdapter.Callback {
            override fun onOpenSections(tab: HomeTab) {
                showDialogFragment(HomeSectionManageDialog.newInstance(tab.title))
            }

            override fun onEdit(tab: HomeTab) {
                showHomeTabEditDialog(requireContext(), tab.title)
            }
        })
    }

    override fun onFragmentCreated(view: View, savedInstanceState: Bundle?) {
        setupTitleBar(
            title = getString(R.string.home_tab_manage),
            menuRes = R.menu.dialog_add
        ) {
            if (it?.itemId == R.id.menu_add) {
                showHomeTabEditDialog(requireContext())
            }
            true
        }
        titleBar!!.menu.findItem(R.id.menu_add)?.setTitle(R.string.home_tab_add)
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
