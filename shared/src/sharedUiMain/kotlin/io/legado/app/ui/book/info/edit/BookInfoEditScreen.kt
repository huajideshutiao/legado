package io.legado.app.ui.book.info.edit

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.legado.app.data.entities.Book
import io.legado.app.ui.compose.component.AppDropdownMenu
import io.legado.app.ui.compose.component.AppOutlinedButton
import io.legado.app.ui.compose.component.AppOutlinedTextField
import io.legado.app.ui.compose.component.AppTitleBar
import io.legado.app.ui.compose.platform.rememberPainter
import io.legado.app.ui.compose.platform.rememberString
import io.legado.app.ui.compose.platform.rememberStringArray
import io.legado.app.ui.compose.theme.AppTheme

// ===== state / actions =====

/**
 * 书籍信息编辑页展示状态 (immutable)。
 *
 * 宿主端 (app 端 [BookInfoEditActivity] / 桌面端) 持有 `mutableStateOf` 各编辑字段,
 * 数据更新时 copy 出新实例以触发 Compose 重组。
 *
 * 字段语义对照原 [BookInfoEditActivity] 同名字段:
 * - [book]: 当前编辑的书籍 (供封面 slot 读取原书名/作者/封面路径)
 * - [name] / [author] / [typeIndex] / [coverUrl] / [intro] / [bookUrl]:
 *   与 Activity 同名编辑态字段一一对应
 * - [coverTick]: 封面重载 key (对照 Activity.coverTick, 切换封面后递增驱动 ShelfCover 重载)
 */
data class BookInfoEditUiState(
    val book: Book?,
    val name: String,
    val author: String,
    val typeIndex: Int,
    val coverUrl: String,
    val intro: String,
    val bookUrl: String,
    val coverTick: Int,
)

/**
 * 书籍信息编辑页事件回调集合。
 *
 * app 端 [BookInfoEditActivity] 实现本接口, 平台相关依赖 (HandleFileContract /
 * ChangeCoverDialog / FileUtils / MD5Utils / externalFiles / readUri 等) 在回调内桥接,
 * shared 端不直接持有 Android 依赖。
 *
 * - [onBack]: 返回 (finish)
 * - [onSave]: 点击标题栏保存按钮, 调 [BookInfoEditActivity.saveData] 落库
 * - [onSelectCover]: 点击"本地图片"按钮, 启动 HandleFileContract 选图
 * - [onChangeCoverSource]: 点击"更换封面源"按钮, 弹出 ChangeCoverDialog
 * - [onRefreshCover]: 点击"刷新封面"按钮, 写入 customCoverUrl 并递增 coverTick
 * - [onNameChange] / [onAuthorChange] / [onTypeChange] / [onCoverUrlChange] /
 *   [onIntroChange] / [onBookUrlChange]: 对应输入框内容变更
 */
interface BookInfoEditUiActions {
    fun onBack()
    fun onSave()
    fun onSelectCover()
    fun onChangeCoverSource()
    fun onRefreshCover()
    fun onNameChange(value: String)
    fun onAuthorChange(value: String)
    fun onTypeChange(index: Int)
    fun onCoverUrlChange(value: String)
    fun onIntroChange(value: String)
    fun onBookUrlChange(value: String)
}

// ===== BookInfoEditScreen =====

/**
 * 书籍信息编辑 Screen (KMP 版, 替代 app 端 [BookInfoEditActivity.Content] 下沉)。
 *
 * 三段式 API:
 * - [BookInfoEditUiState]: 不可变编辑态 (书名/作者/类型/封面路径/简介/书URL/封面 tick),
 *   由宿主端 (Activity/桌面) 持有 mutableStateOf 各字段并 copy
 * - [BookInfoEditUiActions]: 交互回调集合 (返回/保存/选封面/换源/刷新封面/字段变更),
 *   宿主端实现接口
 * - [BookInfoEditScreen]: 纯 Composable 渲染入口, 仅依赖 state + actions + coverSlot
 *
 * 下沉改动:
 * - 字符串资源 `stringResource(R.string.xxx)` → `rememberString("xxx")` (key-based, 跨平台)
 * - 字符串数组 `stringArrayResource(R.array.book_type)` → `rememberStringArray("book_type")`
 * - 图标资源 `painterResource(R.drawable.xxx)` → `rememberPainter("xxx")` (key-based, 跨平台)
 * - 平台依赖 (HandleFileContract / ChangeCoverDialog / FileUtils / MD5Utils 等)
 *   通过 [BookInfoEditUiActions] 回调桥接, 选图与换源弹窗仍由 app 端 Activity 持有
 * - 封面 ShelfCover (AndroidView + CoverImageView + Glide) 通过 [coverSlot] slot 注入,
 *   调用方传入 modifier 已包含 width(110.dp), slot 内部从 state.book + coverTick 读取
 *
 * 注: 视觉/布局/边距/颜色/字号与 app 端原版完全一致, 仅做结构重构。
 *
 * @param coverSlot 封面渲染 slot (AndroidView + CoverImageView + Glide, L3)
 *   - 调用方传入的 modifier 已包含 width(110.dp)
 *   - slot 内部从 state 读 book / coverTick (Activity 闭包捕获)
 */
@Composable
fun BookInfoEditScreen(
    state: BookInfoEditUiState,
    actions: BookInfoEditUiActions,
    coverSlot: @Composable (Book?, Modifier) -> Unit,
) {
    Column(
        Modifier
            .fillMaxSize()
            .windowInsetsPadding(WindowInsets.navigationBars.union(WindowInsets.ime)),
    ) {
        AppTitleBar(
            title = rememberString("book_info_edit"),
            onBack = { actions.onBack() },
            actions = {
                IconButton(onClick = { actions.onSave() }) {
                    Icon(
                        painter = rememberPainter("ic_save"),
                        contentDescription = rememberString("action_save"),
                        tint = AppTheme.colors.primaryText,
                    )
                }
            },
        )
        Row(Modifier.padding(horizontal = 8.dp)) {
            coverSlot(state.book, Modifier.width(110.dp))
            Column(
                Modifier
                    .weight(1f)
                    .padding(start = 4.dp),
            ) {
                AppOutlinedTextField(
                    value = state.name,
                    onValueChange = { actions.onNameChange(it) },
                    label = rememberString("book_name"),
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                AppOutlinedTextField(
                    value = state.author,
                    onValueChange = { actions.onAuthorChange(it) },
                    label = rememberString("author"),
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                TypeSelector(state, actions)
            }
        }
        Column(
            Modifier
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 4.dp),
        ) {
            AppOutlinedTextField(
                value = state.coverUrl,
                onValueChange = { actions.onCoverUrlChange(it) },
                label = rememberString("cover_path"),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 4.dp),
            )
            Row(Modifier.padding(horizontal = 4.dp)) {
                AppOutlinedButton(rememberString("select_local_image")) {
                    actions.onSelectCover()
                }
                AppOutlinedButton(
                    rememberString("change_cover_source"),
                    modifier = Modifier.padding(horizontal = 12.dp),
                ) {
                    actions.onChangeCoverSource()
                }
                AppOutlinedButton(rememberString("refresh_cover")) {
                    actions.onRefreshCover()
                }
            }
            AppOutlinedTextField(
                value = state.intro,
                onValueChange = { actions.onIntroChange(it) },
                label = rememberString("book_intro"),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 4.dp),
            )
            AppOutlinedTextField(
                value = state.bookUrl,
                onValueChange = { actions.onBookUrlChange(it) },
                label = rememberString("book_url"),
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

/**
 * 类型下拉选择器: 对照 sp_type (entries=@array/book_type)。
 *
 * 注: 对照原 [BookInfoEditActivity.TypeSelector], 宽高/边距/字号/颜色逻辑全部保持一致,
 * 仅将 typeIndex 直写替换为外部回调 [BookInfoEditUiActions.onTypeChange]。
 */
@Composable
private fun TypeSelector(
    state: BookInfoEditUiState,
    actions: BookInfoEditUiActions,
) {
    val types = rememberStringArray("book_type")
    var expanded by remember { mutableStateOf(false) }
    Row(
        Modifier.padding(start = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            rememberString("book_type"),
            color = AppTheme.colors.primaryText,
            fontSize = 14.sp,
        )
        Box {
            Row(
                Modifier
                    .clickable { expanded = true }
                    .padding(horizontal = 8.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    types.getOrElse(state.typeIndex) { types[0] },
                    color = AppTheme.colors.primaryText,
                    fontSize = 14.sp,
                )
                Icon(
                    rememberPainter("ic_arrow_drop_down"),
                    null,
                    tint = AppTheme.colors.secondaryText,
                    modifier = Modifier.size(24.dp),
                )
            }
            AppDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                types.forEachIndexed { index, s ->
                    DropdownMenuItem(
                        text = { Text(s, color = AppTheme.colors.primaryText) },
                        onClick = { actions.onTypeChange(index); expanded = false },
                    )
                }
            }
        }
    }
}
