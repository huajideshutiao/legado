package io.legado.app.ui.book.changecover

import io.legado.app.App
import java.io.File

/**
 * Android 端封面持久化, 编排下沉 [CoverPersistenceShared] (原图入图集 + 烘焙两产物,
 * 与桌面同链路); 安卓专属收尾: 清理 pickFile 物化到 cacheDir/file_picker 的临时文件
 * (仅限缓存目录内, 不碰用户原文件)。
 */
class AndroidCoverStorageService : CoverStorageService {

    override fun persistCover(srcPath: String, displayName: String): String? {
        val ref = CoverPersistenceShared.persistCover(srcPath, displayName) ?: return null
        val src = File(srcPath)
        val filePickerDir = File(App.instance.cacheDir, "file_picker")
        if (src.parentFile == filePickerDir) {
            runCatching { src.delete() }
        }
        return ref
    }
}
