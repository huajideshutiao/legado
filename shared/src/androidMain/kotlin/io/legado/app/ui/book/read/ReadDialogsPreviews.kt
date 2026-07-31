package io.legado.app.ui.book.read

import androidx.compose.runtime.Composable
import androidx.compose.ui.tooling.preview.Preview
import io.legado.app.data.entities.Book
import io.legado.app.data.entities.ReplaceRule
import io.legado.app.ui.preview.LegadoThemePreview

/**
 * [EffectiveReplacesDialog.kt] / [EffectiveReplacesScreen.kt] / [ContentEditDialog.kt] 的 @Preview。
 *
 * 假数据: [Book] / [ReplaceRule] 用纯内存对象构造, 不依赖 DB。
 */

private val previewBook = Book(
    name = "三体",
    author = "刘慈欣",
    bookUrl = "preview://book",
)

private val previewReplaceRules = listOf(
    ReplaceRule(
        id = 1L,
        name = "净化广告",
        pattern = "<ad>.*?</ad>",
        replacement = "",
    ),
    ReplaceRule(
        id = 2L,
        name = "繁简转换",
        pattern = "[繁體]",
        replacement = "[简体]",
    ),
    ReplaceRule(
        id = 3L,
        name = "去除水印",
        pattern = "本章未完.*?请翻页",
        replacement = "",
    ),
)

// ===== EffectiveReplacesDialog =====

@Preview
@Composable
fun EffectiveReplacesDialogPreview() = LegadoThemePreview {
    EffectiveReplacesDialog(
        book = previewBook,
        items = previewReplaceRules,
        onAddRule = {},
        onItemClick = {},
        onManageAll = {},
        onDismiss = {},
    )
}

@Preview
@Composable
fun EffectiveReplacesDialogEmptyPreview() = LegadoThemePreview {
    EffectiveReplacesDialog(
        book = previewBook,
        items = emptyList(),
        onAddRule = {},
        onItemClick = {},
        onManageAll = {},
        onDismiss = {},
    )
}

@Preview
@Composable
fun EffectiveReplacesDialogDarkPreview() = LegadoThemePreview(dark = true) {
    EffectiveReplacesDialog(
        book = previewBook,
        items = previewReplaceRules,
        onAddRule = {},
        onItemClick = {},
        onManageAll = {},
        onDismiss = {},
    )
}

// ===== EffectiveReplacesScreen =====

@Preview
@Composable
fun EffectiveReplacesScreenPreview() = LegadoThemePreview {
    EffectiveReplacesScreen(
        items = previewReplaceRules,
        onAddRule = {},
        onItemClick = {},
        onManageAll = {},
        onDismiss = {},
    )
}

@Preview
@Composable
fun EffectiveReplacesScreenEmptyPreview() = LegadoThemePreview {
    EffectiveReplacesScreen(
        items = emptyList(),
        onAddRule = {},
        onItemClick = {},
        onManageAll = {},
        onDismiss = {},
    )
}

// ===== ContentEditDialog =====

@Preview
@Composable
fun ContentEditDialogPreview() = LegadoThemePreview {
    ContentEditDialog(
        chapterName = "第一章 科学边界",
        content = buildString {
            appendLine("物理学在这一切之中扮演了什么角色?")
            appendLine("杨冬在心中默默问自己。")
            appendLine("她看着窗外, 那颗恒星的影像已经在屏幕上消散,")
            appendLine("只剩下空荡荡的宇宙, 像一个无声的嘲弄。")
        },
        onSubmit = {},
        onDismiss = {},
        onReset = {},
        clipTextSink = {},
    )
}

@Preview
@Composable
fun ContentEditDialogLongContentPreview() = LegadoThemePreview {
    ContentEditDialog(
        chapterName = "第二章 疯狂年代",
        content = buildString {
            repeat(30) { i ->
                appendLine("第 ${i + 1} 段: 这是一段用于测试长正文滚动展示效果的占位内容, ")
                appendLine("用于验证 OutlinedTextField 在 maxLines=10 时的滚动行为。")
            }
        },
        onSubmit = {},
        onDismiss = {},
    )
}

@Preview
@Composable
fun ContentEditDialogEmptyContentPreview() = LegadoThemePreview {
    ContentEditDialog(
        chapterName = "空章节",
        content = "",
        onSubmit = {},
        onDismiss = {},
    )
}

@Preview
@Composable
fun ContentEditDialogDarkPreview() = LegadoThemePreview(dark = true) {
    ContentEditDialog(
        chapterName = "第一章 科学边界",
        content = "物理学在这一切之中扮演了什么角色?",
        onSubmit = {},
        onDismiss = {},
        onReset = {},
        clipTextSink = {},
    )
}
