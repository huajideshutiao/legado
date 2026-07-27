package io.legado.app.ui.book.read.config

import platform.Foundation.NSDocumentDirectory
import platform.Foundation.NSFileManager
import platform.Foundation.NSSearchPathForDirectoriesInDomains
import platform.Foundation.NSUserDomainMask

/**
 * iOS 端字体文件扫描（对照 app 端 `FontSelectDialog.getLocalFonts`）。
 *
 * 扫描 Documents/font 目录下的 .ttf / .otf 文件（对齐 app 端 externalFiles/font；
 * iOS 端无 externalFiles 概念，用 Documents 替代）。
 *
 * 属平台特殊行为（NSFileManager），不下沉到 commonMain；UI 由 shared/sharedUiMain 的
 * [FontSelectDialog] 提供，字体列表经本函数扫描后注入。
 */
fun listIosFontFiles(): List<FontItem> {
    val documentsDir = NSSearchPathForDirectoriesInDomains(
        NSDocumentDirectory, NSUserDomainMask, true
    ).firstOrNull() as? String ?: return emptyList()
    val fontDir = "$documentsDir/font"
    val fm = NSFileManager.defaultManager
    if (!fm.fileExistsAtPath(fontDir)) return emptyList()
    val items = fm.contentsOfDirectoryAtPath(fontDir, null)
        ?.mapNotNull { it as? String }
        ?: return emptyList()
    return items
        .filter { it.matches(fontFileRegex) }
        .sortedBy { it.lowercase() }
        .map { FontItem(path = "$fontDir/$it", name = it) }
}
