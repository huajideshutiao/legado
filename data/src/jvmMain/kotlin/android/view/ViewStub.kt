// android.view JVM 空壳 stub: 插件偏好输入框的动态校验合同 (插件在 TextWatcher 里经
// `editText.rootView.findViewById<Button>(android.R.id.button1)` 改确认键可用态) 需要
// 最小 id 查找链与 enabled 状态; 仅存在于 :data 的 jvm 变体 (data/src/jvmMain),
// :app (android 变体) 编译与打包不接触本目录。
package android.view

/**
 * View 空壳: 仅保证 id 查找合同与 enabled 状态可链接可执行, 不复刻绘制/布局行为。
 * [onEnabledChanged] 是宿主把插件校验结果同步到确认键的回调面 (真实 View 无此 API,
 * 桌面活适配专属)。
 */
open class View {

    companion object {
        /** 对齐 android.view.View.NO_ID。 */
        const val NO_ID: Int = -1

        /** 对齐 android.view.View.VISIBLE / INVISIBLE / GONE。 */
        const val VISIBLE: Int = 0x00000000
        const val INVISIBLE: Int = 0x00000004
        const val GONE: Int = 0x00000008
    }

    /** 对齐 android.view.View.setId/getId。 */
    var id: Int = NO_ID

    /** enabled 状态变化回调 (插件校验路径 setEnabled 时触发)。 */
    var onEnabledChanged: ((Boolean) -> Unit)? = null

    // internal: ViewGroup.addView 挂链用 (本 stub 文件内同一模块)
    internal var parent: ViewGroup? = null

    private var enabledState: Boolean = true

    /** 对齐 android.view.View.isEnabled() (插件以属性语法读写时解析到本方法对)。 */
    fun isEnabled(): Boolean = enabledState

    open fun setEnabled(enabled: Boolean) {
        enabledState = enabled
        onEnabledChanged?.invoke(enabled)
    }

    /** 对齐 android.view.View.setVisibility() (无布局系统, 仅保持可调用)。 */
    open fun setVisibility(visibility: Int) {
    }

    /** 对齐 android.view.View.findViewById(): 命中自身 id 即返回。 */
    open fun <T : View> findViewById(id: Int): T? {
        @Suppress("UNCHECKED_CAST")
        return if (id != NO_ID && id == this.id) this as T else null
    }

    /** 对齐 android.view.View.getRootView(): 沿 parent 链到根。 */
    open fun getRootView(): View {
        var root: View = this
        while (root.parent != null) {
            root = root.parent!!
        }
        return root
    }
}

/** ViewGroup 空壳: 承载 findViewById 的父子查找合同 (偏好弹窗把确认键与输入框放进同一树)。 */
open class ViewGroup : View() {

    private val children = ArrayList<View>()

    /** 对齐 android.view.ViewGroup.addView()。 */
    open fun addView(child: View) {
        children.add(child)
        child.parent = this
    }

    override fun <T : View> findViewById(id: Int): T? {
        super.findViewById<T>(id)?.let { return it }
        for (child in children) {
            child.findViewById<T>(id)?.let { return it }
        }
        return null
    }
}
