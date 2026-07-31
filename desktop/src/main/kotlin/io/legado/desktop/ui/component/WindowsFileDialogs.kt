package io.legado.desktop.ui.component

import com.sun.jna.Function
import com.sun.jna.Memory
import com.sun.jna.Native
import com.sun.jna.Pointer
import com.sun.jna.Platform
import com.sun.jna.WString
import com.sun.jna.platform.win32.Guid
import com.sun.jna.platform.win32.Ole32
import com.sun.jna.platform.win32.WinNT
import com.sun.jna.ptr.IntByReference
import com.sun.jna.ptr.PointerByReference
import com.sun.jna.win32.StdCallLibrary
import com.sun.jna.win32.W32APIOptions
import java.awt.EventQueue
import java.awt.Toolkit
import java.awt.Window
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicReference

/**
 * Windows Vista+ 现代文件对话框 (Common Item Dialog / IFileDialog) 的 JNA 直调实现。
 *
 * 根因: [java.awt.FileDialog] 在 Windows 上走 comdlg32 的 `GetOpenFileName`, 那是 Win9x 时代的
 * 旧版对话框, 视觉停留在 Windows 3.x/9x 风格, 且无法选目录。改调 COM 的 IFileOpenDialog /
 * IFileSaveDialog 即得到 Win11 原生样式 (含 FOS_PICKFOLDERS 的现代目录选择器)。
 *
 * jna-platform 只提供 COM 基础设施 (Ole32/Guid/HRESULT), 不含 IFileDialog 包装类,
 * 故此处按 vtable 序号手写调用 ([vtbl])。序号取自 Windows SDK ShObjIdl_core.h 的接口声明顺序。
 */
internal object WindowsFileDialogs {

    val isSupported: Boolean = Platform.isWindows()

    // ---- COM 常量 (ShObjIdl_core.h) ----
    private const val CLSCTX_INPROC_SERVER = 1
    private const val S_OK = 0

    private val CLSID_FILE_OPEN_DIALOG = Guid.GUID("DC1C5A9C-E88A-4dde-A5A1-60F82A20AEF7")
    private val CLSID_FILE_SAVE_DIALOG = Guid.GUID("C0B4E2F3-BA21-4773-8DBA-335EC946EB8B")
    private val IID_IFILE_OPEN_DIALOG = Guid.GUID("d57c7288-d4ad-4768-be02-9d969532d960")
    private val IID_IFILE_SAVE_DIALOG = Guid.GUID("84bccd23-5fde-4cdb-aea4-af64b83d78ab")
    private val IID_ISHELL_ITEM = Guid.GUID("43826d1e-e718-42ee-bc55-a1e261c37bfe")

    // FILEOPENDIALOGOPTIONS
    private const val FOS_OVERWRITEPROMPT = 0x2
    private const val FOS_PICKFOLDERS = 0x20
    private const val FOS_FORCEFILESYSTEM = 0x40
    private const val FOS_ALLOWMULTISELECT = 0x200
    private const val FOS_PATHMUSTEXIST = 0x800
    private const val FOS_FILEMUSTEXIST = 0x1000

    private const val SIGDN_FILESYSPATH = 0x80058000.toInt()

    // IFileDialog vtable 序号 (0~2 为 IUnknown, 3 为 IModalWindow::Show)
    private const val VT_SHOW = 3
    private const val VT_SET_FILE_TYPES = 4
    private const val VT_SET_OPTIONS = 9
    private const val VT_GET_OPTIONS = 10
    private const val VT_SET_FOLDER = 12
    private const val VT_SET_FILE_NAME = 15
    private const val VT_SET_TITLE = 17
    private const val VT_GET_RESULT = 20
    private const val VT_SET_DEFAULT_EXTENSION = 22
    private const val VT_RELEASE = 2

    // IFileOpenDialog::GetResults (IFileDialog 之后第一个新增方法)
    private const val VT_GET_RESULTS = 27

    // IShellItem::GetDisplayName
    private const val VT_ITEM_GET_DISPLAY_NAME = 5

    // IShellItemArray::GetCount / GetItemAt
    private const val VT_ARRAY_GET_COUNT = 7
    private const val VT_ARRAY_GET_ITEM_AT = 8

    private interface Shell32Ex : StdCallLibrary {
        fun SHCreateItemFromParsingName(
            pszPath: WString,
            pbc: Pointer?,
            riid: Guid.GUID,
            ppv: PointerByReference,
        ): WinNT.HRESULT
    }

    private val shell32: Shell32Ex? by lazy {
        runCatching {
            Native.load("shell32", Shell32Ex::class.java, W32APIOptions.DEFAULT_OPTIONS)
        }.getOrNull()
    }

    /**
     * 显示现代文件对话框。返回选中路径 (取消/失败返回空列表)。
     *
     * COM 模态对话框需要 STA 单元, 故固定在一次性专用线程上跑, 不污染调用方 (IO 池) 的单元状态。
     * 调用方线程分两种收尾方式:
     * - EDT: 走 [java.awt.SecondaryLoop] 边等边分发事件 (与 AWT 模态对话框同机制)。直接 join 会掐死
     *   AWT 消息泵, 而 `Show(owner)` 期间主窗口正处于 `EnableWindow(false)` 禁用态, 泵一停就再也收不到
     *   恢复消息 —— 表现为窗口能重绘/能拖动但键鼠永久失效。
     * - 其它线程 (IO 池等): 只阻塞该线程, EDT 照常运行, 用 latch 等即可。
     */
    fun show(
        title: String?,
        save: Boolean = false,
        pickFolders: Boolean = false,
        multiSelect: Boolean = false,
        extensions: List<String> = emptyList(),
        extensionDesc: String? = null,
        defaultName: String? = null,
        initialDir: File? = null,
        owner: Window? = null,
    ): List<File> {
        if (!isSupported) return emptyList()
        val ownerHwnd = owner?.let { runCatching { Native.getWindowPointer(it) }.getOrNull() }
        val result = AtomicReference<List<File>>(emptyList())
        val done = CountDownLatch(1)
        val loop = if (EventQueue.isDispatchThread()) {
            runCatching { Toolkit.getDefaultToolkit().systemEventQueue.createSecondaryLoop() }.getOrNull()
        } else {
            null
        }
        val thread = Thread({
            try {
                result.set(
                    runCatching {
                        showOnStaThread(
                            title, save, pickFolders, multiSelect,
                            extensions, extensionDesc, defaultName, initialDir, ownerHwnd,
                        )
                    }.getOrDefault(emptyList())
                )
            } finally {
                // 异常/崩溃路径下 COM 可能没复原属主窗口的禁用态, 这里无条件兜底
                restoreOwner(ownerHwnd)
                done.countDown()
                // 经事件队列退出: 保证 exit() 排在 enter() 之后。STA 线程直接调 exit() 可能早于
                // EDT 进入循环, 那次 exit 会被丢弃, EDT 反而永久停在 enter()。
                loop?.let { EventQueue.invokeLater { it.exit() } }
            }
        }, "win-file-dialog")
        thread.isDaemon = true
        thread.start()
        // enter() 阻塞本线程但持续分发 AWT 事件; 返回 false 表示循环没起来, 退回 latch 等待
        if (loop == null || !loop.enter()) {
            runCatching { done.await() }
        }
        return result.get()
    }

    /** COM 模态期间属主窗口被 `EnableWindow(false)`; 已是启用态时不动它, 只把焦点交回来。 */
    private fun restoreOwner(ownerHwnd: Pointer?) {
        if (ownerHwnd == null) return
        // jna-platform 的 User32 没声明 EnableWindow, 直接按符号取 (ALT_CONVENTION = stdcall)
        runCatching {
            if (user32("IsWindowEnabled").invokeInt(arrayOf(ownerHwnd)) == 0) {
                user32("EnableWindow").invokeInt(arrayOf(ownerHwnd, 1))
            }
            user32("SetForegroundWindow").invokeInt(arrayOf(ownerHwnd))
        }
    }

    private fun user32(name: String): Function =
        Function.getFunction("user32", name, Function.ALT_CONVENTION)

    private fun showOnStaThread(
        title: String?,
        save: Boolean,
        pickFolders: Boolean,
        multiSelect: Boolean,
        extensions: List<String>,
        extensionDesc: String?,
        defaultName: String?,
        initialDir: File?,
        ownerHwnd: Pointer?,
    ): List<File> {
        // 初始化失败时不能配对 CoUninitialize (会把别人的引用计数减没)
        val initialized = Ole32.INSTANCE
            .CoInitializeEx(Pointer.NULL, Ole32.COINIT_APARTMENTTHREADED).toInt() >= 0
        if (!initialized) return emptyList()
        try {
            val ppv = PointerByReference()
            val hr = Ole32.INSTANCE.CoCreateInstance(
                if (save) CLSID_FILE_SAVE_DIALOG else CLSID_FILE_OPEN_DIALOG,
                Pointer.NULL,
                CLSCTX_INPROC_SERVER,
                if (save) IID_IFILE_SAVE_DIALOG else IID_IFILE_OPEN_DIALOG,
                ppv,
            )
            val dialog = ppv.value
            if (hr.toInt() != S_OK || dialog == null) return emptyList()
            try {
                return configureAndShow(
                    dialog, title, save, pickFolders, multiSelect,
                    extensions, extensionDesc, defaultName, initialDir, ownerHwnd,
                )
            } finally {
                vtbl(dialog, VT_RELEASE)
            }
        } finally {
            Ole32.INSTANCE.CoUninitialize()
        }
    }

    private fun configureAndShow(
        dialog: Pointer,
        title: String?,
        save: Boolean,
        pickFolders: Boolean,
        multiSelect: Boolean,
        extensions: List<String>,
        extensionDesc: String?,
        defaultName: String?,
        initialDir: File?,
        ownerHwnd: Pointer?,
    ): List<File> {
        // native 内存需活到 Show() 返回, 统一挂在本 list 上防提前 GC
        val held = ArrayList<Memory>()

        val current = IntByReference()
        vtbl(dialog, VT_GET_OPTIONS, current)
        var options = current.value or FOS_FORCEFILESYSTEM or FOS_PATHMUSTEXIST
        options = when {
            pickFolders -> options or FOS_PICKFOLDERS
            save -> options or FOS_OVERWRITEPROMPT
            else -> options or FOS_FILEMUSTEXIST
        }
        if (multiSelect && !save && !pickFolders) options = options or FOS_ALLOWMULTISELECT
        vtbl(dialog, VT_SET_OPTIONS, options)

        title?.takeIf { it.isNotEmpty() }?.let {
            vtbl(dialog, VT_SET_TITLE, wide(it, held))
        }
        defaultName?.takeIf { it.isNotEmpty() }?.let {
            vtbl(dialog, VT_SET_FILE_NAME, wide(it, held))
        }
        if (!pickFolders && extensions.isNotEmpty()) {
            applyFilters(dialog, extensions, extensionDesc, held)
            // 保存时用户不打后缀则自动补首个扩展名
            if (save) vtbl(dialog, VT_SET_DEFAULT_EXTENSION, wide(extensions.first(), held))
        }
        initialDir?.takeIf { it.isDirectory }?.let { dir ->
            shellItemOf(dir)?.let { item ->
                vtbl(dialog, VT_SET_FOLDER, item)
                vtbl(item, VT_RELEASE)
            }
        }

        val shown = vtbl(dialog, VT_SHOW, ownerHwnd ?: Pointer.NULL)
        // 用户取消返回 HRESULT_FROM_WIN32(ERROR_CANCELLED), 非错误
        if (shown != S_OK) {
            held.clear()
            return emptyList()
        }
        val paths = if (multiSelect && !save && !pickFolders) readResults(dialog) else readResult(dialog)
        held.clear()
        return paths
    }

    /** 单选: IFileDialog::GetResult → IShellItem */
    private fun readResult(dialog: Pointer): List<File> {
        val out = PointerByReference()
        if (vtbl(dialog, VT_GET_RESULT, out) != S_OK) return emptyList()
        val item = out.value ?: return emptyList()
        return try {
            listOfNotNull(itemPath(item)?.let(::File))
        } finally {
            vtbl(item, VT_RELEASE)
        }
    }

    /** 多选: IFileOpenDialog::GetResults → IShellItemArray */
    private fun readResults(dialog: Pointer): List<File> {
        val out = PointerByReference()
        if (vtbl(dialog, VT_GET_RESULTS, out) != S_OK) return emptyList()
        val array = out.value ?: return emptyList()
        return try {
            val count = IntByReference()
            if (vtbl(array, VT_ARRAY_GET_COUNT, count) != S_OK) return emptyList()
            (0 until count.value).mapNotNull { index ->
                val itemRef = PointerByReference()
                if (vtbl(array, VT_ARRAY_GET_ITEM_AT, index, itemRef) != S_OK) return@mapNotNull null
                val item = itemRef.value ?: return@mapNotNull null
                try {
                    itemPath(item)?.let(::File)
                } finally {
                    vtbl(item, VT_RELEASE)
                }
            }
        } finally {
            vtbl(array, VT_RELEASE)
        }
    }

    /** IShellItem::GetDisplayName(SIGDN_FILESYSPATH); 返回串由 COM 分配, 需 CoTaskMemFree */
    private fun itemPath(item: Pointer): String? {
        val out = PointerByReference()
        if (vtbl(item, VT_ITEM_GET_DISPLAY_NAME, SIGDN_FILESYSPATH, out) != S_OK) return null
        val raw = out.value ?: return null
        return try {
            raw.getWideString(0)
        } finally {
            Ole32.INSTANCE.CoTaskMemFree(raw)
        }
    }

    private fun shellItemOf(dir: File): Pointer? {
        val api = shell32 ?: return null
        val out = PointerByReference()
        val hr = runCatching {
            api.SHCreateItemFromParsingName(WString(dir.absolutePath), null, IID_ISHELL_ITEM, out)
        }.getOrNull() ?: return null
        return out.value.takeIf { hr.toInt() == S_OK }
    }

    /**
     * IFileDialog::SetFileTypes 需要连续的 COMDLG_FILTERSPEC 数组 (每项两个宽字符串指针),
     * 手工排布内存比 Structure.toArray 更可控。
     */
    private fun applyFilters(
        dialog: Pointer,
        extensions: List<String>,
        extensionDesc: String?,
        held: MutableList<Memory>,
    ) {
        val spec = extensions.joinToString(";") { "*.$it" }
        val name = extensionDesc?.takeIf { it.isNotEmpty() }
            ?: extensions.joinToString("/") { it.uppercase() }
        val specs = listOf(name to spec, "所有文件" to "*.*")
        val pointerSize = Native.POINTER_SIZE.toLong()
        val array = Memory(specs.size * 2L * pointerSize)
        held += array
        specs.forEachIndexed { index, (label, pattern) ->
            array.setPointer(index * 2L * pointerSize, wide(label, held))
            array.setPointer((index * 2L + 1) * pointerSize, wide(pattern, held))
        }
        vtbl(dialog, VT_SET_FILE_TYPES, specs.size, array)
    }

    private fun wide(value: String, held: MutableList<Memory>): Memory {
        val memory = Memory((value.length + 1) * 2L)
        memory.setWideString(0, value)
        held += memory
        return memory
    }

    /**
     * 按 vtable 序号调用 COM 方法: 对象首字段是 vtable 指针, 第 [index] 项即目标函数,
     * 第一个实参固定为 this 指针。ALT_CONVENTION = stdcall (x86 必需, x64 无副作用)。
     */
    private fun vtbl(target: Pointer, index: Int, vararg args: Any?): Int {
        val vtable = target.getPointer(0)
        val method = vtable.getPointer(index.toLong() * Native.POINTER_SIZE)
        return Function.getFunction(method, Function.ALT_CONVENTION)
            .invokeInt(arrayOf(target, *args))
    }
}
