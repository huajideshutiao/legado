package io.legado.app.ui.main.explore

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.content.Context
import android.view.View
import android.view.ViewGroup
import android.widget.GridLayout
import android.widget.PopupMenu
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.children
import androidx.core.view.isEmpty
import androidx.core.view.isGone
import androidx.core.view.isNotEmpty
import androidx.interpolator.view.animation.FastOutSlowInInterpolator
import androidx.recyclerview.widget.RecyclerView
import com.script.rhino.runScriptWithContext
import io.legado.app.R
import io.legado.app.base.adapter.ItemViewHolder
import io.legado.app.base.adapter.RecyclerAdapter
import io.legado.app.constant.AppLog
import io.legado.app.data.entities.BookSource
import io.legado.app.data.entities.BookSourcePart
import io.legado.app.data.entities.rule.ExploreKind
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
        const val PAYLOAD_REFRESH = "refresh"
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
            val actualPos = holder.layoutPosition - getHeaderCount()

            if (payloads.contains(PAYLOAD_REFRESH)) {
                if (exIndex == actualPos) handleRefresh(holder, binding, item)
                return@run
            }

            if (payloads.isEmpty()) {
                tvName.text = item.bookSourceName
            }

            val isUpdate = payloads.isNotEmpty()

            resetGridState(flexbox)

            if (exIndex == actualPos) {
                handleExpand(binding, item, isUpdate)
            } else {
                handleCollapse(binding, isUpdate)
            }
        }
    }

    /**
     * 重置分类网格的基础状态并取消未完成的动画
     */
    private fun resetGridState(grid: GridLayout) {
        (grid.tag as? ValueAnimator)?.cancel()
        grid.isVerticalScrollBarEnabled = false
        grid.isHorizontalScrollBarEnabled = false
        grid.overScrollMode = View.OVER_SCROLL_NEVER
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
        val grid = binding.flexbox
        val root = binding.root
        val rv = root.parent as? RecyclerView

        grid.visible()
        grid.layoutParams.height = ViewGroup.LayoutParams.WRAP_CONTENT

        val targetHeight = measureGridTargetHeight(grid, root, rv)
        val parentHeight = rv?.height ?: context.resources.displayMetrics.heightPixels
        val animTargetHeight = targetHeight.coerceAtMost(parentHeight)

        grid.layoutParams.height = 0

        val animator = ValueAnimator.ofInt(0, animTargetHeight).apply {
            interpolator = FastOutSlowInInterpolator()
            duration = EXPAND_DURATION_MS
            addUpdateListener {
                grid.layoutParams = grid.layoutParams.apply {
                    height = it.animatedValue as Int
                }
            }
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    grid.layoutParams.height = ViewGroup.LayoutParams.WRAP_CONTENT
                    grid.tag = null
                    ensureExpandedItemVisible(root, rv)
                }
            })
        }

        grid.tag = animator
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
     * ================= 刷新逻辑 =================
     * 长按菜单"刷新分类"触发：保持展开状态，原地替换 flexbox 内容，
     * 用 ValueAnimator 在旧/新高度之间平滑过渡。
     */
    private fun handleRefresh(
        holder: ItemViewHolder, binding: ItemFindBookBinding, item: BookSourcePart
    ) {
        val bookSource = item.getBookSource() ?: return
        val targetPos = holder.layoutPosition - getHeaderCount()
        val grid = binding.flexbox
        (grid.tag as? ValueAnimator)?.cancel()
        grid.tag = null
        binding.ivStatus.setImageResource(R.drawable.ic_arrow_down)
        binding.rotateLoading.loadingColor = context.accentColor
        binding.rotateLoading.visible()
        Coroutine.async(callBack.scope) {
            bookSource.exploreKinds()
        }.onSuccess { kinds ->
            // ViewHolder 在异步等待期间可能被复用绑定到别的 item，避免把内容写错位置
            val currentPos = holder.layoutPosition - getHeaderCount()
            if (currentPos != targetPos || exIndex != targetPos) return@onSuccess

            val startHeight = grid.height
            // 取消任何残留高度动画后再重建 children；不要走 TransitionManager，
            // 否则 RecyclerView 作为 sceneRoot 会把整批 child 误判为 disappear/appear，
            // 导致部分 TextView 进 overlay 后新 text/columnSpec 不生效。
            upKindList(grid, bookSource, kinds)

            if (!AppConfig.isEInkMode && startHeight > 0) {
                animateRefreshHeight(binding, grid, startHeight)
            } else {
                grid.layoutParams = grid.layoutParams.apply {
                    height = ViewGroup.LayoutParams.WRAP_CONTENT
                }
                grid.visible()
            }
        }.onFinally {
            if (holder.layoutPosition - getHeaderCount() == targetPos) {
                binding.rotateLoading.gone()
            }
        }
    }

    /**
     * 刷新后的高度过渡：从 startHeight 平滑到新内容的实际高度，避免一次性跳变。
     */
    private fun animateRefreshHeight(
        binding: ItemFindBookBinding, grid: GridLayout, startHeight: Int
    ) {
        val root = binding.root
        val rv = root.parent as? RecyclerView
        val targetHeight = measureGridTargetHeight(grid, root, rv)
        val parentHeight = rv?.height ?: context.resources.displayMetrics.heightPixels
        val endHeight = targetHeight.coerceAtMost(parentHeight)

        grid.layoutParams.height = startHeight
        grid.visible()

        val animator = ValueAnimator.ofInt(startHeight, endHeight).apply {
            interpolator = FastOutSlowInInterpolator()
            duration = EXPAND_DURATION_MS
            addUpdateListener {
                grid.layoutParams = grid.layoutParams.apply {
                    height = it.animatedValue as Int
                }
            }
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    grid.layoutParams.height = ViewGroup.LayoutParams.WRAP_CONTENT
                    grid.tag = null
                }
            })
        }
        grid.tag = animator
        animator.start()
    }

    /**
     * ================= 收起逻辑 =================
     */
    private fun handleCollapse(binding: ItemFindBookBinding, isUpdate: Boolean) {
        val grid = binding.flexbox
        binding.rotateLoading.gone()

        if (grid.isGone) {
            binding.ivStatus.setImageResource(R.drawable.ic_arrow_right)
            return
        }

        if (!AppConfig.isEInkMode && isUpdate && grid.height > 0) {
            val rv = binding.root.parent as? RecyclerView
            val parentHeight = rv?.height ?: context.resources.displayMetrics.heightPixels
            val animStartHeight = grid.height.coerceAtMost(parentHeight)

            if (grid.height > animStartHeight) {
                grid.layoutParams.height = animStartHeight
            }

            val animator = ValueAnimator.ofInt(animStartHeight, 0).apply {
                interpolator = FastOutSlowInInterpolator()
                duration = EXPAND_DURATION_MS
                addUpdateListener {
                    grid.layoutParams = grid.layoutParams.apply {
                        height = it.animatedValue as Int
                    }
                }
                addListener(object : AnimatorListenerAdapter() {
                    override fun onAnimationEnd(animation: Animator) {
                        grid.gone()
                        grid.layoutParams.height = ViewGroup.LayoutParams.WRAP_CONTENT
                        recyclerGrid(grid)
                        grid.tag = null
                    }
                })
            }

            grid.tag = animator
            animator.start()
        } else {
            recyclerGrid(grid)
            grid.gone()
            grid.layoutParams = grid.layoutParams.apply {
                height = ViewGroup.LayoutParams.WRAP_CONTENT
            }
        }
        binding.ivStatus.setImageResource(R.drawable.ic_arrow_right)
    }

    /**
     * 测量分类网格的预期高度
     */
    private fun measureGridTargetHeight(
        grid: GridLayout, root: View, rv: RecyclerView?
    ): Int {
        val lp = grid.layoutParams
        val horizontalMargin =
            (lp as? ViewGroup.MarginLayoutParams)?.let { it.leftMargin + it.rightMargin } ?: 0
        val parentWidth = if (root.width > 0) root.width else rv?.width ?: 1000

        val widthSpec = View.MeasureSpec.makeMeasureSpec(
            parentWidth - horizontalMargin, View.MeasureSpec.EXACTLY
        )
        val heightSpec = View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)

        grid.measure(widthSpec, heightSpec)
        return grid.measuredHeight
    }

    private fun upKindList(grid: GridLayout, source: BookSource, kinds: List<ExploreKind>) {
        recyclerGrid(grid)
        if (kinds.isEmpty()) return

        kotlin.runCatching {
            kinds.forEach { kind ->
                val tv = getGridChild(grid)
                grid.addView(tv)
                tv.text = kind.title

                kind.style().apply(tv)

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
    private fun getGridChild(grid: GridLayout): TextView {
        return if (recycler.isEmpty()) {
            ItemFilletTextBinding.inflate(inflater, grid, false).root
        } else {
            recycler.removeLastElement() as TextView
        }
    }

    @Synchronized
    private fun recyclerGrid(grid: GridLayout) {
        if (grid.isNotEmpty()) {
            recycler.addAll(grid.children)
            grid.removeAllViews()
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
                    notifyItemChanged(position + getHeaderCount(), PAYLOAD_REFRESH)
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