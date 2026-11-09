package io.legado.app.ui.reader

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Rect
import io.legado.app.ui.book.read.ReadBookEvents
import io.legado.app.ui.compose.platform.syncGetString
import io.legado.app.ui.compose.theme.AppTextMenuAnchorSpace
import io.legado.app.ui.compose.theme.AppTextMenuContent
import io.legado.app.ui.compose.theme.AppTextMenuEntry
import io.legado.app.ui.compose.theme.AppTextMenuHost

/**
 * 阅读页长按图片的浮动操作菜单: 复用文本菜单的自绘弹层
 * (澎湃样式 + 溢出折叠 + 淡入淡出, 见 [AppTextMenuHost]), 与 [ReaderTextActionMenu] 同款。
 *
 * 对照原版 ReadBookActivity.onImageLongPress 的系统 ActionMode 浮窗 (旧 PopupAction)
 * 改为自绘: 平台仅通过 [ReaderImageActions] 注入动作能力，菜单项文案、顺序及收尾由
 * [buildReaderImageActionMenuRequest] 统一装配。关闭链路对照原版
 * popupAction.onDismiss → ReadBookEvents.postSelectionCancel: 条目动作后直接收尾;
 * 点卡片外不经菜单 —— 透明外框不接收输入, 点按落到页内手势层, 它按原版
 * ACTION_DOWN 分支取消选择并关菜单 (ReadViewComposable 的 isActive/imageMenuShowing
 * 判据), 后续翻页/换章走同一条 selectionDismissed 链路调 [dismiss] 关闭。
 *
 * 请求经 [show]/[dismiss] 注入: Android 的 onImageLongPress 落在 Activity 层, 无组合
 * 环境; 宿主 [Host] 挂在文本菜单宿主旁 (MainActivity 根组合)。
 */
class ImageActionMenuRequest(
    /** 锚点矩形 (长按点), 与菜单宿主同处页面容器局部坐标。 */
    val anchor: Rect,
    val entries: List<ImageActionMenuEntry>,
)

/** 图片操作菜单项 (标签 + 动作)。 */
class ImageActionMenuEntry(val label: String, val onClick: () -> Unit)

/** 平台差异能力；目录选择为空时不显示“选择目录”。 */
class ReaderImageActions(
    val view: () -> Unit,
    val refresh: () -> Unit,
    val save: () -> Unit,
    val selectDirectory: (() -> Unit)? = null,
)

/**
 * 统一装配查看/刷新/保存/可选目录动作，固定顺序并保证动作异常时仍关闭菜单、取消页内选择。
 */
fun buildReaderImageActionMenuRequest(
    anchor: Rect,
    actions: ReaderImageActions,
): ImageActionMenuRequest {
    fun finish() {
        ReaderImageActionMenu.dismiss()
        ReadBookEvents.postSelectionCancel()
    }

    fun entry(labelKey: String, action: () -> Unit) =
        ImageActionMenuEntry(syncGetString(labelKey)) {
            try {
                action()
            } finally {
                finish()
            }
        }

    return ImageActionMenuRequest(
        anchor = anchor,
        entries = buildList {
            add(entry("show", actions.view))
            add(entry("refresh", actions.refresh))
            add(entry("action_save", actions.save))
            actions.selectDirectory?.let { add(entry("select_folder", it)) }
        },
    )
}

object ReaderImageActionMenu {
    var request by mutableStateOf<ImageActionMenuRequest?>(null)
        private set

    fun show(request: ImageActionMenuRequest) {
        this.request = request
    }

    fun show(anchor: Rect, actions: ReaderImageActions) {
        show(buildReaderImageActionMenuRequest(anchor, actions))
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
                // 一次 show() 的请求 = 一个显示会话
                session = it,
                // 长按点与宿主同处页面容器局部坐标
                anchorSpace = AppTextMenuAnchorSpace.Container,
                entries = it.entries.map { e -> AppTextMenuEntry(e.label, e.onClick) },
            )
        }
        AppTextMenuHost(content)
    }
}
