// I18N KEYS: group_edit, group_add, allow_drop_down_refresh, book_sort_default, book_sort_reading_time, book_sort_update_time, book_sort_name, book_sort_manual, book_sort_comprehensive, book_sort_author
package io.legado.app.ui.book.group

import androidx.compose.foundation.background
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
import io.legado.app.ui.compose.component.AlertButton
import io.legado.app.ui.compose.component.AppAlertDialog
import io.legado.app.ui.compose.component.AppCheckbox
import io.legado.app.ui.compose.component.AppDropdownMenu
import io.legado.app.ui.compose.component.AppOutlinedTextField
import io.legado.app.ui.compose.component.AppTextButton
import io.legado.app.ui.compose.component.DialogTitleBar
import io.legado.app.ui.compose.platform.rememberPainter
import io.legado.app.ui.compose.platform.rememberString
import io.legado.app.ui.compose.theme.AppTheme
import io.legado.app.ui.compose.theme.AppTheme.DesignTokens
import kotlinx.coroutines.launch

/**
 * 分组编辑对话框 (KMP 共享, app + desktop 复用)。
 *
 * 对应 app 端 `io.legado.app.ui.book.group.GroupEditDialog` (基于 BaseComposeDialogFragment),
 * 但去掉对 Android Fragment / registerHandleFile / appDb / ViewModel 的依赖, 改为纯
 * @Composable + 回调形式:
 * - 调用方传入 [group] (null=新增) 与 [onConfirm] / [onDismiss] / [onDelete] 回调
 * - 字段: 分组名 (AppOutlinedTextField) + 排序 (AppDropdownMenu, 7 项对照 @array/book_sort)
 *   + 允许下拉刷新 (AppCheckbox)
 * - 确认时把字段写回 [group] (编辑态) 或新 [BookGroup] (新增态), 通过 [onConfirm] 回传;
 *   实际 DB 持久化 (viewModel.upGroup / addGroup) 由调用方决定
 * - 删除: 编辑态且 groupId > 0 || groupId == Long.MIN_VALUE 时显示删除按钮, 点击弹出
 *   AppAlertDialog 二次确认 (对齐原 alert(R.string.delete, R.string.sure_del))
 *
 * # KMP 化取舍
 *
 * - 封面选择: 原 registerHandleFile + ShelfCover 依赖 Android API, 改由 [coverSlot]
 *   (封面渲染) + [onPickCover] (选图落盘返回路径) 注入; 两者均缺省 null 时不显示封面区
 *   (兼容未接入的调用方), cover 字段保留原值
 * - 排序选项原用 stringArrayResource(R.array.book_sort), 该数组未在 rememberStringArray
 *   注册且不能修改 ResourceProvider.jvm.kt, 在文件内用 7 个新 key 硬编码 (值与 app 端
 *   values-zh/strings.xml 对齐)
 * - toastOnUi("分组名称不能为空") 依赖 Android Context, KMP 版静默阻止关闭 (不弹 toast)
 *
 * # 样式 (Arco Design 规范)
 *
 * - 主色: arcoblue-6 (#165DFF) —— 确定按钮文字 + DialogTitleBar 返回箭头
 * - 圆角: arco_radius_lg = 16dp —— Surface shape
 * - 无阴影 (Surface 默认无阴影)
 *
 * @param group 待编辑的分组 (null=新增); 编辑态时字段会被原地修改后通过 [onConfirm] 回传
 * @param onConfirm 用户点击确定按钮, 参数为更新后的 group (新增态为 new BookGroup())
 * @param onDismiss 用户取消 (返回按钮 / 取消按钮)
 * @param onDelete 可选, 删除回调; 编辑态且 groupId 合法时显示删除按钮, 二次确认后调用
 * @param coverSlot 可选, 分组封面渲染槽 (path 可空; 对照 app 端 ShelfCover 110dp NOVEL 比例)
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
    // 编辑态直接复用传入引用 (与原版 bookGroup?.let { it.groupName = name; ... } 一致);
    // 新增态用默认 BookGroup() (groupId=1, bookSort=-1, enableRefresh=true)
    val editingGroup = remember(group) { group ?: BookGroup() }
    val scope = rememberCoroutineScope()

    var cover by remember(group) { mutableStateOf(editingGroup.cover) }
    var groupName by remember(group) { mutableStateOf(editingGroup.groupName) }
    // 越界排序值回落 -1 (对齐原 spinner count 校验: bookSort + 1 in 0..6)
    var bookSort by remember(group) {
        mutableIntStateOf(if (editingGroup.bookSort + 1 in 0..6) editingGroup.bookSort else -1)
    }
    var enableRefresh by remember(group) { mutableStateOf(editingGroup.enableRefresh) }
    var showDeleteDialog by remember { mutableStateOf(false) }

    val titleKey = if (isNew) "group_add" else "group_edit"
    // 7 项排序, 对应 app 端 R.array.book_sort (values-zh 中文值)
    val sortEntries = listOf(
        rememberString("book_sort_default"),
        rememberString("book_sort_reading_time"),
        rememberString("book_sort_update_time"),
        rememberString("book_sort_name"),
        rememberString("book_sort_manual"),
        rememberString("book_sort_comprehensive"),
        rememberString("book_sort_author"),
    )

    Surface(
        shape = DesignTokens.dialogShape,
        color = colors.background,
        modifier = Modifier.fillMaxWidth().padding(8.dp),
    ) {
        Column(Modifier.fillMaxWidth()) {
            DialogTitleBar(
                title = rememberString(titleKey),
                onBack = onDismiss,
            )
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(16.dp)
            ) {
                // 分组封面 (对照 app 端 GroupEditDialog: 110dp 宽 NOVEL 3:4, 点击选图)
                if (coverSlot != null) {
                    Box(
                        Modifier
                            .width(110.dp)
                            .aspectRatio(3f / 4f)
                            .let { m ->
                                if (onPickCover == null) m else m.clickable {
                                    scope.launch { onPickCover()?.let { cover = it } }
                                }
                            },
                    ) {
                        coverSlot(cover, Modifier.fillMaxSize())
                    }
                }
                Column(
                    Modifier
                        .weight(1f)
                        .let { if (coverSlot != null) it.padding(start = 8.dp) else it }
                ) {
                    AppOutlinedTextField(
                        value = groupName,
                        onValueChange = { groupName = it },
                        label = rememberString("group_name"),
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
                            text = rememberString("allow_drop_down_refresh"),
                            color = colors.primaryText,
                            fontSize = 14.sp,
                        )
                    }
                }
            }
            // 底部按钮栏 (与 SourceLoginDialog 下沉版结构一致: bottomBackground + 删除靠左 + 取消/确定靠右)
            Row(
                Modifier
                    .fillMaxWidth()
                    .background(colors.bottomBackground)
                    .padding(horizontal = 8.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                // 删除按钮: 编辑态且 groupId > 0 || groupId == Long.MIN_VALUE 时显示 (对齐原版条件)
                val showDelete = !isNew &&
                        (editingGroup.groupId > 0L || editingGroup.groupId == Long.MIN_VALUE)
                if (showDelete) {
                    AppTextButton(
                        text = rememberString("delete"),
                        color = colors.secondaryText,
                    ) { showDeleteDialog = true }
                }
                Spacer(Modifier.weight(1f))
                AppTextButton(text = rememberString("cancel")) { onDismiss() }
                AppTextButton(
                    text = rememberString("ok"),
                    color = DesignTokens.arcoBlue6,
                ) {
                    // 原版 toastOnUi("分组名称不能为空"); KMP 版无 toast, 静默阻止关闭
                    if (groupName.isNotEmpty()) {
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

    // 删除二次确认弹窗 (对齐原 alert(R.string.delete, R.string.sure_del) { yesButton { ... } })
    if (showDeleteDialog) {
        AppAlertDialog(
            onDismissRequest = { showDeleteDialog = false },
            title = rememberString("delete"),
            message = rememberString("sure_del"),
            okButton = AlertButton(
                text = rememberString("ok"),
                onClick = {
                    showDeleteDialog = false
                    onDelete?.invoke(editingGroup)
                    onDismiss()
                },
            ),
            cancelButton = AlertButton(text = rememberString("cancel")),
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
            text = rememberString("sort"),
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
                    painter = rememberPainter("ic_arrow_drop_down"),
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
