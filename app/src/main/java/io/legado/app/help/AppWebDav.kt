package io.legado.app.help

import android.net.Uri
import io.legado.app.App
import io.legado.app.constant.AppLog
import io.legado.app.data.entities.Book
import io.legado.app.data.entities.BookProgress
import io.legado.app.help.AppWebDav.defaultBookWebDav
import io.legado.app.help.AppWebDav.exportWebDav
import io.legado.app.help.AppWebDav.upBgs
import io.legado.app.help.AppWebDav.upConfig
import io.legado.app.help.i18n.androidAppString
import io.legado.app.help.storage.RestoreShared
import io.legado.app.lib.webdav.Authorization
import io.legado.app.lib.webdav.WebDav
import io.legado.app.lib.webdav.WebDavException
import io.legado.app.lib.webdav.WebDavFile
import io.legado.app.lib.webdav.upload
import io.legado.app.model.remote.RemoteBookWebDav
import io.legado.app.utils.isNetworkAvailable
import io.legado.app.utils.toastOnUi
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.runBlocking
import java.io.File

/**
 * app 端 WebDav 单例, 委托给 KMP 共享版 [AppWebDavShared], 消除重复实现。
 *
 * # 委托策略
 * - 共享方法 (upConfig 主体 / getBackupNames / restoreWebDav / hasBackUp / lastBackUp /
 *   backUpWebDav / uploadBookProgress / getBookProgress / downloadAllBookProgress /
 *   authorization / isOk / isJianGuoYun) 直接转发到 [AppWebDavShared]
 * - Android 专属方法保留在本文件:
 *   - [defaultBookWebDav]: 依赖 `RemoteBookWebDav` (Android 专属)
 *   - [upBgs]: 仅保留 [isNetworkAvailable] 判断, 遍历/上传委托 [AppWebDavShared.upBgs]
 *   - [exportWebDav] 两个重载: 依赖 `Uri` / `ByteArray` + [isNetworkAvailable]
 * - [upConfig] 转发 [AppWebDavShared.upConfig] 完成认证 + 目录创建后, 额外初始化
 *   Android 专属的 [defaultBookWebDav] (RemoteBookWebDav 依赖 Android 专属类, shared 不下沉)
 *
 * # 与 [AppWebDavShared] 的行为对齐说明
 * - 配置读取: shared 走 `AppConfigProviders` / `PreferenceProviders`, app 端注册的
 *   `WebBookProvidersImpl` 已把 `AppConfig.webDavUrl/webDavAccount/webDavPassword/
 *   syncBookProgress/webDavDir` 桥接过去, 与本文件原 `AppConfig.*` 读取同源
 * - 备份恢复: shared `restoreWebDav` / `backUpWebDav` 走 `BackupShared` / `RestoreShared`,
 *   路径与 app 端 `Backup.backupPath` / `Backup.zipFilePath` 一致 (均 filesDir/backup +
 *   externalFiles/tmp_backup.zip), 行为等价
 *
 * # 模式参考
 * - app 端 [io.legado.app.ui.main.MainViewModel] (持有 shared 实例 + 转发公共方法)
 * - app 端 [io.legado.app.model.AudioPlay] (typealias 委托 shared)
 */
object AppWebDav {

    /** 书籍导出 URL (转发自 [AppWebDavShared.exportsWebDavUrl])。 */
    private val exportsWebDavUrl get() = AppWebDavShared.exportsWebDavUrl

    /** 当前认证信息, 转发自 [AppWebDavShared.authorization]。 */
    val authorization: Authorization? get() = AppWebDavShared.authorization

    /**
     * Android 专属: RemoteBookWebDav 实例 (依赖 `RemoteBookWebDav` 类, 未下沉)。
     * 由 [upConfig] 在 [AppWebDavShared.upConfig] 成功后初始化。
     */
    var defaultBookWebDav: RemoteBookWebDav? = null
        private set

    /** 配置是否就绪, 转发自 [AppWebDavShared.isOk]。 */
    val isOk: Boolean get() = AppWebDavShared.isOk

    /** 是否使用坚果云, 转发自 [AppWebDavShared.isJianGuoYun]。 */
    val isJianGuoYun: Boolean get() = AppWebDavShared.isJianGuoYun

    init {
        runBlocking {
            upConfig()
        }
    }

    /**
     * WebDav 根 URL (含子目录, 末尾带 /), 直接委托 [AppWebDavShared.rootWebDavUrl]
     * (原先的拼接逻辑已在 shared 端, 此处不再重复)。
     *
     * 保留在 app 端供 [defaultBookWebDav] 拼接 books 子目录。
     */
    private val rootWebDavUrl: String get() = AppWebDavShared.rootWebDavUrl

    /**
     * 刷新配置: 委托 [AppWebDavShared.upConfig] 完成 WebDav 认证 + 子目录创建,
     * 额外初始化 Android 专属的 [defaultBookWebDav]。
     *
     * 认证失败 shared 抛 [WebDavException], 这里 toast 提示 (与原版 checkAuthorization 内 toast 一致)。
     */
    suspend fun upConfig() {
        // 先清空 Android 专属状态 (与原实现一致)
        defaultBookWebDav = null
        kotlin.runCatching {
            // 委托 shared 完成 WebDav 认证 + 目录创建
            AppWebDavShared.upConfig()
        }.onFailure {
            currentCoroutineContext().ensureActive()
            if (it is WebDavException) {
                App.instance.toastOnUi(androidAppString("webdav_application_authorization_error"))
            }
            AppLog.put("WebDav upConfig 失败\n${it.localizedMessage}", it)
        }
        // shared 成功后, 若有认证信息则初始化 RemoteBookWebDav (Android 专属)
        AppWebDavShared.authorization?.let { auth ->
            val rootBooksUrl = "${rootWebDavUrl}books/"
            defaultBookWebDav = RemoteBookWebDav(rootBooksUrl, auth)
        }
    }

    // ---- 共享方法转发 (消除重复实现) ----

    suspend fun getBackupNames(): ArrayList<String> = AppWebDavShared.getBackupNames()

    /**
     * WebDav 恢复: 转发 [AppWebDavShared.restoreWebDav] (下载 → 解压 → [RestoreShared]),
     * 与本地恢复、桌面端同一条核心路径; Android 专属钩子经 BackupRestoreHooks 注入。
     */
    @Throws(WebDavException::class)
    suspend fun restoreWebDav(name: String) = AppWebDavShared.restoreWebDav(name)

    suspend fun hasBackUp(backUpName: String): Boolean = AppWebDavShared.hasBackUp(backUpName)

    suspend fun lastBackUp(): Result<WebDavFile?> = AppWebDavShared.lastBackUp()

    /**
     * webDav备份
     * @param fileName 备份文件名
     */
    suspend fun backUpWebDav(fileName: String) = AppWebDavShared.backUpWebDav(fileName)

    suspend fun uploadBookProgress(
        book: Book,
        toast: Boolean = false,
        onSuccess: (() -> Unit)? = null
    ) = AppWebDavShared.uploadBookProgress(book, toast, onSuccess)

    suspend fun uploadBookProgress(bookProgress: BookProgress, onSuccess: (() -> Unit)? = null) =
        AppWebDavShared.uploadBookProgress(bookProgress, onSuccess)

    /**
     * 获取书籍进度
     */
    suspend fun getBookProgress(book: Book): BookProgress? = AppWebDavShared.getBookProgress(book)

    suspend fun downloadAllBookProgress() = AppWebDavShared.downloadAllBookProgress()

    // ---- Android 专属方法保留 (依赖 Uri / appCtx / isNetworkAvailable / File) ----

    /**
     * 上传背景图片: 网络判断留 app 端, 遍历/去重/上传委托 [AppWebDavShared.upBgs]。
     */
    suspend fun upBgs(files: Array<File>) {
        if (!isNetworkAvailable()) return
        AppWebDavShared.upBgs(files.map { it.absolutePath })
    }

    @Suppress("unused")
    suspend fun exportWebDav(byteArray: ByteArray, fileName: String) {
        if (!isNetworkAvailable()) return
        try {
            AppWebDavShared.authorization?.let {
                // 如果导出的本地文件存在,开始上传
                val putUrl = exportsWebDavUrl + fileName
                WebDav(putUrl, it).upload(byteArray, "text/plain")
            }
        } catch (e: Exception) {
            currentCoroutineContext().ensureActive()
            AppLog.put("WebDav导出失败\n${e.localizedMessage}", e, true)
        }
    }

    suspend fun exportWebDav(uri: Uri, fileName: String) {
        if (!isNetworkAvailable()) return
        try {
            AppWebDavShared.authorization?.let {
                // 如果导出的本地文件存在,开始上传
                val putUrl = exportsWebDavUrl + fileName
                WebDav(putUrl, it).upload(uri, "text/plain")
            }
        } catch (e: Exception) {
            currentCoroutineContext().ensureActive()
            AppLog.put("WebDav导出失败\n${e.localizedMessage}", e, true)
        }
    }

}
