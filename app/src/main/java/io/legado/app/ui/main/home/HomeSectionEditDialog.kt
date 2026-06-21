package io.legado.app.ui.main.home

import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.TextView
import androidx.core.widget.doAfterTextChanged
import androidx.lifecycle.lifecycleScope
import io.legado.app.R
import io.legado.app.base.BaseDialogFragment
import io.legado.app.data.appDb
import io.legado.app.data.entities.BookSource
import io.legado.app.data.entities.HomeSection
import io.legado.app.data.entities.PinnedExplore
import io.legado.app.databinding.DialogHomeSectionEditBinding
import io.legado.app.help.HomeSectionHelp
import io.legado.app.help.PinnedExploreHelp
import io.legado.app.help.source.exploreKinds
import io.legado.app.lib.dialogs.alert
import io.legado.app.lib.dialogs.cancelButton
import io.legado.app.lib.dialogs.customView
import io.legado.app.utils.dpToPx
import io.legado.app.utils.gone
import io.legado.app.utils.toastOnUi
import io.legado.app.utils.viewbindingdelegate.viewBinding
import io.legado.app.utils.visible
import kotlinx.coroutines.Dispatchers.IO
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import splitties.views.onClick
import java.util.UUID

class HomeSectionEditDialog() : BaseDialogFragment(R.layout.dialog_home_section_edit) {

    constructor(section: HomeSection) : this() {
        arguments = Bundle().apply { putParcelable("section", section) }
    }

    private val binding by viewBinding(DialogHomeSectionEditBinding::bind)

    private var editing: HomeSection? = null
    private var pinnedExplores: List<PinnedExplore> = emptyList()

    private var selectedSource: BookSource? = null
    private var selectedExploreUrl: String? = null
    private var selectedExploreName: String? = null

    override fun onFragmentCreated(view: View, savedInstanceState: Bundle?) {
        @Suppress("DEPRECATION")
        editing = arguments?.getParcelable("section")
        initView()
        loadData()
    }

    private fun initView() {
        binding.run {
            editing?.let { toolBar.setTitle(R.string.home_edit_section) }
            tvPickFavorite.setOnClickListener { pickFavorite() }
            tvPickSource.setOnClickListener { pickSource() }
            tvPickCategory.setOnClickListener { pickCategory() }
            btnCancel.onClick { dismiss() }
            btnOk.onClick { save() }
            if (editing != null) {
                btnDelete.visible()
                btnDelete.onClick { delete() }
            } else {
                btnDelete.gone()
            }
        }
    }

    private fun loadData() {
        lifecycleScope.launch {
            pinnedExplores = withContext(IO) { PinnedExploreHelp.getPinnedExplores() }
            editing?.let { section ->
                binding.etTitle.setText(section.title)
                selectedExploreUrl = section.exploreUrl
                selectedExploreName = section.exploreName
                binding.rgStyle.check(
                    when (section.style) {
                        HomeSection.STYLE_RANK_LIST -> R.id.rb_rank_list
                        HomeSection.STYLE_FOUR_ROW -> R.id.rb_four_row
                        HomeSection.STYLE_INFINITE_GRID -> R.id.rb_infinite_grid
                        else -> R.id.rb_cover_row
                    }
                )
                binding.cbCoverVideo.isChecked = section.coverVideo
                selectedSource = withContext(IO) {
                    appDb.bookSourceDao.getBookSource(section.sourceUrl)
                }
            }
            refreshSelectionUI()
        }
    }

    /** 统一刷新选择相关的 UI（匹配收藏、更新结果显示区、禁用逻辑） */
    private fun refreshSelectionUI() {
        val source = selectedSource
        val sourceName = source?.bookSourceName
        val categoryName = selectedExploreName

        // 更新结果显示区：使用 "当前选择：源 · 分类" 格式
        if (sourceName == null) {
            binding.tvResult.text = ""
        } else {
            val content = if (categoryName == null) {
                sourceName
            } else {
                String.format("%s · %s", sourceName, categoryName)
            }
            binding.tvResult.text = getString(R.string.current_selection, content)
        }
        binding.tvPickCategory.isEnabled = source != null

        // 自动填充标题
        if (binding.etTitle.text.isNullOrBlank() && !selectedExploreName.isNullOrBlank()) {
            binding.etTitle.setText(selectedExploreName)
        }
    }

    private fun pickSource() {
        lifecycleScope.launch {
            val parts = withContext(IO) {
                appDb.bookSourceDao.flowExplore(enabled = true).first()
            }
            if (parts.isEmpty()) {
                toastOnUi(R.string.explore_empty)
                return@launch
            }
            searchPick(R.string.home_select_source, parts, { it.bookSourceName }) { part ->
                lifecycleScope.launch {
                    val source = withContext(IO) {
                        appDb.bookSourceDao.getBookSource(part.bookSourceUrl)
                    } ?: return@launch
                    selectedSource = source
                    selectedExploreUrl = null
                    selectedExploreName = null
                    refreshSelectionUI()
                }
            }
        }
    }

    private fun pickCategory() {
        val source = selectedSource ?: run {
            toastOnUi(R.string.home_select_source)
            return
        }
        lifecycleScope.launch {
            val kinds = withContext(IO) {
                runCatching { source.exploreKinds() }.getOrDefault(emptyList())
            }.filter { !it.url.isNullOrBlank() }
            if (kinds.isEmpty()) {
                toastOnUi(R.string.home_source_no_explore)
                return@launch
            }
            searchPick(R.string.home_select_category, kinds, { it.title }) { kind ->
                selectedExploreUrl = kind.url
                selectedExploreName = kind.title
                refreshSelectionUI()
            }
        }
    }

    private fun pickFavorite() {
        if (pinnedExplores.isEmpty()) {
            toastOnUi(R.string.home_no_favorite)
            return
        }
        searchPick(
            R.string.home_pick_favorite,
            pinnedExplores, { String.format("%s · %s", it.sourceName, it.categoryName) }
        ) { fav ->
            lifecycleScope.launch {
                val source = withContext(IO) {
                    appDb.bookSourceDao.getBookSource(fav.sourceUrl)
                }
                if (source == null) {
                    toastOnUi(R.string.home_source_no_explore)
                    return@launch
                }
                selectedSource = source
                selectedExploreUrl = fav.categoryUrl
                selectedExploreName = fav.categoryName
                refreshSelectionUI()
            }
        }
    }

    private fun <T> searchPick(
        titleResId: Int,
        items: List<T>,
        label: (T) -> String,
        onPick: (T) -> Unit
    ) {
        val ctx = requireContext()
        val shown = ArrayList(items)
        val listAdapter =
            object : ArrayAdapter<T>(ctx, android.R.layout.simple_list_item_1, shown) {
                override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
                    val v = super.getView(position, convertView, parent) as TextView
                    v.text = label(getItem(position)!!)
                    return v
                }
            }
        val editText = EditText(ctx).apply {
            setHint(R.string.search)
            setSingleLine()
        }
        val listView = ListView(ctx).apply {
            adapter = listAdapter
            divider = null
            dividerHeight = 0
        }
        val container = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            addView(
                editText,
                LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                )
            )
            addView(
                listView,
                LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 360.dpToPx())
            )
        }
        editText.doAfterTextChanged { e ->
            val q = e?.toString().orEmpty()
            shown.clear()
            shown.addAll(items.filter { label(it).contains(q, ignoreCase = true) })
            listAdapter.notifyDataSetChanged()
        }
        val dialog = ctx.alert(titleResId) {
            customView { container }
            cancelButton()
        }
        listView.setOnItemClickListener { _, _, position, _ ->
            listAdapter.getItem(position)?.let { onPick(it) }
            dialog.dismiss()
        }
    }

    private fun save() {
        val title = binding.etTitle.text?.toString()?.trim()
        if (title.isNullOrBlank()) {
            toastOnUi(R.string.home_title_empty)
            return
        }
        val source = selectedSource ?: run {
            toastOnUi(R.string.home_select_source)
            return
        }
        val exploreUrl = selectedExploreUrl ?: run {
            toastOnUi(R.string.home_select_category)
            return
        }
        val style = when (binding.rgStyle.checkedRadioButtonId) {
            R.id.rb_rank_list -> HomeSection.STYLE_RANK_LIST
            R.id.rb_four_row -> HomeSection.STYLE_FOUR_ROW
            R.id.rb_infinite_grid -> HomeSection.STYLE_INFINITE_GRID
            else -> HomeSection.STYLE_COVER_ROW
        }
        if (style == HomeSection.STYLE_INFINITE_GRID) {
            val existsOther = HomeSectionHelp.getSections().any {
                it.style == HomeSection.STYLE_INFINITE_GRID && it.id != editing?.id
            }
            if (existsOther) {
                toastOnUi(R.string.home_infinite_only_one)
                return
            }
        }
        val old = editing
        val section = HomeSection(
            id = old?.id ?: UUID.randomUUID().toString(),
            title = title,
            sourceUrl = source.bookSourceUrl,
            sourceName = source.bookSourceName,
            exploreUrl = exploreUrl,
            exploreName = selectedExploreName ?: title,
            style = style,
            sortOrder = old?.sortOrder ?: HomeSectionHelp.getSections().size,
            coverVideo = binding.cbCoverVideo.isChecked
        )
        lifecycleScope.launch {
            withContext(IO) {
                if (old == null) HomeSectionHelp.addSection(section)
                else HomeSectionHelp.updateSection(section)
            }
            (parentFragment as? Callback)
                ?.onHomeSectionChanged(if (old == null) "add" else "update", section)
            dismiss()
        }
    }

    private fun delete() {
        val section = editing ?: return
        lifecycleScope.launch {
            withContext(IO) {
                HomeSectionHelp.removeSection(section)
            }
            (parentFragment as? Callback)?.onHomeSectionChanged("delete", section)
            dismiss()
        }
    }

    interface Callback {
        fun onHomeSectionChanged(action: String, section: HomeSection?)
    }
}
