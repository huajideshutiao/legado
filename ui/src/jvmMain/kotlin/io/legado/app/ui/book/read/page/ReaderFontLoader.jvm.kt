package io.legado.app.ui.book.read.page

import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.platform.Font
import androidx.compose.ui.text.platform.Typeface
import io.legado.app.constant.AppLog
import java.io.File

/**
 * 桌面 JVM actual: Skia FontMgr 直接按文件路径加载。
 *
 * 读完把本次读到的文件标识 (大小 + 最后修改时刻) 登记给 [ReaderFontSource], 度量侧据此复用
 * 已加载字形 (度量侧与绘制侧是同一份字形)。
 *
 * 字体文件读不到或建不出字形时: 度量侧已建过字形就直接复用那一份 (不取授权、不回落默认字形),
 * 从未建过才登记读不到并回落默认字体; [AppLog] 只在真回落时留一条, 不弹任何界面。
 */
actual fun loadReaderFontFamily(path: String): FontFamily? {
    val file = File(path)
    if (!file.isFile) {
        return reportFontLoadFailure(path, "文件不存在或不是普通文件", null)
    }
    val family = runCatching { FontFamily(Font(file)) }.getOrElse {
        return reportFontLoadFailure(path, "字体数据建不出字形", it)
    }
    ReaderFontSource.onRead(
        path,
        ReaderFontSource.FileIdentity(file.length(), file.lastModified()),
    )
    return family
}

/**
 * 字体用不了: [ReaderFontSource] 里已建过的字形仍可复用时继续用它 (那正是度量侧用的同一份字形),
 * 否则登记读不到并回落默认字体。
 */
private fun reportFontLoadFailure(path: String, reason: String, cause: Throwable?): FontFamily? {
    ReaderFontSource.builtTypeface(path)?.let { return FontFamily(Typeface(it)) }
    ReaderFontSource.onUnreadable(path)
    AppLog.put("正文字体加载失败, 回落默认字体: $path\n$reason", cause)
    return null
}
