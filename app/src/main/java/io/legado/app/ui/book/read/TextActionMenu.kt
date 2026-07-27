package io.legado.app.ui.book.read

import android.app.SearchManager
import android.content.Context
import android.content.Intent
import android.content.pm.ResolveInfo
import android.graphics.Color
import android.graphics.Rect
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.StateListDrawable
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.PopupWindow
import android.widget.TextView
import androidx.core.net.toUri
import io.legado.app.R
import io.legado.app.constant.AppLog
import io.legado.app.help.config.AppConfig
import io.legado.app.lib.theme.backgroundColor
import io.legado.app.lib.theme.primaryTextColor
import io.legado.app.lib.theme.radius
import io.legado.app.lib.theme.stroke
import io.legado.app.utils.ColorUtils
import io.legado.app.utils.dpToPx
import io.legado.app.utils.isAbsUrl
import io.legado.app.utils.printOnDebug
import io.legado.app.utils.sendToClip
import io.legado.app.utils.share
import io.legado.app.utils.toastOnUi

/**
 * 阅读正文页文本选择菜单：MD2 风格自绘 PopupWindow，锚定自绘排版的选区。
 * 对接点（show/dismiss/CallBack）签名不变。
 */
class TextActionMenu(private val context: Context, private val callBack: CallBack) {

    private val contentRect = Rect()
    private var primaryPopup: PopupWindow? = null
    private var overflowPopup: PopupWindow? = null

    // 主菜单锚点与位置缓存：溢出菜单"先收起再展开"时复用定位
    private var anchorView: View? = null
    private var viewLoc = IntArray(2)
    // suppressPrimaryRestore：dismiss() 全量关闭时阻止溢出菜单 dismiss 监听恢复主菜单
    private var suppressPrimaryRestore = false

    private data class MenuAction(
        val id: Int,
        val title: CharSequence,
        val intent: Intent? = null,
    )

    /** 主排项：R.menu.content_select_action 中未标 never 的项，保持原顺序 */
    private val primaryActions by lazy {
        listOf(
            MenuAction(R.id.menu_replace, context.getString(R.string.replace)),
            MenuAction(R.id.menu_copy, context.getString(android.R.string.copy)),
            MenuAction(R.id.menu_bookmark, context.getString(R.string.bookmark)),
            MenuAction(R.id.menu_aloud, context.getString(R.string.read_aloud)),
            MenuAction(R.id.menu_dict, context.getString(R.string.dict)),
        )
    }

    /** 溢出项：原标 never 的三项 + 第三方 PROCESS_TEXT 意图注册项 */
    private val overflowActions by lazy {
        buildList {
            add(MenuAction(R.id.menu_search_content, context.getString(R.string.search_content)))
            add(MenuAction(R.id.menu_browser, context.getString(R.string.browser)))
            add(MenuAction(R.id.menu_share_str, context.getString(R.string.share)))
            runCatching {
                getSupportedActivities().forEach { info ->
                    add(
                        MenuAction(
                            id = View.NO_ID,
                            title = info.loadLabel(context.packageManager),
                            intent = createProcessTextIntentForResolveInfo(info)
                        )
                    )
                }
            }.onFailure {
                context.toastOnUi("获取文字操作菜单出错:${it.localizedMessage}")
            }
        }
    }

    fun show(
        view: View,
        startX: Int,
        startTopY: Int,
        endX: Int,
        endBottomY: Int
    ) {
        contentRect.set(
            minOf(startX, endX),
            startTopY,
            maxOf(startX, endX),
            endBottomY
        )
        // 主题/日夜可能变化，每次重建以取动态色
        dismiss()

        anchorView = view
        view.getLocationInWindow(viewLoc)
        createAndShowPrimary(view, viewLoc)
    }

    /**
     * 创建并显示主菜单 Popup（show 与溢出菜单关闭后恢复共用）。
     */
    private fun createAndShowPrimary(view: View, loc: IntArray) {
        val eInk = AppConfig.isEInkMode
        val bar = buildBar(eInk)
        val popup = PopupWindow(
            bar,
            ViewGroup.LayoutParams.WRAP_CONTENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply {
            isClippingEnabled = false
            // 非 focusable/非 outsideTouchable：外部触摸落到 readView，走原 ACTION_DOWN dismiss
            setBackgroundDrawable(cardBackground(eInk))
            if (!eInk) elevation = 6.dpToPx().toFloat()
        }
        primaryPopup = popup

        val dm = context.resources.displayMetrics
        bar.measure(
            View.MeasureSpec.makeMeasureSpec(dm.widthPixels, View.MeasureSpec.AT_MOST),
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)
        )
        val menuW = bar.measuredWidth
        val menuH = bar.measuredHeight

        val margin = 8.dpToPx()
        val gap = 8.dpToPx()

        var x = loc[0] + (contentRect.left + contentRect.right) / 2 - menuW / 2
        x = x.coerceIn(margin, (dm.widthPixels - menuW - margin).coerceAtLeast(margin))

        // 优先浮在选区上方，放不下翻到下方
        var y = loc[1] + contentRect.top - menuH - gap
        if (y < margin) {
            y = loc[1] + contentRect.bottom + gap
        }
        y = y.coerceAtMost((dm.heightPixels - menuH - margin).coerceAtLeast(margin))

        popup.showAtLocation(view, Gravity.NO_GRAVITY, x, y)
    }

    fun dismiss() {
        // 全量关闭：阻止溢出菜单 dismiss 监听恢复主菜单
        suppressPrimaryRestore = true
        dismissOverflow()
        primaryPopup?.dismiss()
        primaryPopup = null
        suppressPrimaryRestore = false
    }

    private fun dismissOverflow() {
        overflowPopup?.dismiss()
        overflowPopup = null
    }

    // ---- 视图构建 ----

    private fun buildBar(eInk: Boolean): View {
        val textColor = context.primaryTextColor
        val row = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        primaryActions.forEach { row.addView(barItemView(it, textColor)) }
        if (overflowActions.isNotEmpty()) {
            row.addView(overflowButton(textColor))
        }
        return row
    }

    private fun barItemView(action: MenuAction, textColor: Int): View {
        val h = 12.dpToPx()
        return TextView(context).apply {
            text = action.title
            setTextColor(textColor)
            textSize = 14f
            maxLines = 1
            gravity = Gravity.CENTER
            setPadding(h, 0, h, 0)
            minWidth = 44.dpToPx()
            background = pressBackground(textColor)
            isClickable = true
            isFocusable = true
            setOnClickListener { perform(action) }
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                44.dpToPx()
            )
        }
    }

    private fun overflowButton(textColor: Int): View {
        val p = 10.dpToPx()
        return ImageView(context).apply {
            setImageResource(R.drawable.ic_more_vert)
            setColorFilter(textColor)
            setPadding(p, p, p, p)
            background = pressBackground(textColor)
            isClickable = true
            isFocusable = true
            setOnClickListener { toggleOverflow(this) }
            layoutParams = LinearLayout.LayoutParams(44.dpToPx(), 44.dpToPx())
        }
    }

    private fun toggleOverflow(anchor: View) {
        if (overflowPopup?.isShowing == true) {
            dismissOverflow()
            return
        }
        val view = anchorView ?: return
        // 先收起主菜单（外观上"先收起再展开"，非直接 dropdown 展开）
        suppressPrimaryRestore = true
        primaryPopup?.dismiss()
        primaryPopup = null
        suppressPrimaryRestore = false

        val eInk = AppConfig.isEInkMode
        val textColor = context.primaryTextColor
        val list = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
        }
        overflowActions.forEach { list.addView(overflowItemView(it, textColor)) }

        // 复用主菜单定位：水平居中于选区，垂直优先上方放不下翻下方
        val dm = context.resources.displayMetrics
        list.measure(
            View.MeasureSpec.makeMeasureSpec(dm.widthPixels, View.MeasureSpec.AT_MOST),
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)
        )
        val menuW = list.measuredWidth
        val menuH = list.measuredHeight
        val margin = 8.dpToPx()
        val gap = 8.dpToPx()
        var x = viewLoc[0] + (contentRect.left + contentRect.right) / 2 - menuW / 2
        x = x.coerceIn(margin, (dm.widthPixels - menuW - margin).coerceAtLeast(margin))
        var y = viewLoc[1] + contentRect.top - menuH - gap
        if (y < margin) {
            y = viewLoc[1] + contentRect.bottom + gap
        }
        y = y.coerceAtMost((dm.heightPixels - menuH - margin).coerceAtLeast(margin))

        overflowPopup = PopupWindow(
            list,
            ViewGroup.LayoutParams.WRAP_CONTENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply {
            isFocusable = true
            isOutsideTouchable = true
            setBackgroundDrawable(cardBackground(eInk))
            if (!eInk) elevation = 6.dpToPx().toFloat()
            showAtLocation(view, Gravity.NO_GRAVITY, x, y)
        }
        // 溢出菜单被外部触摸/返回键关闭时恢复主菜单；dismiss() 全量关闭时抑制恢复
        overflowPopup?.setOnDismissListener {
            overflowPopup = null
            if (!suppressPrimaryRestore) {
                createAndShowPrimary(view, viewLoc)
            }
        }
    }

    private fun overflowItemView(action: MenuAction, textColor: Int): View {
        val h = 16.dpToPx()
        val v = 12.dpToPx()
        return TextView(context).apply {
            text = action.title
            setTextColor(textColor)
            textSize = 14f
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
            gravity = Gravity.CENTER_VERTICAL
            setPadding(h, v, h, v)
            minHeight = 44.dpToPx()
            background = pressBackground(textColor)
            isClickable = true
            isFocusable = true
            setOnClickListener { perform(action) }
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        }
    }

    private fun cardBackground(eInk: Boolean): GradientDrawable {
        return GradientDrawable().apply {
            cornerRadius = context.radius.defaultF
            setColor(context.backgroundColor)
            if (eInk) setStroke(context.stroke.thin, context.primaryTextColor)
        }
    }

    private fun pressBackground(baseColor: Int): StateListDrawable {
        val press = ColorUtils.withAlpha(baseColor, 0.12f)
        return StateListDrawable().apply {
            addState(intArrayOf(android.R.attr.state_pressed), ColorDrawable(press))
            addState(intArrayOf(), ColorDrawable(Color.TRANSPARENT))
        }
    }

    // ---- 点击处理 ----

    private fun perform(action: MenuAction) {
        if (!callBack.onMenuItemSelected(action.id)) {
            onActionClick(action)
        }
        // 与原 ActionMode 语义一致：回调内会 dismiss + cancelSelect
        callBack.onMenuActionFinally()
    }

    private fun onActionClick(action: MenuAction) {
        when (action.id) {
            R.id.menu_copy -> context.sendToClip(callBack.selectedText)
            R.id.menu_share_str -> context.share(callBack.selectedText)
            R.id.menu_browser -> {
                kotlin.runCatching {
                    val intent = if (callBack.selectedText.isAbsUrl()) {
                        Intent(Intent.ACTION_VIEW).apply {
                            data = callBack.selectedText.toUri()
                        }
                    } else {
                        Intent(Intent.ACTION_WEB_SEARCH).apply {
                            putExtra(SearchManager.QUERY, callBack.selectedText)
                        }
                    }
                    context.startActivity(intent)
                }.onFailure {
                    it.printOnDebug()
                    context.toastOnUi(it.localizedMessage ?: "ERROR")
                }
            }

            else -> action.intent?.let {
                kotlin.runCatching {
                    it.putExtra(Intent.EXTRA_PROCESS_TEXT, callBack.selectedText)
                    it.putExtra(Intent.EXTRA_PROCESS_TEXT_READONLY, false)
                    context.startActivity(it)
                }.onFailure { e ->
                    AppLog.put("执行文本菜单操作出错\n$e", e, true)
                }
            }
        }
    }

    private fun createProcessTextIntent(): Intent {
        return Intent()
            .setAction(Intent.ACTION_PROCESS_TEXT)
            .setType("text/plain")
    }

    private fun getSupportedActivities(): List<ResolveInfo> {
        return context.packageManager
            .queryIntentActivities(createProcessTextIntent(), 0)
    }

    private fun createProcessTextIntentForResolveInfo(info: ResolveInfo): Intent {
        return createProcessTextIntent()
            .putExtra(Intent.EXTRA_PROCESS_TEXT_READONLY, false)
            .setClassName(info.activityInfo.packageName, info.activityInfo.name)
    }

    interface CallBack {
        val selectedText: String

        fun onMenuItemSelected(itemId: Int): Boolean

        fun onMenuActionFinally()
    }
}
