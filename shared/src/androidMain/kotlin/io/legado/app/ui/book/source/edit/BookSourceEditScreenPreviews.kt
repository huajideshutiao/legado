package io.legado.app.ui.book.source.edit

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.tooling.preview.Preview
import io.legado.app.ui.preview.LegadoThemePreview
import io.legado.app.ui.widget.text.EditEntity

/**
 * [BookSourceEditScreen] 的 @Preview。
 *
 * 假数据: [BookSourceEditState] 用 remember 构造并预设开关状态;
 * [editEntities] 返回两条测试 [EditEntity]; 代码字段由 Screen 内部共享 CodeTextField 渲染。
 */

@Composable
private fun previewState(): BookSourceEditState = remember {
    BookSourceEditState().apply {
        bookSourceTypeIndex = 0
        enabled = true
        enabledCookieJar = false
        enableDangerousApi = false
        enabledExplore = true
        enabledReview = false
        exploreStyleIndex = 0
        exploreColsIndex = 3
        currentTab = 0
    }
}

private val previewCallbacks = BookSourceEditCallbacks()

private fun previewEntities(tab: Int): List<EditEntity> = when (tab) {
    0 -> listOf(
        EditEntity(key = "bookSourceName", value = "测试书源", hint = "书源名称"),
        EditEntity(key = "bookSourceUrl", value = "https://test.com", hint = "书源URL"),
        EditEntity(key = "bookSourceGroup", value = "默认", hint = "书源分组"),
    )
    1 -> listOf(
        EditEntity(key = "searchUrl", value = "https://test.com/search?q={{key}}", hint = "搜索URL"),
        EditEntity(key = "ruleSearch", value = "{}", hint = "搜索规则"),
    )
    else -> listOf(
        EditEntity(key = "field_$tab", value = "value_$tab", hint = "字段_$tab"),
    )
}

@Preview
@Composable
fun BookSourceEditScreenPreview() = LegadoThemePreview {
    BookSourceEditScreen(
        state = previewState(),
        callbacks = previewCallbacks,
        editEntities = ::previewEntities,
    )
}

@Preview
@Composable
fun BookSourceEditScreenSearchTabPreview() = LegadoThemePreview {
    val state = remember {
        BookSourceEditState().apply {
            enabled = true
            currentTab = 1
        }
    }
    BookSourceEditScreen(
        state = state,
        callbacks = previewCallbacks,
        editEntities = ::previewEntities,
    )
}

@Preview
@Composable
fun BookSourceEditScreenDarkPreview() = LegadoThemePreview(dark = true) {
    BookSourceEditScreen(
        state = previewState(),
        callbacks = previewCallbacks,
        editEntities = ::previewEntities,
    )
}
