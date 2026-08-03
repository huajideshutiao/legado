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
 * 所有方法阻塞当前线程直到用户选择/取消, 但两条分支在 EDT 上都会继续分发 AWT 事件
 * (Windows 分支见 [WindowsFileDialogs.show], AWT 分支靠模态 Dialog 自带的二级事件循环),
 * 故 EDT / IO 线程调用均安全。
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
            .firstOrNull()
    }

    /** 选多个文件（打开）。Windows 走 IFileDialog 多选；macOS/Linux 走 AWT 多选模式。 */
    fun pickOpenFiles(
        title: String? = null,
        extensions: List<String> = emptyList(),
        extensionDesc: String? = null,
    ): List<File> {
        if (WindowsFileDialogs.isSupported) {
            return WindowsFileDialogs.show(
                title = title,
                multiSelect = true,
                extensions = extensions,
                extensionDesc = extensionDesc,
                owner = ownerWindow(),
            )
        }
        return showAwtDialog(
            title, FileDialog.LOAD, fileMode = false, extensions, extensionDesc,
            multiSelect = true,
        )
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
        ).firstOrNull()
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
            .firstOrNull()
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
            showAwtDialog(null, FileDialog.LOAD, fileMode = false, extensions).firstOrNull()
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
        multiSelect: Boolean = false,
    ): List<File> {
        // 属主优先用当前应用窗口; 取不到才造临时 Frame, 用完必须 dispose 否则每次调用泄漏一个原生窗口
        val parent = ownerWindow() as? Frame
        val temp = if (parent == null) Frame() else null
        val dialog = FileDialog(parent ?: temp!!, title ?: "", mode)
        try {
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
            // 多选模式 (macOS NSOpenPanel / Linux GTK 均支持); Windows 走 WindowsFileDialogs 分支不至此
            if (multiSelect && mode == FileDialog.LOAD) {
                dialog.isMultipleMode = true
            }
            // 模态 show: 在 EDT 上 AWT 自行开二级事件循环 (UI 不冻结), 其它线程上只阻塞该线程
            dialog.isVisible = true
            val dir = dialog.directory ?: return emptyList()
            return if (multiSelect && dialog.isMultipleMode) {
                dialog.files
                    .filter { it.exists() }
                    .map { File(it.absolutePath) }
            } else {
                val file = dialog.file ?: return emptyList()
                listOfNotNull(File(dir, file).takeIf { it.exists() || mode == FileDialog.SAVE })
            }
        } finally {
            dialog.dispose()
            temp?.dispose()
        }
    }
}
