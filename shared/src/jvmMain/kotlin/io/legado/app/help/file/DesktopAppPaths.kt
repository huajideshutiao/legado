package io.legado.app.help.file

import java.io.File

/** codeSource 定位锚点 (top-level 函数无 Class 对象, 借私有类取 protectionDomain)。 */
private class DesktopAppPathsAnchor

/**
 * 桌面端应用数据根目录解析 (统一入口)。
 *
 * 目录策略:
 * - **便携模式**: 程序目录存在 `portable.txt` 标记 (packagePortableZip 打包时放置) → `{程序目录}/data`;
 *   `legado.portable.root` 系统属性 (Main.kt 编译期 InstallType=portable 时设置) 优先级更高。
 * - **安装/开发模式**: 系统推荐数据目录 + `/legado` (Win=%APPDATA%, Linux=XDG_DATA_HOME
 *   兜底 ~/.local/share, macOS=~/Library/Application Support)。
 *
 * 消费方: DesktopAppFilesDir / BundledDatabaseDriver / JvmBookStorage / DesktopFileBookAccessor 等。
 *
 * @return 应用数据根目录绝对路径 (已存在, 调用方负责子目录创建)
 */
fun desktopAppRootDir(): String = resolvedRootDir

/**
 * 桌面端缓存根目录 (可随时清空, 与数据目录分离)。
 *
 * Win=%LOCALAPPDATA%\legado\cache, Linux=XDG_CACHE_HOME 兜底 ~/.cache, macOS=~/Library/Caches;
 * 便携模式跟随程序目录 (`{数据根}/cache`), 解析失败兜底 `{java.io.tmpdir}/legado/cache`。
 */
fun desktopAppCacheDir(): String = resolvedCacheDir

/**
 * 桌面端**用户可见产物**目录: 导出的书籍 TXT/EPUB、导出的书源/替换规则/书签 json。
 *
 * 落在系统桌面目录下的 `legado` 子目录 (导出物是"拿出去用"的, 桌面最好找);
 * 便携模式落在程序目录 `{程序目录}/export`, 不往系统目录写。
 */
fun desktopUserExportDir(): String = resolvedUserExportDir

/**
 * 桌面端备份 zip 默认落地目录: 系统文档目录下的 `legado/backup` (备份是归档件, 不占桌面);
 * 便携模式落在 `{程序目录}/backup`。用户在设置里指定了备份路径时以用户选择为准。
 */
fun desktopBackupOutputDir(): String = resolvedBackupOutputDir

/** 根目录解析结果缓存 (进程内只解析一次; Main.kt 在首个消费方构造前已完成便携属性设置)。 */
private val resolvedRootDir: String by lazy { resolveRootDir() }

private val resolvedCacheDir: String by lazy { resolveCacheDir() }

private val resolvedUserExportDir: String by lazy {
    portableBaseDir()?.let { return@lazy File(it, "export").absolutePath }
    File(systemDesktopDir(), "legado").absolutePath
}

private val resolvedBackupOutputDir: String by lazy {
    portableBaseDir()?.let { return@lazy File(it, "backup").absolutePath }
    File(systemDocumentsDir(), "legado" + File.separator + "backup").absolutePath
}

private fun resolveRootDir(): String {
    val portable = portableDataDir()
    if (portable != null) return portable.apply { mkdirs() }.absolutePath
    return systemDataDir().apply { mkdirs() }.absolutePath
}

/**
 * 便携模式数据目录, 非便携返回 null。
 *
 * 1. 显式覆盖: Main.kt 编译期 InstallType=portable 时设置 `legado.portable.root`
 * 2. 便携标记: 程序目录 (jpackage app/ 布局取其上级) 存在 portable.txt → 程序同目录 data/。
 *    只认 portable.txt, data/ 可能只是打包占位目录, 不能作为便携模式标记。
 */
private fun portableDataDir(): File? {
    System.getProperty("legado.portable.root")?.takeIf { it.isNotEmpty() }?.let { return File(it) }
    programDir()?.let { dir ->
        if (File(dir, "portable.txt").exists()) return File(dir, "data")
    }
    return null
}

/** 便携模式下的程序目录 (data/ 的同级), 非便携返回 null。 */
private fun portableBaseDir(): File? = portableDataDir()?.let { it.parentFile ?: it }

/**
 * 程序目录定位: codeSource jar 路径向上解析 (user.dir 取决于启动方式, 不可靠)。
 * jpackage 布局 (jar 位于 `{root}/app/` 内) → 返回 root (exe 所在目录); 开发期 classes/libs 目录无标记, 自然跳过。
 */
private fun programDir(): File? = runCatching {
    val source = DesktopAppPathsAnchor::class.java.protectionDomain?.codeSource?.location
        ?: return null
    val jarDir = File(source.toURI()).let { if (it.isFile) it.parentFile else it } ?: return null
    // jpackage: jar 在 app/ 子目录, exe 及 portable.txt 在其上级
    if (jarDir.name == "app") jarDir.parentFile else jarDir
}.getOrNull()

/** 按 OS 返回系统推荐数据目录 + /legado (不自动创建)。 */
private fun systemDataDir(): File {
    val home = System.getProperty("user.home").orEmpty()
    return when {
        isWindows -> {
            val base = System.getenv("APPDATA")?.takeIf { it.isNotBlank() }
                ?: System.getenv("LOCALAPPDATA")?.takeIf { it.isNotBlank() }
                ?: "$home${File.separator}AppData${File.separator}Roaming"
            File(base, "legado")
        }

        isMac -> File(home, "Library/Application Support/legado")
        else -> {
            val base = System.getenv("XDG_DATA_HOME")?.takeIf { it.isNotBlank() }
                ?: "$home/.local/share"
            File(base, "legado")
        }
    }
}

/** 按 OS 返回系统缓存目录 (Linux 走 XDG_CACHE_HOME), 便携模式跟随数据根; 失败兜底临时目录。 */
private fun resolveCacheDir(): String {
    val dir = runCatching { preferredCacheDir() }.getOrNull()
    if (dir != null && runCatching { dir.mkdirs(); dir.isDirectory }.getOrDefault(false)) {
        return dir.absolutePath
    }
    return File(System.getProperty("java.io.tmpdir"), "legado${File.separator}cache")
        .apply { mkdirs() }.absolutePath
}

private fun preferredCacheDir(): File {
    portableDataDir()?.let { return File(it, "cache") }
    val home = System.getProperty("user.home").orEmpty()
    return when {
        isWindows -> {
            val base = System.getenv("LOCALAPPDATA")?.takeIf { it.isNotBlank() }
                ?: "$home${File.separator}AppData${File.separator}Local"
            File(base, "legado${File.separator}cache")
        }

        isMac -> File(home, "Library/Caches/legado")
        else -> {
            val base = System.getenv("XDG_CACHE_HOME")?.takeIf { it.isNotBlank() }
                ?: "$home/.cache"
            File(base, "legado")
        }
    }
}

/** 系统"文档"目录 (Win 可能被 OneDrive 重定向, Linux 走 XDG user-dirs)。 */
private fun systemDocumentsDir(): File = userDir(xdgKey = "XDG_DOCUMENTS_DIR", fallbackName = "Documents")

/** 系统"桌面"目录 (同上, Win 的桌面同样可能被 OneDrive 接管)。 */
private fun systemDesktopDir(): File = userDir(xdgKey = "XDG_DESKTOP_DIR", fallbackName = "Desktop")

/**
 * 解析用户目录 (文档/桌面)。
 *
 * - Windows: `%USERPROFILE%\{name}` 未必对 (文档/桌面可被 OneDrive 或"位置"选项卡整个重定向,
 *   本机实测就落在 D 盘), 故先问 shell ([javax.swing.filechooser.FileSystemView] 内部走 Win32
 *   shell, 与 SHGetKnownFolderPath 同源), 拿不到再回退环境变量。
 * - Linux: XDG 规范, 先读环境变量, 再解析 `{XDG_CONFIG_HOME:-~/.config}/user-dirs.dirs`。
 * - macOS: `~/{name}` 即惯例路径。
 */
private fun userDir(xdgKey: String, fallbackName: String): File {
    val home = System.getProperty("user.home").orEmpty()
    if (isWindows) {
        windowsShellUserDir(fallbackName)?.let { return it }
        val profile = System.getenv("USERPROFILE")?.takeIf { it.isNotBlank() } ?: home
        return File(profile, fallbackName)
    }
    if (!isMac) {
        System.getenv(xdgKey)?.takeIf { it.isNotBlank() }?.let { return File(it) }
        xdgUserDir(xdgKey, home)?.let { return it }
    }
    return File(home, fallbackName)
}

/**
 * Windows: 经 shell 取已知文件夹 —— defaultDirectory = 文档 (CSIDL_PERSONAL),
 * homeDirectory = 桌面, 两者都已含重定向结果。
 *
 * 返回值转成普通 File: FileSystemView 给的是 ShellFolder, 其 getParentFile 走 shell 命名空间
 * (文档的"父"是桌面), 拿去做路径运算会错。
 */
private fun windowsShellUserDir(name: String): File? = runCatching {
    val view = javax.swing.filechooser.FileSystemView.getFileSystemView()
    val dir = if (name.equals("Desktop", ignoreCase = true)) {
        view.homeDirectory
    } else {
        view.defaultDirectory
    }
    dir?.takeIf { it.isDirectory }?.let { File(it.absolutePath) }
}.getOrNull()

/** 解析 XDG user-dirs.dirs 的 `XDG_XXX_DIR="$HOME/Xxx"` 行。 */
private fun xdgUserDir(key: String, home: String): File? = runCatching {
    val configHome = System.getenv("XDG_CONFIG_HOME")?.takeIf { it.isNotBlank() } ?: "$home/.config"
    val file = File(configHome, "user-dirs.dirs").takeIf { it.isFile } ?: return null
    val line = file.readLines().lastOrNull { it.trimStart().startsWith("$key=") } ?: return null
    val raw = line.substringAfter('=').trim().trim('"')
    if (raw.isEmpty()) return null
    File(raw.replace("\$HOME", home).replace("\${HOME}", home))
}.getOrNull()

private val osName: String = System.getProperty("os.name").orEmpty().lowercase()
private val isWindows: Boolean = osName.contains("windows")
private val isMac: Boolean = osName.contains("mac") || osName.contains("darwin")
