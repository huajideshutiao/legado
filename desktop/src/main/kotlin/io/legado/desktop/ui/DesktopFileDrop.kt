package io.legado.desktop.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.draganddrop.dragAndDropTarget
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.padding
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.State
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.draganddrop.DragAndDropEvent
import androidx.compose.ui.draganddrop.DragAndDropTarget
import androidx.compose.ui.draganddrop.DragData
import androidx.compose.ui.draganddrop.dragData
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import io.legado.app.ui.compose.theme.AppTheme
import io.legado.desktop.offerDroppedFiles

/**
 * 把"文件从资源管理器/Finder 拖进桌面主窗口"接进既有的文件关联分发链。
 *
 * # 为什么走 Compose 的 `Modifier.dragAndDropTarget` 而不是自己给 AWT Frame 装 DropTarget
 *
 * CMP 1.11.1 的桌面场景层 **无条件** 给场景容器装了 AWT `DropTarget`
 * (`org.jetbrains.compose.ui:ui-desktop` 的 `ComposeSceneMediator.desktop.kt:408-409`:
 * `container.transferHandler = dragAndDropManager.transferHandler` /
 * `container.dropTarget = dragAndDropManager.dropTarget`), 它的 `dropTargetListener.dragEnter`
 * 在没有 Compose 侧 target 认领时直接 `dtde.rejectDrag()`。同一个窗口上再装一个自己的
 * DropTarget 就是跟它抢注册 (谁能收到事件取决于 AWT 的层级解析顺序, 不可靠),
 * 所以正确做法是接进 Compose 这一层, 让平台管理器把事件投给本节点。
 *
 * 外部文件载荷确实支持: `ui-desktop` 的 `AwtDragData.kt` 里
 * `Transferable.dragData()` 第一分支就是 `DataFlavor.javaFileListFlavor` →
 * `DragData.FilesList.readFiles()` (返回 `File.toURI()` 形态的 `file:///…` 字符串)。
 *
 * 旧版 CMP 的 `Modifier.onExternalDrag` 在 1.11.1 已不存在 (ui-desktop 1.11.1 sources 零命中),
 * 不要用。
 *
 * # 命中与让位
 *
 * 本修饰符挂在主窗口根 Box 上 (最外层)。Compose 的拖放命中是"祖先先于子孙"的深度优先
 * (`DragAndDropNode.onMoved` → `firstDescendantOrNull`), 所以必须只认领**文件**载荷:
 * 文本/图片拖放 (如往搜索框里拖一段文字) 一律 `shouldStartDragAndDrop = false` 让位给
 * 内层 target (foundation 的输入框自带文本拖放 target)。
 *
 * # 拖放反馈 (高亮遮罩)
 *
 * 回调集取的是 CMP 1.11.1 真实存在的那几个 (`ui` 的 `DragAndDropTarget` 接口:
 * `onDrop` 抽象 + `onStarted/onEntered/onMoved/onExited/onChanged/onEnded` 默认空实现,
 * 见 ui-desktop-1.11.1-sources.jar `commonMain/androidx/compose/ui/draganddrop/DragAndDrop.kt:60-98`),
 * 反馈走"entered 起、exited/ended 落"的官方最小用法, 不自创遮罩体系:
 *
 * - `onEntered` → [dropHint]=true, `onExited`/`onEnded` → false。
 *   为什么 `onEnded` 也要清: 桌面通道 (`AwtDragAndDropManager.desktop.kt` 的 `dropTargetListener`)
 *   里 `dragEnter` 才转发 onStarted+onEntered, `dragExit` 转发 onExited+onEnded, 而**真正松手落下**
 *   走 `drop()`: 只发 onDrop + onEnded, **不发 onExited**。少接一个 onEnded, 成功落下后遮罩就粘住。
 * - `onMoved` 不重写: 它是 AWT `dragOver` 每次指针移动都要转发的最高频事件, 留空 = 拖拽过程中
 *   一次 state 写都不发生, 不会有逐帧重组。
 * - [dropHint] 的读点只有 [FileDropHintOverlay] 一处 (只有它订阅), 所以翻转它只重组那一个 Box,
 *   根 Box 下面整棵页面树不参与重组; `mutableStateOf` 默认 `structuralEqualityPolicy()`
 *   (Compose Runtime `SnapshotState.kt:64`), 重复写同值不产生失效, entered 连发也安全。
 * - 落点线程: `dropTargetListener` 是 AWT 的 DropTargetListener, 回调直接在 EDT 上同步进 Compose 节点;
 *   同一个 mediator 的鼠标输入也是在 EDT 监听里直接 `scene.onMouseWheelEvent(...)` 入场景、不跳线程
 *   (`ComposeSceneMediator.desktop.kt:504-505`) —— 即拖放回调与场景组合同源, 所以这里直接写 state,
 *   不再 invokeLater 包一层 (对 `MutableState` 的写本身就走 Snapshot 事务)。
 * - 拖文本时本 target 的 `shouldStartDragAndDrop` 返回 false → `dragEnter` 走 `rejectDrag()` 分支,
 *   根本不会收到 onEntered → 不会出现假高亮, 输入框自己的文本拖放照旧。
 *
 * @param dropHint 拖放反馈的瞬时 UI 态: 本修饰符写, [FileDropHintOverlay] 读。它**不是**业务状态,
 *   不要塞进 ScreenModel/UiState。调用方必须 `remember { mutableStateOf(false) }` 传同一个实例:
 *   `target` 实例变化会销毁重建拖放节点 (拖放进行中被打断 = 会话丢失)。
 */
@Composable
fun Modifier.onFilesDroppedToWindow(dropHint: MutableState<Boolean>): Modifier {
    // 两个钩子都要 remember: DropTargetElement 用 === 比较 shouldStartDragAndDrop,
    // target 实例变化会销毁重建拖放节点 (拖放进行中被重组打断)。
    val shouldStart = remember<(DragAndDropEvent) -> Boolean> {
        { event -> droppedFileUris(event) != null }
    }
    val target = remember(dropHint) {
        object : DragAndDropTarget {
            override fun onEntered(event: DragAndDropEvent) {
                dropHint.value = true
            }

            // onExited / onEnded 只碰 dropHint, 不碰 event: 这两路里 dragExit 构造的
            // DragAndDropEvent 的 nativeEvent 是裸 DropTargetEvent, 对它调 dragData() 会直接 error()
            // (见 droppedFileUris 的注释)。
            override fun onExited(event: DragAndDropEvent) {
                dropHint.value = false
            }

            override fun onEnded(event: DragAndDropEvent) {
                dropHint.value = false
            }

            override fun onDrop(event: DragAndDropEvent): Boolean {
                val uris = droppedFileUris(event) ?: return false
                offerDroppedFiles(uris)
                return true
            }
        }
    }
    return then(dragAndDropTarget(shouldStartDragAndDrop = shouldStart, target = target))
}

/**
 * [onFilesDroppedToWindow] 的反馈层: 文件被拖进窗口期间, 在整个客户区盖一层"accent 描边 + 同色极淡
 * 底 + 居中胶囊文案"的松手提示 (对应桌面平台拖放高亮的通用形态), 拖出/落下/会话结束自动消失。
 *
 * # 为什么画在这一层
 *
 * 热区判定 (`DragAndDropNode.contains`) 用的是挂修饰符那个节点的 `positionInRoot()` + 实测 size,
 * 事件坐标 `DragAndDropEvent.positionInRoot` 由 `AwtDragAndDropManager` 从 AWT 的
 * `DropTargetDragEvent.location` 换算而来 —— 两者同以**场景根容器**为原点。所以遮罩必须与热区同一个
 * 容器: 作为挂了 [onFilesDroppedToWindow] 的那个 Box 的子项、用 `matchParentSize()` 取同一个尺寸,
 * 做到"看到的高亮 == 能落下的范围", 不额外引入新图层窗口。
 *
 * 不加 pointerInput/clickable ⇒ 不吃鼠标事件; 也不是 dragAndDropTarget ⇒ 不参与拖放命中,
 * 不会把事件从根 Box 那里抢走。
 *
 * 本函数必须是 `BoxScope` 的扩展: `matchParentSize` 是 BoxScope 的**成员**扩展
 * (foundation-layout `Box.kt:262`), 不能 import, 只能靠隐式接收者解析。
 *
 * @param dropHint 与 [onFilesDroppedToWindow] 同一个实例。
 * @param text 提示文案 (默认只说"松开即可打开", 具体分流仍由 shared FileAssociationDispatch 决定)。
 */
@Composable
fun BoxScope.FileDropHintOverlay(
    dropHint: State<Boolean>,
    text: String = "松开即可打开",
) {
    // 唯一的 state 读点: 失效范围 = 本 composable 自身, 不牵连根 Box 的其它子项。
    if (!dropHint.value) return
    val accent = AppTheme.colors.accent
    Box(
        modifier = Modifier
            .matchParentSize()
            .background(accent.copy(alpha = DROP_SCRIM_ALPHA))
            .border(DROP_BORDER_WIDTH, accent),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = text,
            color = Color.White,
            modifier = Modifier
                .background(DROP_HINT_BACKGROUND, AppTheme.DesignTokens.shapeDefault)
                .padding(
                    horizontal = AppTheme.DesignTokens.spacingLg,
                    vertical = AppTheme.DesignTokens.spacingDefault,
                ),
        )
    }
}

/** 描边宽度: 官方示例那种小方块目标用 2dp 就够, 整窗口周长下 2dp 几乎看不见, 抬到 3dp。 */
private val DROP_BORDER_WIDTH = 3.dp

/** 遮罩底色: 只取主题 accent 的极淡一层, 保证底下页面仍看得清 (不是模态遮罩)。 */
private const val DROP_SCRIM_ALPHA = 0.10f

/** 文案胶囊底色, 与 [DesktopToastHost] 的 Toast 胶囊同值 (高对比深色半透明)。 */
private val DROP_HINT_BACKGROUND = Color(0xE6212121)

/**
 * 拖放载荷 → 文件地址列表。形态 = `File.toURI()`: Windows 为 `file:///D:/x/y.mp4`,
 * POSIX (macOS/Linux) 为 `file:/Users/x/y.mp4` —— 两种 [io.legado.app.ui.FileAssociationDispatch]
 * 的 toLocalPath 都已认 (argv 冷启动那条链就是收的同一形态);
 * 非文件载荷 / 空载荷 / 取数据抛异常 → null。
 *
 * runCatching 不是可有可无: `DragAndDropEvent.awtTransferable` 对认不出的原生事件直接 `error()`,
 * `getTransferData` 也会抛 UnsupportedFlavorException —— 而这些是在 AWT EDT 的拖放回调里跑的,
 * 抛出去就是窗口崩溃 (且发生在 CMP 的 DropTargetListener 内部, 不会被 Compose 的异常处理器接住)。
 */
@OptIn(ExperimentalComposeUiApi::class)
private fun droppedFileUris(event: DragAndDropEvent): List<String>? = runCatching {
    (event.dragData() as? DragData.FilesList)?.readFiles()?.takeIf { it.isNotEmpty() }
}.getOrNull()
