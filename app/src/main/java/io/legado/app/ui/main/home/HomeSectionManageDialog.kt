package io.legado.app.ui.main.home

import android.os.Bundle
import android.view.View
import androidx.core.view.isVisible
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import io.legado.app.R
import io.legado.app.base.BaseDialogFragment
import io.legado.app.constant.EventBus
import io.legado.app.data.entities.HomeSection
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
 * 管理某个分组（tabTitle）下的展示项。列表、拖序、增删全部限定在该 tab 内。
 */
class HomeSectionManageDialog : BaseDialogFragment(R.layout.dialog_home_section_manage) {

    override val isFullHeight: Boolean = true

    private val binding by viewBinding(DialogHomeSectionManageBinding::bind)

    private val tabTitle: String get() = arguments?.getString(ARG_TAB_TITLE).orEmpty()

    private val adapter by lazy {
        HomeSectionManageAdapter(requireContext(), object : HomeSectionManageAdapter.Callback {
            override fun onEdit(section: HomeSection) {
                showDialogFragment(HomeSectionEditDialog.newInstance(tabTitle, section))
            }

            override fun onDelete(section: HomeSection) {
                HomeTabHelp.removeSection(tabTitle, section.id)
                postEvent(
                    EventBus.HOME_SECTION,
                    HomeSectionEvent(HomeSectionEvent.REMOVE, tabTitle, section)
                )
                upData()
            }
        })
    }

    override fun onFragmentCreated(view: View, savedInstanceState: Bundle?) {
        binding.toolBar.title = getString(R.string.home_manage_for_tab, tabTitle)
        initMenu()
        binding.recyclerView.setEdgeEffectColor(primaryColor)
        binding.recyclerView.layoutManager = LinearLayoutManager(requireContext())
        binding.recyclerView.adapter = adapter
        initDragSort()
        upData()
        observeEvent<HomeSectionEvent>(EventBus.HOME_SECTION) { event ->
            if (event.tabTitle == tabTitle && event.action != HomeSectionEvent.REORDER) {
                upData()
            }
        }
    }

    private fun initMenu() {
        binding.toolBar.inflateMenu(R.menu.dialog_home_section_manage)
        binding.toolBar.menu.applyTint(requireContext())
        binding.toolBar.setOnMenuItemClickListener {
            when (it.itemId) {
                R.id.menu_add_section ->
                    showDialogFragment(HomeSectionEditDialog.newInstance(tabTitle))
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
                HomeTabHelp.saveSectionsOrder(tabTitle, adapter.getItems())
                postEvent(
                    EventBus.HOME_SECTION,
                    HomeSectionEvent(HomeSectionEvent.REORDER, tabTitle)
                )
            }
        })
        touchCallback.isCanDrag = true
        ItemTouchHelper(touchCallback).attachToRecyclerView(binding.recyclerView)
    }

    private fun upData() {
        val sections = HomeTabHelp.getSections(tabTitle)
        adapter.setItems(sections)
        binding.tvEmpty.isVisible = sections.isEmpty()
    }

    companion object {
        private const val ARG_TAB_TITLE = "tabTitle"

        fun newInstance(tabTitle: String) = HomeSectionManageDialog().apply {
            arguments = Bundle().apply { putString(ARG_TAB_TITLE, tabTitle) }
        }
    }
}
