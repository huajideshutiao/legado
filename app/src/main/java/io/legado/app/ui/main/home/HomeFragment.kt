@file:Suppress("DEPRECATION")

package io.legado.app.ui.main.home

import android.os.Bundle
import android.view.Menu
import android.view.MenuItem
import android.view.View
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.recyclerview.widget.RecyclerView
import androidx.viewpager2.adapter.FragmentStateAdapter
import com.google.android.material.tabs.TabLayout
import com.google.android.material.tabs.TabLayoutMediator
import io.legado.app.R
import io.legado.app.base.VMBaseFragment
import io.legado.app.constant.EventBus
import io.legado.app.data.entities.HomeTab
import io.legado.app.databinding.FragmentHomeBinding
import io.legado.app.lib.theme.accentColor
import io.legado.app.lib.theme.primaryColor
import io.legado.app.ui.main.MainFragmentInterface
import io.legado.app.utils.observeEvent
import io.legado.app.utils.setEdgeEffectColor
import io.legado.app.utils.showDialogFragment
import io.legado.app.utils.viewbindingdelegate.viewBinding

/**
 * 主页外壳：标题栏 + TabLayout + 老 ViewPager。
 * 各 tab 内容由 HomeTabFragment 承载，状态由共享的 HomeViewModel 按 tabTitle 分桶持有。
 */
class HomeFragment() : VMBaseFragment<HomeViewModel>(R.layout.fragment_home),
    MainFragmentInterface {

    constructor(position: Int) : this() {
        arguments = Bundle().apply { putInt("position", position) }
    }

    override val position: Int? get() = arguments?.getInt("position")

    private val binding by viewBinding(FragmentHomeBinding::bind)
    override val viewModel by viewModels<HomeViewModel>()

    private val tabLayout: TabLayout by lazy {
        binding.titleBar.findViewById(R.id.tab_layout)
    }

    private val tabs = mutableListOf<HomeTab>()
    private val pagerAdapter by lazy { HomeTabPagerAdapter(this) }

    override fun onFragmentCreated(view: View, savedInstanceState: Bundle?) {
        setSupportToolbar(binding.titleBar.toolbar)
        initView()
        viewModel.initTabs()
        viewModel.tabsLiveData.observe(viewLifecycleOwner) { upTabs(it) }
        observeEvent<HomeTabEvent>(EventBus.HOME_TAB) { event ->
            viewModel.onTabsChanged(
                rename = if (event.action == HomeTabEvent.RENAME && event.oldTitle != null && event.newTitle != null)
                    event.oldTitle to event.newTitle else null,
                removed = if (event.action == HomeTabEvent.REMOVE) event.oldTitle else null
            )
        }
        savedInstanceState?.getInt(STATE_CURRENT_ITEM, 0)?.let { restored ->
            binding.viewPagerHome.post {
                if (restored in 0 until pagerAdapter.itemCount) {
                    binding.viewPagerHome.setCurrentItem(restored, false)
                }
            }
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putInt(STATE_CURRENT_ITEM, binding.viewPagerHome.currentItem)
        super.onSaveInstanceState(outState)
    }

    private fun initView() {
        (binding.viewPagerHome.getChildAt(0) as? RecyclerView)?.setEdgeEffectColor(primaryColor)
        binding.viewPagerHome.adapter = pagerAdapter
        tabLayout.isTabIndicatorFullWidth = false
        tabLayout.tabMode = TabLayout.MODE_SCROLLABLE
        tabLayout.setSelectedTabIndicatorColor(requireContext().accentColor)
        TabLayoutMediator(tabLayout, binding.viewPagerHome) { tab, position ->
            tab.text = tabs[position].title
        }.attach()
    }

    private fun upTabs(newTabs: List<HomeTab>) {
        tabs.clear()
        tabs.addAll(newTabs)
        pagerAdapter.notifyDataSetChanged()
        tabLayout.visibility = if (tabs.size > 1) View.VISIBLE else View.GONE
    }

    /** 当前选中 tab 的标题；用于"管理展示项"快捷入口 */
    val currentTabTitle: String?
        get() = tabs.getOrNull(binding.viewPagerHome.currentItem)?.title

    override fun onCompatCreateOptionsMenu(menu: Menu) {
        menuInflater.inflate(R.menu.main_home, menu)
    }

    override fun onCompatOptionsItemSelected(item: MenuItem) {
        when (item.itemId) {
            R.id.menu_manage_tab ->
                showDialogFragment(HomeTabManageDialog())

            R.id.menu_manage_section -> {
                val tabTitle = currentTabTitle ?: return
                showDialogFragment(HomeSectionManageDialog.newInstance(tabTitle))
            }
        }
    }

    private inner class HomeTabPagerAdapter(fragment: Fragment) :
        FragmentStateAdapter(fragment) {

        override fun getItemCount(): Int = tabs.size

        override fun createFragment(position: Int): Fragment = HomeTabFragment(tabs[position].title)

        override fun getItemId(position: Int): Long = stableId(tabs[position].title)

        override fun containsItem(itemId: Long): Boolean =
            tabs.any { stableId(it.title) == itemId }

        private fun stableId(title: String): Long {
            var h = 1125899906842597L
            for (c in title) h = 31 * h + c.code
            return h
        }
    }

    companion object {
        private const val STATE_CURRENT_ITEM = "homeCurrentItem"
    }
}
