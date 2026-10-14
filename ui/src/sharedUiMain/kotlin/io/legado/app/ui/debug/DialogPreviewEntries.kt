package io.legado.app.ui.debug

import androidx.compose.runtime.Composable
import io.legado.app.ui.root.AppOverlay
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.stringResource

/**
 * 弹窗预览分组 (按弹窗形制划分, 便于改形制时横向对照)。
 *
 * - [BASE]: 对话框壳体与基础控件 (标题栏/按钮行/输入类控件), 改外观时最常用;
 * - [ALERT]: alert 形制 (标题 + 正文 + 按钮行), 含确认/提示/选择/文本输入;
 * - [DIALOG]: 居中/全屏对话框形制 (自带顶栏的壳);
 * - [SHEET]: 贴底弹层形制 (上圆角面板, 可拖拽收起)。
 */
enum class DialogPreviewGroup { BASE, ALERT, DIALOG, SHEET }

/**
 * 预览条目的显示名。
 *
 * 名字与它打开的那个弹窗显示的标题同源: 产品里该标题是文案资源, 就引用同一资源 (随语言切换, 也不会
 * 与弹窗漂移); 标题只是弹窗内部的内联字面量、是运行时取值、或弹窗本就没有标题时, 才写死文字。
 */
sealed interface DialogPreviewName {

    /** 引用弹窗标题的文案资源; 多段表示弹窗把多段资源拼成标题, [Part.args] 是资源的格式参数。 */
    data class Res(val parts: List<Part>) : DialogPreviewName {

        data class Part(val resource: StringResource, val args: List<Any> = emptyList())
    }

    /** 引用不到时写死的文字。 */
    data class Text(val text: String) : DialogPreviewName
}

/** 引用弹窗标题: 单段资源, 可带格式参数 (标题里的变量在预览里取固定值)。 */
internal fun titleOf(resource: StringResource, vararg args: Any): DialogPreviewName =
    DialogPreviewName.Res(listOf(DialogPreviewName.Res.Part(resource, args.toList())))

/** 引用弹窗标题: 弹窗把多段资源拼成一个标题时, 按拼接顺序给出。 */
internal fun titleConcat(vararg resources: StringResource): DialogPreviewName =
    DialogPreviewName.Res(resources.map { DialogPreviewName.Res.Part(it) })

/** 弹窗标题引用不到时写死的文字。 */
internal fun nameText(text: String): DialogPreviewName = DialogPreviewName.Text(text)

/** 解析成显示文字: 资源引用按当前语言取, 多段按弹窗的拼接顺序拼。 */
@Composable
internal fun DialogPreviewName.resolve(): String = when (this) {
    is DialogPreviewName.Res -> buildString {
        parts.forEach { part ->
            if (part.args.isEmpty()) {
                append(stringResource(part.resource))
            } else {
                append(stringResource(part.resource, *part.args.toTypedArray()))
            }
        }
    }

    is DialogPreviewName.Text -> text
}

/**
 * 预览清单条目。
 *
 * 两种承载方式对应产品里真实存在的两条链路, 预览不另造挂载点:
 * - [Inline]: 由调用方直接组合的弹窗 (产品里也是调用方直接调用的那些);
 * - [Overlay]: 走应用级覆盖物栈的弹窗, 点开即 [AppOverlay.Dialog] 入栈, 与真实调用完全同一条路径。
 */
sealed interface DialogPreviewEntry {
    val group: DialogPreviewGroup

    /** 稳定标识 (同一条目重复点开时重置内部状态用)。 */
    val id: String

    /** 列表显示名 (来源见 [DialogPreviewName])。 */
    val name: DialogPreviewName

    /**
     * 真实弹窗标识: 覆盖物条目＝覆盖物键, 内嵌条目＝弹窗组件名。
     *
     * 列表第二行展示它, 长按条目复制「名字 · 标识」—— 拿着这串就能直接定位到要改的那个弹窗。
     */
    val locator: String

    /** 直接组合的弹窗: [show] 收到关闭回调后由宿主卸载。 */
    class Inline(
        override val group: DialogPreviewGroup,
        override val id: String,
        override val name: DialogPreviewName,
        override val locator: String,
        val show: @Composable (onDismiss: () -> Unit) -> Unit,
    ) : DialogPreviewEntry

    /**
     * 覆盖物栈承载的弹窗/弹层: [build] 在点击时产出覆盖物 (payload 依赖当时的数据)。
     *
     * 类型是覆盖物基类而非仅 Dialog —— 半屏弹层 ([AppOverlay.Sheet]) 也走同一条入栈路径。
     */
    class Overlay(
        override val group: DialogPreviewGroup,
        override val id: String,
        override val name: DialogPreviewName,
        override val locator: String,
        val build: () -> AppOverlay,
    ) : DialogPreviewEntry
}
