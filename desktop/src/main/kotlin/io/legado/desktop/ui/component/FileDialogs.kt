package io.legado.desktop.ui.component

import java.awt.FileDialog
import java.awt.Frame
import java.awt.KeyboardFocusManager
import java.awt.Window
import java.io.File

/**
 * 桌面端文件选择统一入口。
 *
 * Windows 走 [WindowsFileDialogs] 的 COM IFileDialog (Win11 原生样式); macOS/Linux 继续走
 * [java.awt.FileDialog] —— AWT 在 mac 上本就是原生 NSOpenPanel, 在 Linux 上是 GTK 对话框, 观感正常。
 * 仅 Windows 的 AWT 实现落在 comdlg32 旧版 `GetOpenFileName` 上, 视觉停留在 Win9x 风格, 故单独替换。
 *
 * 所有方法阻塞当前线程直到用户选择/取消, 须在 IO 线程或 EDT 调用。
 */
object FileDialogs {

    /** 选文件（打开）。[extensions] 为空表示不限；非空时作为文件名过滤后缀（不含点）。 */
    fun pickOpenFile(
        title: String? = null,
        extensions: List<String> = emptyList(),
        extensionDesc: String? = null,
    ): File? {
        if (WindowsFileDialogs.isSupported) {
            return WindowsFileDialogs.show(
                title = title,
                extensions = extensions,
                extensionDesc = extensionDesc,
                owner = ownerWindow(),
            ).firstOrNull()
        }
        return showAwtDialog(title, FileDialog.LOAD, fileMode = false, extensions, extensionDesc)
    }

    /** 选文件（保存）。返回用户选定的 File（调用方自行保证后缀）。 */
    fun pickSaveFile(
        title: String? = null,
        defaultName: String? = null,
        extensions: List<String> = emptyList(),
        extensionDesc: String? = null,
        initialDir: File? = null,
    ): File? {
        if (WindowsFileDialogs.isSupported) {
            return WindowsFileDialogs.show(
                title = title,
                save = true,
                extensions = extensions,
                extensionDesc = extensionDesc,
                defaultName = defaultName,
                initialDir = initialDir,
                owner = ownerWindow(),
            ).firstOrNull()
        }
        return showAwtDialog(
            title, FileDialog.SAVE, fileMode = false,
            extensions, extensionDesc, defaultName, initialDir,
        )
    }

    /** 选目录。Windows 走 FOS_PICKFOLDERS 现代目录选择器；其他平台沿用 AWT 兜底。 */
    fun pickDirectory(title: String? = null, initialDir: File? = null): File? {
        if (WindowsFileDialogs.isSupported) {
            return WindowsFileDialogs.show(
                title = title,
                pickFolders = true,
                initialDir = initialDir,
                owner = ownerWindow(),
            ).firstOrNull()
        }
        return showAwtDialog(title, FileDialog.LOAD, fileMode = true, initialDir = initialDir)
    }

    // 选图片并读取字节，返回 (bytes, fileName) 供调用方做扩展名判断（如 .9.png），取消返回 null
    fun pickImageFile(): Pair<ByteArray, String>? {
        val extensions = listOf("jpg", "jpeg", "png", "webp", "bmp", "gif")
        val file = if (WindowsFileDialogs.isSupported) {
            WindowsFileDialogs.show(
                title = null,
                extensions = extensions,
                extensionDesc = "图片",
                owner = ownerWindow(),
            ).firstOrNull()
        } else {
            showAwtDialog(null, FileDialog.LOAD, fileMode = false, extensions)
        } ?: return null
        return file.readBytes() to file.name
    }

    // 原生对话框需要属主窗口才能正确置顶并屏蔽主窗口输入
    private fun ownerWindow(): Window? =
        KeyboardFocusManager.getCurrentKeyboardFocusManager().activeWindow
            ?: Window.getWindows().firstOrNull { it.isDisplayable && it.isVisible }

    private fun showAwtDialog(
        title: String?,
        mode: Int,
        fileMode: Boolean,
        extensions: List<String> = emptyList(),
        extensionDesc: String? = null,
        defaultName: String? = null,
        initialDir: File? = null,
    ): File? {
        val dialog = FileDialog(Frame(), title ?: "", mode)
        initialDir?.takeIf { it.isDirectory }?.let { dialog.directory = it.absolutePath }
        if (fileMode) {
            // 目录选择：FilenameFilter 配合 setFile("*")，在 Linux 选目录后 getDirectory 返回选中目录
            dialog.file = "*"
        }
        if (extensions.isNotEmpty()) {
            dialog.setFilenameFilter { _, name ->
                extensions.any { ext -> name.endsWith(".$ext", ignoreCase = true) }
            }
        }
        defaultName?.let { dialog.file = it }
        dialog.isVisible = true
        val dir = dialog.directory ?: return null
        val file = dialog.file ?: return null
        return File(dir, file).takeIf { it.exists() || mode == FileDialog.SAVE }
    }
}
