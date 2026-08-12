package io.legado.app.ui.book.changecover

import io.legado.app.constant.AppLog
import io.legado.app.utils.FileUtils
import io.legado.app.utils.MD5Utils
import splitties.init.appCtx
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream

/**
 * Android 端封面持久化 (对齐原版 BookInfoEditActivity.coverChangeTo(uri)):
 * 复制到 `externalFilesDir/covers/<md5(内容)>.<ext>`, 同名 (同内容) 文件已存在则复用
 * 不重复复制; 成功后顺带清理 pickFile 物化到 cacheDir/file_picker 的临时文件
 * (仅限缓存目录内, 不碰用户原文件)。
 */
class AndroidCoverStorageService : CoverStorageService {

    override fun persistCover(srcPath: String, displayName: String): String? {
        val src = File(srcPath)
        val externalFilesDir = appCtx.getExternalFilesDir(null)
        if (!src.isFile || externalFilesDir == null) return null
        return runCatching {
            val suffix = displayName.substringAfterLast(".")
            val md5 = FileInputStream(src).use { MD5Utils.md5Encode(it) }
            val coversDir = FileUtils.createFolderIfNotExist(externalFilesDir, "covers")
            val target = File(coversDir, "$md5.$suffix")
            // 同名文件已存在 (md5 同内容) 则复用, 不重复复制
            if (!target.exists()) {
                src.inputStream().use { input ->
                    FileOutputStream(target).use { output -> input.copyTo(output) }
                }
            }
            // 清理 file_picker 临时物化文件 (仅限缓存目录内, 不碰用户原文件)
            val filePickerDir = File(appCtx.cacheDir, "file_picker")
            if (src.parentFile == filePickerDir) {
                runCatching { src.delete() }
            }
            target.absolutePath
        }.onFailure {
            AppLog.put("AndroidCoverStorageService 保存封面失败: ${it.localizedMessage}")
        }.getOrNull()
    }
}
