package io.legado.app.lib.prefs

import android.content.Context
import android.graphics.drawable.Drawable
import android.util.AttributeSet
import android.view.LayoutInflater
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.TextView
import androidx.core.view.isGone
import androidx.core.view.isVisible
import androidx.preference.PreferenceViewHolder
import io.legado.app.R
import io.legado.app.lib.theme.accentColor
import io.legado.app.lib.theme.bottomBackground
import io.legado.app.lib.theme.getPrimaryTextColor
import io.legado.app.lib.theme.getSecondaryTextColor
import io.legado.app.utils.ColorUtils
import splitties.views.onLongClick
import kotlin.math.roundToInt

/**
 * 解析 [R.styleable.Preference_isBottomBackground] 属性。
 * 替代 Preference / SwitchPreference / NameListPreference / EditTextPreference 等子类中的重复解析逻辑。
 */
fun AttributeSet.parseIsBottomBackground(context: Context): Boolean {
    val typedArray = context.obtainStyledAttributes(this, R.styleable.Preference)
    val isBottomBackground = typedArray.getBoolean(R.styleable.Preference_isBottomBackground, false)
    typedArray.recycle()
    return isBottomBackground
}

open class Preference(context: Context, attrs: AttributeSet) :
    androidx.preference.Preference(context, attrs) {

    private var onLongClick: ((preference: Preference) -> Boolean)? = null
    private val isBottomBackground: Boolean

    init {
        layoutResource = R.layout.view_preference
        isBottomBackground = attrs.parseIsBottomBackground(context)
    }

    companion object {

        fun <T : View> bindView(
            context: Context,
            viewHolder: PreferenceViewHolder?,
            icon: Drawable?,
            title: CharSequence?,
            summary: CharSequence?,
            weightLayoutRes: Int? = null,
            viewId: Int? = null,
            weightWidth: Int = 0,
            weightHeight: Int = 0,
            isBottomBackground: Boolean = false
        ): T? {
            if (viewHolder == null) return null
            bindTexts(context, viewHolder, title, summary, isBottomBackground)
            bindIcon(context, viewHolder, icon)
            return bindWidget<T>(
                context, viewHolder, weightLayoutRes, viewId, weightWidth, weightHeight
            )
        }

        /** 绑定标题与摘要文本，并按 [isBottomBackground] 应用底栏主题色 */
        private fun bindTexts(
            context: Context,
            viewHolder: PreferenceViewHolder,
            title: CharSequence?,
            summary: CharSequence?,
            isBottomBackground: Boolean
        ) {
            val tvTitle = viewHolder.findViewById(R.id.preference_title) as? TextView
            tvTitle?.apply {
                text = title
                isVisible = !title.isNullOrEmpty()
            }
            val tvSummary = viewHolder.findViewById(R.id.preference_desc) as? TextView
            tvSummary?.apply {
                text = summary
                isGone = summary.isNullOrEmpty()
            }
            if (isBottomBackground && !viewHolder.itemView.isInEditMode) {
                val isLight = ColorUtils.isColorLight(context.bottomBackground)
                tvTitle?.setTextColor(context.getPrimaryTextColor(isLight))
                tvSummary?.setTextColor(context.getSecondaryTextColor(isLight))
            }
        }

        /** 绑定图标并应用强调色滤镜 */
        private fun bindIcon(
            context: Context,
            viewHolder: PreferenceViewHolder,
            icon: Drawable?
        ) {
            val iconView = viewHolder.findViewById(R.id.preference_icon)
            if (iconView is ImageView) {
                iconView.isVisible = icon != null
                iconView.setImageDrawable(icon)
                iconView.setColorFilter(context.accentColor)
            }
        }

        /**
         * 根据 [weightLayoutRes] / [viewId] 填充 widget 容器并返回目标 View。
         * - 若容器中已存在目标 View，则仅按需重新布局；
         * - 否则 inflate [weightLayoutRes] 到 [R.id.preference_widget] 容器中。
         */
        @Suppress("UNCHECKED_CAST")
        private fun <T : View> bindWidget(
            context: Context,
            viewHolder: PreferenceViewHolder,
            weightLayoutRes: Int?,
            viewId: Int?,
            weightWidth: Int,
            weightHeight: Int
        ): T? {
            if (weightLayoutRes == null || weightLayoutRes == 0 || viewId == null || viewId == 0) {
                return null
            }
            val lay = viewHolder.findViewById(R.id.preference_widget)
            if (lay !is FrameLayout) return null
            var needRequestLayout = false
            var v = viewHolder.itemView.findViewById<T>(viewId)
            if (v == null) {
                val inflater: LayoutInflater = LayoutInflater.from(context)
                val childView = inflater.inflate(weightLayoutRes, null)
                lay.removeAllViews()
                lay.addView(childView)
                lay.isVisible = true
                v = lay.findViewById(viewId)
            } else {
                needRequestLayout = true
            }

            if (weightWidth > 0 || weightHeight > 0) {
                val lp = lay.layoutParams
                if (weightHeight > 0)
                    lp.height =
                        (context.resources.displayMetrics.density * weightHeight).roundToInt()
                if (weightWidth > 0)
                    lp.width =
                        (context.resources.displayMetrics.density * weightWidth).roundToInt()
                lay.layoutParams = lp
            } else if (needRequestLayout)
                v?.requestLayout()

            return v
        }

    }

    final override fun onBindViewHolder(holder: PreferenceViewHolder) {
        super.onBindViewHolder(holder)
        onBindView(holder)
        onLongClick?.let { listener ->
            holder.itemView.onLongClick {
                listener.invoke(this)
            }
        }
    }

    open fun onBindView(holder: PreferenceViewHolder) {
        bindView<View>(
            context, holder, icon, title, summary,
            isBottomBackground = isBottomBackground
        )
    }

    fun onLongClick(listener: (preference: Preference) -> Boolean) {
        onLongClick = listener
    }

}
