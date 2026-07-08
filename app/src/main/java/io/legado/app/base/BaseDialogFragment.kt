package io.legado.app.base

import android.content.DialogInterface
import android.content.DialogInterface.OnDismissListener
import android.os.Bundle
import android.view.LayoutInflater
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import androidx.annotation.LayoutRes
import androidx.annotation.MenuRes
import androidx.appcompat.app.AppCompatActivity
import androidx.core.graphics.drawable.toDrawable
import androidx.core.view.LayoutInflaterCompat
import androidx.fragment.app.DialogFragment
import androidx.fragment.app.FragmentManager
import androidx.lifecycle.lifecycleScope
import io.legado.app.R
import io.legado.app.constant.AppLog
import io.legado.app.help.config.AppConfig
import io.legado.app.help.coroutine.Coroutine
import io.legado.app.lib.theme.ThemeInterceptor
import io.legado.app.lib.theme.bottomBackground
import io.legado.app.lib.theme.filletBackground
import io.legado.app.ui.widget.AutoShrinkLinearLayout
import io.legado.app.ui.widget.TitleBar
import io.legado.app.utils.applyTint
import io.legado.app.utils.getCompatDrawable
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlin.coroutines.CoroutineContext


abstract class BaseDialogFragment(
    @LayoutRes private val layoutID: Int
) : DialogFragment() {

    protected open val applyFilletBackground: Boolean = true
    protected open val isFullHeight: Boolean = false

    /** 指向布局中 id=title_bar 的 TitleBar（XML 中有则非 null，无则 null） */
    protected val titleBar: TitleBar? get() = view?.findViewById(R.id.title_bar)

    private var onDismissListener: OnDismissListener? = null

    fun setOnDismissListener(onDismissListener: OnDismissListener?) {
        this.onDismissListener = onDismissListener
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        if (layoutID != 0) return inflater.inflate(layoutID, container, false)
        return super.onCreateView(inflater, container, savedInstanceState) ?: View(inflater.context)
    }

    override fun onStart() {
        super.onStart()
        dialog?.window?.let {
            it.decorView.setPadding(0, 0, 0, 0)
            it.addFlags(WindowManager.LayoutParams.FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS)
            if (view != null) {
                it.setBackgroundDrawable(android.graphics.Color.TRANSPARENT.toDrawable())
            }
            val attr = it.attributes
            if (AppConfig.isEInkMode) {
                it.clearFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
                attr.dimAmount = 0.0f
                attr.windowAnimations = 0
            } else {
                attr.windowAnimations = R.style.Animation_Dialog
            }
            it.attributes = attr
            val dm = resources.displayMetrics
            val width = (dm.widthPixels * 0.9).toInt().coerceAtMost((800 * dm.density).toInt())
            val maxHeight = (dm.heightPixels * 0.8).toInt()

            if (isFullHeight) {
                it.setLayout(width, maxHeight)
            } else {
                // Window WRAP_CONTENT 让对话框高度自动适应内容；
                // AutoShrinkLinearLayout.onMeasure 会限制总高度不超过 maxHeight，
                // 并处理 0dp+weight 子 View 在 AT_MOST 模式下的测量问题。
                // 数据变化后 View.requestLayout 会自动触发 Window 重新测量，无需手动 resize。
                (view as? AutoShrinkLinearLayout)?.maxHeight = maxHeight
                it.setLayout(width, WindowManager.LayoutParams.WRAP_CONTENT)
            }
        }
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        if (!AppConfig.isEInkMode && applyFilletBackground) {
            view.background = requireContext().filletBackground
            view.clipToOutline = true
        }
        // TitleBar 背景对齐内容区底栏色（XML 有 title_bar 时自动生效）
        if (!AppConfig.isEInkMode) {
            titleBar?.setBackgroundColor(requireContext().bottomBackground)
        }
        onFragmentCreated(view, savedInstanceState)
        observeLiveBus()
    }

    abstract fun onFragmentCreated(view: View, savedInstanceState: Bundle?)

    /** 一次性配置 title / 返回图标 / menu / 菜单监听。
     *  backNavigation=true（默认）：设置返回图标并 dismiss；false：不设置，子类自管。
     *  onMenuClick 接受 (MenuItem?) -> Boolean 以兼容 Toolbar.OnMenuItemClickListener 的可空签名。 */
    protected fun setupTitleBar(
        title: CharSequence? = null,
        backNavigation: Boolean = true,
        @MenuRes menuRes: Int = 0,
        onMenuClick: ((MenuItem?) -> Boolean)? = null
    ) {
        val bar = titleBar ?: return
        title?.let { bar.title = it }
        if (backNavigation) {
            bar.toolbar.navigationIcon = getCompatDrawable(
                androidx.appcompat.R.drawable.abc_ic_ab_back_material
            )
            bar.setNavigationOnClickListener { dismissAllowingStateLoss() }
        }
        if (menuRes != 0) {
            bar.toolbar.inflateMenu(menuRes)
            bar.toolbar.menu.applyTint(requireContext())
        }
        onMenuClick?.let { callback ->
            bar.toolbar.setOnMenuItemClickListener { item -> callback(item) }
        }
    }

    override fun show(manager: FragmentManager, tag: String?) {
        kotlin.runCatching {
            //在每个add事务前增加一个remove事务，防止连续的add
            manager.beginTransaction().remove(this).commit()
            super.show(manager, tag)
        }.onFailure {
            AppLog.put("显示对话框失败 tag:$tag", it)
        }
    }

    override fun onDismiss(dialog: DialogInterface) {
        super.onDismiss(dialog)
        onDismissListener?.onDismiss(dialog)
    }

    fun <T> execute(
        scope: CoroutineScope = lifecycleScope,
        context: CoroutineContext = Dispatchers.IO,
        block: suspend CoroutineScope.() -> T
    ) = Coroutine.async(scope, context) { block() }

    open fun observeLiveBus() {
    }

    /**
     * 给 Fragment 的 layoutInflater 套一层 Factory2,在 View 创建后调用 ThemeInterceptor.apply。
     *
     * 背景: Fragment 的 performGetLayoutInflater 会克隆 Activity 的 layoutInflater 后,
     * 用 LayoutInflaterCompat.setFactory2 把 Fragment 自己设为 mFactory2(覆盖式而非包装式),
     * 导致 BaseActivity 注册的 ThemeInterceptor 在 Fragment 内 inflate View 时不会被触发。
     * 这里在 onGetLayoutInflater 返回前先包一层,确保所有 inflate 的 View 都能走 ThemeInterceptor,
     * 与 Activity 行为保持一致,业务代码无需手动 applyTint。
     */
    override fun onGetLayoutInflater(savedInstanceState: Bundle?): LayoutInflater {
        val inflater = super.onGetLayoutInflater(savedInstanceState)
        if (!themeInterceptorFactoryInstalled) {
            originalFactory2 = inflater.factory2
            originalFactory = inflater.factory
            LayoutInflaterCompat.setFactory2(inflater, ThemeInterceptorFactory(inflater))
            themeInterceptorFactoryInstalled = true
        }
        return inflater
    }

    /**
     * 包装原有 factory2 的代理: 先让原 factory 创建 View (处理 <fragment> 标签),
     * 复用 Activity 的创建逻辑 (AppCompatDelegate + 全类名反射兜底), 然后统一调用 ThemeInterceptor.apply。
     *
     * 为什么需要复用 Activity 的创建逻辑: Fragment 的 layoutInflater 克隆自 Activity 后,
     * mFactory2/mFactory/mPrivateFactory 都被 Fragment 覆盖, BaseActivity 注册的 ThemeInterceptor
     * 在 Fragment 内不会被触发。这里在 factory2 层复用 Activity 的创建逻辑, 确保短名控件
     * (TextView/CheckBox/Switch 等) 经 AppCompat 转换后被 ThemeInterceptor 处理, 全类名自定义 View
     * 也通过反射兜底创建后被处理, 与 Activity 行为完全对齐。
     */
    private inner class ThemeInterceptorFactory(private val inflater: LayoutInflater) :
        LayoutInflater.Factory2 {

        private val createViewMethod by lazy {
            LayoutInflater::class.java.getDeclaredMethod(
                "createView",
                String::class.java,
                String::class.java,
                android.util.AttributeSet::class.java
            ).apply { isAccessible = true }
        }

        override fun onCreateView(
            parent: View?,
            name: String,
            context: android.content.Context,
            attrs: android.util.AttributeSet
        ): View? {
            // 1. 先让原有 factory2/factory 创建(处理 <fragment> 标签)
            // 2. 复用 Activity 的创建逻辑: AppCompatDelegate.createView (AppCompat 控件转换)
            //    + 全类名自定义 View 反射兜底
            // 3. 统一调用 ThemeInterceptor.apply
            val view = originalFactory2?.onCreateView(parent, name, context, attrs)
                ?: originalFactory?.onCreateView(name, context, attrs)
                ?: createViewLikeActivity(parent, name, context, attrs)
            view?.let { ThemeInterceptor.apply(it, attrs) }
            return view
        }

        override fun onCreateView(
            name: String,
            context: android.content.Context,
            attrs: android.util.AttributeSet
        ): View? {
            return onCreateView(null, name, context, attrs)
        }

        /**
         * 复用 Activity 的 View 创建逻辑, 与 BaseActivity.onCreateView 保持一致:
         * 1. AppCompatDelegate.createView 处理系统短名控件的 AppCompat 转换
         *    (TextView → AppCompatTextView, CheckBox → AppCompatCheckBox, Switch → SwitchCompat 等),
         *    让短名控件也能被 ThemeInterceptor 处理
         * 2. 全类名自定义 View (含 '.') 反射兜底创建
         */
        private fun createViewLikeActivity(
            parent: View?,
            name: String,
            context: android.content.Context,
            attrs: android.util.AttributeSet
        ): View? {
            val activity = activity as? AppCompatActivity ?: return null
            return runCatching {
                activity.delegate.createView(parent, name, context, attrs)
            }.getOrNull() ?: takeIf { name.contains('.') }?.let {
                runCatching {
                    createViewMethod.invoke(inflater, name, null, attrs) as? View
                }.getOrNull()
            }
        }
    }

    private var themeInterceptorFactoryInstalled = false
    private var originalFactory2: LayoutInflater.Factory2? = null
    private var originalFactory: LayoutInflater.Factory? = null
}