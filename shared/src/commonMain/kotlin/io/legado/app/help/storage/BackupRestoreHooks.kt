package io.legado.app.help.storage

import io.legado.app.help.config.ThemeConfigProviders
import kotlin.concurrent.Volatile

/**
 * 备份/恢复流程中平台专属环节的注入点。
 *
 * [BackupShared] / [RestoreShared] 承载全部纯逻辑, 只有 Android 独占的段
 * (SAF content:// 读写、config.xml 旧格式解析、恢复后图标/日夜间刷新、
 * LocalConfig.lastBackup、背景图上传 WebDav) 通过本接口回调宿主。
 * 所有方法都有默认实现, 桌面/iOS/鸿蒙不注册即可跑通。
 */
interface BackupRestoreHook {

    /** 备份开始 (app 端: LocalConfig.lastBackup = now)。 */
    fun onBackupStart() {}

    /**
     * 把备份 zip 复制到用户指定目录。
     *
     * @return true 表示宿主已处理 (app 端 content:// 走 SAF), false 走 [BackupFileOps.copyFile]
     */
    fun copyBackupTo(zipFilePath: String, destination: String, fileName: String): Boolean = false

    /** 备份收尾 (app 端: 上传阅读背景图到 WebDav)。 */
    suspend fun onBackupFinished(uploadToWebDav: Boolean) {}

    /**
     * 解压备份 zip 到目录。
     *
     * @return true 表示宿主已处理 (app 端 content:// 走 FileDoc 输入流), false 走 [BackupFileOps.unZipToPath]
     */
    fun unZipBackup(zipPath: String, destDir: String): Boolean = false

    /** 本地 zip 恢复完成 (app 端: LocalConfig.lastBackup = now)。 */
    fun onRestoreFromZipFinished() {}

    /** config.json 不存在时读旧版 config.xml (app 端: XmlPullParser)。 */
    fun readLegacyConfig(dirPath: String): Map<String, Any?>? = null

    /** themeConfig.json 覆盖落盘后重载内存列表 (app 端: ThemeConfig.upConfig())。 */
    fun onThemeConfigRestored() {
        runCatching { ThemeConfigProviders.get().upConfig() }
    }

    /** 恢复完成后的 UI 钩子 (app 端: toast + LauncherIconHelp.changeIcon + ThemeConfig.applyDayNight)。 */
    suspend fun onRestoreFinished() {}
}

/** 默认实现 (无平台副作用), 宿主未注册时使用。 */
private object DefaultBackupRestoreHook : BackupRestoreHook

/** [BackupRestoreHook] 容器, 宿主启动早期注册一次。 */
object BackupRestoreHooks {

    @Volatile
    private var impl: BackupRestoreHook? = null

    fun register(hook: BackupRestoreHook) {
        impl = hook
    }

    fun get(): BackupRestoreHook = impl ?: DefaultBackupRestoreHook
}
