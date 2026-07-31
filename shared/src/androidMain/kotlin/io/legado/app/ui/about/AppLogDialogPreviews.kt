package io.legado.app.ui.about

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.tooling.preview.Preview
import io.legado.app.constant.AppLog
import io.legado.app.ui.preview.LegadoThemePreview

/**
 * [AppLogDialogContent] 的 @Preview。
 *
 * 组件直接读 AppLog.logs 单例 (纯内存环形列表), Preview 前用 putNotSave 播种几条;
 * host 未注册时副作用 no-op, 不落盘不弹 toast。
 */

@Preview
@Composable
fun AppLogDialogContentPreview() = LegadoThemePreview {
    remember {
        AppLog.putNotSave("书源[示例书源]校验通过")
        AppLog.putNotSave("换源: 三体 找到 12 个可用源")
        AppLog.putNotSave("章节解析失败", IllegalStateException("preview: 规则 content 返回空"))
    }
    AppLogDialogContent(onDismiss = {})
}

@Preview
@Composable
fun AppLogDialogContentDarkPreview() = LegadoThemePreview(dark = true) {
    remember {
        AppLog.putNotSave("WebDav 备份完成")
    }
    AppLogDialogContent(onDismiss = {})
}
