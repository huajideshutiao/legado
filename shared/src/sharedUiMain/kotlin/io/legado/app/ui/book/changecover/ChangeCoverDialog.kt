// I18N KEYS (均已存在 jvmMain ResourceProvider):
// - change_cover_source: "更换封面源" (标题)
// - stop: "停止" (搜索中按钮 contentDescription)
// - refresh: "刷新" (非搜索中按钮 contentDescription)
//
// PAINTER KEYS (均已存在 jvmMain ResourceProvider):
// - ic_stop_black_24dp: 停止搜索图标
// - ic_refresh_black_24dp: 刷新/开始搜索图标

package io.legado.app.ui.book.changecover

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import io.legado.app.data.entities.SearchBook
import io.legado.app.ui.compose.component.DialogTitleBar
import io.legado.app.ui.compose.platform.rememberPainter
import io.legado.app.ui.compose.platform.rememberString
import io.legado.app.ui.compose.theme.AppTheme
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.conflate

/** Arco Design arco_radius_lg = 16dp, 用于对话框圆角 (与 SpeakEngineDialog 一致)。 */
private val ArcoRadiusLg = 16.dp

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
 * - **stringResource → rememberString / painterResource → rememberPainter**:
 *   KMP 资源访问走 `ResourceProvider` (commonMain expect + 各平台 actual)。
 * - **isFullHeight**: 原版 `isFullHeight = true` 让 Dialog 全屏; 下沉版用
 *   `DialogProperties(usePlatformDefaultWidth = false)` + `Modifier.fillMaxSize()` (Dialog 内部 Column)
 *   让 Dialog 内容自适应窗口 (与 SpeakEngineDialog 模式一致, 不强制全屏, 桌面端更合理)。
 *
 * # 样式 (Arco Design 规范)
 *
 * - 圆角 arco_radius_lg = 16dp: Dialog Surface 圆角 (与 SpeakEngineDialog 一致)
 * - 无阴影 (Surface 默认无阴影)
 * - 标题栏用 [DialogTitleBar] (复用 shared 组件)
 * - 进度条 LinearProgressIndicator 高 2dp (对照 app 端原实现)
 * - 网格 LazyVerticalGrid(GridCells.Fixed(3)) (对照 app 端原实现)
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

    // 收集搜索结果 (对照原 LaunchedEffect: viewModel.dataFlow.conflate().collect)
    // delay(1000) 节流, 避免高频 trySend 触发过多重组 (与 app 端原实现一致)
    LaunchedEffect(Unit) {
        viewModel.dataFlow.conflate().collect {
            items = it
            delay(1000)
        }
    }

    // 收集搜索状态 (对照原 DisposableEffect + Observer searchStateData)
    LaunchedEffect(Unit) {
        viewModel.searchState.collect { searching = it }
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Surface(
            shape = RoundedCornerShape(ArcoRadiusLg),
            color = colors.background,
            // 原版 isFullHeight=true 全屏; 下沉版用 fillMaxSize 让 Dialog 内容自适应窗口
            // (桌面端非全屏更合理, 与 SpeakEngineDialog 模式一致)
            modifier = Modifier.fillMaxSize(),
        ) {
            Column(Modifier.fillMaxSize()) {
                DialogTitleBar(
                    title = rememberString("change_cover_source"),
                    onBack = onDismiss,
                ) {
                    // 启停搜索按钮 (对照原 IconButton + startOrStopSearch)
                    IconButton(onClick = { viewModel.startOrStopSearch() }) {
                        Icon(
                            painter = rememberPainter(
                                if (searching) "ic_stop_black_24dp" else "ic_refresh_black_24dp"
                            ),
                            contentDescription = rememberString(
                                if (searching) "stop" else "refresh"
                            ),
                            tint = colors.primaryText,
                        )
                    }
                }
                // 搜索中显示进度条 (对照原 LinearProgressIndicator, 高 2dp)
                if (searching) {
                    LinearProgressIndicator(
                        color = colors.accent,
                        backgroundColor = colors.bottomBackground,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(2.dp),
                    )
                }
                // 搜索结果网格 (对照原 LazyVerticalGrid(GridCells.Fixed(3)))
                LazyVerticalGrid(
                    columns = GridCells.Fixed(3),
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f),
                ) {
                    items(items, key = { it.bookUrl }) { item ->
                        CoverItem(item, coverSlot = coverSlot) {
                            // 点击 item: 回调 coverUrl + 关闭 Dialog (对照原 callBack.coverChangeTo + dismiss)
                            onCoverSelected(item.coverUrl ?: "")
                            onDismiss()
                        }
                    }
                }
            }
        }
    }
}

/**
 * 单个封面项: 封面图 + 源名 (对照原 CoverItem)。
 *
 * - Column(fillMaxWidth + padding(4.dp) + clickable)
 *   - [coverSlot] (fillMaxWidth + aspectRatio(0.66f)): 封面渲染槽, 由调用方注入
 *   - Text(originName, 12.sp, maxLines=2, ellipsis, center, padding(top=8.dp)): 源名
 *
 * 与原版差异: 原版用 `AndroidView { CoverImageView }` 渲染封面, 下沉版改为 [coverSlot] 注入,
 * 解耦平台专属封面渲染 (app 端 CoverImageView + Glide / 桌面端 DesktopBookCover + ImageIO)。
 *
 * @param item 搜索结果 (含 coverUrl / name / author / originName)
 * @param coverSlot 封面渲染槽 (由上层 ChangeCoverDialog 透传)
 * @param onClick 点击回调 (上层调 onCoverSelected + onDismiss)
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
            .padding(4.dp)
            .clickable(onClick = onClick)
    ) {
        // 封面渲染槽 (对照原 AndroidView + CoverImageView, fillMaxWidth + aspectRatio(0.66f))
        coverSlot(
            item,
            Modifier
                .fillMaxWidth()
                .aspectRatio(0.66f)
        )
        // 源名 (对照原 Text, 12.sp, maxLines=2, ellipsis, center, padding(top=8.dp))
        Text(
            text = item.originName,
            color = AppTheme.colors.primaryText,
            fontSize = 12.sp,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Center,
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 8.dp)
        )
    }
}
