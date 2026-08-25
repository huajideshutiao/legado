package io.legado.app.ui.reader

import androidx.compose.runtime.Composable
import androidx.compose.ui.geometry.Rect
import io.legado.app.ui.compose.theme.AppTextMenuContent
import io.legado.app.ui.compose.theme.AppTextMenuEntry
import io.legado.app.ui.compose.theme.AppTextMenuHost
import legado.shared.generated.resources.Res
import legado.shared.generated.resources.bookmark
import legado.shared.generated.resources.browser
import legado.shared.generated.resources.copy
import legado.shared.generated.resources.lookup_word
import legado.shared.generated.resources.read_aloud
import legado.shared.generated.resources.replace
import legado.shared.generated.resources.search_content
import legado.shared.generated.resources.share
import org.jetbrains.compose.resources.stringResource

/** 平台附加菜单项 (Android: 系统注册的 ACTION_PROCESS_TEXT 应用; 其余端无等价机制)。 */
internal class ReaderTextPlatformAction(val label: String, val onClick: (String) -> Unit)

/**
 * 平台附加项。
 *
 * 阅读页正文是自绘 Canvas, 选区由 ReadViewComposable 自己算, 走不到 Compose 的文本选区通道,
 * 所以 foundation 内建的 PROCESS_TEXT 项 (见 AppTextToolbar 的说明) 在这里拿不到, 得自己列。
 * 好处是选中文本现成, 不像 SelectionContainer 那样要绕剪贴板。
 */
@Composable
internal expect fun rememberReaderTextPlatformActions(): List<ReaderTextPlatformAction>

/** 一次请求: 选中文本 + 选区锚点矩形 (与弹层宿主父节点同坐标空间)。 */
class ReaderTextSelectionRequest(val text: String, val anchor: Rect)

/** 阅读页选中文本的动作集, 顺序即菜单顺序 (对照原版 content_select_action.xml)。 */
class ReaderTextActions(
    val onReplace: (String) -> Unit,
    val onCopy: (String) -> Unit,
    val onBookmark: (String) -> Unit,
    val onReadAloud: (String) -> Unit,
    val onDict: (String) -> Unit,
    val onSearchContent: (String) -> Unit,
    val onBrowser: (String) -> Unit,
    val onShare: (String) -> Unit,
)

/**
 * 阅读页长按文本的浮动操作菜单: 复用主文本菜单的自绘弹层
 * (澎湃样式 + 溢出折叠 + 淡入淡出, 见 [AppTextMenuHost])。
 *
 * 各端原本是四份平行实现 (app 端 ActionMode.TYPE_FLOATING / iOS UIMenuController /
 * 桌面对话框), 这里下沉成一份。目前只有 Android 接了 (见 AndroidReaderPlatformProvider),
 * 其余端仍走各自实现。
 *
 * 内容不走 remember: 8~10 个项每次重组重建成本可忽略, 而 remember 会把 [onFinally] /
 * [actions] 的旧闭包一起钉住。菜单只在显示期间参与组合。
 *
 * @param request null = 不显示
 * @param onFinally 动作执行后收尾 (对照原版 onMenuActionFinally: 关菜单 + 取消页内选择)
 */
@Composable
fun ReaderTextActionMenu(
    request: ReaderTextSelectionRequest?,
    actions: ReaderTextActions,
    onFinally: () -> Unit,
) {
    val platformActions = rememberReaderTextPlatformActions()
    val replaceText = stringResource(Res.string.replace)
    val copyText = stringResource(Res.string.copy)
    val bookmarkText = stringResource(Res.string.bookmark)
    val readAloudText = stringResource(Res.string.read_aloud)
    val dictText = stringResource(Res.string.lookup_word)
    val searchContentText = stringResource(Res.string.search_content)
    val browserText = stringResource(Res.string.browser)
    val shareText = stringResource(Res.string.share)

    val content = request?.let { req ->
        fun entry(label: String, action: (String) -> Unit) =
            AppTextMenuEntry(label) {
                action(req.text)
                onFinally()
            }
        AppTextMenuContent(
            anchor = req.anchor,
            entries = buildList {
                add(entry(replaceText, actions.onReplace))
                add(entry(copyText, actions.onCopy))
                add(entry(bookmarkText, actions.onBookmark))
                add(entry(readAloudText, actions.onReadAloud))
                add(entry(dictText, actions.onDict))
                add(entry(searchContentText, actions.onSearchContent))
                add(entry(browserText, actions.onBrowser))
                add(entry(shareText, actions.onShare))
                // 平台附加项排在原版菜单项之后 (对照原版 onInitializeMenu 的 menuItemOrder 从 100 起)
                platformActions.forEach { add(entry(it.label, it.onClick)) }
            },
        )
    }
    AppTextMenuHost(content, onDismiss = onFinally)
}
