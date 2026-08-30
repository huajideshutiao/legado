package io.legado.desktop.audio

import com.sun.jna.Function
import com.sun.jna.Library
import com.sun.jna.Memory
import com.sun.jna.Native
import com.sun.jna.Platform
import com.sun.jna.Pointer
import com.sun.jna.WString
import com.sun.jna.platform.win32.Guid
import com.sun.jna.platform.win32.Ole32
import com.sun.jna.ptr.PointerByReference
import io.legado.app.constant.AppLog
import io.legado.app.help.file.desktopAppRootDir
import io.legado.app.ui.compose.platform.jvmGetString
import io.legado.desktop.audio.DesktopAppUserModelId.PKEY_APP_USER_MODEL_ID_PID
import io.legado.desktop.audio.DesktopAppUserModelId.applyToWindow
import io.legado.desktop.audio.DesktopAppUserModelId.ensureProcessAppId
import org.jetbrains.skia.Bitmap
import org.jetbrains.skia.Canvas
import org.jetbrains.skia.ColorAlphaType
import org.jetbrains.skia.EncodedImageFormat
import org.jetbrains.skia.Image
import org.jetbrains.skia.ImageInfo
import org.jetbrains.skia.Rect
import org.jetbrains.skia.SamplingMode
import org.jetbrains.skia.impl.use
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream

/**
 * Windows AppUserModelID 设置 (SMTC 媒体卡应用名 / 任务栏分组身份)。
 *
 * # 背景
 * SMTC 会话经 GetForWindow 绑主窗口创建后, Windows 按进程级 AUMID 归属会话
 * (GlobalSystemMediaTransportControlsSession.SourceAppUserModelId 可查), 媒体卡显示名
 * 则从该 AUMID 解析 —— 解析只认"开始菜单快捷方式里的 System.AppUserModel.ID 属性"
 * (MSDN: enable-desktop-toast-with-appusermodelid, toast 与媒体卡同一套身份解析;
 * 注册表 AppUserModelId\DisplayName 不参与, 实测无效)。AUMID 解析不到显示名时显示"未知应用"。
 *
 * # 策略
 * - [ensureProcessAppId]: 优先复用启动器 (jpackage exe launcher) 已设置的进程 AUMID
 *   (安装包已在开始菜单注册同名快捷方式, 直接可用); 读不到 (java -jar / :desktop:run)
 *   则设置兜底值, 并按官方示例自建开始菜单快捷方式注册身份。
 * - [applyToWindow]: 把解析出的 AUMID + relaunch 属性写到主窗口属性存储 (任务栏身份,
 *   幂等)。
 *
 * 进程 AUMID + 快捷方式应在 main() 早期调用一次; 窗口属性在 SMTC init (窗口就绪) 时调用。
 */
internal object DesktopAppUserModelId {

    /** 进程 AUMID 兜底值 (未从启动器读到时的默认身份)。 */
    private const val DEFAULT_AUMID = "io.legado.desktop"

    /** PKEY_AppUserModel_ID fmtid = {9F4C2855-9F79-4B39-A8D0-E1D42DE1D5F3},
     *  Data1..3 按 Windows 内存小端布局手写。 */
    private val PKEY_APP_USER_MODEL_ID_FMTID = byteArrayOf(
        0x55.toByte(), 0x28.toByte(), 0x4C.toByte(), 0x9F.toByte(),
        0x79.toByte(), 0x9F.toByte(),
        0x39.toByte(), 0x4B.toByte(),
        0xA8.toByte(), 0xD0.toByte(), 0xE1.toByte(), 0xD4.toByte(),
        0x2D.toByte(), 0xE1.toByte(), 0xD5.toByte(), 0xF3.toByte(),
    )

    /** IPropertyStore IID。 */
    private val IID_IPROPERTYSTORE = Guid.GUID("886D8EEB-8CF2-4446-8D02-CDBA1DBDCF99")

    /** CLSID_ShellLink / IID_IShellLinkW / IID_IPersistFile (开始菜单快捷方式注册身份用)。 */
    private val CLSID_SHELLLINK = Guid.GUID("00021401-0000-0000-C000-000000000046")
    private val IID_ISHELLLINKW = Guid.GUID("000214F9-0000-0000-C000-000000000046")
    private val IID_IPERSISTFILE = Guid.GUID("0000010B-0000-0000-C000-000000000046")

    private const val VT_LPWSTR = 31

    // PKEY_AppUserModel_* 的 pid (fmtid 都是 {9F4C2855-9F79-4B39-A8D0-E1D42DE1D5F3})
    private const val PKEY_RELAUNCH_COMMAND_PID = 2
    private const val PKEY_RELAUNCH_DISPLAY_NAME_PID = 4
    private const val PKEY_APP_USER_MODEL_ID_PID = 5

    // IPropertyStore vtable 槽位 (0-2 IUnknown)
    private const val SLOT_SET_VALUE = 6
    private const val SLOT_COMMIT = 7
    private const val SLOT_RELEASE = 2

    // IShellLinkW vtable 槽位 (0-2 IUnknown)
    private const val SLOT_SHELLLINK_SET_ARGUMENTS = 11
    private const val SLOT_SHELLLINK_SET_ICON_LOCATION = 17
    private const val SLOT_SHELLLINK_SET_PATH = 20

    // IPersistFile vtable 槽位 (0-2 IUnknown, 3 GetClassID, 4 IsDirty, 5 Load, 6 Save)
    private const val SLOT_PERSISTFILE_SAVE = 6

    /** 已解析的进程 AUMID (幂等标志)。 */
    @Volatile
    private var resolvedAppId: String? = null

    /**
     * 解析并保证进程级 AUMID 就绪 (幂等; 非 Windows 跳过)。
     * 启动器已设置则复用, 否则用 [SetCurrentProcessExplicitAppUserModelID] 写兜底值。
     */
    fun ensureProcessAppId() {
        if (!Platform.isWindows()) return
        if (resolvedAppId != null) return
        var current: String? = null
        runCatching {
            val ref = PointerByReference()
            if (ShellAppId.INSTANCE.GetCurrentProcessExplicitAppUserModelID(ref) == 0) {
                val p = ref.value
                if (p != null) {
                    try {
                        current = p.getWideString(0)?.takeIf { it.isNotBlank() }
                    } finally {
                        Ole32.INSTANCE.CoTaskMemFree(p)
                    }
                }
            }
        }.onFailure {
            AppLog.put("读取进程 AppUserModelID 失败", it)
        }
        val appId = current ?: DEFAULT_AUMID
        resolvedAppId = appId
        if (current == null) {
            runCatching {
                ShellAppId.INSTANCE.SetCurrentProcessExplicitAppUserModelID(WString(appId))
            }.onFailure {
                AppLog.put("设置进程 AppUserModelID 失败", it)
            }
            // 启动器未注册身份 (java -jar / :desktop:run): 按 MSDN 文档
            // (enable-desktop-toast-with-appusermodelid) 自建开始菜单快捷方式注册,
            // 否则媒体卡/toast 的应用名解析不到显示名。安装包 (jpackage) 已注册则跳过。
            ensureShortcut(appId)
        }
    }

    /** 把 AUMID 写到主窗口属性存储 (SMTC 媒体卡应用名来源; 幂等)。 */
    fun applyToWindow(hwnd: Pointer) {
        if (!Platform.isWindows()) return
        ensureProcessAppId()
        val appId = resolvedAppId ?: return
        try {
            val initHr = Ole32.INSTANCE.CoInitializeEx(Pointer.NULL, Ole32.COINIT_APARTMENTTHREADED)
                .toInt()
            if (initHr != 0 && initHr != 1) {
                AppLog.put(
                    "设置窗口 AppUserModelID: CoInitializeEx 失败 hr=0x${Integer.toHexString(initHr)}"
                )
                return
            }
            try {
                val ppv = PointerByReference()
                val hr =
                    ShellAppId.INSTANCE.SHGetPropertyStoreForWindow(hwnd, IID_IPROPERTYSTORE, ppv)
                if (hr != 0 || ppv.value == null) {
                    AppLog.put(
                        "设置窗口 AppUserModelID: SHGetPropertyStoreForWindow 失败 hr=0x${
                            Integer.toHexString(
                                hr
                            )
                        }"
                    )
                    return
                }
                val store = ppv.value
                try {
                    // 窗口级 AUMID 已设置的情况下, RelaunchCommand 与 RelaunchDisplayNameResource
                    // 必须成对设置, 否则 Windows 不解析应用名 (MSDN System.AppUserModel 属性文档)。
                    setStringProp(store, PKEY_APP_USER_MODEL_ID_PID, appId)
                    setStringProp(store, PKEY_RELAUNCH_COMMAND_PID, relaunchCommand())
                    setStringProp(store, PKEY_RELAUNCH_DISPLAY_NAME_PID, displayName())
                    vtbl(store, SLOT_COMMIT)
                } finally {
                    vtbl(store, SLOT_RELEASE)
                }
            } finally {
                Ole32.INSTANCE.CoUninitialize()
            }
        } catch (e: Throwable) {
            AppLog.put("设置窗口 AppUserModelID 失败", e)
        }
    }

    /** 往窗口属性存储写一个字符串属性 (PROPVARIANT 的 wchar 指针在调用作用域内保持存活)。 */
    private fun setStringProp(store: Pointer, pid: Int, value: String) {
        val key = Memory(20)
        key.clear()
        key.write(0, PKEY_APP_USER_MODEL_ID_FMTID, 0, 16)
        key.setInt(16, pid)
        val pv = Memory(24)
        pv.clear()
        pv.setShort(0, VT_LPWSTR.toShort())
        val strMem = Memory((value.length + 1) * 2L)
        strMem.clear()
        value.forEachIndexed { i, c -> strMem.setChar(i * 2L, c) }
        pv.setPointer(8, strMem)
        vtbl(store, SLOT_SET_VALUE, key, pv)
    }

    /** 重新拉起本应用的命令 (仅用于任务栏固定/跳转列表; 显示名解析只需要非空)。 */
    private fun relaunchCommand(): String {
        val args = arguments()
        return if (args.isBlank()) "\"${javaExePath()}\"" else "\"${javaExePath()}\" $args"
    }

    /** 应用显示名 (媒体卡/任务栏); 字符串表取不到时回落 "Legado"。 */
    private fun displayName(): String {
        val name = runCatching { jvmGetString("app_name") }.getOrNull()
        return name?.takeIf { it.isNotBlank() && it != "app_name" } ?: "Legado"
    }

    // ==================== 开始菜单快捷方式 (身份注册, 对照 MSDN 官方示例) ====================

    /**
     * 幂等创建开始菜单快捷方式: 文件名 = 显示名, 目标 = 当前 java 命令,
     * 属性含 [PKEY_APP_USER_MODEL_ID_PID] 与图标。同名快捷方式已存在则跳过。
     */
    private fun ensureShortcut(appId: String) {
        if (!Platform.isWindows()) return
        runCatching {
            val startMenu =
                File(System.getenv("APPDATA") ?: return, "Microsoft\\Windows\\Start Menu\\Programs")
            if (!startMenu.isDirectory) return
            val lnk = File(startMenu, "${displayName()}.lnk")
            if (lnk.exists()) return
            createShortcut(lnk, ensureIconFile(), appId)
            AppLog.put("已创建开始菜单快捷方式: ${lnk.absolutePath}")
        }.onFailure {
            AppLog.put("创建开始菜单快捷方式失败", it)
        }
    }

    /** 生成应用图标 .ico (classpath icon.png → 256x256 ICO 容器, 存数据目录)。 */
    private fun ensureIconFile(): String? {
        val ico = File(desktopAppRootDir(), "icon.ico")
        if (ico.exists()) return ico.absolutePath
        val rawBytes = Thread.currentThread().contextClassLoader?.getResourceAsStream("icon.png")
            ?.use { it.readBytes() }
            ?: return null
        val size = 256
        val pngBytes = runCatching {
            val image = Image.makeFromEncoded(rawBytes)
            if (image.width == size && image.height == size) {
                image.close()
                rawBytes
            } else {
                val bitmap = Bitmap()
                bitmap.allocPixels(ImageInfo.makeN32(size, size, ColorAlphaType.PREMUL))
                bitmap.use { dst ->
                    Canvas(dst).use { canvas ->
                        image.use { img ->
                            // 缩图必须给采样模式: 默认是最近邻, 缩下来全是锯齿
                            canvas.drawImageRect(
                                img,
                                Rect.makeWH(img.width.toFloat(), img.height.toFloat()),
                                Rect.makeWH(size.toFloat(), size.toFloat()),
                                SamplingMode.MITCHELL,
                                null,
                                true,
                            )
                        }
                    }
                    Image.makeFromBitmap(dst).use { scaled ->
                        scaled.encodeToData(EncodedImageFormat.PNG, 100)?.bytes
                    }
                }
            }
        }.getOrNull() ?: return null
        // ICO 容器 (Vista+ 支持内嵌 PNG): ICONDIR(6) + ICONDIRENTRY(16) + PNG 数据
        val out = ByteArrayOutputStream()
        fun u16(v: Int) {
            out.write(v and 0xFF)
            out.write((v shr 8) and 0xFF)
        }

        fun u32(v: Int) {
            out.write(v and 0xFF)
            out.write((v shr 8) and 0xFF)
            out.write((v shr 16) and 0xFF)
            out.write((v shr 24) and 0xFF)
        }
        u16(0) // reserved
        u16(1) // type = icon
        u16(1) // count
        out.write(0) // width (0 = 256)
        out.write(0) // height (0 = 256)
        out.write(0) // color count
        out.write(0) // reserved
        u16(1) // planes
        u16(32) // bit count
        u32(pngBytes.size)
        u32(22) // data offset
        out.write(pngBytes)
        ico.parentFile?.mkdirs()
        FileOutputStream(ico).use { it.write(out.toByteArray()) }
        return ico.absolutePath
    }

    /** IShellLink + IPropertyStore(AUMID) + IPersistFile 创建快捷方式 (对照 MSDN 示例的 C 流程)。 */
    private fun createShortcut(lnk: File, iconPath: String?, appId: String) {
        val initHr = Ole32.INSTANCE.CoInitializeEx(Pointer.NULL, Ole32.COINIT_APARTMENTTHREADED)
            .toInt()
        if (initHr != 0 && initHr != 1) return
        try {
            val pp = PointerByReference()
            val hr = Ole32.INSTANCE.CoCreateInstance(
                CLSID_SHELLLINK,
                Pointer.NULL,
                1, // CLSCTX_INPROC_SERVER
                IID_ISHELLLINKW,
                pp,
            ).toInt()
            if (hr != 0 || pp.value == null) return
            val link = pp.value
            try {
                vtbl(link, SLOT_SHELLLINK_SET_PATH, WString(javaExePath()))
                vtbl(link, SLOT_SHELLLINK_SET_ARGUMENTS, WString(arguments()))
                if (!iconPath.isNullOrBlank()) {
                    vtbl(link, SLOT_SHELLLINK_SET_ICON_LOCATION, WString(iconPath), 0)
                }
                // 快捷方式的 System.AppUserModel.ID 属性
                val pstore = PointerByReference()
                if (queryInterface(link, IID_IPROPERTYSTORE, pstore) == 0 && pstore.value != null) {
                    try {
                        setStringProp(pstore.value, PKEY_APP_USER_MODEL_ID_PID, appId)
                        vtbl(pstore.value, SLOT_COMMIT)
                    } finally {
                        vtbl(pstore.value, SLOT_RELEASE)
                    }
                }
                // IPersistFile::Save(path, TRUE)
                val pf = PointerByReference()
                if (queryInterface(link, IID_IPERSISTFILE, pf) == 0 && pf.value != null) {
                    try {
                        vtbl(pf.value, SLOT_PERSISTFILE_SAVE, WString(lnk.absolutePath), 1)
                    } finally {
                        vtbl(pf.value, SLOT_RELEASE)
                    }
                }
            } finally {
                vtbl(link, SLOT_RELEASE)
            }
        } finally {
            Ole32.INSTANCE.CoUninitialize()
        }
    }

    private fun queryInterface(obj: Pointer, iid: Guid.GUID, out: PointerByReference): Int {
        val vtable = obj.getPointer(0)
        val qi = vtable.getPointer(0)
        return Function.getFunction(qi, Function.ALT_CONVENTION)
            .invokeInt(arrayOf(obj, iid, out))
    }

    private fun javaExePath(): String =
        File(System.getProperty("java.home"), "bin").resolve("java.exe").absolutePath

    /** 快捷方式/relaunch 用的命令行参数 (classpath + 主类)。 */
    private fun arguments(): String {
        val main = System.getProperty("sun.java.command", "")
            .substringBefore(' ')
            .takeIf { it.isNotBlank() }
        val cp = System.getProperty("java.class.path", "")
            .takeIf { it.isNotBlank() }
        return listOfNotNull(if (cp != null) "-cp \"$cp\"" else null, main).joinToString(" ")
    }

    private fun vtbl(target: Pointer, index: Int, vararg args: Any?): Int {
        val vtable = target.getPointer(0)
        val method = vtable.getPointer(index.toLong() * Native.POINTER_SIZE)
        return Function.getFunction(method, Function.ALT_CONVENTION)
            .invokeInt(arrayOf(target, *args))
    }

    private interface ShellAppId : Library {
        fun SetCurrentProcessExplicitAppUserModelID(appID: WString): Int
        fun GetCurrentProcessExplicitAppUserModelID(appID: PointerByReference): Int
        fun SHGetPropertyStoreForWindow(
            hwnd: Pointer,
            riid: Guid.GUID,
            ppv: PointerByReference
        ): Int

        companion object {
            val INSTANCE: ShellAppId = Native.load("shell32", ShellAppId::class.java)
        }
    }
}
