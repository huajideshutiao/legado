package io.legado.app.ui.book.search

import android.annotation.SuppressLint
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import androidx.appcompat.widget.SearchView
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.RecyclerView
import io.legado.app.R
import io.legado.app.base.BaseDialogFragment
import io.legado.app.base.adapter.ItemViewHolder
import io.legado.app.constant.AppLog
import io.legado.app.data.AppDatabase
import io.legado.app.data.appDb
import io.legado.app.data.entities.BookSourcePart
import io.legado.app.databinding.DialogSearchScopeBinding
import io.legado.app.databinding.ItemCheckBoxBinding
import io.legado.app.databinding.ItemRadioButtonBinding
import io.legado.app.utils.applyTint
import io.legado.app.utils.flowWithLifecycleAndDatabaseChange
import io.legado.app.utils.viewbindingdelegate.viewBinding
import kotlinx.coroutines.Dispatchers.IO
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class SearchScopeDialog : BaseDialogFragment(R.layout.dialog_search_scope) {

    override val isFullHeight: Boolean = true

    private val binding by viewBinding(DialogSearchScopeBinding::bind)
    private var sourceFlowJob: Job? = null
    val callback: Callback get() = parentFragment as? Callback ?: activity as Callback
    var groups: List<String> = emptyList()
    val screenSources = arrayListOf<BookSourcePart>()
    var screenText: String? = null

    val adapter by lazy {
        RecyclerAdapter()
    }

    override fun onFragmentCreated(view: View, savedInstanceState: Bundle?) {
        binding.recyclerView.adapter = adapter
        initMenu()
        initSearchView()
        initOtherView()
        initData()
    }

    private fun initMenu() {
        binding.toolBar.inflateMenu(R.menu.book_search_scope)
        binding.toolBar.menu.applyTint(requireContext())
    }

    private fun initSearchView() {
        val searchView = binding.toolBar.menu.findItem(R.id.menu_screen).actionView as SearchView
        searchView.setOnQueryTextListener(object : SearchView.OnQueryTextListener {
            override fun onQueryTextSubmit(query: String?): Boolean {
                return false
            }

            override fun onQueryTextChange(newText: String?): Boolean {
                screenText = newText
                upData()
                return false
            }

        })
    }

    private fun initOtherView() {
        binding.rgScope.setOnCheckedChangeListener { _, checkedId ->
            binding.toolBar.menu.findItem(R.id.menu_screen)?.isVisible = checkedId == R.id.rb_source
            upData()
        }
        binding.tvCancel.setOnClickListener {
            dismiss()
        }
        binding.tvAllSource.setOnClickListener {
            callback.onSearchScopeOk(SearchScope(""))
            dismiss()
        }
        binding.tvOk.setOnClickListener {
            if (binding.rbGroup.isChecked) {
                callback.onSearchScopeOk(SearchScope(adapter.selectGroups))
            } else {
                val selected = adapter.selectSource
                if (selected != null) {
                    callback.onSearchScopeOk(SearchScope(selected))
                } else {
                    callback.onSearchScopeOk(SearchScope(""))
                }
            }
            dismiss()
        }
    }

    private fun initData() {
        lifecycleScope.launch {
            groups = withContext(IO) {
                appDb.bookSourceDao.allEnabledGroups()
            }
            upData()
        }
    }

    @SuppressLint("NotifyDataSetChanged")
    private fun upData() {
        if (binding.rbSource.isChecked) {
            upBookSource(screenText)
        } else {
            adapter.notifyDataSetChanged()
        }
    }

    @SuppressLint("NotifyDataSetChanged")
    private fun upBookSource(searchKey: String? = null) {
        sourceFlowJob?.cancel()
        sourceFlowJob = lifecycleScope.launch {
            when {
                searchKey.isNullOrEmpty() -> appDb.bookSourceDao.flowAll()
                else -> appDb.bookSourceDao.flowSearch(searchKey)
            }.flowWithLifecycleAndDatabaseChange(
                lifecycle,
                table = AppDatabase.BOOK_SOURCE_TABLE_NAME
            ).catch {
                AppLog.put("多分组/书源界面更新书源出错", it)
            }.flowOn(IO).conflate().collect { data ->
                screenSources.clear()
                screenSources.addAll(data)
                adapter.notifyDataSetChanged()
                delay(500)
            }
        }
    }

    inner class RecyclerAdapter : RecyclerView.Adapter<ItemViewHolder>() {

        val selectGroups = arrayListOf<String>()
        var selectSource: BookSourcePart? = null

        override fun getItemViewType(position: Int): Int {
            return if (binding.rbSource.isChecked) 1 else 0
        }

        override fun getItemCount(): Int {
            return if (binding.rbSource.isChecked) screenSources.size else groups.size
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ItemViewHolder {
            return if (viewType == 1) {
                ItemViewHolder(ItemRadioButtonBinding.inflate(layoutInflater, parent, false))
            } else {
                ItemViewHolder(ItemCheckBoxBinding.inflate(layoutInflater, parent, false))
            }
        }

        override fun onBindViewHolder(
            holder: ItemViewHolder,
            position: Int,
            payloads: MutableList<Any>
        ) {
            if (payloads.isEmpty()) {
                super.onBindViewHolder(holder, position, payloads)
                return
            }
            when (val itemBinding = holder.binding) {
                is ItemCheckBoxBinding -> {
                    groups.getOrNull(position)?.let {
                        itemBinding.checkBox.isChecked = selectGroups.contains(it)
                        itemBinding.checkBox.text = it
                    }
                }

                is ItemRadioButtonBinding -> {
                    screenSources.getOrNull(position)?.let { src ->
                        itemBinding.radioButton.isChecked =
                            selectSource?.bookSourceUrl == src.bookSourceUrl
                        itemBinding.radioButton.text = src.bookSourceName
                    }
                }
            }
        }

        override fun onBindViewHolder(holder: ItemViewHolder, position: Int) {
            when (val itemBinding = holder.binding) {
                is ItemCheckBoxBinding -> {
                    val group = groups.getOrNull(position) ?: return
                    itemBinding.checkBox.isChecked = selectGroups.contains(group)
                    itemBinding.checkBox.text = group
                    itemBinding.checkBox.setOnUserCheckedChangeListener { isChecked ->
                        if (isChecked) {
                            selectGroups.add(group)
                        } else {
                            selectGroups.remove(group)
                        }
                        holder.itemView.post {
                            notifyItemRangeChanged(0, itemCount, "up")
                        }
                    }
                }

                is ItemRadioButtonBinding -> {
                    val src = screenSources.getOrNull(position) ?: return
                    itemBinding.radioButton.isChecked =
                        selectSource?.bookSourceUrl == src.bookSourceUrl
                    itemBinding.radioButton.text = src.bookSourceName
                    itemBinding.radioButton.setOnUserCheckedChangeListener { isChecked ->
                        if (isChecked) {
                            selectSource = src
                            holder.itemView.post {
                                notifyItemRangeChanged(0, itemCount, "up")
                            }
                        }
                    }
                }
            }
        }

    }

    interface Callback {

        /**
         * 搜索范围确认
         */
        fun onSearchScopeOk(searchScope: SearchScope)

    }

}
