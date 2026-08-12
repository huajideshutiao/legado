package io.legado.desktop.help.changecover

import io.legado.app.help.file.desktopAppRootDir
import io.legado.app.ui.book.changecover.CoverStorageService
import io.legado.app.utils.MD5Utils
import java.io.File
import java.io.FileInputStream

/**
 * 桌面端封面持久化 (对齐 Android 原版 externalFiles/covers/<md5>.<ext>):
 * 复制到应用数据根目录 `covers/` (desktopAppRootDir()/covers), 同名复用不重复复制。
 * 桌面 pickFile 直接返回用户文件真实路径, 不产生临时物化文件, 无需清理。
 */
class DesktopCoverStorageService : CoverStorageService {

    override fun persistCover(srcPath: String, displayName: String): String? {
        val src = File(srcPath)
        if (!src.isFile) return null
        return runCatching {
            val suffix = displayName.substringAfterLast(".")
            val md5 = FileInputStream(src).use { MD5Utils.md5Encode(it) }
            val coversDir = File(desktopAppRootDir(), "covers").apply { mkdirs() }
            val target = File(coversDir, "$md5.$suffix")
            if (!target.exists()) {
                src.copyTo(target)
            }
            target.absolutePath
        }.getOrNull()
    }
}
