package io.legado.app.ui.book.changecover

import io.legado.app.constant.AppLog
import io.legado.app.help.FileUtilsCommon
import io.legado.app.help.FileUtilsCommon.createFolderIfNotExist
import io.legado.app.help.FileUtilsCommon.exist
import io.legado.app.help.FileUtilsCommon.getName
import io.legado.app.help.FileUtilsCommon.getPath
import io.legado.app.help.writeBytesAtomically
import io.legado.app.model.bakeCoverVariantsToCache
import io.legado.app.model.coverOriginalDir
import io.legado.app.ui.root.normalizeImageSuffix

/**
 * 封面持久化编排 (四端统一, 对照安卓/桌面 `CoverStorageService` 平行实现): 原图复制到
 * 图集目录 `customImg/covers/<字节数>.<ext>` (同字节数=同内容, 已存在则复用), 并按封面
 * 比例烘焙两产物写缓存自定义目录 (派生物, 失败只记日志, 渲染端回落原图解码)。
 *
 * 返回**图集内部相对引用** `covers/<size>.<ext>` (经 resolveImagePath 解析到图集目录):
 * customCoverUrl 存绝对路径时跨机/跨端恢复备份必然失效, 存相对引用则恢复即有效。
 *
 * 各端差异只留注入点: 平台专属的选中文件清理 (Android 的 file_picker 临时物化文件)
 * 留各端 [CoverStorageService] 实现。
 */
object CoverPersistenceShared {

    /** 把选中的图片文件复制到图集目录, 返回图集内部相对引用; 失败返回 null。 */
    fun persistCover(srcPath: String, displayName: String): String? {
        val bytes = FileUtilsCommon.readBytes(srcPath) ?: return null
        if (bytes.isEmpty()) return null
        return runCatching {
            // 旧实现按内容 MD5 命名; 统一改字节数特征值 (与背景/启动图同规则, 撞长度可忽略)
            val id = bytes.size.toString()
            val dir = coverOriginalDir().also { createFolderIfNotExist(it) }
            val suffix = normalizeImageSuffix(displayName.substringAfterLast("."))
            val target = getPath(dir, "$id.$suffix")
            // 同名文件已存在 (同字节数=同内容) 则复用, 不重复复制; 落盘整份归位:
            // 半截文件会被上面的存在性判定当成有效封面, 之后就再也不会重新生成
            if (!exist(target) && !writeBytesAtomically(target, bytes)) {
                throw IllegalStateException("封面写入图集失败: $target")
            }
            // 烘焙产物写缓存 (派生物; 失败只记日志——原图已入图集, 渲染端回落原图解码)
            if (!bakeCoverVariantsToCache(bytes, id)) {
                AppLog.put("封面烘焙失败: $srcPath")
            }
            "covers/${getName(target)}"
        }.onFailure {
            AppLog.put("保存封面失败: ${it.message}")
        }.getOrNull()
    }
}
