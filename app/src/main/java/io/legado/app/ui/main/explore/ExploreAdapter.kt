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
        binding: ItemFindBookBinding,
        item: BookSourcePart,
        isUpdate: Boolean
    ) {
        val bookSource = item.getBookSource() ?: return

        binding.ivStatus.setImageResource(R.drawable.ic_arrow_down)
        binding.rotateLoading.loadingColor = context.accentColor
        binding.rotateLoading.visible()

        if (scrollTo >= 0) {
            callBack.scrollTo(scrollTo)
        }

        Coroutine.async(callBack.scope) {
            bookSource.exploreKinds()
        }.onSuccess { kindList ->
            if (binding.flexbox.isEmpty()) {
                upKindList(binding.flexbox, bookSource, kindList)
            }

            if (!AppConfig.isEInkMode && isUpdate) {
                executeExpandAnimation(binding)
            } else {
                binding.flexbox.layoutParams.height = ViewGroup.LayoutParams.WRAP_CONTENT
                binding.flexbox.visible()
            }
        }.onFinally {
            binding.rotateLoading.gone()
            if (scrollTo >= 0) {
                callBack.scrollTo(scrollTo)
                scrollTo = -1
            }
        }
    }

    /**
     * 执行高度展开动画及平滑同步滚动
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
        val durationMs = calculateDurationMs(animTargetHeight)

        flexbox.layoutParams.height = 0

        // 【核心优化：记录动画开始前的初始位置和目标位置】
        val startTop = root.top
        val targetTop = rv?.paddingTop ?: 0
        // 计算需要滚动的总距离
        val totalScrollDistance = startTop - targetTop

        val animator = ValueAnimator.ofInt(0, animTargetHeight).apply {
            addUpdateListener {
                // fraction 是动画进度，范围 0.0 ~ 1.0，自带缓动效果
                val fraction = it.animatedFraction

                // 1. 驱动高度向下扩张
                flexbox.layoutParams.height = it.animatedValue as Int
                flexbox.requestLayout()

                // 2. 驱动列表向上平滑滚动
                if (rv != null) {
                    // 计算在当前动画进度下，标题应该处于的 Y 轴位置
                    val expectedTop = startTop - (totalScrollDistance * fraction).toInt()
                    // 获取真实的当前位置
                    val currentTop = root.top
                    // 计算偏差值（包含动画所需的滚动量 + 修正由于高度变化带来的挤压偏移）
                    val dy = currentTop - expectedTop

                    // 利用底层的 scrollBy 逐帧推进滚动
                    if (dy != 0) {
                        rv.scrollBy(0, dy)
                    }
                }
            }
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    flexbox.layoutParams.height = ViewGroup.LayoutParams.WRAP_CONTENT
                    flexbox.tag = null
                }
            })
            duration = durationMs
        }

        flexbox.tag = animator
        animator.start()
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
            val startHeight = flexbox.height

            // 视口裁剪：瞬间砍掉不可见部分
            val animStartHeight = startHeight.coerceAtMost(parentHeight)
            val durationMs = calculateDurationMs(animStartHeight)

            if (startHeight > animStartHeight) {
                flexbox.layoutParams.height = animStartHeight
            }

            // 【核心优化 1：记录收起前的初始顶部坐标】
            val startTop = binding.root.top
            // 【核心优化 2：判断是否是单纯的收起动作】
            // 如果 exIndex == -1，说明没有新的项正在展开，我们才去锁死当前视图。
            val isPureCollapse = exIndex == -1

            val animator = ValueAnimator.ofInt(animStartHeight, 0).apply {
                addUpdateListener {
                    flexbox.layoutParams.height = it.animatedValue as Int
                    flexbox.requestLayout()

                    // 【核心优化 3：单纯收起时的“静止锚点锁”】
                    // 抵消由于高度缩小带来的 RecyclerView 默认滚动，让标题像钉在屏幕上一样平滑收起
                    if (isPureCollapse && rv != null) {
                        val currentTop = binding.root.top
                        val dy = currentTop - startTop
                        // 利用底层 scrollBy 逐帧将标题拉回原本的位置
                        if (dy != 0) {
                            rv.scrollBy(0, dy)
                        }
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
                duration = durationMs
            }

            flexbox.tag = animator
            animator.start()
        } else {
            recyclerFlexbox(flexbox)
            flexbox.gone()
            flexbox.layoutParams.height = ViewGroup.LayoutParams.WRAP_CONTENT
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

    /**
     * 根据高度计算动画时长
     */
    private fun calculateDurationMs(heightPx: Int): Long {
        val density = context.resources.displayMetrics.density
        return (heightPx / 2 / density).toLong().coerceAtLeast(150L)
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
        fun scrollTo(pos: Int)
        fun openExplore(source: BookSource, title: String, exploreUrl: String?)
        fun editSource(sourceUrl: String)
        fun toTop(source: BookSourcePart)
        fun deleteSource(source: BookSourcePart)
        fun searchBook(bookSource: BookSourcePart)
    }
}