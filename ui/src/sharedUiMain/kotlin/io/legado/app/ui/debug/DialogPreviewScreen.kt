package io.legado.app.ui.debug

import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material.Icon
import androidx.compose.material.IconButton
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.sp
import io.legado.app.help.coroutine.IoDispatcher
import io.legado.app.help.toast.Toasters
import io.legado.app.ui.compose.component.AppSearchField
import io.legado.app.ui.compose.component.AppTitleBar
import io.legado.app.ui.compose.component.FastScrollLazyVerticalGrid
import io.legado.app.ui.compose.component.rememberResponsiveColumns
import io.legado.app.ui.compose.platform.AppBackHandler
import io.legado.app.ui.compose.theme.AppTheme
import io.legado.app.ui.compose.theme.AppTheme.DesignTokens
import io.legado.app.ui.root.AppNavigator
import io.legado.app.ui.root.PlatformCapabilityProviders
import kotlinx.coroutines.withContext
import legado.ui.generated.resources.Res
import legado.ui.generated.resources.copied_to_clipboard
import legado.ui.generated.resources.dialog_preview
import legado.ui.generated.resources.dialog_preview_group_alert
import legado.ui.generated.resources.dialog_preview_group_base
import legado.ui.generated.resources.dialog_preview_group_dialog
import legado.ui.generated.resources.dialog_preview_group_sheet
import legado.ui.generated.resources.ic_search
import legado.ui.generated.resources.search
import org.jetbrains.compose.resources.painterResource
import org.jetbrains.compose.resources.stringResource

/**
 * 弹窗预览页: 按弹窗形制分组列出全部对话框/弹层, 点一项就地弹出。
 *
 * # 数据安全
 * 页内所有弹窗读写的是**数据副本**: 桌面端入口先用 `VACUUM INTO` 取真实库的一致性快照并复制私有
 * 文件目录, 再以该数据根重启进程进入本页 (见 `io.legado.desktop.ui.DialogPreviewLauncher`)。
 * 故本页内点"保存/删除"也不会碰到正式数据。
 *
 * # 列表
 * 按容器宽度自动分列 (与规则管理/书架同一口径: 窄屏单列, 宽屏按比例加列), 分组标题跨整行。
 * 顶栏放大镜进搜索态, 即时按名称过滤 (与 tachi 插件管理页同一形制: 系统返回退搜索态, 返回箭头退出预览)。
 * 每项第二行是该弹窗的真实标识 (覆盖物键或弹窗组件名), 长按条目即复制「名字 · 标识」。
 *
 * # 覆盖范围
 * 每条对应产品里真实存在的一个弹窗 (覆盖物键或直接调用的弹窗组件), 走真实调用路径, 不复制实现。
 * 少数弹窗需要真实网络响应或活着的阅读会话 (大图查看/验证码/书源登录/段评列表), 打开后为空态或占位。
 */
@Composable
fun DialogPreviewScreen(navigator: AppNavigator) {
    // 清单在进入页面时构建一次 (含库读), 构建完成前不渲染列表
    var entries by remember { mutableStateOf<List<DialogPreviewEntry>?>(null) }
    // 当前打开的直接组合弹窗: 挂在页面层而不是列表项里 —— 列表项滚出组合会被回收,
    // 弹窗会跟着消失 (用户表现为"滚一下就没了")
    var openInline by remember { mutableStateOf<DialogPreviewEntry.Inline?>(null) }
    // 搜索态: null = 未搜索, "" = 已进搜索态但没输入
    var searchQuery by remember { mutableStateOf<String?>(null) }
    val searchFocus = remember { FocusRequester() }

    LaunchedEffect(Unit) {
        entries = withContext(IoDispatcher) { buildDialogPreviewEntries(navigator) }
    }

    // 搜索态的系统返回 = 退搜索态 (返回箭头仍退出预览, 与 tachi 插件管理页一致)
    AppBackHandler(enabled = searchQuery != null) { searchQuery = null }

    Column(Modifier.fillMaxSize()) {
        // 预览页是初始路由 (栈内只有它), pop 无处可去: 退出走宿主注入的重启入口
        AppTitleBar(
            title = stringResource(Res.string.dialog_preview),
            onBack = {
                val exit = DialogPreviewLaunchers.exitOrNull()
                if (exit != null) exit() else navigator.pop()
            },
            titleContent = if (searchQuery != null) {
                // 输入框顶掉标题 (与 tachi 插件管理页同一形制); 焦点请求挂在输入框本身
                val searchField: @Composable RowScope.() -> Unit = {
                    AppSearchField(
                        value = searchQuery.orEmpty(),
                        onValueChange = { searchQuery = it },
                        hint = stringResource(Res.string.search),
                        textFieldModifier = Modifier.focusRequester(searchFocus),
                    )
                    LaunchedEffect(Unit) { runCatching { searchFocus.requestFocus() } }
                }
                searchField
            } else {
                null
            },
            actions = {
                // 搜索态下隐藏入口 (退出由系统返回负责), 不切成关闭图标
                if (searchQuery == null) {
                    IconButton(onClick = { searchQuery = "" }) {
                        Icon(
                            painter = painterResource(Res.drawable.ic_search),
                            contentDescription = stringResource(Res.string.search),
                            tint = AppTheme.colors.primaryText,
                        )
                    }
                }
            },
        )
        val list = entries ?: return@Column
        val query = searchQuery.orEmpty()
        // 名字可能与弹窗标题同源 (引用文案资源), 只能在组合期解析; 解析后连同条目一起用于过滤与显示
        val rows = list.map { it to it.name.resolve() }
        val visible = if (query.isBlank()) rows else rows.filter { it.second.contains(query, true) }
        FastScrollLazyVerticalGrid(
            columns = rememberResponsiveColumns(1),
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f),
        ) {
            DialogPreviewGroup.entries.forEach { group ->
                val groupRows = visible.filter { it.first.group == group }
                if (groupRows.isEmpty()) return@forEach
                // 分组标题占满整行, 不被分列切开 (与规则管理页的分组标题同一做法)
                item(
                    key = "header_${group.name}",
                    span = { GridItemSpan(maxLineSpan) },
                    contentType = "header",
                ) {
                    Text(
                        text = stringResource(group.titleRes()),
                        color = AppTheme.colors.accent,
                        fontSize = 14.sp,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(
                                start = DesignTokens.spacingLg,
                                end = DesignTokens.spacingLg,
                                top = DesignTokens.spacingLg,
                                bottom = DesignTokens.spacingDefault,
                            ),
                    )
                }
                items(
                    items = groupRows,
                    key = { it.first.id },
                    contentType = { "item" },
                ) { row ->
                    DialogPreviewRow(
                        name = row.second,
                        locator = row.first.locator,
                        onClick = {
                            when (val entry = row.first) {
                                is DialogPreviewEntry.Inline -> openInline = entry
                                is DialogPreviewEntry.Overlay -> navigator.showOverlay(entry.build())
                            }
                        },
                    )
                }
            }
        }
    }

    // 直接组合的弹窗在页面层渲染 (与产品里调用方直接组合弹窗同语义: 关闭即卸载)
    openInline?.let { entry ->
        entry.show { openInline = null }
    }
}

@Composable
private fun DialogPreviewRow(name: String, locator: String, onClick: () -> Unit) {
    val copiedText = stringResource(Res.string.copied_to_clipboard)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            // 单击打开弹窗; 长按复制「名字 · 真实标识」, 拿着这串就能定位到要改的那个弹窗
            .combinedClickable(
                onClick = onClick,
                onLongClick = {
                    PlatformCapabilityProviders.get().copyToClipboard("$name · $locator")
                    Toasters.get().toast(copiedText)
                },
            )
            .padding(
                horizontal = DesignTokens.spacingLg,
                vertical = DesignTokens.spacingDefault,
            ),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                text = name,
                color = AppTheme.colors.primaryText,
                fontSize = 15.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.fillMaxWidth(),
            )
            Text(
                text = locator,
                color = AppTheme.colors.secondaryText,
                fontSize = 12.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

/** 分组标题的资源 id。 */
private fun DialogPreviewGroup.titleRes() = when (this) {
    DialogPreviewGroup.BASE -> Res.string.dialog_preview_group_base
    DialogPreviewGroup.ALERT -> Res.string.dialog_preview_group_alert
    DialogPreviewGroup.DIALOG -> Res.string.dialog_preview_group_dialog
    DialogPreviewGroup.SHEET -> Res.string.dialog_preview_group_sheet
}

/**
 * 汇总全部预览条目 (基础形制 + 三类弹窗形制)。
 *
 * 覆盖物条目与直接组合条目混在同一份清单里, 由 [DialogPreviewEntry] 的两种实现决定挂载方式。
 */
internal suspend fun buildDialogPreviewEntries(
    navigator: AppNavigator,
): List<DialogPreviewEntry> =
    buildList {
        addAll(buildBaseEntries())
        addAll(buildAlertEntries())
        addAll(buildDialogEntries(navigator))
        addAll(buildSheetEntries())
        addAll(buildOverlayEntries())
    }
