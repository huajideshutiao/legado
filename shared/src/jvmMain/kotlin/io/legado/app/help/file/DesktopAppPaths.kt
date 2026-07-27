package io.legado.app.help.file

import java.io.File

/**
 * 桌面端应用根目录解析 (统一入口)。
 *
 * # 两种模式
 *
 * - **便携模式** (jpackage 打包后: MSI / 便携版 exe 目录):
 *   由 desktop `Main.kt` 启动时检测 jpackage 目录结构, 设置 `legado.portable.root` 系统属性,
 *   指向 exe 同级 `data/` 目录。配置 / 数据库 / 缓存全部跟随 exe, 拷贝整个目录即可迁移,
 *   卸载即清空 (便携版语义, 用户要求"配置文件 + exe")。
 *
 * - **开发模式** (`:desktop:run` 直接跑):
 *   `legado.portable.root` 未设置, fallback 到 `~/.legado` (用户主目录下),
 *   避免把数据库 / 缓存写到项目源码树污染 Git 工作区。
 *
 * # 消费方
 *
 * - [io.legado.app.help.file.DesktopAppFilesDir] (filesDir / cacheDir)
 * - [io.legado.app.data.BundledDatabaseDriver] (数据库路径)
 * - [io.legado.app.help.book.JvmBookStorage] (章节缓存)
 *
 * 三者均调用本函数取根目录, 保证便携 / 开发模式行为一致。
 *
 * @return 应用根目录绝对路径 (已存在, 调用方负责子目录创建)
 */
fun desktopAppRootDir(): String {
    // 便携模式: Main.kt 已设置 legado.portable.root 指向 exe 同级 data/ 目录
    val portable = System.getProperty("legado.portable.root")
    if (!portable.isNullOrEmpty()) {
        return portable
    }
    // 开发模式: ~/.legado (与历史行为一致, 不污染项目源码树)
    val home = System.getProperty("user.home") ?: System.getProperty("user.dir")
    return File(home, ".legado").absolutePath
}
