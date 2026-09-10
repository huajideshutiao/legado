package io.legado.desktop.help.changecover

import io.legado.app.constant.AppLog
import io.legado.app.model.bakeCoverVariantsToCache
import io.legado.app.model.coverOriginalDir
import io.legado.app.ui.book.changecover.CoverStorageService
import io.legado.app.ui.root.normalizeImageSuffix
import java.io.File

/**
 * 桌面端封面持久化 (与 Android 端同规则, 见 AndroidCoverStorageService 注释): 原图复制到
 * 图集目录 `customImg/covers/<字节数>.<ext>` (desktop 的 AppFilesDirs.filesDir 根, 与主题背景/
 * 启动图同一图集机制), 同名 (同字节数) 复用; 烘焙两 ratio 产物写缓存目录。
 * 桌面 pickFile 直接返回用户文件真实路径, 不产生临时物化文件, 无需清理。
 */
class DesktopCoverStorageService : CoverStorageService {

    override fun persistCover(srcPath: String, displayName: String): String? {
        val src = File(srcPath)
        if (!src.isFile) return null
        return runCatching {
            val bytes = src.readBytes()
            if (bytes.isEmpty()) return@runCatching null
            val id = bytes.size.toString()
            val suffix = normalizeImageSuffix(displayName.substringAfterLast("."))
            val dir = File(coverOriginalDir()).apply { mkdirs() }
            val target = File(dir, "$id.$suffix")
            if (!target.exists()) {
                target.writeBytes(bytes)
            }
            if (!bakeCoverVariantsToCache(bytes, id)) {
                AppLog.put("封面烘焙失败: $srcPath")
            }
            "covers/${target.name}"
        }.getOrNull()
    }
}
