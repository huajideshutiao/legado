package io.legado.app

import android.app.Application
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.ActivityInfo
import android.content.pm.ApplicationInfo
import android.content.res.Configuration
import android.os.Build
import io.legado.app.base.AppContextWrapper
import io.legado.app.constant.AppConst.channelIdDownload
import io.legado.app.constant.AppConst.channelIdReadAloud
import io.legado.app.constant.AppConst.channelIdWeb
import io.legado.app.constant.PreferKey
import io.legado.app.constant.registerAndroidAppLogHost
import io.legado.app.data.appDb
import io.legado.app.help.AppFreezeMonitor
import io.legado.app.help.AppWebDav
import io.legado.app.help.CrashHandler
import io.legado.app.help.DefaultData
import io.legado.app.help.DispatchersMonitor
import io.legado.app.help.HomeTabHelpShared
import io.legado.app.help.LifecycleHelp
import io.legado.app.help.PinnedExploreHelp
import io.legado.app.help.archive.AndroidArchiveProvider
import io.legado.app.help.archive.ArchiveProviders
import io.legado.app.help.book.AndroidBookImageStorage
import io.legado.app.help.book.AndroidBookStorage
import io.legado.app.help.book.AndroidLocalBookLocator
import io.legado.app.help.book.BookHelp
import io.legado.app.help.book.BookImageStorageProviders
import io.legado.app.help.book.BookStorageProviders
import io.legado.app.help.book.LocalBookLocators
import io.legado.app.help.config.AndroidReadConfigProviders
import io.legado.app.help.config.AppConfig
import io.legado.app.help.config.LocalConfig
import io.legado.app.help.config.ReadBookConfig
import io.legado.app.help.config.ReadBookConfigProviders
import io.legado.app.help.config.ThemeConfig
import io.legado.app.help.config.ThemeConfig.applyDayNight
import io.legado.app.help.config.ThemeConfig.applyDayNightInit
import io.legado.app.help.config.registerAndroidPreferenceProvider
import io.legado.app.help.coroutine.Coroutine
import io.legado.app.help.coroutine.registerAndroidDebugState
import io.legado.app.help.file.registerAndroidAppFilesDir
import io.legado.app.help.http.CookieJarBridgeHolder
import io.legado.app.help.http.CookieManager
import io.legado.app.help.http.Cronet
import io.legado.app.help.http.ObsoleteUrlFactory
import io.legado.app.help.http.okHttpClient
import io.legado.app.help.http.registerAndroidBackstageWebView
import io.legado.app.help.http.registerAndroidCookieStoreProvider
import io.legado.app.help.i18n.registerAndroidAppStringProvider
import io.legado.app.help.image.registerAndroidBookImageLoader
import io.legado.app.help.registerAndroidDirectLinkUploadProviders
import io.legado.app.help.registerAndroidFileCacheProvider
import io.legado.app.help.service.registerAndroidServiceLauncher
import io.legado.app.help.source.SourceHelp
import io.legado.app.help.source.SourceUiEventBridge
import io.legado.app.help.storage.Backup
import io.legado.app.help.storage.registerAndroidPasswordProvider
import io.legado.app.help.toast.registerAndroidToaster
import io.legado.app.help.ui.registerAndroidOpenUrlProvider
import io.legado.app.help.ui.registerAndroidToastProvider
import io.legado.app.help.ui.registerAndroidUserAgentProvider
import io.legado.app.model.BookCover
import io.legado.app.model.CacheBook
import io.legado.app.model.fileBook.registerAndroidFileBookProviders
import io.legado.app.model.fileBook.registerEpubApplicationContext
import io.legado.app.model.registerAndroidAudioPlayProviders
import io.legado.app.model.script.JsEngines
import io.legado.app.model.script.registerAndroidJsEngines
import io.legado.app.model.webBook.registerAndroidBookInfoRefresher
import io.legado.app.model.webBook.registerAndroidWebBookProviders
import io.legado.app.service.WebService
import io.legado.app.ui.book.changesource.registerAndroidChangeBookSourcePlatform
import io.legado.app.ui.book.manage.registerAndroidBookshelfManagePlatform
import io.legado.app.ui.compose.platform.AndroidPreferenceStoreProvider
import io.legado.app.ui.platform.registerSharedAppContext
import io.legado.app.utils.LogUtils
import io.legado.app.utils.defaultSharedPreferences
import io.legado.app.utils.registerAndroidACacheDirProvider
import io.legado.app.utils.registerAndroidRegexErrorHandler
import io.legado.app.utils.registerAndroidScreenInfoProvider
import io.legado.app.utils.removePref
import io.legado.app.web.registerAndroidWebServerPlatform
import io.legado.app.web.utils.registerAndroidWebAssetSource
import io.legado.app.web.utils.registerAndroidWebStrings
import kotlinx.coroutines.launch
import splitties.init.appCtx
import splitties.systemservices.notificationManager
import java.net.URL
import java.util.concurrent.TimeUnit

class App : Application() {

    private lateinit var oldConfig: Configuration

    override fun onCreate() {
        super.onCreate()
        // Android-KMP library 不生成 BuildConfig，由宿主注入 ApplicationInfo 可调试状态。
        registerAndroidDebugState(this)
        // 注册 shared 模块的 ApplicationContext, 供 commonMain 的 stringRes(resId) 使用
        registerSharedAppContext(this)
        // 注册 commonMain 的 Toasters actual (AndroidToaster), 供下沉业务调用 Toasters.get().toast()
        registerAndroidToaster(this)
        // 注册 EpubFile androidMain 的 ApplicationContext, 供 LocalEpubResource android actual
        // 打开 content scheme 本地书籍 (contentResolver.openFileDescriptor); 未注册时
        // content scheme 路径返回 null (PFD 获取失败, EpubFile 记录错误日志)
        registerEpubApplicationContext(this)
        registerAndroidAppFilesDir(appCtx)
        registerAndroidAppStringProvider()
        registerAndroidAppLogHost()
        // 注册 ScreenInfoProvider (供 SystemUtils.screenWidthPx/screenHeightPx 委托读取),
        // 须在任何 SystemUtils 屏幕尺寸访问之前 (PdfFile 渲染等)
        registerAndroidScreenInfoProvider()
        // 注册 UI Provider (Toast/OpenUrl/UserAgent, 供 JsExtensionsCommon.toast/longToast/
        // getWebViewUA/openUrl 回调, 须在任何 JS 业务调用之前)
        registerAndroidToastProvider()
        registerAndroidOpenUrlProvider()
        registerAndroidUserAgentProvider()
        registerAndroidJsEngines()
        // 注册 ACache 的 cacheDir/filesDir 注入 (供 ACache.get(cacheName) 获取目录),
        // 必须在 registerAndroidFileCacheProvider 之前 (FileCacheProvider 委托 ACache)
        registerAndroidACacheDirProvider()
        // 注册 FileCacheProvider (委托 ACache), 供 commonMain 的 CacheManager
        // getFile/putFile/getByteArray/put(ByteArray)/delete 文件操作调用;
        // 必须在任何 CacheManager 文件操作之前完成 (JsEngines 注册后 JS 即可能触发)
        registerAndroidFileCacheProvider()
        registerAndroidBackstageWebView()
        registerAndroidBookInfoRefresher()
        // 注册 BookHelp 章节缓存 / 图片缓存 / 本地书定位三个平台 provider
        // (commonMain 下沉的业务编排层经 BookStorageProviders/BookImageStorageProviders/
        // LocalBookLocators 间接调用 app 端 BookHelp, 须在任何 commonMain 业务调用前完成注册)
        BookStorageProviders.register(AndroidBookStorage)
        BookImageStorageProviders.register(AndroidBookImageStorage)
        LocalBookLocators.register(AndroidLocalBookLocator())
        // 注册 RegexErrorHandler (longToastOnUi/saveCrashInfo2File/restart),
        // 供 shared jvmAndAndroidMain 的 RegexReplacerImpl 在替换超时分支调用;
        // 须在 registerAndroidWebBookProviders 之前 (任何 RegexReplacers.get().replace 之前)
        registerAndroidRegexErrorHandler()
        registerAndroidWebBookProviders()
        // 注册 Coil3 BookImageLoader (Compose 图片加载, 替代 Glide 迁移批 1 共享面接线)
        // 依赖 OkHttpClientProviders (上一步 registerAndroidWebBookProviders 已注册),
        // ImageLoader 内部 lazy 构建故注册本身不触发网络栈初始化
        registerAndroidBookImageLoader(appCtx)
        // JsExtensions 压缩方法 (getZip/Rar/7zByteArrayContent 等) 走 ArchiveProviders,
        // app 端委托 ArchiveUtils/LibArchiveUtils (libarchive 全格式)
        ArchiveProviders.register(AndroidArchiveProvider)
        // Coil3 批 2: 设置 SingletonImageLoader.Factory, 让 app 端 AsyncImage / imageView.load 默认走
        // 注册了 SourceOriginHeaderInterceptor + 共享 OkHttpClient 的 ImageLoader (防盗链 header 自动注入)
        // 批 4: 在同一个 ComponentRegistry 中追加 MangaModelFetcher，不能对已构建的 loader
        // 再调用 newBuilder().components { ... }，该 API 会替换而不是追加全部组件。
        coil3.SingletonImageLoader.setSafe {
            io.legado.app.help.image.buildBookImageLoader(it) {
                add(io.legado.app.help.glide.MangaModelFetcher.Factory())
            }
        }
        // 注册 FileBook 平台 provider (commonMain FileBook object 经
        // FileBookProviders 调到 app 端 FileBookAccessorImpl, 含 importFromArchive /
        // importLocalFile / saveBookFile / downloadRemoteBook / mergeBook 等重 Android 逻辑)
        registerAndroidFileBookProviders()
        registerAndroidPasswordProvider()
        // 注册 ServiceLaunchers (commonMain Download/CacheBook/UpdateBook 启动入口)
        registerAndroidServiceLauncher(appCtx)
        // 注册 Web 服务 provider (commonMain WebServerManager 调用 WebServerPlatform/WebAssetSource/WebStrings)
        // - WebServerPlatform: HttpServer+WebSocketServer 起停 + serve 回调拉起 WebService 续命 wakelock
        // - WebAssetSource: composeResources 读 web 静态资源 (单一数据源 commonMain/composeResources/files/web)
        // - WebStrings: R.string.cannot_empty 文案注入 WebSocketServer
        // 须在任何 WebServerManager.start()/stop() 之前注册 (用户触发 Web 服务开关时)
        registerAndroidWebServerPlatform { WebService.serve() }
        registerAndroidWebAssetSource(appCtx)
        registerAndroidWebStrings(appCtx, R.string.cannot_empty)
        // 注册 AudioPlay 平台 provider (commonMain AudioPlayShared 调用
        // AudioPlayCommanders/AudioPlayBookBridges 派发 Service 命令与 Book 操作,
        // 须在 registerAndroidWebBookProviders 之后, 因 AudioPlayShared 依赖 AppDbProviders)
        registerAndroidAudioPlayProviders()
        // 注册 ChangeBookSource 平台 provider (commonMain ChangeBookSourceViewModelShared 调用
        // ChangeBookSourcePlatformProviders.get() 取 AndroidChangeBookSourcePlatform,
        // 须在 registerAndroidWebBookProviders 之后, 因换源依赖 AppDbProviders / WebBookProviders)
        registerAndroidChangeBookSourcePlatform()
        // 注册 BookshelfManage 平台 provider (commonMain BookshelfManageViewModelShared 调用
        // BookshelfManagePlatformProviders.get() 取 AndroidBookshelfManagePlatform,
        // 须在 registerAndroidWebBookProviders 之后, 因换源依赖 AppDbProviders / WebBookProviders)
        registerAndroidBookshelfManagePlatform()
        // 注册 CacheBookCallback 桥接 ReadBook 单例 (CacheBookShared 调度核心下沉到 commonMain 后,
        // app 端通过 callback 把下载完成事件回放到 ReadBook.contentLoadFinish / downloadedChapters /
        // downloadFailChapters, 行为与下沉前一致)
        CacheBook.registerCallback()
        registerAndroidPreferenceProvider()
        registerAndroidDirectLinkUploadProviders()
        // 注册 ReadBookConfigProviders 防 BackupShared/RestoreShared 走到 error() (Android 主路径
        // 走 app 端 Backup/Restore, 此注册仅兜底; 非 app 端 ReadBookConfig 桥接, 桥接待 KP2-H)
        ReadBookConfigProviders.register(
            AndroidReadConfigProviders(AndroidPreferenceStoreProvider()).readBookConfig
        )
        CrashHandler(this)
        oldConfig = Configuration(resources.configuration)
        applyDayNightInit(this)
        registerActivityLifecycleCallbacks(LifecycleHelp)
        SourceUiEventBridge.init()
        defaultSharedPreferences.registerOnSharedPreferenceChangeListener(AppConfig)
        // 注入 HomeTabHelpShared 的 SP provider (commonMain 下沉的主页分组持久化,
        // 包装 defaultSharedPreferences, 行为与原 appCtx.getPrefString/putPrefString 等价)
        HomeTabHelpShared.prefs = AndroidPreferenceStoreProvider()
        // 注入 PinnedExploreHelp 的 SP provider (commonMain 下沉的发现页收藏分类持久化,
        // 必须与 HomeTabHelpShared 同源 defaultSharedPreferences, 保证老数据兼容 + Backup.kt 备份)
        PinnedExploreHelp.prefs = AndroidPreferenceStoreProvider()
        // jsoup-compat 复用宿主共享 OkHttpClient,继承 CookieJar/限流/Cronet 拦截器
        org.jsoup.Jsoup.clientFactory = { okHttpClient }
        Coroutine.async {
            LogUtils.init(this@App)
            LogUtils.d("App", "onCreate")
            LogUtils.logDeviceInfo()
            createNotificationChannels()
            DefaultData.upVersion()
            // Arco: 清理旧版本遗留的 barElevation 偏好值（elevation 已移除）
            appCtx.removePref(PreferKey.barElevation)
            AppFreezeMonitor.init(this@App)
            DispatchersMonitor.init()
        }
        Coroutine.async {
            if (AppConfig.isCronet) {
                Cronet.preDownload(null)
                // 尝试初始化 GMS Cronet Provider
                runCatching {
                    val installer =
                        Class.forName("com.google.android.gms.net.CronetProviderInstaller")
                    installer.getMethod("installWithSharedLibrary", Context::class.java)
                        .invoke(null, this@App)
                }.onFailure {
                    LogUtils.d("App", "GMS Cronet not available: ${it.message}")
                }
            }
            // 注册 CookieJarBridge, 让 shared 端 ObsoleteUrlFactory 能调用 app 端 CookieManager
            CookieJarBridgeHolder.register(CookieManager)
            // 注册 CookieStoreProvider, 让 shared 端业务层能跨平台调用 app 端 CookieStore/CookieManager
            registerAndroidCookieStoreProvider()
            URL.setURLStreamHandlerFactory(ObsoleteUrlFactory(okHttpClient))
            launch { installGmsTlsProvider(appCtx) }
            initQuickJs()
            //初始化封面
            BookCover.toString()
        }
        Coroutine.async {
            if (LocalConfig.lastBackup + TimeUnit.DAYS.toMillis(1) < System.currentTimeMillis()) {
                appDb.cacheDao.clearDeadline(System.currentTimeMillis())
                BookHelp.clearInvalidCache()
                Backup.clearCache()
                ReadBookConfig.clearBgAndCache()
                ThemeConfig.clearBg()
            }
        }
        Coroutine.async {
            //调整排序序号
            SourceHelp.adjustSortNumber()
            //同步阅读记录
            if (AppConfig.syncBookProgress) {
                AppWebDav.downloadAllBookProgress()
            }
        }
    }

    override fun attachBaseContext(base: Context) {
        super.attachBaseContext(AppContextWrapper.wrap(base))
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        val diff = newConfig.diff(oldConfig)
        if ((diff and ActivityInfo.CONFIG_UI_MODE) != 0) {
            applyDayNight(this)
        }
        oldConfig = Configuration(newConfig)
    }

    /**
     * 尝试在安装了GMS的设备上(GMS或者MicroG)使用GMS内置的Conscrypt
     * 作为首选JCE提供程序，而使Okhttp在低版本Android上
     * 能够启用TLSv1.3
     * https://f-droid.org/zh_Hans/2020/05/29/android-updates-and-tls-connections.html
     * https://developer.android.google.cn/reference/javax/net/ssl/SSLSocket
     *
     * @param context
     * @return
     */
    private fun installGmsTlsProvider(context: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            return
        }
        try {
            val gmsPackageName = "com.google.android.gms"
            val appInfo = packageManager.getApplicationInfo(gmsPackageName, 0)
            if ((appInfo.flags and ApplicationInfo.FLAG_SYSTEM) == 0) {
                return
            }
            val gms = context.createPackageContext(
                gmsPackageName,
                CONTEXT_INCLUDE_CODE or CONTEXT_IGNORE_SECURITY
            )
            gms.classLoader
                .loadClass("com.google.android.gms.common.security.ProviderInstallerImpl")
                .getMethod("insertProvider", Context::class.java)
                .invoke(null, gms)
        } catch (e: java.lang.Exception) {
            e.printStackTrace()
        }
    }

    /**
     * 创建通知ID
     */
    private fun createNotificationChannels() {
        val downloadChannel = NotificationChannel(
            channelIdDownload,
            getString(R.string.action_download),
            NotificationManager.IMPORTANCE_DEFAULT
        ).apply {
            enableLights(false)
            enableVibration(false)
            setSound(null, null)
            lockscreenVisibility = Notification.VISIBILITY_PUBLIC
        }

        val readAloudChannel = NotificationChannel(
            channelIdReadAloud,
            getString(R.string.read_aloud),
            NotificationManager.IMPORTANCE_DEFAULT
        ).apply {
            enableLights(false)
            enableVibration(false)
            setSound(null, null)
            lockscreenVisibility = Notification.VISIBILITY_PUBLIC
        }

        val webChannel = NotificationChannel(
            channelIdWeb,
            getString(R.string.web_service),
            NotificationManager.IMPORTANCE_DEFAULT
        ).apply {
            enableLights(false)
            enableVibration(false)
            setSound(null, null)
            lockscreenVisibility = Notification.VISIBILITY_PUBLIC
        }

        //向notification manager 提交channel
        notificationManager.createNotificationChannels(
            listOf(
                downloadChannel,
                readAloudChannel,
                webChannel
            )
        )
    }

    @Suppress("UnusedExpression")
    private fun initQuickJs() {
        // 触发当前 JS 引擎单例初始化,预加载 bootstrap (quickjs 预编译 bytecode / rhino 加载类)
        JsEngines.get()
    }

    companion object {
        init {
            if (BuildConfig.DEBUG) {
                System.setProperty("kotlinx.coroutines.debug", "on")
            }
        }
    }

}
