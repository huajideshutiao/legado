package io.legado.app.ui.book.info.edit

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.DropdownMenuItem
import androidx.compose.material.Icon
import androidx.compose.material.IconButton
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
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.legado.app.data.entities.Book
import io.legado.app.ui.compose.component.AppDropdownMenu
import io.legado.app.ui.compose.component.AppOutlinedButton
import io.legado.app.ui.compose.component.AppOutlinedTextField
import io.legado.app.ui.compose.component.AppTitleBar
import io.legado.app.ui.compose.platform.bringIntoViewOnIme
import io.legado.app.ui.compose.platform.imeDismissPadding
import io.legado.app.ui.compose.platform.imeFollowVisibleOnIme
import io.legado.app.ui.compose.platform.imeScrollNowFor
import io.legado.app.ui.compose.platform.rememberImeVisible
import io.legado.app.ui.compose.theme.AppTheme
import legado.shared.generated.resources.Res
import legado.shared.generated.resources.action_save
import legado.shared.generated.resources.author
import legado.shared.generated.resources.book_info_edit
import legado.shared.generated.resources.book_intro
import legado.shared.generated.resources.book_name
import legado.shared.generated.resources.book_type
import legado.shared.generated.resources.book_url
import legado.shared.generated.resources.change_cover_source
import legado.shared.generated.resources.cover_path
import legado.shared.generated.resources.ic_arrow_drop_down
import legado.shared.generated.resources.ic_save
import legado.shared.generated.resources.refresh_cover
import legado.shared.generated.resources.select_local_image
import org.jetbrains.compose.resources.painterResource
import org.jetbrains.compose.resources.stringArrayResource
import org.jetbrains.compose.resources.stringResource
import kotlin.math.roundToInt

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
 * - 字符串资源 `stringResource(R.string.xxx)` → `stringResource(Res.string.xxx)` (key-based, 跨平台)
 * - 字符串数组 `stringArrayResource(R.array.book_type)` → `stringArrayResource(Res.array.book_type)`
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
    // 导航条避让走滚动区末尾 Spacer (对齐原版 scrollView clipToPadding=false), ime 留在根
    // 减去 ime (对齐原版 navigationBarHeight 的 coerceAtLeast(0))。事件化: imeVisible 为
    // 事件性布尔 (翻转时重组一次), 键盘弹出期间不再逐帧读 ime 数值
    val imeVisible = rememberImeVisible()
    val navBottom = if (imeVisible) 0.dp else WindowInsets.navigationBars
        .asPaddingValues()
        .calculateBottomPadding()
    Column(
        Modifier
            .fillMaxSize()
            .imeDismissPadding(),
    ) {
        AppTitleBar(
            title = stringResource(Res.string.book_info_edit),
            onBack = { actions.onBack() },
            actions = {
                IconButton(onClick = { actions.onSave() }) {
                    Icon(
                        painter = painterResource(Res.drawable.ic_save),
                        contentDescription = stringResource(Res.string.action_save),
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
                    label = stringResource(Res.string.book_name),
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                AppOutlinedTextField(
                    value = state.author,
                    onValueChange = { actions.onAuthorChange(it) },
                    label = stringResource(Res.string.author),
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                TypeSelector(state, actions)
            }
        }
        // 键盘弹出动画期间的瞬移滚动器 (见 ImeInsets): 视口逐帧收缩时把聚焦字段无动画滚到
        // 可见 —— 字段始终可见且不打断; 滚动区顶部窗口 Y 由 onGloballyPositioned 记录
        val scrollState = rememberScrollState()
        val scope = rememberCoroutineScope()
        val density = LocalDensity.current
        val imeMarginPx = with(density) { 12.dp.toPx() }.roundToInt()
        var editWindowY by remember { mutableIntStateOf(0) }
        val imeScrollNow = remember(scrollState, scope) {
            imeScrollNowFor(scrollState, { editWindowY }, imeMarginPx, scope)
        }
        Column(
            Modifier
                .verticalScroll(scrollState)
                .onGloballyPositioned { editWindowY = it.positionInWindow().y.roundToInt() }
                .padding(horizontal = 4.dp),
        ) {
            var coverUrlFocused by remember { mutableStateOf(false) }
            AppOutlinedTextField(
                value = state.coverUrl,
                onValueChange = { actions.onCoverUrlChange(it) },
                label = stringResource(Res.string.cover_path),
                modifier = Modifier
                    .fillMaxWidth()
                    .onFocusChanged { coverUrlFocused = it.isFocused }
                    // 键盘弹出动画期间瞬移跟随视口收缩 (光标始终可见, 无动画不打断);
                    // 动画结束兜底由 bringIntoViewOnIme 承担 (幂等, 已可见则不滚)
                    .imeFollowVisibleOnIme(coverUrlFocused, imeScrollNow)
                    // 键盘弹出/窗口收缩后聚焦字段可能再次滚出视口, 重新滚到可见 (见 ImeInsets KDoc)
                    .bringIntoViewOnIme(coverUrlFocused),
            )
            Row(Modifier.padding(horizontal = 4.dp)) {
                AppOutlinedButton(stringResource(Res.string.select_local_image)) {
                    actions.onSelectCover()
                }
                AppOutlinedButton(
                    stringResource(Res.string.change_cover_source),
                    modifier = Modifier.padding(horizontal = 12.dp),
                ) {
                    actions.onChangeCoverSource()
                }
                AppOutlinedButton(stringResource(Res.string.refresh_cover)) {
                    actions.onRefreshCover()
                }
            }
            var introFocused by remember { mutableStateOf(false) }
            AppOutlinedTextField(
                value = state.intro,
                onValueChange = { actions.onIntroChange(it) },
                label = stringResource(Res.string.book_intro),
                modifier = Modifier
                    .fillMaxWidth()
                    .onFocusChanged { introFocused = it.isFocused }
                    .imeFollowVisibleOnIme(introFocused, imeScrollNow)
                    .bringIntoViewOnIme(introFocused),
            )
            var bookUrlFocused by remember { mutableStateOf(false) }
            AppOutlinedTextField(
                value = state.bookUrl,
                onValueChange = { actions.onBookUrlChange(it) },
                label = stringResource(Res.string.book_url),
                modifier = Modifier
                    .fillMaxWidth()
                    .onFocusChanged { bookUrlFocused = it.isFocused }
                    .imeFollowVisibleOnIme(bookUrlFocused, imeScrollNow)
                    .bringIntoViewOnIme(bookUrlFocused),
            )
            Spacer(Modifier.height(navBottom))
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
    val types = stringArrayResource(Res.array.book_type)
    var expanded by remember { mutableStateOf(false) }
    Row(
        Modifier.padding(start = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            stringResource(Res.string.book_type),
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
                    painterResource(Res.drawable.ic_arrow_drop_down),
                    null,
                    tint = AppTheme.colors.secondaryText,
                    modifier = Modifier.size(24.dp),
                )
            }
            AppDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                types.forEachIndexed { index, s ->
                    DropdownMenuItem(
                        onClick = { actions.onTypeChange(index); expanded = false },
                    ) {
                        Text(s, color = AppTheme.colors.primaryText)
                    }
                }
            }
        }
    }
}
