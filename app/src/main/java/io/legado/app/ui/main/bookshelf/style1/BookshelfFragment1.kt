package io.legado.app.ui.main.bookshelf.style1

import android.os.Bundle
import android.view.View
import androidx.appcompat.widget.SearchView
import androidx.fragment.app.Fragment
import androidx.recyclerview.widget.RecyclerView
import androidx.viewpager2.adapter.FragmentStateAdapter
import com.google.android.material.tabs.TabLayout
import com.google.android.material.tabs.TabLayoutMediator
import io.legado.app.R
import io.legado.app.constant.EventBus
import io.legado.app.data.appDb
import io.legado.app.data.entities.Book
import io.legado.app.data.entities.BookGroup
import io.legado.app.databinding.FragmentViewPager2Binding
import io.legado.app.help.config.AppConfig
import io.legado.app.lib.theme.accentColor
import io.legado.app.lib.theme.primaryColor
import io.legado.app.ui.book.group.GroupEditDialog
import io.legado.app.ui.book.search.SearchActivity
import io.legado.app.ui.main.bookshelf.BaseBookshelfFragment
import io.legado.app.ui.main.bookshelf.style1.books.BooksFragment
import io.legado.app.utils.observeEvent
import io.legado.app.utils.setEdgeEffectColor
import io.legado.app.utils.showDialogFragment
import io.legado.app.utils.viewbindingdelegate.viewBinding

/**
 * 书架界面
 */
class BookshelfFragment1() : BaseBookshelfFragment(R.layout.fragment_view_pager2),
    TabLayout.OnTabSelectedListener, SearchView.OnQueryTextListener {

    constructor(position: Int) : this() {
        val bundle = Bundle()
        bundle.putInt("position", position)
        arguments = bundle
    }

    private val binding by viewBinding(FragmentViewPager2Binding::bind)
    private val adapter by lazy { TabFragmentStateAdapter(this@BookshelfFragment1) }
    private val tabLayout: TabLayout by lazy {
        binding.titleBar.findViewById(R.id.tab_layout)
    }
    private val bookGroups = mutableListOf<BookGroup>()
    private val fragmentMap = hashMapOf<Long, BooksFragment>()
    private var tabLayoutMediator: TabLayoutMediator? = null
    override val groupId: Long get() = selectedGroup?.groupId ?: 0

    override val books: List<Book>
        get() {
            val fragment = fragmentMap[groupId]
            return fragment?.getBooks() ?: emptyList()
        }

    override fun onFragmentCreated(view: View, savedInstanceState: Bundle?) {
        setSupportToolbar(binding.titleBar.toolbar)
        initView()
        initBookGroupData()
    }

    private val selectedGroup: BookGroup?
        get() = bookGroups.getOrNull(binding.viewPager.currentItem)

    private fun initView() {
        (binding.viewPager.getChildAt(0) as? RecyclerView)?.setEdgeEffectColor(primaryColor)
        tabLayout.isTabIndicatorFullWidth = false
        tabLayout.tabMode = TabLayout.MODE_SCROLLABLE
        tabLayout.setSelectedTabIndicatorColor(requireContext().accentColor)
        binding.viewPager.offscreenPageLimit = 1
        binding.viewPager.adapter = adapter
        tabLayoutMediator = TabLayoutMediator(tabLayout, binding.viewPager) { tab, position ->
            val group = bookGroups[position]
            tab.text = tabTitle(group, groupBookCountMap[group.groupId])
            tab.view.setOnLongClickListener {
                showDialogFragment(GroupEditDialog(group))
                true
            }
        }.also { it.attach() }
    }

    private val groupBookCountMap = mutableMapOf<Long, Int>()

    override fun onQueryTextSubmit(query: String?): Boolean {
        SearchActivity.start(requireContext(), query)
        return false
    }

    override fun onQueryTextChange(newText: String?): Boolean {
        return false
    }

    @Synchronized
    override fun upGroup(data: List<BookGroup>) {
        if (data.isEmpty()) {
            appDb.bookGroupDao.enableGroup(BookGroup.IdAll)
        } else {
            if (data != bookGroups) {
                bookGroups.clear()
                bookGroups.addAll(data)
                adapter.notifyItemRangeChanged(0, adapter.itemCount)
                adapter.notifyItemInserted(0)
                selectLastTab()
            }
        }
    }

    override fun upSort() {
        adapter.notifyDataSetChanged()
    }

    private fun selectLastTab() {
        tabLayout.post {
            val lastPosition = AppConfig.saveTabPosition
            if (lastPosition in 0 until adapter.itemCount) {
                tabLayout.removeOnTabSelectedListener(this)
                binding.viewPager.setCurrentItem(lastPosition, false)
                tabLayout.getTabAt(lastPosition)?.select()
                tabLayout.addOnTabSelectedListener(this)
            }
        }
    }

    override fun onTabReselected(tab: TabLayout.Tab) {
        gotoTop()
    }

    override fun observeLiveBus() {
        super.observeLiveBus()
        observeEvent<String>(EventBus.BOOKSHELF_REFRESH) {
            refreshTabTitles()
        }
    }

    private fun tabTitle(group: BookGroup, count: Int?): CharSequence {
        if (!AppConfig.bookshelfShowGroupCount) return group.groupName
        return "${group.groupName}(${count ?: ".."})"
    }

    private fun refreshTabTitles() {
        bookGroups.forEachIndexed { index, group ->
            tabLayout.getTabAt(index)?.text = tabTitle(group, groupBookCountMap[group.groupId])
        }
    }

    fun updateTabTitle(groupId: Long, count: Int) {
        val groupIndex = bookGroups.indexOfFirst { it.groupId == groupId }
        if (groupIndex != -1) {
            groupBookCountMap[groupId] = count
            tabLayout.getTabAt(groupIndex)?.text = tabTitle(bookGroups[groupIndex], count)
        }
    }

    override fun onTabUnselected(tab: TabLayout.Tab) = Unit

    override fun onTabSelected(tab: TabLayout.Tab) {
        AppConfig.saveTabPosition = tab.position
    }

    override fun gotoTop() {
        fragmentMap[groupId]?.gotoTop()
    }

    override fun onDestroyView() {
        tabLayoutMediator?.detach()
        tabLayoutMediator = null
        super.onDestroyView()
    }

    private inner class TabFragmentStateAdapter(fragment: Fragment) :
        FragmentStateAdapter(fragment) {

        override fun getItemCount(): Int = bookGroups.size

        override fun createFragment(position: Int): Fragment {
            val group = bookGroups[position]
            return BooksFragment(position, group).also {
                fragmentMap[group.groupId] = it
            }
        }

        override fun getItemId(position: Int): Long {
            return bookGroups[position].groupId
        }

        override fun containsItem(itemId: Long): Boolean {
            return bookGroups.any { it.groupId == itemId }
        }
    }
}
