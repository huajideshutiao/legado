package io.legado.app.ui.book.changecover

import io.legado.app.App
import io.legado.app.constant.AppLog
import io.legado.app.model.bakeCoverVariantsToCache
import io.legado.app.model.coverOriginalDir
import io.legado.app.ui.root.normalizeImageSuffix
import java.io.File

/**
 * Android 端封面持久化 (对齐原版 BookInfoEditActivity.coverChangeTo(uri) 的"原图入图集"
 * 语义, 与主题背景/启动图机制统一): 原图复制到 `customImg/covers/<字节数>.<ext>`
 * (同字节数=同内容, 已存在则复用), 并按封面比例烘焙两产物写缓存自定义目录
 * (cacheDir/customImg/covers, 见 [bakeCoverVariantsToCache]); 成功后顺带清理 pickFile
 * 物化到 cacheDir/file_picker 的临时文件 (仅限缓存目录内, 不碰用户原文件)。
 *
 * 返回**图集内部相对引用** `covers/<size>.<ext>` (不带 customImg 前缀, 经
 * [io.legado.app.help.config.resolveImagePath] 解析到图集目录): customCoverUrl 存绝对路径
 * 时跨机/跨端恢复备份必然失效 (文件随 zip 到了新文件根, DB 里的路径还指旧机),
 * 存相对引用则恢复即有效, 读取端解析。
 */
class AndroidCoverStorageService : CoverStorageService {

    override fun persistCover(srcPath: String, displayName: String): String? {
        val src = File(srcPath)
        if (!src.isFile) return null
        return runCatching {
            val bytes = src.readBytes()
            if (bytes.isEmpty()) return@runCatching null
            // 旧实现按内容 MD5 命名; 统一改字节数特征值 (与背景/启动图同规则, 撞长度可忽略)
            val id = bytes.size.toString()
            val dir = File(coverOriginalDir()).apply { mkdirs() }
            val suffix = normalizeImageSuffix(displayName.substringAfterLast("."))
            val target = File(dir, "$id.$suffix")
            // 同名文件已存在 (同字节数=同内容) 则复用, 不重复复制
            if (!target.exists()) {
                target.writeBytes(bytes)
            }
            // 烘焙产物写缓存 (派生物; 失败只记日志——原图已入图集, 渲染端回落原图解码)
            if (!bakeCoverVariantsToCache(bytes, id)) {
                AppLog.put("封面烘焙失败: $srcPath")
            }
            // 清理 file_picker 临时物化文件 (仅限缓存目录内, 不碰用户原文件)
            val filePickerDir = File(App.instance.cacheDir, "file_picker")
            if (src.parentFile == filePickerDir) {
                runCatching { src.delete() }
            }
            "covers/${target.name}"
        }.onFailure {
            AppLog.put("AndroidCoverStorageService 保存封面失败: ${it.localizedMessage}")
        }.getOrNull()
    }
}
