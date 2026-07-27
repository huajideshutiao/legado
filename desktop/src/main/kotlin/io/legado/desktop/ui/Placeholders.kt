package io.legado.desktop.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import io.legado.app.ui.compose.platform.rememberString

/**
 * 桌面端剩余 1 个一级入口的占位 Screen (设置)。
 *
 * 书架/书源/搜索/替换规则/WebDav 已下沉 KMP 共享 Screen:
 * - [io.legado.desktop.ui.bookshelf.BookshelfScreen]
 * - [io.legado.desktop.ui.booksource.BookSourceScreen]
 * - [io.legado.desktop.ui.search.SearchScreen]
 * - [io.legado.desktop.ui.replace.ReplaceRuleScreen]
 * - [io.legado.desktop.ui.webdav.WebDavConfigScreen]
 *
 * 设置 Screen 待后续下沉 app 端 ConfigScreen 后替换。
 */

@Composable
fun SettingsScreenPlaceholder() {
    PlaceholderBox(text = rememberString("settings_not_implemented"))
}

/**
 * 占位 Composable 通用实现：居中显示 [text]，使用 MaterialTheme headlineMedium 字号。
 */
@Composable
private fun PlaceholderBox(text: String) {
    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.headlineMedium,
        )
    }
}
