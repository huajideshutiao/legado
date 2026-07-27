package io.legado.app.ui.book.source

import androidx.compose.runtime.Composable
import io.legado.app.ui.preview.AppPreview
import io.legado.app.data.entities.BookSource
import io.legado.app.ui.preview.LegadoThemePreview

/**
 * [SourceLoginDialog] 的 @Preview。
 *
 * 假数据: [BookSource] 用纯内存对象构造, 含/不含 loginUi 两种场景。
 * loginUi 为 JSON 数组字符串 (无 @js:/<js> 前缀, BaseSource.loginUi() 直接解析)。
 */

private val previewSource = BookSource(
    bookSourceUrl = "https://test.com",
    bookSourceName = "测试书源",
).apply {
    loginUrl = "https://test.com/login"
    // 对照 RowUi @Serializable 字段: name/type/action/chars
    loginUi = """[
        {"name":"用户名","type":"text"},
        {"name":"密码","type":"password"},
        {"name":"记住我","type":"toggle"},
        {"name":"服务器","type":"select","chars":["线1","线2","线3"]},
        {"name":"登录","type":"button","action":"login()"}
    ]"""
}

private val previewSourceNoLogin = BookSource(
    bookSourceUrl = "https://no-login.com",
    bookSourceName = "无登录书源",
)

@AppPreview
@Composable
fun SourceLoginDialogPreview() = LegadoThemePreview {
    SourceLoginDialog(
        source = previewSource,
        onDismiss = {},
        onOpenUrl = {},
    )
}

@AppPreview
@Composable
fun SourceLoginDialogNoLoginPreview() = LegadoThemePreview {
    SourceLoginDialog(
        source = previewSourceNoLogin,
        onDismiss = {},
        onOpenUrl = {},
    )
}

@AppPreview
@Composable
fun SourceLoginDialogDarkPreview() = LegadoThemePreview(dark = true) {
    SourceLoginDialog(
        source = previewSource,
        onDismiss = {},
        onOpenUrl = {},
    )
}
