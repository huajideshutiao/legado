package io.legado.desktop.help.changecover

import io.legado.app.ui.book.changecover.CoverPersistenceShared
import io.legado.app.ui.book.changecover.CoverStorageService

/**
 * 桌面端封面持久化, 编排下沉 [CoverPersistenceShared] (原图入图集 + 烘焙两产物,
 * 与 Android 同链路)。桌面 pickFile 直接返回用户文件真实路径, 不产生临时物化文件,
 * 无平台专属清理。
 */
class DesktopCoverStorageService : CoverStorageService {

    override fun persistCover(srcPath: String, displayName: String): String? =
        CoverPersistenceShared.persistCover(srcPath, displayName)
}
