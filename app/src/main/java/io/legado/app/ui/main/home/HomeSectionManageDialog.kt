package io.legado.app.ui.main.home

import android.os.Bundle
import android.view.View
import androidx.core.view.isVisible
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import io.legado.app.R
import io.legado.app.base.BaseDialogFragment
import io.legado.app.data.entities.HomeSection
import io.legado.app.databinding.DialogHomeSectionManageBinding
import io.legado.app.help.HomeSectionHelp
import io.legado.app.lib.theme.primaryColor
import io.legado.app.ui.widget.recycler.ItemTouchCallback
import io.legado.app.utils.applyTint
import io.legado.app.utils.setEdgeEffectColor
import io.legado.app.utils.showDialogFragment
import io.legado.app.utils.viewbindingdelegate.viewBinding

/**
 * 管理主页展示项：列出已添加项，支持点击编辑、删除、拖动排序，右上角 + 添加。
 * 所有变更通过 EventBus.HOME_SECTION 通知主页与本对话框同步。
 */
class HomeSectionManageDialog : BaseDialogFragment(R.layout.dialog_home_section_manage),
    HomeSectionEditDialog.Callback {

    override val isFullHeight: Boolean = true

    private val binding by viewBinding(DialogHomeSectionManageBinding::bind)

    private val adapter by lazy {
        HomeSectionManageAdapter(requireContext(), object : HomeSectionManageAdapter.Callback {
            override fun onEdit(section: HomeSection) {
                showDialogFragment(HomeSectionEditDialog(section))
            }

            override fun onDelete(section: HomeSection) {
                HomeSectionHelp.removeSection(section)
                onHomeSectionChanged("delete", section)
            }
        })
    }

    override fun onFragmentCreated(view: View, savedInstanceState: Bundle?) {
        binding.toolBar.setTitle(R.string.home_manage)
        initMenu()
        binding.recyclerView.setEdgeEffectColor(primaryColor)
        binding.recyclerView.layoutManager = LinearLayoutManager(requireContext())
        binding.recyclerView.adapter = adapter
        initDragSort()
        upData()
    }

    private fun initMenu() {
        binding.toolBar.inflateMenu(R.menu.dialog_home_section_manage)
        binding.toolBar.menu.applyTint(requireContext())
        binding.toolBar.setOnMenuItemClickListener {
            when (it.itemId) {
                R.id.menu_add_section -> showDialogFragment(HomeSectionEditDialog())
            }
            true
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
                // 拖动结束后按当前顺序整体持久化，并通知主页仅重排
                HomeSectionHelp.saveOrder(adapter.getItems())
                onHomeSectionChanged("reorder", null)
            }
        })
        touchCallback.isCanDrag = true
        ItemTouchHelper(touchCallback).attachToRecyclerView(binding.recyclerView)
    }

    private fun upData() {
        val sections = HomeSectionHelp.getSections()
        adapter.setItems(sections)
        binding.tvEmpty.isVisible = sections.isEmpty()
    }

    /**
     * 编辑对话框 / 列表删除 / 拖动排序统一汇入此处：管理对话框负责刷新自身列表，
     * 再把变更转发给主界面。reorder 时列表 UI 已即时更新，无需重建以免打断动画。
     */
    override fun onHomeSectionChanged(action: String, section: HomeSection?) {
        if (action != "reorder") upData()
        (parentFragment as? HomeSectionEditDialog.Callback)
            ?.onHomeSectionChanged(action, section)
    }
}
