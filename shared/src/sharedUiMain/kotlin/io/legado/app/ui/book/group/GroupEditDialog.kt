// I18N KEYS: group_edit, group_add, allow_drop_down_refresh, book_sort_default, book_sort_reading_time, book_sort_update_time, book_sort_name, book_sort_manual, book_sort_comprehensive, book_sort_author
package io.legado.app.ui.book.group

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.DropdownMenuItem
import androidx.compose.material.Icon
import androidx.compose.material.Surface
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.legado.app.data.entities.BookGroup
import io.legado.app.help.coroutine.IoDispatcher
import io.legado.app.help.toast.Toasters
import io.legado.app.ui.bookshelf.LocalGroupCoverSlot
import io.legado.app.ui.bookshelf.SharedGroupCover
import io.legado.app.ui.compose.component.AlertButton
import io.legado.app.ui.compose.component.AppAlertDialog
import io.legado.app.ui.compose.component.AppCheckbox
import io.legado.app.ui.compose.component.AppDialog
import io.legado.app.ui.compose.component.AppDialogSizes
import io.legado.app.ui.compose.component.AppDropdownMenu
import io.legado.app.ui.compose.component.AppTextButton
import io.legado.app.ui.compose.component.AppUnderlineTextField
import io.legado.app.ui.compose.component.DialogTitleBar
import io.legado.app.ui.compose.component.appDialogSize
import io.legado.app.ui.compose.platform.rememberString
import io.legado.app.ui.compose.theme.AppTheme
import io.legado.app.ui.compose.theme.AppTheme.DesignTokens
import io.legado.app.ui.root.FileFilter
import io.legado.app.ui.root.PlatformServiceProviders
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import legado.shared.generated.resources.Res
import legado.shared.generated.resources.allow_drop_down_refresh
import legado.shared.generated.resources.book_sort_author
import legado.shared.generated.resources.book_sort_comprehensive
import legado.shared.generated.resources.book_sort_default
import legado.shared.generated.resources.book_sort_manual
import legado.shared.generated.resources.book_sort_name
import legado.shared.generated.resources.book_sort_reading_time
import legado.shared.generated.resources.book_sort_update_time
import legado.shared.generated.resources.cancel
import legado.shared.generated.resources.delete
import legado.shared.generated.resources.group_name
import legado.shared.generated.resources.ic_arrow_drop_down
import legado.shared.generated.resources.ok
import legado.shared.generated.resources.sort
import legado.shared.generated.resources.sure_del
import org.jetbrains.compose.resources.painterResource
import org.jetbrains.compose.resources.stringResource

/**
 * 分组编辑对话框 (KMP 共享, app + desktop 复用)。
 *
 * 对应 app 端 `io.legado.app.ui.book.group.GroupEditDialog` (基于 BaseComposeDialogFragment),
 * 但去掉对 Android Fragment / registerHandleFile / appDb / ViewModel 的依赖, 改为纯
 * @Composable + 回调形式:
 * - 调用方传入 [group] (null=新增) 与 [onConfirm] / [onDismiss] / [onDelete] 回调
 * - 字段: 分组名 (AppUnderlineTextField) + 排序 (AppDropdownMenu, 7 项对照 @array/book_sort)
 *   + 允许下拉刷新 (AppCheckbox)
 * - 确认时把字段写回 [group] (编辑态) 或新 [BookGroup] (新增态), 通过 [onConfirm] 回传;
 *   实际 DB 持久化 (viewModel.upGroup / addGroup) 由调用方决定
 * - 删除: 编辑态且 groupId > 0 || groupId == Long.MIN_VALUE 时显示删除按钮, 点击弹出
 *   AppAlertDialog 二次确认 (对齐原 alert(R.string.delete, R.string.sure_del))
 *
 * # KMP 化取舍
 *
 * - 封面: 恒显示封面区 (对照原版 ivCover)。渲染缺省取书架同源槽 [LocalGroupCoverSlot]
 *   (与书架 style2 分组条目同一实现, 默认 [SharedGroupCover], 行为以书架封面为准:
 *   真封面 → 加载失败/无封面/useDefaultCover 时走用户图集/内置默认封面图),
 *   可用 [coverSlot] 覆盖注入平台实现; 选图缺省走 [PlatformServiceProviders] 文件选择器,
 *   可用 [onPickCover] 覆盖
 * - 排序选项原用 stringArrayResource(R.array.book_sort), 该数组未在 rememberStringArray
 *   注册且不能修改 ResourceProvider.jvm.kt, 在文件内用 7 个新 key 硬编码 (值与 app 端
 *   values-zh/strings.xml 对齐)
 * - 空名称通过共享 Toaster 提示，行为与 Android 原版一致。
 *
 * # 样式 (Arco Design 规范)
 *
 * - 主色: arcoblue-6 (#165DFF) —— 确定按钮文字 + DialogTitleBar 返回箭头
 * - 圆角: arco_radius_lg = 16dp —— Surface shape
 * - 无阴影 (Surface 默认无阴影)
 *
 * @param group 待编辑的分组 (null=新增); 编辑态基于副本修改并通过 [onConfirm] 回传
 * @param onConfirm 用户点击确定按钮, 参数为更新后的 group (新增态为 new BookGroup())
 * @param onDismiss 用户取消 (返回按钮 / 取消按钮)
 * @param onDelete 可选, 删除回调; 编辑态且 groupId 合法时显示删除按钮, 二次确认后调用
 * @param coverSlot 可选, 分组封面渲染槽 (path 可空; 缺省取 [LocalGroupCoverSlot] 书架同源实现,
 *   即 3:4 NOVEL 比例 + 默认封面链)
 * @param onPickCover 可选, 选图回调 (平台自行弹选择器+落盘, 返回封面路径, 取消返回 null)
 */
@Composable
fun GroupEditDialog(
    group: BookGroup?,
    onConfirm: (BookGroup) -> Unit,
    onDismiss: () -> Unit,
    onDelete: ((BookGroup) -> Unit)? = null,
    coverSlot: (@Composable (path: String?, modifier: Modifier) -> Unit)? = null,
    onPickCover: (suspend () -> String?)? = null,
) {
    val colors = AppTheme.colors
    val isNew = group == null
    // 原版通过 Parcelable 传入副本，取消编辑不能污染列表中的实体。
    val editingGroup = remember(group) { group?.copy() ?: BookGroup() }
    val scope = rememberCoroutineScope()

    var cover by remember(group) { mutableStateOf(editingGroup.cover) }
    var groupName by remember(group) { mutableStateOf(editingGroup.groupName) }
    // 越界排序值回落 -1 (对齐原 spinner count 校验: bookSort + 1 in 0..6)
    var bookSort by remember(group) {
        mutableIntStateOf(if (editingGroup.bookSort + 1 in 0..6) editingGroup.bookSort else -1)
    }
    var enableRefresh by remember(group) { mutableStateOf(editingGroup.enableRefresh) }
    var showDeleteDialog by remember { mutableStateOf(false) }
    // 选图缺省: 平台文件选择器 (对照 BookInfoEditRoute.onSelectCover 同款; 阻塞式须切 IO)
    val pickCover = onPickCover ?: remember {
        val files = PlatformServiceProviders.getOrNull()?.files
        if (files == null) null else suspend { withContext(IoDispatcher) { files.pickFile(FileFilter.Images) } }
    }

    val titleKey = if (isNew) "group_add" else "group_edit"
    // 分组封面渲染槽: 与书架同源 (LocalGroupCoverSlot → SharedGroupCover), 保持行为一致;
    // 显式 coverSlot 参数仍可覆盖 (桌面等宿主注入平台实现)
    val groupCoverSlot = LocalGroupCoverSlot.current
    // 7 项排序, 对应 app 端 R.array.book_sort (values-zh 中文值)
    val sortEntries = listOf(
        stringResource(Res.string.book_sort_default),
        stringResource(Res.string.book_sort_reading_time),
        stringResource(Res.string.book_sort_update_time),
        stringResource(Res.string.book_sort_name),
        stringResource(Res.string.book_sort_manual),
        stringResource(Res.string.book_sort_comprehensive),
        stringResource(Res.string.book_sort_author),
    )

    AppDialog(
        onDismissRequest = onDismiss,
        properties = AppDialogSizes.properties(),
    ) {
        // 不能套 fillMaxSize: 撑满窗口会让整窗都算"框内", 点外部永远关不掉; 居中由 RootMeasurePolicy 负责。
        Surface(
            shape = DesignTokens.shapeDefault,
            // 对话框根背景 = 底栏色 (fillet 即 bottomBackground, 对照原版 filletBackground)
            color = colors.fillet,
            modifier = Modifier.appDialogSize(),
        ) {
            Column(Modifier.fillMaxWidth()) {
                DialogTitleBar(
                    title = rememberString(titleKey),
                    onBack = onDismiss,
                )
                Row(
                    Modifier
                        .fillMaxWidth()
                        .padding(horizontal = DesignTokens.spacingDefault, vertical = 16.dp),
                ) {
                    // 封面区恒显示 (对照原版 ivCover: 新分组为占位, 编辑态显示 cover)
                    Box(
                        Modifier
                            .width(110.dp)
                            .aspectRatio(3f / 4f)
                            .let { modifier ->
                                if (pickCover == null) modifier else modifier.clickable {
                                    scope.launch { pickCover()?.let { cover = it } }
                                }
                            },
                    ) {
                        if (coverSlot != null) {
                            coverSlot(cover, Modifier.fillMaxSize())
                        } else {
                            // 与书架分组条目同款渲染 (3:4, 默认封面链); tick 恒 0 (对话框无配置变更重载)
                            groupCoverSlot(
                                remember(cover) { editingGroup.copy(cover = cover) },
                                Modifier.fillMaxSize(),
                                false,
                                0,
                            )
                        }
                    }
                    Column(
                        Modifier
                            .weight(1f)
                            .padding(start = 8.dp),
                    ) {
                        AppUnderlineTextField(
                            value = groupName,
                            onValueChange = { groupName = it },
                            label = stringResource(Res.string.group_name),
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth(),
                        )
                        SortRow(
                            bookSort = bookSort,
                            sortEntries = sortEntries,
                            onSortSelected = { bookSort = it },
                        )
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .padding(top = 8.dp)
                                .clickable { enableRefresh = !enableRefresh },
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            AppCheckbox(
                                checked = enableRefresh,
                                onCheckedChange = { enableRefresh = it },
                            )
                            Text(
                                text = stringResource(Res.string.allow_drop_down_refresh),
                                color = colors.primaryText,
                                fontSize = 14.sp,
                            )
                        }
                    }
                }
                Row(
                    Modifier
                        .fillMaxWidth()
                        .padding(
                            start = DesignTokens.spacingDefault,
                            top = 4.dp,
                            end = DesignTokens.spacingDefault,
                            bottom = DesignTokens.spacingDefault,
                        ),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    val showDelete = !isNew &&
                        (editingGroup.groupId > 0L || editingGroup.groupId == Long.MIN_VALUE)
                    if (showDelete) {
                        AppTextButton(text = stringResource(Res.string.delete)) {
                            showDeleteDialog = true
                        }
                    }
                    Spacer(Modifier.weight(1f))
                    AppTextButton(text = stringResource(Res.string.cancel), onClick = onDismiss)
                    AppTextButton(text = stringResource(Res.string.ok)) {
                        if (groupName.isEmpty()) {
                            Toasters.get().toast("分组名称不能为空")
                        } else {
                            editingGroup.groupName = groupName
                            editingGroup.bookSort = bookSort
                            editingGroup.enableRefresh = enableRefresh
                            editingGroup.cover = cover
                            onConfirm(editingGroup)
                        }
                    }
                }
            }
        }
    }

    // 删除二次确认弹窗 (对齐原 alert(R.string.delete, R.string.sure_del) { yesButton { ... } })
    if (showDeleteDialog) {
        AppAlertDialog(
            onDismissRequest = { showDeleteDialog = false },
            title = stringResource(Res.string.delete),
            message = stringResource(Res.string.sure_del),
            okButton = AlertButton(
                text = stringResource(Res.string.ok),
                onClick = {
                    showDeleteDialog = false
                    onDelete?.invoke(editingGroup)
                    onDismiss()
                },
            ),
            cancelButton = AlertButton(text = stringResource(Res.string.cancel)),
        )
    }
}

/**
 * 排序选择行, 对照原 AppCompatSpinner (@array/book_sort, 选中位 = bookSort + 1)。
 *
 * 点击展开 AppDropdownMenu, 7 项对应 [sortEntries]; 选中后回调 [onSortSelected] 传入
 * `index - 1` (即 bookSort 值, 范围 -1..5)。
 */
@Composable
private fun SortRow(
    bookSort: Int,
    sortEntries: List<String>,
    onSortSelected: (Int) -> Unit,
) {
    val colors = AppTheme.colors
    var sortMenu by remember { mutableStateOf(false) }
    Row(
        Modifier
            .fillMaxWidth()
            .padding(top = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = stringResource(Res.string.sort),
            color = colors.accent,
            fontSize = 14.sp,
            modifier = Modifier.padding(4.dp),
        )
        Box {
            Row(
                Modifier
                    .clickable { sortMenu = true }
                    .padding(4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = sortEntries[bookSort + 1],
                    color = colors.primaryText,
                    fontSize = 14.sp,
                )
                Icon(
                    painter = painterResource(Res.drawable.ic_arrow_drop_down),
                    contentDescription = null,
                    tint = colors.secondaryText,
                    modifier = Modifier.size(24.dp),
                )
            }
            AppDropdownMenu(expanded = sortMenu, onDismissRequest = { sortMenu = false }) {
                sortEntries.forEachIndexed { index, entry ->
                    DropdownMenuItem(
                        onClick = { sortMenu = false; onSortSelected(index - 1) },
                    ) {
                        Text(entry, color = colors.primaryText)
                    }
                }
            }
        }
    }
}
