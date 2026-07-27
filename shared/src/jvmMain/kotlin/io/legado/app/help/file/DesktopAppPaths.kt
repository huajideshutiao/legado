package io.legado.app.help.file

import io.legado.app.constant.AppLog
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
 * - **迁移兼容**: 新默认目录为空而旧目录 `~/.legado` 有数据时沿用旧目录并 AppLog 提示 (不自动搬迁)。
 *
 * 消费方: DesktopAppFilesDir / BundledDatabaseDriver / JvmBookStorage / DesktopFileBookAccessor 等。
 *
 * @return 应用数据根目录绝对路径 (已存在, 调用方负责子目录创建)
 */
fun desktopAppRootDir(): String = resolvedRootDir

/**
 * 桌面端缓存根目录: `{java.io.tmpdir}/legado/cache` (系统临时目录下的应用子目录)。
 * 缓存语义 = 可随时清空, 与数据目录分离, 不跟随便携/安装模式。
 */
fun desktopAppCacheDir(): String = resolvedCacheDir

/** 根目录解析结果缓存 (进程内只解析一次; Main.kt 在首个消费方构造前已完成便携属性设置)。 */
private val resolvedRootDir: String by lazy { resolveRootDir() }

private val resolvedCacheDir: String by lazy {
    File(System.getProperty("java.io.tmpdir"), "legado${File.separator}cache")
        .apply { mkdirs() }.absolutePath
}

private fun resolveRootDir(): String {
    // 1. 显式覆盖: Main.kt 编译期 InstallType=portable 时设置 (兼容旧打包链路)
    val portable = System.getProperty("legado.portable.root")
    if (!portable.isNullOrEmpty()) {
        return File(portable).apply { mkdirs() }.absolutePath
    }
    // 2. 便携标记: 程序目录 (jpackage app/ 布局取其上级) 存在 portable.txt → 程序同目录 data/。
    //    只认 portable.txt 不认 data/ 目录: 旧便携 zip 编译为 dev 型且带 data/README 占位,
    //    数据实际落 ~/.legado, 若把 data/ 当标记会孤立旧用户数据
    programDir()?.let { dir ->
        if (File(dir, "portable.txt").exists()) {
            return File(dir, "data").apply { mkdirs() }.absolutePath
        }
    }
    // 3. 系统推荐数据目录, 带旧目录 ~/.legado 迁移兼容
    val newDir = systemDataDir()
    val home = System.getProperty("user.home")
    if (home != null) {
        val legacy = File(home, ".legado")
        val newHasData = newDir.list()?.isNotEmpty() == true
        val legacyHasData = legacy.list()?.isNotEmpty() == true
        if (!newHasData && legacyHasData) {
            AppLog.put(
                "检测到旧版数据目录 ${legacy.absolutePath}, 继续沿用 " +
                    "(新默认目录 ${newDir.absolutePath} 为空, 不自动搬迁)"
            )
            return legacy.absolutePath
        }
    }
    return newDir.apply { mkdirs() }.absolutePath
}

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

/** 按 OS 返回系统推荐数据目录 + /legado (不自动创建, 供与旧目录比较)。 */
private fun systemDataDir(): File {
    val os = System.getProperty("os.name").orEmpty().lowercase()
    val home = System.getProperty("user.home").orEmpty()
    return when {
        os.contains("windows") -> {
            val base = System.getenv("APPDATA")?.takeIf { it.isNotBlank() }
                ?: System.getenv("LOCALAPPDATA")?.takeIf { it.isNotBlank() }
                ?: "$home${File.separator}AppData${File.separator}Roaming"
            File(base, "legado")
        }
        os.contains("mac") || os.contains("darwin") ->
            File(home, "Library/Application Support/legado")
        else -> {
            val base = System.getenv("XDG_DATA_HOME")?.takeIf { it.isNotBlank() }
                ?: "$home/.local/share"
            File(base, "legado")
        }
    }
}
