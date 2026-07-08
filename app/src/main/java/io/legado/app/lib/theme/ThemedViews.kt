package io.legado.app.lib.theme

import android.content.Context
import android.view.View
import android.view.ViewGroup
import android.widget.CheckBox
import android.widget.EditText
import android.widget.ProgressBar
import android.widget.RadioButton
import android.widget.Switch
import android.widget.TextView
import androidx.appcompat.widget.SwitchCompat
import androidx.recyclerview.widget.RecyclerView
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout
import com.google.android.material.progressindicator.BaseProgressIndicator
import com.google.android.material.textfield.TextInputLayout

/**
 * 动态创建 View 的主题色兜底扩展。
 *
 * 背景: 代码里 `new EditText(context)` 等直接构造的 View 不走 LayoutInflater.inflate,
 * 不会被 ThemeInterceptor 的 Factory2 拦截。ThemeInterceptor.applyToTree 已在两个入口兜底:
 * - alert DSL 的 customView / customTitle (AndroidDialogs.kt)
 * - ItemViewHolder 构造 (覆盖所有继承 RecyclerAdapter / DiffRecyclerAdapter 的 Adapter)
 *
 * 但仍有少量场景绕过上述入口:
 * - 直接继承 RecyclerView.Adapter 且自定义 ViewHolder 的 Adapter (如 BookSourceEditAdapter)
 * - MenuItem actionView (MenuExtensions.kt)
 * - Dialog 内零散动态构造的 View (如 SourceLoginDialog 内 TextView)
 *
 * 这些场景推荐使用本文件提供的扩展函数替代裸 `new View(context)`,
 * 或对已构造的 View 调用 [View.applyTheme] / [View.applyThemeTree]。
 *
 * 用法:
 * ```kotlin
 * val et = context.editText { hint = "请输入" }
 * val tv = context.textView { setTextColor(Color.RED) }
 * container.applyThemeTree()   // 对已构造的容器整树着色
 * ```
 */

/**
 * 构造一个 EditText 并立即应用主题色。
 * 等价于 `EditText(this).also { ThemeInterceptor.applyDynamic(it) }`。
 *
 * @param init 可选的配置块, 在着色前执行 (避免着色覆盖业务设置的颜色)。
 *             注意: 主题色 tint 主要影响底线/光标/hint/link 颜色, 不影响 setTextColor 设置的文本颜色,
 *             但为安全起见, 业务配置应在此块内完成, 着色在最后执行。
 */
inline fun Context.editText(init: EditText.() -> Unit = {}): EditText =
    EditText(this).also {
        it.init()
        ThemeInterceptor.applyDynamic(it)
    }

inline fun Context.textView(init: TextView.() -> Unit = {}): TextView =
    TextView(this).also {
        it.init()
        ThemeInterceptor.applyDynamic(it)
    }

inline fun Context.checkBox(init: CheckBox.() -> Unit = {}): CheckBox =
    CheckBox(this).also {
        it.init()
        ThemeInterceptor.applyDynamic(it)
    }

inline fun Context.radioButton(init: RadioButton.() -> Unit = {}): RadioButton =
    RadioButton(this).also {
        it.init()
        ThemeInterceptor.applyDynamic(it)
    }

inline fun Context.switch(init: Switch.() -> Unit = {}): Switch =
    Switch(this).also {
        it.init()
        ThemeInterceptor.applyDynamic(it)
    }

inline fun Context.switchCompat(init: SwitchCompat.() -> Unit = {}): SwitchCompat =
    SwitchCompat(this).also {
        it.init()
        ThemeInterceptor.applyDynamic(it)
    }

inline fun Context.progressBar(init: ProgressBar.() -> Unit = {}): ProgressBar =
    ProgressBar(this).also {
        it.init()
        ThemeInterceptor.applyDynamic(it)
    }

inline fun <T : BaseProgressIndicator<*>> Context.baseProgressIndicator(
    factory: (Context) -> T,
    init: T.() -> Unit = {}
): T = factory(this).also {
    it.init()
    ThemeInterceptor.applyDynamic(it)
}

inline fun Context.swipeRefreshLayout(init: SwipeRefreshLayout.() -> Unit = {}): SwipeRefreshLayout =
    SwipeRefreshLayout(this).also {
        it.init()
        ThemeInterceptor.applyDynamic(it)
    }

inline fun Context.recyclerView(init: RecyclerView.() -> Unit = {}): RecyclerView =
    RecyclerView(this).also {
        it.init()
        ThemeInterceptor.applyDynamic(it)
    }

inline fun Context.textInputLayout(init: TextInputLayout.() -> Unit = {}): TextInputLayout =
    TextInputLayout(this).also {
        it.init()
        ThemeInterceptor.applyDynamic(it)
    }

/**
 * 对已构造的 View 应用主题色 (单 View, 不递归子树)。
 * 适用: 不在 alert DSL / ItemViewHolder 路径内的零散动态 View。
 */
fun View.applyTheme() {
    ThemeInterceptor.applyDynamic(this)
}

/**
 * 对已构造的 View 及其整棵子树应用主题色 (去重)。
 * 适用: 动态构造的容器 View (如 LinearLayout 内 addView 多个动态子 View),
 *       在 addView 完成后调用一次, 整树着色。
 */
fun View.applyThemeTree() {
    ThemeInterceptor.applyToTree(this)
}

/**
 * 对 ViewGroup 内所有子 View 应用主题色 (不包含自身)。
 * 适用: 父容器已通过其他路径着色 (如 inflate), 仅需对动态 addView 进来的子 View 兜底。
 */
fun ViewGroup.applyThemeToChildren() {
    for (i in 0 until childCount) {
        getChildAt(i)?.let { ThemeInterceptor.applyToTree(it) }
    }
}
