package io.legado.app.ui.book.manage

import android.content.Context
import android.os.Bundle
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.appcompat.widget.SearchView
import androidx.appcompat.widget.Toolbar
import androidx.core.view.setPadding
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.viewbinding.ViewBinding
import io.legado.app.R
import io.legado.app.base.BaseDialogFragment
import io.legado.app.base.adapter.ItemViewHolder
import io.legado.app.base.adapter.RecyclerAdapter
import io.legado.app.constant.AppLog
import io.legado.app.data.appDb
import io.legado.app.data.entities.BookSource
import io.legado.app.data.entities.BookSourcePart
import io.legado.app.databinding.DialogSourcePickerBinding
import io.legado.app.help.config.AppConfig
import io.legado.app.lib.theme.primaryTextColor
import io.legado.app.ui.widget.number.showNumberPicker
import io.legado.app.utils.applyTint
import io.legado.app.utils.dpToPx
import io.legado.app.utils.viewbindingdelegate.viewBinding
import kotlinx.coroutines.Dispatchers.IO
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.launch
import splitties.views.onClick

/**
 * 书源选择
 */
class SourcePickerDialog : BaseDialogFragment(R.layout.dialog_source_picker),
    Toolbar.OnMenuItemClickListener {

    override val isFullHeight: Boolean = true

    private val binding by viewBinding(DialogSourcePickerBinding::bind)
    private val searchView: SearchView by lazy {
        // view_search 被 include 到布局顶部，SearchView 不再嵌在 TitleBar 内
        binding.root.findViewById(R.id.search_view)
    }
    private val adapter by lazy {
        SourceAdapter(requireContext())
    }
    private var sourceFlowJob: Job? = null

    override fun onFragmentCreated(view: View, savedInstanceState: Bundle?) {
        initView()
        initData()
        initMenu()
    }

    private fun initView() {
        titleBar!!.title = "选择书源"
        binding.recyclerView.layoutManager = LinearLayoutManager(requireContext())
        binding.recyclerView.adapter = adapter
        searchView.applyTint(primaryTextColor)
        searchView.queryHint = getString(R.string.search_book_source)
        searchView.setOnQueryTextListener(object : SearchView.OnQueryTextListener {
            override fun onQueryTextSubmit(query: String?): Boolean {
                return false
            }

            override fun onQueryTextChange(newText: String?): Boolean {
                initData(newText)
                return false
            }
        })
    }

    private fun initData(searchKey: String? = null) {
        sourceFlowJob?.cancel()
        sourceFlowJob = lifecycleScope.launch {
            when {
                searchKey.isNullOrEmpty() -> appDb.bookSourceDao.flowEnabled()
                else -> appDb.bookSourceDao.flowSearch(searchKey,true)
            }.catch {
                AppLog.put("书源选择界面获取书源数据失败\n${it.localizedMessage}", it)
            }.flowOn(IO).collect {
                adapter.setItems(it)
            }
        }
    }

    private fun initMenu() {
        setupTitleBar(
            menuRes = R.menu.source_picker,
            onMenuClick = ::onMenuItemClick
        )
    }

    override fun onMenuItemClick(item: MenuItem?): Boolean {
        when (item?.itemId) {
            R.id.menu_change_source_delay -> showNumberPicker(
                requireContext(),
                titleResId = R.string.change_source_delay,
                max = 9999, min = 0, value = AppConfig.batchChangeSourceDelay
            ) {
                AppConfig.batchChangeSourceDelay = it
            }
        }
        return true
    }

    class TextItemBinding(val root: TextView) : ViewBinding {
        override fun getRoot(): View = root
    }

    inner class SourceAdapter(context: Context) :
        RecyclerAdapter<BookSourcePart, TextItemBinding>(context) {

        override fun getViewBinding(parent: ViewGroup): TextItemBinding {
            val tv = TextView(parent.context).apply {
                layoutParams = ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                )
                setPadding(16.dpToPx())
            }
            return TextItemBinding(tv)
        }

        override fun convert(
            holder: ItemViewHolder,
            binding: TextItemBinding,
            item: BookSourcePart,
            payloads: MutableList<Any>
        ) {
            binding.root.text = item.getDisPlayNameGroup()
        }

        override fun registerListener(holder: ItemViewHolder, binding: TextItemBinding) {
            binding.root.onClick {
                getItemByLayoutPosition(holder.layoutPosition)?.let {
                    it.getBookSource()?.let { source ->
                        callback?.sourceOnClick(source)
                    }
                    dismissAllowingStateLoss()
                }
            }
        }

    }

    private val callback: Callback?
        get() {
            return (parentFragment as? Callback) ?: activity as? Callback
        }

    interface Callback {
        fun sourceOnClick(source: BookSource)
    }

}
