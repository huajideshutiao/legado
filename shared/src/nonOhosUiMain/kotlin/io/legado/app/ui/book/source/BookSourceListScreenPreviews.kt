package io.legado.app.ui.book.source

import androidx.compose.runtime.Composable
import io.legado.app.ui.preview.AppPreview
import io.legado.app.data.entities.BookSourcePart
import io.legado.app.ui.preview.LegadoThemePreview

/**
 * [BookSourceListScreen] 的 @Preview。
 *
 * 假数据: [BookSourcePart] 列表用纯内存对象构造, [BookSourceListState] /
 * [BookSourceListCallbacks] 用默认值 + 假数据填充。
 */

private val previewSources = listOf(
    BookSourcePart(
        bookSourceUrl = "https://source1.com",
        bookSourceName = "测试书源1",
        bookSourceGroup = "默认",
        enabled = true,
        enabledExplore = true,
        hasLoginUrl = false,
        hasExploreUrl = true,
        respondTime = 200L,
        weight = 100,
    ),
    BookSourcePart(
        bookSourceUrl = "https://source2.com",
        bookSourceName = "测试书源2",
        bookSourceGroup = "默认",
        enabled = true,
        enabledExplore = false,
        hasLoginUrl = true,
        hasExploreUrl = false,
        respondTime = 350L,
        weight = 80,
    ),
    BookSourcePart(
        bookSourceUrl = "https://source3.com",
        bookSourceName = "测试书源3(禁用)",
        bookSourceGroup = "备用",
        enabled = false,
        enabledExplore = false,
        hasLoginUrl = false,
        hasExploreUrl = false,
        respondTime = 1200L,
        weight = 0,
    ),
)

private val previewState = BookSourceListState(
    sources = previewSources,
    selected = setOf("https://source2.com"),
    searchKey = "",
    groups = listOf("默认", "备用"),
)

private val previewCallbacks = BookSourceListCallbacks(
    getSourceHost = { url ->
        // 简易 host 提取: 去掉 scheme 后取到首个 / 之前的部分 (Preview 用, 避免平台依赖)
        url.substringAfter("://").substringBefore("/").ifEmpty { "#" }
    },
)

private val previewStateChecking = previewState.copy(
    checkSourceVisible = true,
    checkSourceMsg = "正在校验 2/3...",
)

private val previewStateEmpty = BookSourceListState(
    sources = emptyList(),
    groups = listOf("默认"),
)

@AppPreview
@Composable
fun BookSourceListScreenPreview() = LegadoThemePreview {
    BookSourceListScreen(state = previewState, callbacks = previewCallbacks)
}

@AppPreview
@Composable
fun BookSourceListScreenCheckingPreview() = LegadoThemePreview {
    BookSourceListScreen(state = previewStateChecking, callbacks = previewCallbacks)
}

@AppPreview
@Composable
fun BookSourceListScreenEmptyPreview() = LegadoThemePreview {
    BookSourceListScreen(state = previewStateEmpty, callbacks = previewCallbacks)
}

@AppPreview
@Composable
fun BookSourceListScreenDarkPreview() = LegadoThemePreview(dark = true) {
    BookSourceListScreen(state = previewState, callbacks = previewCallbacks)
}
