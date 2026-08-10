package io.legado.app.ui.root

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Overlay 栈抽象：管理 Dialog/Sheet 顺序与互斥语义。
 */
class OverlayBackStack(
    initialOverlays: List<AppOverlay> = emptyList(),
) {
    private val _overlays: MutableStateFlow<List<AppOverlay>> = MutableStateFlow(initialOverlays)
    val overlays: StateFlow<List<AppOverlay>> = _overlays.asStateFlow()

    val size: Int get() = _overlays.value.size

    fun peek(): AppOverlay? = _overlays.value.lastOrNull()

    fun show(overlay: AppOverlay) {
        _overlays.value = _overlays.value.filterNot { it.key == overlay.key } + overlay
    }

    fun dismiss(key: String): Boolean {
        val current = _overlays.value
        val updated = current.filterNot { it.key == key }
        if (updated.size == current.size) return false
        _overlays.value = updated
        return true
    }

    /** 栈顶 Overlay 是否可由返回键关闭 (dismissOnBack)。与 [dismissTop] 同判据, 供
     * 返回键拦截器判定 "栈顶 overlay 吃返回键是否真的会改变界面", 避免两处逻辑漂移。 */
    fun isTopDismissibleOnBack(): Boolean {
        val top = _overlays.value.lastOrNull() ?: return false
        return when (top) {
            is AppOverlay.Dialog -> top.dismissOnBack
            is AppOverlay.Sheet -> top.dismissOnBack
        }
    }

    fun dismissTop(): Boolean {
        if (!isTopDismissibleOnBack()) return false
        _overlays.value = _overlays.value.dropLast(1)
        return true
    }

    fun clear() {
        _overlays.value = emptyList()
    }
}
