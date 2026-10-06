package io.legado.app.help.extension

import android.content.Context
import io.legado.app.help.extension.installer.ExtensionInstaller
import io.legado.app.help.extension.model.InstallStep
import io.legado.app.help.extension.model.MangaExtension
import io.legado.app.help.extension.util.ExtensionInstallReceiver
import io.legado.app.help.extension.util.ExtensionLoader
import kotlinx.coroutines.flow.Flow

/**
 * [MangaExtensionHost] 安卓实现: 枚举装载/安装/卸载全部委托 app 端既有
 * PackageManager 面 ([ExtensionLoader] 扫已装包 + 私有扩展文件、[ExtensionInstaller]
 * 下载安装), 广播监听在 [onInitialized] 注册 (保持"扩展 UI 首入口才装广播"时序)。
 */
class AndroidMangaExtensionHost(context: Context) : MangaExtensionHost {

    private val appContext = context.applicationContext
    private val installer by lazy { ExtensionInstaller(appContext) }

    companion object {
        @Volatile
        private var instance: AndroidMangaExtensionHost? = null

        /** 进程级单例: 安装任务表需跨 Activity 重建保持, 否则进行中的安装无法取消。 */
        fun get(context: Context): AndroidMangaExtensionHost =
            instance ?: synchronized(this) {
                instance ?: AndroidMangaExtensionHost(context.applicationContext).also { instance = it }
            }
    }

    override fun onInitialized() {
        ExtensionInstallReceiver(appContext) { MangaExtensionManager.reloadExtensions() }.register()
    }

    override suspend fun loadExtensions(
        alreadyLoaded: Map<String, MangaExtension.Loaded>,
    ): List<MangaExtension.Installed> = ExtensionLoader.loadExtensions(appContext, alreadyLoaded)

    override fun install(extension: MangaExtension.Available): Flow<InstallStep> {
        return installer.install(extension)
    }

    override fun cancelInstall(pkgName: String) {
        installer.cancelInstall(pkgName)
    }

    override fun uninstall(extension: MangaExtension.Installed) {
        if (extension.isShared) {
            // 共享扩展经系统卸载界面, 完成后由系统广播触发整表重扫
            installer.uninstallSharedApk(extension.pkgName)
        } else {
            ExtensionLoader.uninstallPrivateExtension(appContext, extension.pkgName)
            MangaExtensionManager.reloadExtensions()
        }
    }
}
