package io.legado.desktop.ui.component

import java.awt.FileDialog
import java.awt.Frame
import java.io.File
import javax.swing.filechooser.FileNameExtensionFilter

/**
 * 桌面端文件选择统一入口：全部走 [java.awt.FileDialog]。
 *
 * 用户裁决：混用 JFileChooser 与 FileDialog 是偷懒，必须全统一为 FileDialog
 * （原生 AWT 控件，跟随系统文件对话框风格，比 Swing JFileChooser 更贴近桌面体验）。
 *
 * 所有方法阻塞当前线程直到用户选择/取消，须在 IO 线程或 EDT 调用（与原 JFileChooser 一致）。
 */
object FileDialogs {

    /** 选文件（打开）。[extensions] 为空表示不限；非空时作为文件名过滤后缀（不含点）。 */
    fun pickOpenFile(
        title: String? = null,
        extensions: List<String> = emptyList(),
        extensionDesc: String? = null,
    ): File? = showFileDialog(title, FileDialog.LOAD, fileMode = false, extensions, extensionDesc)

    /** 选文件（保存）。返回用户选定的 File（FileDialog 不自动加后缀，调用方自行保证）。 */
    fun pickSaveFile(
        title: String? = null,
        defaultName: String? = null,
        extensions: List<String> = emptyList(),
        extensionDesc: String? = null,
    ): File? = showFileDialog(title, FileDialog.SAVE, fileMode = false, extensions, extensionDesc, defaultName)

    /** 选目录。FileDialog 的目录选择依赖 setFile("*")，跨平台行为见实现注释。 */
    fun pickDirectory(title: String? = null): File? =
        showFileDialog(title, FileDialog.LOAD, fileMode = true)

    // 选图片并读取字节，返回 (bytes, fileName) 供调用方做扩展名判断（如 .9.png），取消返回 null
    fun pickImageFile(): Pair<ByteArray, String>? {
        val file = showFileDialog(
            title = null,
            mode = FileDialog.LOAD,
            fileMode = false,
            extensions = listOf("jpg", "jpeg", "png", "webp", "bmp", "gif"),
        ) ?: return null
        return file.readBytes() to file.name
    }

    private fun showFileDialog(
        title: String?,
        mode: Int,
        fileMode: Boolean,
        extensions: List<String> = emptyList(),
        extensionDesc: String? = null,
        defaultName: String? = null,
    ): File? {
        val dialog = FileDialog(Frame(), title ?: "", mode)
        if (fileMode) {
            // 目录选择：FilenameFilter 配合 setFile("*")，在 Windows/Linux 选目录后 getDirectory 返回选中目录
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
