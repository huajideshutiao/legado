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
import io.legado.app.ui.compose.platform.jvmGetString
import io.legado.desktop.audio.DesktopAppUserModelId.applyToWindow
import io.legado.desktop.audio.DesktopAppUserModelId.ensureProcessAppId
import java.io.File

/**
 * Windows AppUserModelID 设置 (SMTC 媒体卡应用名 / 任务栏分组身份)。
 *
 * # 背景
 * SMTC 会话经 GetForWindow 绑主窗口创建后, 媒体卡上显示的应用名取自窗口的
 * AppUserModelID (PKEY_AppUserModel_ID 窗口属性存储) 或进程级 AUMID。
 * 两者都不设置时, Windows 显示"未知应用"。
 * 仅设 AUMID 还不够: 解析显示名需要 RelaunchCommand + RelaunchDisplayNameResource
 * 成对设置 (MSDN System.AppUserModel.RelaunchDisplayNameResource, 无快捷方式时的官方途径),
 * 本类一次性把三者都写到窗口属性存储。
 *
 * # 策略
 * - [ensureProcessAppId]: 优先复用启动器 (jpackage 的 exe launcher) 已设置的进程 AUMID,
 *   保证与安装包快捷方式注册的身份一致; 读不到 (如 java -jar 直接跑) 则设置兜底值。
 * - [applyToWindow]: 把解析出的 AUMID + relaunch 属性写到主窗口属性存储, 让 GetForWindow
 *   会话能以该身份显示应用名。
 *
 * 进程 AUMID 应在 main() 早期调用一次; 窗口属性在 SMTC init (窗口就绪) 时调用, 幂等。
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

    private const val VT_LPWSTR = 31

    // PKEY_AppUserModel_* 的 pid (fmtid 都是 {9F4C2855-9F79-4B39-A8D0-E1D42DE1D5F3})
    private const val PKEY_RELAUNCH_COMMAND_PID = 2
    private const val PKEY_RELAUNCH_DISPLAY_NAME_PID = 4
    private const val PKEY_APP_USER_MODEL_ID_PID = 5

    // IPropertyStore vtable 槽位 (0-2 IUnknown)
    private const val SLOT_SET_VALUE = 6
    private const val SLOT_COMMIT = 7
    private const val SLOT_RELEASE = 2

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
        val javaExe = File(System.getProperty("java.home"), "bin")
            .resolve("java.exe")
            .absolutePath
        val main = System.getProperty("sun.java.command", "")
            .substringBefore(' ')
            .takeIf { it.isNotBlank() }
        return listOfNotNull("\"$javaExe\"", main).joinToString(" ")
    }

    /** 应用显示名 (媒体卡/任务栏); 字符串表取不到时回落 "Legado"。 */
    private fun displayName(): String {
        val name = runCatching { jvmGetString("app_name") }.getOrNull()
        return name?.takeIf { it.isNotBlank() && it != "app_name" } ?: "Legado"
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
