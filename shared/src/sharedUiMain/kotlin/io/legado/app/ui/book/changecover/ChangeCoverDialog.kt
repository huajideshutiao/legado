package io.legado.app.ui.book.changecover

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material.Icon
import androidx.compose.material.IconButton
import androidx.compose.material.LinearProgressIndicator
import androidx.compose.material.Surface
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.legado.app.data.entities.SearchBook
import io.legado.app.ui.compose.component.AppDialog
import io.legado.app.ui.compose.component.AppDialogSizes
import io.legado.app.ui.compose.component.DialogTitleBar
import io.legado.app.ui.compose.component.appDialogSize
import io.legado.app.ui.compose.component.rememberResponsiveColumns
import io.legado.app.ui.compose.theme.AppTheme
import io.legado.app.ui.compose.theme.AppTheme.DesignTokens
import io.legado.app.utils.throttleLatest
import legado.shared.generated.resources.Res
import legado.shared.generated.resources.change_cover_source
import legado.shared.generated.resources.ic_refresh_black_24dp
import legado.shared.generated.resources.ic_stop_black_24dp
import legado.shared.generated.resources.refresh
import legado.shared.generated.resources.stop
import org.jetbrains.compose.resources.painterResource
import org.jetbrains.compose.resources.stringResource

/**
 * 换封面搜索对话框 (KMP 共享, app + desktop 复用)。
 *
 * 对应 app 端 `io.legado.app.ui.book.changecover.ChangeCoverDialog` (继承 BaseComposeDialogFragment),
 * 但去掉对 Android Fragment / ViewModel / LifecycleObserver / AndroidView / CoverImageView 的依赖,
 * 改为纯 @Composable + 回调形式:
 *
 * # 调用方职责
 *
 * - 调用方在创建 [viewModel] 后立即调用 `viewModel.initData(book.name, book.author)` 初始化数据
 *   (与 [io.legado.app.ui.book.changesource.ChangeSourceScreen] 模式一致, initData 不在 Dialog 内部调用);
 * - [onCoverSelected] 回调: 用户点击某条搜索结果时触发, 参数为 `item.coverUrl`
 *   (可能为 `"use_default_cover"` 标记默认封面, 调用方按需处理: app 端 BookHelp.clearCover /
 *   桌面端清 customCoverUrl);
 * - [onDismiss] 回调: 用户关闭对话框时触发 (点击返回键 / 点击 item 后);
 * - [coverSlot] 槽: 由调用方注入封面渲染 Composable, 接收 `SearchBook` 与 `Modifier`:
 *   - **app 端**: 注入 `CoverImageView` 桥接 (AndroidView + Glide);
 *   - **桌面端**: 注入 `DesktopBookCover.InfoCover(searchBook.toBook(), modifier)`
 *     (JDK ImageIO + OkHttp, 不引入 Glide)。
 *
 * # 与 app 端原实现的差异 (KMP 限制)
 *
 * - **Fragment 移除**: 原版继承 `BaseComposeDialogFragment`, 用 `dismissAllowingStateLoss()` 关闭;
 *   下沉版用 [Dialog] + [onDismiss] 回调, 由调用方控制显示/隐藏。
 * - **ViewModel 移除**: 原版用 `by viewModels()` 取 `ChangeCoverViewModel`;
 *   下沉版由调用方传入 [ChangeCoverViewModelShared] 实例 (KMP 共享核心)。
 * - **LiveData → StateFlow**: 原版用 `DisposableEffect + Observer` 观察 `searchStateData`;
 *   下沉版用 `LaunchedEffect + collect` 收集 [ChangeCoverViewModelShared.searchState] (StateFlow)。
 * - **AndroidView → coverSlot**: 原版 `CoverItem` 内嵌 `AndroidView { CoverImageView }`;
 *   下沉版改为 [coverSlot] 注入, 解耦平台专属封面渲染。
 * - **资源访问**: 走 compose-resources (`Res.string` / `Res.drawable`)。
 * - **窗口尺寸**: 对齐原 `BaseComposeDialogFragment` 的全高模式：0.9 窗口宽、最大 800dp、
 *   0.8 窗口高；不是铺满整个窗口。
 *
 * # 样式 (Arco Design 规范)
 *
 * - 圆角 arco_radius_lg = 16dp: Dialog Surface 圆角 (与 SpeakEngineDialog 一致)
 * - 无阴影 (Surface 默认无阴影)
 * - 标题栏用 [DialogTitleBar] (复用 shared 组件)
 * - 进度条高 2dp, 非搜索态留同高占位 (对照原 RefreshProgressBar 常驻不跳变)
 * - 网格 LazyVerticalGrid(响应式 3 列) (对照原 GridLayoutManager(3))
 * - 条目对照 item_bookshelf_grid.xml: 封面 12dp 外边距 + 3:4, 源名 8dp 上边距 / 12sp / 2 行
 *
 * @param viewModel 换封面 ViewModel 共享核心 (调用方持有, 已 initData)
 * @param onCoverSelected 用户点击搜索结果回调 (参数为 item.coverUrl, 可能为 "use_default_cover")
 * @param onDismiss 关闭回调
 * @param coverSlot 封面渲染槽 (调用方注入平台专属 Composable):
 *   - app 端: `CoverImageView` 桥接 (AndroidView + Glide)
 *   - 桌面端: `DesktopBookCover.InfoCover(searchBook.toBook(), modifier)`
 */
@Composable
fun ChangeCoverDialog(
    viewModel: ChangeCoverViewModelShared,
    onCoverSelected: (String) -> Unit,
    onDismiss: () -> Unit,
    coverSlot: @Composable (SearchBook, Modifier) -> Unit,
) {
    val colors = AppTheme.colors
    // 搜索结果列表 (对照原 itemsFlow: MutableStateFlow<List<SearchBook>>)
    var items by remember { mutableStateOf(emptyList<SearchBook>()) }
    // 搜索中状态 (对照原 searching var, 原版通过 Observer searchStateData 更新)
    var searching by remember { mutableStateOf(false) }

    // 首值立即显示，持续搜索期间最多每秒刷新一次，并保留窗口内最新结果。
    LaunchedEffect(Unit) {
        viewModel.dataFlow.throttleLatest(1_000).collect { items = it }
    }

    // 收集搜索状态 (对照原 DisposableEffect + Observer searchStateData)
    LaunchedEffect(Unit) {
        viewModel.searchState.collect { searching = it }
    }

    AppDialog(
        onDismissRequest = onDismiss,
        properties = AppDialogSizes.properties(),
    ) {
        // 全高型: 高度锁定 0.8 屏高
        Surface(
            shape = DesignTokens.dialogShape,
            color = colors.fillet,
            modifier = Modifier.appDialogSize(fullHeight = true),
        ) {
            Column(Modifier.fillMaxSize()) {
                DialogTitleBar(
                    title = stringResource(Res.string.change_cover_source),
                    onBack = onDismiss,
                ) {
                    // 启停搜索按钮 (对照原 menu_start_stop + startOrStopSearch)
                    IconButton(onClick = { viewModel.startOrStopSearch() }) {
                        Icon(
                            painter = painterResource(
                                if (searching) Res.drawable.ic_stop_black_24dp
                                else Res.drawable.ic_refresh_black_24dp
                            ),
                            contentDescription = stringResource(
                                if (searching) Res.string.stop else Res.string.refresh
                            ),
                            tint = colors.primaryText,
                        )
                    }
                }
                // 搜索中显示进度条 (对照原 RefreshProgressBar, 高 2dp; 非搜索中占位保持高度不跳变)
                if (searching) {
                    LinearProgressIndicator(
                        color = colors.accent,
                        backgroundColor = colors.bottomBackground,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(2.dp),
                    )
                } else {
                    Spacer(Modifier.fillMaxWidth().height(2.dp))
                }
                // 搜索结果网格 (对照原 GridLayoutManager(3), 宽屏按参考宽度加列)
                LazyVerticalGrid(
                    columns = rememberResponsiveColumns(3),
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f),
                ) {
                    items(items, key = { it.bookUrl }) { item ->
                        CoverItem(item, coverSlot = coverSlot) {
                            // 由回调携带结果并关闭 Overlay，避免结果关闭后再次 dismiss 同一实例
                            onCoverSelected(item.coverUrl ?: "")
                        }
                    }
                }
            }
        }
    }
}

/**
 * 单个封面项 (对照原 CoverAdapter + item_bookshelf_grid.xml)。
 *
 * 布局: 封面 (arco_spacing_md=12dp 外边距, 3:4 小说比例) + 源名
 * (arco_spacing_default=8dp 上边距, 12sp, 居中, 最多 2 行省略)。
 *
 * 与原版差异: 原版用 `AndroidView { CoverImageView }` 渲染封面, 下沉版改为 [coverSlot] 注入,
 * 解耦平台专属封面渲染 (app 端 CoverImageView / 桌面端 DesktopBookCover)。
 *
 * @param item 搜索结果 (含 coverUrl / name / author / originName)
 * @param coverSlot 封面渲染槽 (由上层 ChangeCoverDialog 透传)
 * @param onClick 点击回调 (上层调 onCoverSelected)
 */
@Composable
private fun CoverItem(
    item: SearchBook,
    coverSlot: @Composable (SearchBook, Modifier) -> Unit,
    onClick: () -> Unit,
) {
    Column(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
    ) {
        // 封面: 对照 item_bookshelf_grid.xml 的 layout_margin=arco_spacing_md(12dp) + CoverImageView 3:4
        coverSlot(
            item,
            Modifier
                .fillMaxWidth()
                .padding(12.dp)
                .aspectRatio(3f / 4f)
        )
        // 源名: 对照 tv_name (marginTop=arco_spacing_default(8dp), 12sp, maxLines=2, 居中省略)
        Text(
            text = item.originName,
            color = AppTheme.colors.primaryText,
            fontSize = 12.sp,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Center,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp)
                .padding(top = 8.dp, bottom = 12.dp)
        )
    }
}
