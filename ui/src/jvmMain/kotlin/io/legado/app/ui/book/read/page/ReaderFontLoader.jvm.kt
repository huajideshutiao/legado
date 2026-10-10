package io.legado.app.ui.book.read.page

import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.platform.Font
import io.legado.app.constant.AppLog
import java.io.File

/**
 * 桌面 JVM actual: Skia FontMgr 直接按文件路径加载。
 *
 * 读完把本次读到的文件标识 (大小 + 最后修改时刻) 登记给 [ReaderFontSource], 度量侧据此复用
 * 已加载字形; 读不到或建不出字体时登记读不到, 由 [resolveReaderFontFamily] 回落默认字体。
 *
 * 读不到与建不出字体都留一条可查日志, 仍回落默认字体、不弹任何界面。
 */
actual fun loadReaderFontFamily(path: String): FontFamily? {
    val file = File(path)
    if (!file.isFile) {
        ReaderFontSource.onUnreadable(path)
        AppLog.put("正文字体加载失败, 回落默认字体: $path\n文件不存在或不是普通文件")
        return null
    }
    val family = runCatching { FontFamily(Font(file)) }.getOrElse {
        ReaderFontSource.onUnreadable(path)
        AppLog.put("正文字体加载失败, 回落默认字体: $path\n字体数据建不出字形", it)
        return null
    }
    ReaderFontSource.onRead(
        path,
        ReaderFontSource.FileIdentity(file.length(), file.lastModified()),
    )
    return family
}
