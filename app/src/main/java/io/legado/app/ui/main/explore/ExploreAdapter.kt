package io.legado.app.ui.main.explore

import android.content.Context
import android.view.View
import android.view.ViewGroup
import android.widget.PopupMenu
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.children
import com.google.android.flexbox.FlexboxLayout
import com.script.rhino.runScriptWithContext
import io.legado.app.R
import io.legado.app.base.adapter.ItemViewHolder
import io.legado.app.base.adapter.RecyclerAdapter
import io.legado.app.constant.AppLog
import io.legado.app.data.entities.BookSource
import io.legado.app.data.entities.BookSourcePart
import io.legado.app.data.entities.rule.ExploreKind
import io.legado.app.data.entities.rule.FlexChildStyle
import io.legado.app.data.entities.rule.RowUi
import io.legado.app.databinding.ItemFilletTextBinding
import io.legado.app.databinding.ItemFindBookBinding
import io.legado.app.help.coroutine.Coroutine
import io.legado.app.help.source.clearExploreKindsCache
import io.legado.app.help.source.exploreKinds
import io.legado.app.lib.theme.accentColor
import io.legado.app.ui.widget.dialog.TextDialog
import io.legado.app.utils.activity
import io.legado.app.utils.gone
import io.legado.app.utils.removeLastElement
import io.legado.app.utils.showDialogFragment
import io.legado.app.utils.visible
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers.IO
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import splitties.views.onLongClick

class ExploreAdapter(context: Context, val callBack: CallBack) :
    RecyclerAdapter<BookSourcePart, ItemFindBookBinding>(context) {

    private val recycler = arrayListOf<View>()
    private var exIndex = -1
    private var scrollTo = -1

    override fun getViewBinding(parent: ViewGroup): ItemFindBookBinding {
        return ItemFindBookBinding.inflate(inflater, parent, false)
    }

    override fun convert(
        holder: ItemViewHolder,
        binding: ItemFindBookBinding,
        item: BookSourcePart,
        payloads: MutableList<Any>
    ) {
        binding.run {
            if (payloads.isEmpty()) {
                tvName.text = item.bookSourceName
            }
            if (exIndex == holder.layoutPosition - getHeaderCount()) {
                val item = item.getBookSource()!!
                ivStatus.setImageResource(R.drawable.ic_arrow_down)
                rotateLoading.loadingColor = context.accentColor
                rotateLoading.visible()
                if (scrollTo >= 0) {
                    callBack.scrollTo(scrollTo)
                }
                Coroutine.async(callBack.scope) {
                    item.exploreKinds()
                }.onSuccess { kindList ->
                    upKindList(flexbox, item, kindList)
                }.onFinally {
                    rotateLoading.gone()
                    if (scrollTo >= 0) {
                        callBack.scrollTo(scrollTo)
                        scrollTo = -1
                    }
                }
            } else kotlin.runCatching {
                ivStatus.setImageResource(R.drawable.ic_arrow_right)
                rotateLoading.gone()
                recyclerFlexbox(flexbox)
                flexbox.gone()
            }
        }
    }

    private fun upKindList(
        flexbox: FlexboxLayout,
        source: BookSource,
        kinds: List<ExploreKind>
    ) {
        if (kinds.isNotEmpty()) kotlin.runCatching {
            recyclerFlexbox(flexbox)
            flexbox.visible()
            kinds.forEach { kind ->
                val tv = getFlexboxChild(flexbox)
                flexbox.addView(tv)
                tv.text = kind.title
                if (kind.type == RowUi.Type.title) {
                    FlexChildStyle(
                        layout_flexBasisPercent = 1F
                    ).apply(tv)
                } else kind.style().apply(tv)
                tv.setOnClickListener {
                    when {
                        kind.url.isNullOrBlank() -> {}
                        kind.title.startsWith("ERROR:") ->
                            it.activity?.showDialogFragment(
                                TextDialog("ERROR", kind.url)
                            )

                        kind.type == RowUi.Type.button -> CoroutineScope(IO).launch {
                            kotlin.runCatching {
                                runScriptWithContext {
                                    source.evalJS(kind.url)
                                }
                            }.onFailure { e ->
                                ensureActive()
                                AppLog.put("JS错误${e.localizedMessage}", e, true)
                            }
                        }

                        else -> callBack.openExplore(source, kind.title, kind.url)
                    }
                }

            }
        }
    }

    @Synchronized
    private fun getFlexboxChild(flexbox: FlexboxLayout): TextView {
        return if (recycler.isEmpty()) {
            ItemFilletTextBinding.inflate(inflater, flexbox, false).root
        } else {
            recycler.removeLastElement() as TextView
        }
    }

    @Synchronized
    private fun recyclerFlexbox(flexbox: FlexboxLayout) {
        recycler.addAll(flexbox.children)
        flexbox.removeAllViews()
    }

    override fun registerListener(holder: ItemViewHolder, binding: ItemFindBookBinding) {
        binding.apply {
            llTitle.setOnClickListener {
                val layoutPos = holder.layoutPosition
                val actualPos = layoutPos - getHeaderCount()
                val oldEx = exIndex
                exIndex = if (exIndex == actualPos) -1 else actualPos
                if (oldEx != -1) {
                    notifyItemChanged(oldEx + getHeaderCount(), false)
                }
                if (exIndex != -1) {
                    scrollTo = layoutPos
                    callBack.scrollTo(layoutPos)
                    notifyItemChanged(layoutPos, false)
                }
            }
            llTitle.onLongClick {
                showMenu(llTitle, holder.layoutPosition - getHeaderCount())
            }
        }
    }

    fun compressExplore(): Boolean {
        return if (exIndex < 0) {
            false
        } else {
            val oldExIndex = exIndex
            exIndex = -1
            notifyItemChanged(oldExIndex + getHeaderCount())
            true
        }
    }

    private fun showMenu(view: View, position: Int) {
        val source = getItem(position) ?: return
        val popupMenu = PopupMenu(context, view)
        popupMenu.inflate(R.menu.explore_item)
        popupMenu.menu.findItem(R.id.menu_login).isVisible = source.hasLoginUrl
        popupMenu.setOnMenuItemClickListener {
            when (it.itemId) {
                R.id.menu_edit -> callBack.editSource(source.bookSourceUrl)
                R.id.menu_top -> callBack.toTop(source)
                R.id.menu_search -> callBack.searchBook(source)
                R.id.menu_login -> source.getBookSource()
                    ?.showLoginDialog(context as AppCompatActivity)

                R.id.menu_refresh -> Coroutine.async(callBack.scope) {
                    source.clearExploreKindsCache()
                }.onSuccess {
                    notifyItemChanged(position + getHeaderCount())
                }

                R.id.menu_del -> callBack.deleteSource(source)
            }
            true
        }
        popupMenu.show()
    }

    interface CallBack {
        val scope: CoroutineScope
        fun scrollTo(pos: Int)
        fun openExplore(source: BookSource, title: String, exploreUrl: String?)
        fun editSource(sourceUrl: String)
        fun toTop(source: BookSourcePart)
        fun deleteSource(source: BookSourcePart)
        fun searchBook(bookSource: BookSourcePart)
    }
}
