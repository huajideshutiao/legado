package io.legado.app.ui.reader

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Rect
import io.legado.app.ui.compose.theme.AppTextMenuContent
import io.legado.app.ui.compose.theme.AppTextMenuEntry
import io.legado.app.ui.compose.theme.AppTextMenuHost

/**
 * 阅读页长按图片的浮动操作菜单: 复用文本菜单的自绘弹层
 * (澎湃样式 + 溢出折叠 + 淡入淡出, 见 [AppTextMenuHost]), 与 [ReaderTextActionMenu] 同款。
 *
 * 对照原版 ReadBookActivity.onImageLongPress 的系统 ActionMode 浮窗 (旧 PopupAction)
 * 改为自绘: 菜单项 (查看/刷新/保存/选择目录) 与动作由平台侧 (Android MainActivity)
 * 提供, 关闭链路 ([ImageActionMenuRequest.onDismiss] / 条目动作后) 对照原版
 * popupAction.onDismiss → ReadBookEvents.postSelectionCancel:
 * 取消页内选择标志 (imageMenuShowing), 后续翻页/换章走同一条 selectionDismissed 链路
 * 调 [dismiss] 关闭。
 *
 * 请求经 [show]/[dismiss] 注入: Android 的 onImageLongPress 落在 Activity 层, 无组合
 * 环境; 宿主 [Host] 挂在文本菜单宿主旁 (MainActivity 根组合)。
 */
class ImageActionMenuRequest(
    /** 锚点矩形 (长按点), 与弹层宿主父节点同坐标空间 (窗口坐标)。 */
    val anchor: Rect,
    val entries: List<ImageActionMenuEntry>,
    /** 菜单被关闭 (点外部/退场) 时回调; 条目动作的关闭由条目 onClick 自行收尾。 */
    val onDismiss: () -> Unit,
)

/** 图片操作菜单项 (标签 + 动作), 标签由平台侧按当前语言解析。 */
class ImageActionMenuEntry(val label: String, val onClick: () -> Unit)

object ReaderImageActionMenu {
    var request by mutableStateOf<ImageActionMenuRequest?>(null)
        private set

    fun show(request: ImageActionMenuRequest) {
        this.request = request
    }

    fun dismiss() {
        request = null
    }

    /** 自绘浮动菜单宿主: 内容快照与动画语义同 [AppTextMenuHost], 挂载后由 show/dismiss 驱动。 */
    @Composable
    fun Host() {
        val req = request
        val content = req?.let {
            AppTextMenuContent(
                anchor = it.anchor,
                entries = it.entries.map { e -> AppTextMenuEntry(e.label, e.onClick) },
            )
        }
        AppTextMenuHost(content, onDismiss = { req?.onDismiss?.invoke() })
    }
}
