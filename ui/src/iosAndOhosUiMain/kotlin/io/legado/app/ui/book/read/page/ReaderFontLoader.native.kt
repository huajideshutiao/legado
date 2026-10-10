package io.legado.app.ui.book.read.page

import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.platform.Font
import androidx.compose.ui.text.platform.Typeface
import io.legado.app.constant.AppLog
import io.legado.app.constant.DirUsage
import io.legado.app.help.FileUtilsCommon
import io.legado.app.ui.withLocalDirAccess

/**
 * iOS / 鸿蒙 actual: 两端文本栈同为 skiko, 只有「字节数组」入口,
 * 先经 [FileUtilsCommon] 读盘再建 Font。
 *
 * 字体文件可能是用户在应用容器外选定的字体目录里的文件, 读盘包在平台授权内
 * (用途 [DirUsage.Font]); 读不到 (未授权/授权失效) 或建不出字形时, [ReaderFontSource] 里
 * 已建过的字形仍可复用时继续用那一份 (度量侧用的就是同一份字形, 不回落默认字体), 从未建过才登记
 * 读不到并回落默认字体。
 *
 * 文件标识在同一段授权内随读盘一起取 (大小 + 最后修改时刻) 并登记给 [ReaderFontSource],
 * 度量侧据此复用已加载字形, 不必为探测标识再取一次授权。
 *
 * [AppLog] 只在真回落时留一条, 不弹任何界面。
 */
actual fun loadReaderFontFamily(path: String): FontFamily? {
    val read = runCatching {
        withLocalDirAccess(path, DirUsage.Font) {
            val bytes = FileUtilsCommon.readBytes(path)
            if (bytes == null || bytes.isEmpty()) {
                null
            } else {
                ReaderFontSource.FileIdentity(
                    size = bytes.size.toLong(),
                    lastModified = FileUtilsCommon.lastModified(path),
                ) to bytes
            }
        }
    }.getOrElse { return reportFontLoadFailure(path, "取授权或读盘失败", it) }
    if (read == null) return reportFontLoadFailure(path, "字体文件读不到或内容为空", null)
    val family = runCatching { FontFamily(Font(identity = path, data = read.second)) }
        .getOrElse { return reportFontLoadFailure(path, "字体数据建不出字形", it) }
    ReaderFontSource.onRead(path, read.first)
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
