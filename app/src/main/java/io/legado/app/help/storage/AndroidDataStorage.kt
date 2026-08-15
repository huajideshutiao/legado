package io.legado.app.help.storage

import android.os.Environment
import io.legado.app.App
import io.legado.app.help.book.BookHelp
import io.legado.app.model.BookCover
import io.legado.app.utils.FileUtils
import io.legado.app.utils.externalFiles
import java.io.File

/**
 * [DataStorage] 安卓实现: 委托 app 端各目录原定义 (BookHelp.cachePath / BookCover.coversDir /
 * Backup.backupPath), 不另算路径, 保证与下沉前完全同值。
 *
 * 注册: [io.legado.app.model.webBook.registerAndroidWebBookProviders] 首行。
 */
object AndroidDataStorage : DataStorage {

    override val chapterCacheDir: String get() = BookHelp.cachePath

    override val coversDir: String get() = BookCover.coversDir.absolutePath

    override val backgroundsDir: String get() = FileUtils.getPath(App.instance.externalFiles, "bg")

    override val fontsDir: String get() = FileUtils.getPath(App.instance.externalFiles, "font")

    override val backupDir: String get() = Backup.backupPath

    /** 公共文档目录下的 legado (无存储权限时回退应用外部目录, 调用方不必再判权限)。 */
    override val userExportDir: String
        get() = runCatching {
            @Suppress("DEPRECATION")
            val documents = Environment
                .getExternalStoragePublicDirectory(Environment.DIRECTORY_DOCUMENTS)
            val dir = File(documents, "legado")
            if (dir.isDirectory || dir.mkdirs()) dir.absolutePath else null
        }.getOrNull() ?: FileUtils.getPath(App.instance.externalFiles, "export")

    /**
     * 备份落地目录由用户经 SAF 选 (PreferKey.backupPath), 返回 null 让调用方拉起目录选择器,
     * 保持原有交互不变 —— 直写公共目录在 Android 11+ 需额外授权, 不能当默认值。
     */
    override val defaultBackupDir: String? get() = null
}
