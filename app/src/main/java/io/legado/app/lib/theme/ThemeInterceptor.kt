package io.legado.app.lib.theme

import android.content.res.ColorStateList
import android.util.AttributeSet
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
import io.legado.app.help.config.AppConfig
import io.legado.app.lib.theme.ThemeInterceptor.appliedViews
import io.legado.app.lib.theme.ThemeInterceptor.apply
import io.legado.app.lib.theme.ThemeInterceptor.applyDynamic
import io.legado.app.lib.theme.ThemeInterceptor.applyToTree
import io.legado.app.utils.ColorUtils
import io.legado.app.utils.setEdgeEffectColor
import java.util.Collections
import java.util.WeakHashMap

/**
 * 自动主题拦截器：在 View 填充时自动应用 ThemeStore 中的颜色。
 * 解决“苦哈哈给所有控件上色”的问题。
 *
 * 覆盖路径:
 * 1. XML inflate 路径: 通过 BaseActivity / BaseDialogFragment 注册为 LayoutInflater.Factory2,
 *    在 [apply] 中接收 attrs (来自 XML 标签, 当前 when 分支未读取 attrs, 保留参数以便未来扩展)。
 * 2. 动态创建 View 路径: 代码里 `new EditText(context)` 等直接构造的 View 不走 inflate,
 *    通过 [applyDynamic] 单独着色, 或通过 [applyToTree] 递归着色整棵 View 树
 *    (alert DSL customView / ItemViewHolder 在此层拦截)。
 *
 * 去重: 已着色的 View 记录在 [appliedViews] (WeakHashMap), 避免重复执行 tint。
 * 用 WeakHashMap 而非 View.setTag(key, value) 的原因:
 * 1. setTag 的 key 必须是 application-specific resource id (需在 ids.xml 声明),
 *    为单一标记新建 ids.xml 不够轻量。
 * 2. WeakHashMap 不污染 View 的 tag (业务代码可自由使用 view.tag / setTag(key, value),
 *    如 BookSourceEditAdapter 用 root.tag 存 spinner/editText 引用)。
 * 3. WeakHashMap 持有 View 的弱引用, View 被 GC 回收时 entry 自动清除, 无内存泄漏。
 */
object ThemeInterceptor {

    /**
     * 已着色 View 集合。WeakHashMap 的 key 是弱引用, View 回收时自动清除 entry。
     * UI 操作均在主线程, 用 synchronizedMap 仅作防御性同步。
     */
    private val appliedViews = Collections.synchronizedMap(WeakHashMap<View, Boolean>())

    /**
     * 对单个 View 应用主题色。XML inflate 路径的入口。
     *
     * @param attrs XML 标签属性集, 当前 when 分支未读取 (历史遗留: 早期版本通过
     *              obtainStyledAttributes 解析 @color/accent 等, 性能优化后已移除)。
     *              保留为可选参数, 动态创建场景传 null 即可。
     */
    fun apply(view: View, attrs: AttributeSet? = null) {
        runCatching {
            val context = view.context
            val accentColor = context.accentColor
            val isDark = AppConfig.isNightTheme
            when (view) {
                is TextInputLayout -> {
                    setTint(view, accentColor, isDark)
                }

                is EditText -> {
                    TintHelper.setTintAuto(view, accentColor, false, isDark)
                    if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.VANILLA_ICE_CREAM) {
                        view.isLocalePreferredLineHeightForMinimumUsed = false
                    }
                    view.setLinkTextColor(accentColor)
                    view.setHighlightColor(ColorUtils.adjustAlpha(accentColor, 0.4f))
                }

                is CheckBox, is RadioButton, is Switch, is SwitchCompat -> {
                    TintHelper.setTintAuto(view, accentColor, false, isDark)
                }

                is BaseProgressIndicator<*> -> {
                    view.setIndicatorColor(accentColor)
                }

                is ProgressBar -> {
                    TintHelper.setTintAuto(view, accentColor, false, isDark)
                }

                is SwipeRefreshLayout -> {
                    view.setColorSchemeColors(accentColor)
                }

                is RecyclerView -> {
                    view.setEdgeEffectColor(accentColor)
                }

                is TextView -> {
                    view.setLinkTextColor(accentColor)
                    view.setHighlightColor(ColorUtils.adjustAlpha(accentColor, 0.4f))
                }
            }
            // 标记已着色, 供 applyToTree 去重 (Factory2 apply 过的 View 不会被 applyToTree 重复处理)。
            // 放在 runCatching 内部: 着色失败时不标记, 下次仍会重试。
            appliedViews[view] = true
        }
    }

    /**
     * 对动态创建的 View 应用主题色。等价于 [apply] 传 null attrs。
     *
     * 适用场景: 代码里 `new EditText(context)` 等直接构造的 View, 不走 LayoutInflater.inflate,
     * 因此不会触发 BaseActivity / BaseDialogFragment 的 Factory2 回调。
     */
    fun applyDynamic(view: View) {
        apply(view, null)
    }

    /**
     * 对 View 树递归应用主题色, 并用 [appliedViews] 标记去重。
     *
     * 拦截点:
     * - alert DSL 的 customView/customTitle (AndroidDialogs.kt): 传入的容器可能是代码动态构造
     *   (如 HomeTabEditDialog/WaitDialog), 也可能是 inflate (已通过 Factory2 着色)。
     *   两者统一在此处去重, inflate 的子节点已被 [apply] 标记, 遍历时零成本跳过。
     * - ItemViewHolder 构造: RecyclerView item View 同样混合 inflate/动态构造, 统一去重。
     *
     * 实现要点:
     * 1. 迭代而非递归, 防止深 View 栈 (如嵌套 LinearLayout) 触发 StackOverflowError。
     * 2. 根节点已标记则整树跳过 (应对 dialog 重 show / RecyclerView holder 复用)。
     * 3. 子节点已标记则跳过该节点本身, 但仍入栈遍历其子树
     *    (应对 inflate 子树中后续动态 addView 进来的未着色节点)。
     * 4. 单个 View 的着色异常被 [apply] 内部 runCatching 吞掉, 不会中断整树遍历。
     */
    fun applyToTree(root: View) {
        if (appliedViews.containsKey(root)) return
        applyDynamic(root)
        if (root is ViewGroup) {
            val stack = ArrayDeque<ViewGroup>()
            stack.addLast(root)
            while (stack.isNotEmpty()) {
                val group = stack.removeLast()
                for (i in 0 until group.childCount) {
                    val child = group.getChildAt(i) ?: continue
                    if (!appliedViews.containsKey(child)) {
                        applyDynamic(child)
                    }
                    if (child is ViewGroup) {
                        stack.addLast(child)
                    }
                }
            }
        }
    }

    /**
     * 为 TextInputLayout 动态应用主题色。
     *
     * 调研依据（Material TextInputLayout 1.14 源码 + javap 反编译确认）：
     *
     * 1. boxStrokeColor / boxStrokeColorStateList: 控制 FILLED/OUTLINED 模式的 box 描边/底线颜色。
     *    - setBoxStrokeColor(int) 仅设单色, updateBoxStrokeColorState() 在聚焦时改用
     *      focusedStrokeColor (默认取主题 colorControlActivated 静态值) → 聚焦底线颜色不跟随主题。
     *    - setBoxStrokeColorStateList(ColorStateList) 设置后, updateBoxStrokeColorState 优先用它
     *      ( boxStrokeColorStateList != null 时走 setStrokeStrokeColor(csl) 分支),
     *      不再回退到 focusedStrokeColor。用含 focused 状态的 ColorStateList 即可让聚焦底线
     *      跟随动态主题色 (用户报告的"点击输入框后底线颜色不对"根因)。
     *
     * 2. cursorColor: Material 1.14 新增 public setCursorColor(ColorStateList)。
     *    setCursorColor 会调 updateCursorColor(), 且 setEditText() 在 EditText attach 时
     *    也会调 updateCursorColor(), 所以无需 addOnEditTextAttachedListener 手动设置。
     *
     * 3. editTextBackgroundTintList: private 字段, 无 public setter (javap 确认),
     *    仅在 BOX_BACKGROUND_NONE 模式下覆盖 EditText supportBackgroundTintList。
     *    FILLED/OUTLINED 模式下底线由 boxStrokeColorStateList 控制, 无需设置此字段。
     *
     * 修复历史:
     * - 旧版: setBoxStrokeColor(单色) + addOnEditTextAttachedListener 里 setCursorTint。
     *   聚焦时 updateBoxStrokeColorState 回退到 focusedStrokeColor (主题静态值) → 底线颜色不对。
     * - 中间版: 尝试 layout.editTextBackgroundTintList = ... → 编译失败 (API 不存在)。
     * - 现版: setBoxStrokeColorStateList(createEditTextTintList) + setCursorColor(csl)。
     */
    private fun setTint(layout: TextInputLayout, color: Int, isDark: Boolean) {
        val csl = ColorStateList.valueOf(color)
        // boxStrokeColor 作单色兜底 (boxStrokeColorStateList 为 null 时使用)。
        layout.boxStrokeColor = color
        // 关键: 设置含 focused 状态的 ColorStateList, 防止 updateBoxStrokeColorState
        // 在聚焦时回退到默认 focusedStrokeColor (取自主题 colorControlActivated)。
        layout.setBoxStrokeColorStateList(
            TintHelper.createEditTextTintList(layout.context, color, isDark)
        )
        layout.defaultHintTextColor = csl
        layout.hintTextColor = csl
        // 光标颜色: setCursorColor 会在 EditText attach 时经 setEditText→updateCursorColor 生效,
        // 无需 addOnEditTextAttachedListener 手动设置。
        layout.setCursorColor(csl)
    }
}
