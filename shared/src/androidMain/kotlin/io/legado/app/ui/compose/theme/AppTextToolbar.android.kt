package io.legado.app.ui.compose.theme

import androidx.compose.foundation.MutatorMutex
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.text.contextmenu.data.TextContextMenuItem
import androidx.compose.foundation.text.contextmenu.data.TextContextMenuSession
import androidx.compose.foundation.text.contextmenu.provider.LocalTextContextMenuToolbarProvider
import androidx.compose.foundation.text.contextmenu.provider.TextContextMenuDataProvider
import androidx.compose.foundation.text.contextmenu.provider.TextContextMenuProvider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.neverEqualPolicy
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import kotlinx.coroutines.channels.Channel

/**
 * [ProvidePlatformTextMenu] 的 Android actual: 接新的 text context menu 通道。
 *
 * 占位 [LocalTextContextMenuToolbarProvider] 后, 框架的
 * ProvideDefaultPlatformTextContextMenuProviders 只补默认 dropdown (鼠标右键那套) 而不再装
 * AndroidTextContextMenuToolbarProvider, 长按选区就不会起平台 ActionMode。
 *
 * Box 只为拿一份 LayoutCoordinates: TextContextMenuDataProvider.contentBounds 要求给出目标
 * 坐标空间, 弹层挂在同一个 Box 里, Popup 的 anchorBounds 便正是该 Box 的窗口位置。
 * onGloballyPositioned 可能带着同一实例回调 (位置变了), 故用 neverEqualPolicy 强制通知。
 *
 * 坐标以 [State] 形式传进 [AndroidTextMenuProvider.Host] 而不是先取值: Box 的 content 是
 * inline lambda, 在那里读状态会把整个 `content()` 一起拖进重组, 每帧布局都重来一遍。
 */
@Composable
internal actual fun ProvidePlatformTextMenu(
    state: AppTextMenuState,
    content: @Composable () -> Unit,
) {
    val provider = remember { AndroidTextMenuProvider() }
    val hostCoordinates = remember {
        mutableStateOf<LayoutCoordinates?>(null, neverEqualPolicy())
    }
    CompositionLocalProvider(LocalTextContextMenuToolbarProvider provides provider) {
        Box(
            Modifier.onGloballyPositioned { hostCoordinates.value = it },
            propagateMinConstraints = true,
        ) {
            content()
            provider.Host(state, hostCoordinates)
        }
    }
}

/**
 * 把框架的菜单数据渲染成自绘弹层。
 *
 * 剪切/复制/粘贴/全选与 ACTION_PROCESS_TEXT 第三方项 (翻译/流转/搜索等) 都由框架按真实选区
 * 构建好塞进 [TextContextMenuDataProvider.data], 标签也已本地化, 这里只负责画。
 */
private class AndroidTextMenuProvider : TextContextMenuProvider {

    /** 与框架同构: 新的一次 show 取消上一次, 避免多字段同时持有会话。 */
    private val mutatorMutex = MutatorMutex()

    private var request by mutableStateOf<Request?>(null)

    override suspend fun showTextContextMenu(dataProvider: TextContextMenuDataProvider) {
        mutatorMutex.mutate {
            val session = MenuSession()
            try {
                request = Request(session, dataProvider)
                session.awaitClose()
            } finally {
                if (request?.session === session) request = null
            }
        }
    }

    @Composable
    fun Host(state: AppTextMenuState, hostCoordinates: State<LayoutCoordinates?>) {
        // 不提前 return: 退场动画期间 request 已为 null, 宿主仍要参与组合。
        // 坐标只在有请求时才读: hostCoordinates 是 neverEqualPolicy, 无条件读会让本作用域
        // 跟着每一次布局 pass 重组 (全局每帧), 而菜单没弹时根本用不到它。
        val current = request
        val content = if (current == null) {
            null
        } else {
            // data() / contentBounds() 都是快照感知的, 组合期直接读 → 选区或剪贴板一变自动重组
            hostCoordinates.value?.takeIf { it.isAttached }?.let { coordinates ->
                current.entries(state.findReplaceAction).takeIf { it.isNotEmpty() }?.let {
                    AppTextMenuContent(current.dataProvider.contentBounds(coordinates), it)
                }
            }
        }
        AppTextMenuHost(content)
    }

    private inner class Request(
        val session: MenuSession,
        val dataProvider: TextContextMenuDataProvider,
    ) {
        fun entries(findReplace: (() -> Unit)?): List<AppTextMenuEntry> = buildList {
            dataProvider.data().components.forEach { component ->
                // 分隔符按自绘样式不画; TextClassifier 智能项是 internal 类型, 取不到标签只能跳过
                if (component is TextContextMenuItem) {
                    add(AppTextMenuEntry(component.label) { activate { component.onClick(it) } })
                }
            }
            findReplace?.let { action ->
                add(AppTextMenuEntry(FIND_REPLACE_LABEL) { activate { action() } })
            }
        }

        /**
         * 先关菜单再执行动作。
         *
         * 顺序是刻意的: PROCESS_TEXT 第三方项 (流转/翻译等) 会 startActivity, 本窗口随即失去
         * 帧时钟, 之后再关要等回到前台才渲染得出来 —— 表现为"点了第三方项菜单不关"。
         * 剪切/复制/全选看不出问题是因为它们额外清了选区, 框架会再走一遍 hide()。
         * 关闭幂等 (框架项自己的 onClick 末尾也会 close 一次), 动作抛异常也不会漏关。
         */
        private fun activate(action: (MenuSession) -> Unit) {
            try {
                action(session)
            } finally {
                session.close()
            }
        }
    }

    /**
     * 关闭时**同步**清 [request], 不等协程从 [awaitClose] 恢复: 恢复要经一次 Main 派发,
     * 而点击第三方项那一下之后本窗口可能已经不再出帧。channel 只用于让 showTextContextMenu 收尾。
     */
    private inner class MenuSession : TextContextMenuSession {
        /** CONFLATED: close() 早于 awaitClose() 时也不丢信号 (rendezvous 会丢)。 */
        private val channel = Channel<Unit>(Channel.CONFLATED)

        override fun close() {
            if (request?.session === this) request = null
            channel.trySend(Unit)
        }

        suspend fun awaitClose() {
            channel.receive()
        }
    }
}
