package io.legado.app.help.storage

import io.legado.app.help.book.BookHelp
import io.legado.app.model.BookCover
import io.legado.app.utils.FileUtils
import io.legado.app.utils.externalFiles
import splitties.init.appCtx

/**
 * [DataStorage] 安卓实现: 委托 app 端各目录原定义 (BookHelp.cachePath / BookCover.coversDir /
 * Backup.backupPath), 不另算路径, 保证与下沉前完全同值。
 *
 * 注册: [io.legado.app.model.webBook.registerAndroidWebBookProviders] 首行。
 */
object AndroidDataStorage : DataStorage {

    override val chapterCacheDir: String get() = BookHelp.cachePath

    override val coversDir: String get() = BookCover.coversDir.absolutePath

    override val backgroundsDir: String get() = FileUtils.getPath(appCtx.externalFiles, "bg")

    override val fontsDir: String get() = FileUtils.getPath(appCtx.externalFiles, "font")

    override val backupDir: String get() = Backup.backupPath
}
