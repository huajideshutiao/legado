package io.legado.desktop.ui.component

import com.sun.jna.Platform
import java.awt.Dialog
import java.awt.EventQueue
import java.awt.FileDialog
import java.awt.Frame
import java.awt.KeyboardFocusManager
import java.awt.Window
import java.io.File
import java.util.concurrent.atomic.AtomicReference
import javax.swing.JFileChooser
import javax.swing.UIManager

/**
 * 桌面端文件选择统一入口。
 *
 * Windows 走 [WindowsFileDialogs] 的 COM IFileDialog (Win11 原生样式); macOS/Linux 继续走
 * [java.awt.FileDialog] —— AWT 在 mac 上本就是原生 NSOpenPanel, 在 Linux 上是 GTK 对话框, 观感正常。
 * 仅 Windows 的 AWT 实现落在 comdlg32 旧版 `GetOpenFileName` 上, 视觉停留在 Win9x 风格, 故单独替换。
 * 选目录是例外: AWT 只有文件模式, 非 Windows 走 [awtPickDirectory]。
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
        return showAwtDialog(title, FileDialog.LOAD, extensions, extensionDesc).firstOrNull()
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
            title, FileDialog.LOAD, extensions, extensionDesc, multiSelect = true,
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
            title, FileDialog.SAVE, extensions, extensionDesc, defaultName, initialDir,
        ).firstOrNull()
    }

    /** 选目录。Windows 走 FOS_PICKFOLDERS 现代目录选择器；其他平台见 [awtPickDirectory]。 */
    fun pickDirectory(title: String? = null, initialDir: File? = null): File? {
        if (WindowsFileDialogs.isSupported) {
            return WindowsFileDialogs.show(
                title = title,
                pickFolders = true,
                initialDir = initialDir,
                owner = ownerWindow(),
            ).firstOrNull()
        }
        return onEdt { awtPickDirectory(title, initialDir) }
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
            showAwtDialog(null, FileDialog.LOAD, extensions).firstOrNull()
        } ?: return null
        return file.readBytes() to file.name
    }

    // 原生对话框需要属主窗口才能正确置顶并屏蔽主窗口输入
    private fun ownerWindow(): Window? =
        KeyboardFocusManager.getCurrentKeyboardFocusManager().activeWindow
            ?: Window.getWindows().firstOrNull { it.isDisplayable && it.isVisible }

    /**
     * 非 Windows 的目录选择。[FileDialog] 只有文件模式, 两端都拿不到文件夹:
     * - macOS 的 NSOpenPanel 需 `apple.awt.fileDialogForDirectories` 才把文件夹当可选目标, 否则只能双击进入;
     * - Linux 的 GtkFileDialogPeer 固定用 GTK_FILE_CHOOSER_ACTION_OPEN, 没有目录模式, 只能改用 Swing。
     */
    private fun awtPickDirectory(title: String?, initialDir: File?): File? {
        if (!Platform.isMac()) return swingPickDirectory(title, initialDir)
        val key = "apple.awt.fileDialogForDirectories"
        val previous = System.getProperty(key)
        System.setProperty(key, "true")
        return try {
            showAwtDialog(title, FileDialog.LOAD, initialDir = initialDir).firstOrNull()
        } finally {
            if (previous == null) System.clearProperty(key) else System.setProperty(key, previous)
        }
    }

    private fun swingPickDirectory(title: String?, initialDir: File?): File? {
        // JFileChooser 跟随全局 LAF, 默认的 Metal 与桌面观感差太远 (Linux 下系统 LAF 即 GTK)
        val system = UIManager.getSystemLookAndFeelClassName()
        if (UIManager.getLookAndFeel()?.javaClass?.name != system) {
            runCatching { UIManager.setLookAndFeel(system) }
        }
        val chooser = JFileChooser().apply {
            fileSelectionMode = JFileChooser.DIRECTORIES_ONLY
            isMultiSelectionEnabled = false
            title?.takeIf { it.isNotEmpty() }?.let { dialogTitle = it }
            initialDir?.takeIf { it.isDirectory }?.let { currentDirectory = it }
        }
        if (chooser.showOpenDialog(ownerWindow()) != JFileChooser.APPROVE_OPTION) return null
        return chooser.selectedFile?.takeIf { it.isDirectory }
    }

    /** Swing 组件必须在 EDT 上创建/显示; 模态 show 自带二级事件循环, EDT 不会冻住。 */
    private fun <T> onEdt(block: () -> T): T? {
        if (EventQueue.isDispatchThread()) return block()
        val result = AtomicReference<T?>()
        runCatching { EventQueue.invokeAndWait { result.set(block()) } }
        return result.get()
    }

    private fun showAwtDialog(
        title: String?,
        mode: Int,
        extensions: List<String> = emptyList(),
        extensionDesc: String? = null,
        defaultName: String? = null,
        initialDir: File? = null,
        multiSelect: Boolean = false,
    ): List<File> {
        // 属主优先用当前应用窗口 (支持 Frame / Dialog); 取不到才造临时 Frame, 用完必须 dispose 否则每次调用泄漏一个原生窗口
        val owner = ownerWindow()
        val temp = if (owner !is Frame && owner !is Dialog) Frame() else null
        val dialog = when (owner) {
            is Frame -> FileDialog(owner, title ?: "", mode)
            is Dialog -> FileDialog(owner, title ?: "", mode)
            else -> FileDialog(temp!!, title ?: "", mode)
        }
        try {
            initialDir?.takeIf { it.isDirectory }?.let { dialog.directory = it.absolutePath }
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
