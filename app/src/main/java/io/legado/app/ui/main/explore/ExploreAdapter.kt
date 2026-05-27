package io.legado.app.ui.main.explore

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.content.Context
import android.view.View
import android.view.ViewGroup
import android.widget.PopupMenu
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.children
import androidx.core.view.isEmpty
import androidx.core.view.isGone
import androidx.core.view.isNotEmpty
import androidx.interpolator.view.animation.FastOutSlowInInterpolator
import androidx.recyclerview.widget.RecyclerView
import androidx.transition.ChangeBounds
import androidx.transition.TransitionManager
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
import io.legado.app.help.config.AppConfig
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

    companion object {
        // Material standard duration：展开/收起动画统一固定时长，不再按内容高度计算
        private const val EXPAND_DURATION_MS = 220L
    }

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

            val actualPos = holder.layoutPosition - getHeaderCount()
            val isUpdate = payloads.isNotEmpty()

            resetFlexboxState(flexbox)

            if (exIndex == actualPos) {
                handleExpand(binding, item, isUpdate)
            } else {
                handleCollapse(binding, isUpdate)
            }
        }
    }

    /**
     * 重置 Flexbox 基础状态和取消未完成的动画
     */
    private fun resetFlexboxState(flexbox: FlexboxLayout) {
        (flexbox.tag as? ValueAnimator)?.cancel()
        flexbox.isVerticalScrollBarEnabled = false
        flexbox.isHorizontalScrollBarEnabled = false
        flexbox.overScrollMode = View.OVER_SCROLL_NEVER
    }

    /**
     * ================= 展开逻辑 =================
     */
    private fun handleExpand(
        binding: ItemFindBookBinding, item: BookSourcePart, isUpdate: Boolean
    ) {
        val bookSource = item.getBookSource() ?: return

        binding.ivStatus.setImageResource(R.drawable.ic_arrow_down)
        binding.rotateLoading.loadingColor = context.accentColor
        binding.rotateLoading.visible()

        Coroutine.async(callBack.scope) {
            bookSource.exploreKinds()
        }.onSuccess { kindList ->
            if (binding.flexbox.isEmpty()) {
                upKindList(binding.flexbox, bookSource, kindList)
            }

            if (!AppConfig.isEInkMode && isUpdate) {
                executeExpandAnimation(binding)
            } else {
                binding.flexbox.layoutParams = binding.flexbox.layoutParams.apply {
                    height = ViewGroup.LayoutParams.WRAP_CONTENT
                }
                binding.flexbox.visible()
            }
        }.onFinally {
            binding.rotateLoading.gone()
        }
    }

    /**
     * 高度展开动画：每帧只改高度，不在动画期间手动 scroll。
     * 下方 item 的让位由 RecyclerView 默认行为完成；如果展开后底部超出视口，动画结束时再 smoothScroll 一次。
     */
    private fun executeExpandAnimation(binding: ItemFindBookBinding) {
        val flexbox = binding.flexbox
        val root = binding.root
        val rv = root.parent as? RecyclerView

        flexbox.visible()
        flexbox.layoutParams.height = ViewGroup.LayoutParams.WRAP_CONTENT

        val targetHeight = measureFlexboxTargetHeight(flexbox, root, rv)
        val parentHeight = rv?.height ?: context.resources.displayMetrics.heightPixels
        val animTargetHeight = targetHeight.coerceAtMost(parentHeight)

        flexbox.layoutParams.height = 0

        val animator = ValueAnimator.ofInt(0, animTargetHeight).apply {
            interpolator = FastOutSlowInInterpolator()
            duration = EXPAND_DURATION_MS
            addUpdateListener {
                flexbox.layoutParams = flexbox.layoutParams.apply {
                    height = it.animatedValue as Int
                }
            }
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    flexbox.layoutParams.height = ViewGroup.LayoutParams.WRAP_CONTENT
                    flexbox.tag = null
                    ensureExpandedItemVisible(root, rv)
                }
            })
        }

        flexbox.tag = animator
        animator.start()
    }

    /**
     * 展开完成后按需平滑滚动，让展开行尽量完整可见。
     * 优先策略：底部超出 → 让标题贴顶（但不会把标题滚到顶部以上）；
     *           顶部被切 → 把标题拉回到顶部；
     *           其他情况不动。
     */
    private fun ensureExpandedItemVisible(itemView: View, rv: RecyclerView?) {
        if (rv == null) return
        val rvTop = rv.paddingTop
        val rvBottom = rv.height - rv.paddingBottom
        val viewTop = itemView.top
        val viewBottom = itemView.bottom

        val dy = when {
            viewBottom > rvBottom -> (viewTop - rvTop).coerceAtLeast(0)
            viewTop < rvTop -> viewTop - rvTop
            else -> 0
        }
        if (dy != 0) rv.smoothScrollBy(0, dy)
    }

    /**
     * ================= 收起逻辑 =================
     */
    private fun handleCollapse(binding: ItemFindBookBinding, isUpdate: Boolean) {
        val flexbox = binding.flexbox
        binding.rotateLoading.gone()

        if (flexbox.isGone) {
            binding.ivStatus.setImageResource(R.drawable.ic_arrow_right)
            return
        }

        if (!AppConfig.isEInkMode && isUpdate && flexbox.height > 0) {
            val rv = binding.root.parent as? RecyclerView
            val parentHeight = rv?.height ?: context.resources.displayMetrics.heightPixels
            val animStartHeight = flexbox.height.coerceAtMost(parentHeight)

            if (flexbox.height > animStartHeight) {
                flexbox.layoutParams.height = animStartHeight
            }

            val animator = ValueAnimator.ofInt(animStartHeight, 0).apply {
                interpolator = FastOutSlowInInterpolator()
                duration = EXPAND_DURATION_MS
                addUpdateListener {
                    flexbox.layoutParams = flexbox.layoutParams.apply {
                        height = it.animatedValue as Int
                    }
                }
                addListener(object : AnimatorListenerAdapter() {
                    override fun onAnimationEnd(animation: Animator) {
                        flexbox.gone()
                        flexbox.layoutParams.height = ViewGroup.LayoutParams.WRAP_CONTENT
                        recyclerFlexbox(flexbox)
                        flexbox.tag = null
                    }
                })
            }

            flexbox.tag = animator
            animator.start()
        } else {
            recyclerFlexbox(flexbox)
            flexbox.gone()
            flexbox.layoutParams = flexbox.layoutParams.apply {
                height = ViewGroup.LayoutParams.WRAP_CONTENT
            }
        }
        binding.ivStatus.setImageResource(R.drawable.ic_arrow_right)
    }

    /**
     * 测量 Flexbox 的预期高度
     */
    private fun measureFlexboxTargetHeight(
        flexbox: FlexboxLayout, root: View, rv: RecyclerView?
    ): Int {
        val lp = flexbox.layoutParams
        val horizontalMargin =
            (lp as? ViewGroup.MarginLayoutParams)?.let { it.leftMargin + it.rightMargin } ?: 0
        val parentWidth = if (root.width > 0) root.width else rv?.width ?: 1000

        val widthSpec = View.MeasureSpec.makeMeasureSpec(
            parentWidth - horizontalMargin, View.MeasureSpec.EXACTLY
        )
        val heightSpec = View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)

        flexbox.measure(widthSpec, heightSpec)
        return flexbox.measuredHeight
    }

    private fun upKindList(flexbox: FlexboxLayout, source: BookSource, kinds: List<ExploreKind>) {
        recyclerFlexbox(flexbox)
        if (kinds.isEmpty()) return

        kotlin.runCatching {
            kinds.forEach { kind ->
                val tv = getFlexboxChild(flexbox)
                flexbox.addView(tv)
                tv.text = kind.title

                if (kind.type == RowUi.Type.title) {
                    FlexChildStyle(layout_flexBasisPercent = 1F).apply(tv)
                } else {
                    kind.style().apply(tv)
                }

                tv.setOnClickListener {
                    when {
                        kind.url.isNullOrBlank() -> {}
                        kind.title.startsWith("ERROR:") -> it.activity?.showDialogFragment(
                            TextDialog("ERROR", kind.url)
                        )

                        kind.type == RowUi.Type.button -> CoroutineScope(IO).launch {
                            kotlin.runCatching {
                                runScriptWithContext { source.evalJS(kind.url) }
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
        if (flexbox.isNotEmpty()) {
            recycler.addAll(flexbox.children)
            flexbox.removeAllViews()
        }
    }

    override fun registerListener(holder: ItemViewHolder, binding: ItemFindBookBinding) {
        binding.apply {
            llTitle.setOnClickListener {
                val layoutPos = holder.layoutPosition
                val actualPos = layoutPos - getHeaderCount()
                val oldEx = exIndex
                exIndex = if (exIndex == actualPos) -1 else actualPos

                if (oldEx != -1) notifyItemChanged(oldEx + getHeaderCount(), false)

                if (exIndex != -1) {
                    notifyItemChanged(layoutPos, false)
                }
            }
            llTitle.onLongClick {
                showMenu(llTitle, holder.layoutPosition - getHeaderCount())
            }
        }
    }

    fun compressExplore(): Boolean {
        if (exIndex < 0) return false

        val oldExIndex = exIndex
        exIndex = -1
        notifyItemChanged(oldExIndex + getHeaderCount(), false)
        return true
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
                    if (!AppConfig.isEInkMode) {
                        (view.parent?.parent as? ViewGroup)?.let { parentView ->
                            TransitionManager.beginDelayedTransition(parentView, ChangeBounds())
                        }
                    }
                    exIndex = -1
                    notifyItemChanged(position + getHeaderCount(), false)
                }

                R.id.menu_del -> callBack.deleteSource(source)
            }
            true
        }
        popupMenu.show()
    }

    interface CallBack {
        val scope: CoroutineScope
        fun openExplore(source: BookSource, title: String, exploreUrl: String?)
        fun editSource(sourceUrl: String)
        fun toTop(source: BookSourcePart)
        fun deleteSource(source: BookSourcePart)
        fun searchBook(bookSource: BookSourcePart)
    }
}