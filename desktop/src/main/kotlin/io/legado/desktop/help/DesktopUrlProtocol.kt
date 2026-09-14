package io.legado.desktop.help

import com.sun.jna.Platform
import com.sun.jna.platform.win32.Advapi32Util
import com.sun.jna.platform.win32.WinReg
import io.legado.app.constant.AppLog
import io.legado.app.constant.AppPattern
import java.io.File

/**
 * 桌面端 legado:// / yuedu:// 系统级 URL protocol 与可播视频类型的运行时注册 (幂等, 后台执行)。
 *
 * 让浏览器/系统把 `legado://...` / `yuedu://...` 链接唤起桌面版
 * (对照 app 端 AndroidManifest intent-filter 与 iosApp CFBundleURLTypes)。
 *
 * # 各 OS 方案
 *
 * - **Windows**: 写 `HKCU\Software\Classes\<scheme>` (per-user, 无需管理员权限), 含
 *   `URL Protocol` 空值 + `shell\open\command` 默认值 `"<exe>" "%1"`。jpackage MSI /
 *   便携 zip 产物布局 `<app>/legado.exe` + `<app>/runtime/`, 由 `java.home` 反推 exe。
 * - **Linux**: 写 `~/.local/share/applications/legado.desktop`
 *   (`MimeType=x-scheme-handler/legado;x-scheme-handler/yuedu;` + 可播视频 MIME,
 *   再 `Exec="<launcher>" %u`), 仅对 scheme 跑 `xdg-mime default`。jpackage 产物布局
 *   `<app>/bin/legado` + `<app>/lib/runtime`, 由 `java.home` 反推 launcher。
 * - **文件类型 (视频)**: Windows 另写 `HKCU\Software\Classes\Applications\<exe>\SupportedTypes`
 *   (见 [registerWindowsApplication]), Linux 写进同一份 .desktop 的 MimeType —— 安装器只在
 *   **安装那一刻**按当时路径登记, 便携 zip 不跑安装器 / 安装后目录被移动都会造成
 *   “系统不知道我们能播视频”, 所以这项必须运行期幂等重写 (macOS 无运行期手段, 保持打包期
 *   Info.plist 静态声明)。
 * - **macOS**: 打包期注入 Info.plist `CFBundleURLTypes` (见 desktop/build.gradle.kts
 *   `nativeDistributions.macOS.infoPlist`), LaunchServices 安装 .app 时即关联;
 *   运行时回调走 Main.kt 的 `Desktop.setOpenURIHandler` (Apple Event), 无需运行时注册。
 *
 * # 幂等性
 *
 * 每次启动后台跑一次: Windows 的 scheme 分支先读现有 command, 相同则跳过 (避免无谓写注册表),
 * `Applications\<exe>` 分支每次都写同一批固定值 (原因见 [registerWindowsApplication]);
 * Linux 仅当 .desktop 内容变化才重写, xdg-mime default 本身幂等。
 *
 * # 开发期 (:desktop:run)
 *
 * 没有打包产物的可执行入口 (java.home 是 JDK, 反推路径不存在), 直接跳过, 不影响开发。
 */
object DesktopUrlProtocol {

    private const val TAG = "url-protocol"
    private const val LINUX_DESKTOP_FILE = "legado.desktop"
    private val winSchemes = listOf("legado", "yuedu")

    /** 只拿 scheme 当默认 handler 的 MIME (视频类型只申报候选, 不抢默认程序) */
    private val schemeMimes =
        listOf("x-scheme-handler/legado", "x-scheme-handler/yuedu")

    /** 后台线程注册, 不阻塞首窗口; 幂等, 失败仅记日志。 */
    fun ensureRegisteredAsync() {
        // 只对打包产物有意义 (需要可执行入口反推路径); 开发期 java.home 是 JDK, 直接跳过
        val launcher = resolveLauncher() ?: return
        Thread({
            try {
                when {
                    Platform.isWindows() -> registerWindows(launcher)
                    Platform.isLinux() -> registerLinux(launcher)
                    // macOS: Info.plist 已在打包期注入, 无运行时注册
                }
            } catch (t: Throwable) {
                AppLog.put("URL protocol 注册失败", t, tag = TAG)
            }
        }, "legado-url-protocol").apply {
            isDaemon = true
            start()
        }
    }

    /**
     * 反推打包产物的可执行入口 (jpackage app image / 便携 zip 同布局)。
     * - Windows: `java.home` = `<app>/runtime` → exe = `<app>/legado.exe`
     * - Linux: `java.home` = `<app>/lib/runtime` → launcher = `<app>/bin/legado`
     * 开发期 (:desktop:run) java.home 是 JDK, 反推路径不存在 → 返回 null。
     */
    private fun resolveLauncher(): File? {
        val javaHome = File(System.getProperty("java.home") ?: return null)
        val launcher = when {
            Platform.isWindows() -> javaHome.parentFile.resolve("legado.exe")
            Platform.isLinux() -> javaHome.parentFile.parentFile.resolve("bin/legado")
            else -> return null
        }
        return launcher.takeIf { it.isFile }
    }

    // ==================== Windows: HKCU 注册表 (JNA, Unicode 安全) ====================

    private fun registerWindows(exe: File) {
        val cmd = "\"${exe.absolutePath}\" \"%1\""
        winSchemes.forEach { scheme ->
            val base = "Software\\Classes\\$scheme"
            val commandKey = "$base\\shell\\open\\command"
            val current = runCatching {
                Advapi32Util.registryGetStringValue(WinReg.HKEY_CURRENT_USER, commandKey, "")
            }.getOrNull()
            if (current == cmd) {
                return@forEach
            }
            Advapi32Util.registryCreateKey(WinReg.HKEY_CURRENT_USER, base)
            Advapi32Util.registrySetStringValue(
                WinReg.HKEY_CURRENT_USER, base, "", "URL:$scheme protocol"
            )
            // URL Protocol 空值: 浏览器据此识别为可唤起协议 (与系统自带协议写法一致)
            Advapi32Util.registrySetStringValue(
                WinReg.HKEY_CURRENT_USER, base, "URL Protocol", ""
            )
            Advapi32Util.registryCreateKey(WinReg.HKEY_CURRENT_USER, commandKey)
            Advapi32Util.registrySetStringValue(
                WinReg.HKEY_CURRENT_USER, commandKey, "", cmd
            )
        }
        registerWindowsApplication(exe, cmd)
    }

    /**
     * 把“我这个 exe 能开哪些视频类型”申报给系统 (per-user, 不动扩展名的全局默认关联)。
     *
     * 写点 `HKCU\Software\Classes\Applications\<exe 文件名>` (键名与值形式按官方
     * Shell 应用注册页 https://learn.microsoft.com/en-us/windows/win32/shell/app-registration
     * 的示例形态, 2026-09-14 拓原文核实后改过 —— 原来写的 `ApplicationName` 与
     * “值名=video/mp4”都**没有该页依据**):
     * - `FriendlyAppName`      “打开方式”列表里显示的名字
     * - `shell\open\command`   选中后的实际命令行
     * - `SupportedTypes`       官方示例 (`wmplayer.exe` / `mspaint.exe`) 的**值名是带点
     *   扩展名** (`.3gp2` / `.bmp` / `.jpg`), 不是 MIME; 值一律 REG_SZ。MIME 形式官方
     *   没写“无效”, 但也没背书 —— 按能确证的形态写, 不拿未文档化的形式赌系统行为。
     *   官方只说 `SupportedTypes` “使应用出现在 Open with 级联菜单里”, 未要求也不改全局默认关联。
     *
     * 为什么要在运行期写一遍而不是只靠安装器: jpackage 的 `fileAssociation` 是**安装时**
     * 写注册表的, 便携 zip 根本没跑安装器 (双击 mp4 的候选列表里压根看不到 legado),
     * 而 MSI 安装后被整目录移动 / 换盘符也会把注册表里的旧路径留成死项。安装器只认它
     * 当时看到的那个路径, 运行期这一遍才是“按当前真实 exe 校正”。
     *
     * 不比对就每次都写: 要幂等得先读回 SupportedTypes 的现有值集, 比上面用过的
     * registryGetStringValue 多的那个批量读 API 本会话没实测过 (未授权编译, 不能赌);
     * 而这里是同一批固定字符串的覆盖写, 八条值一次启动写一遍开销可忽略 (本函数就在后台线程)。
     * 已知代价: 若日后从 [AppPattern.videoFileTypes] 删某个类型, 注册表里的旧值不会自动清
     * (我们会申报一个自己不再认的类型, 点开会得到“不支持的文件”), 重装即自愈。
     * 历史上写过的 MIME 形式值名不会自动清除 —— 升级后首次启动需手动删一次
     * `HKCU\\Software\\Classes\\Applications\\legado.exe\\SupportedTypes` 下旧值 (或重装)。
     */
    private fun registerWindowsApplication(exe: File, cmd: String) {
        val base = "Software\\Classes\\Applications\\${exe.name}"
        Advapi32Util.registryCreateKey(WinReg.HKEY_CURRENT_USER, base)
        Advapi32Util.registrySetStringValue(
            WinReg.HKEY_CURRENT_USER, base, "FriendlyAppName", "Legado"
        )
        val commandKey = "$base\\shell\\open\\command"
        Advapi32Util.registryCreateKey(WinReg.HKEY_CURRENT_USER, commandKey)
        Advapi32Util.registrySetStringValue(WinReg.HKEY_CURRENT_USER, commandKey, "", cmd)
        val typesKey = "$base\\SupportedTypes"
        Advapi32Util.registryCreateKey(WinReg.HKEY_CURRENT_USER, typesKey)
        AppPattern.videoFileTypes.forEach { (ext, _) ->
            // 值名 = ".mp4" 形式 (官方示例), 值数据 = 空 REG_SZ (官方未定义该值的内容)
            Advapi32Util.registrySetStringValue(
                WinReg.HKEY_CURRENT_USER, typesKey, ".${ext}", ""
            )
        }
    }

    // ==================== Linux: .desktop + xdg-mime ====================

    private fun registerLinux(launcher: File) {
        val appsDir = File(System.getProperty("user.home"), ".local/share/applications")
        appsDir.mkdirs()
        val desktopFile = File(appsDir, LINUX_DESKTOP_FILE)
        val content = buildString {
            appendLine("[Desktop Entry]")
            appendLine("Type=Application")
            appendLine("Version=1.0")
            appendLine("Name=Legado")
            appendLine("Comment=Legado desktop reader")
            appendLine("Exec=\"${launcher.absolutePath}\" %u")
            // scheme handler + 能播的视频类型 (同源于 [AppPattern.videoFileTypes], 不手写第二份);
            // 视频只进“候选程序”列表, 不做 xdg-mime default —— 把用户的播放器默认值抢走不是本意
            appendLine(
                "MimeType=" +
                        (schemeMimes + AppPattern.videoFileTypes.map { it.second })
                            .joinToString(";") + ";"
            )
            appendLine("Terminal=false")
            appendLine("Categories=Office;")
        }
        val old = runCatching { desktopFile.readText() }.getOrNull()
        if (old != content) {
            desktopFile.writeText(content)
        }
        runQuiet(
            "xdg-mime", "default", LINUX_DESKTOP_FILE,
            *schemeMimes.toTypedArray(),
        )
        // 通知桌面环境刷新菜单/关联 (无该命令时静默)
        runQuiet("update-desktop-database", appsDir.absolutePath)
    }

    /** 运行外部命令, 只记失败日志 (xdg-utils 可能缺失/非零退出, 不阻塞启动)。 */
    private fun runQuiet(vararg cmd: String) {
        runCatching {
            val result = DesktopCommandRunner.run(cmd.toList(), 10_000L)
            val cmdText = cmd.joinToString(" ")
            when {
                result.exitCode == null ->
                    AppLog.put("命令超时: $cmdText", tag = TAG)

                result.exitCode != 0 ->
                    AppLog.put("命令退出码 ${result.exitCode}: $cmdText", tag = TAG)
            }
        }.onFailure {
            AppLog.put("命令执行失败: ${cmd.joinToString(" ")}", it, tag = TAG)
        }
    }
}
