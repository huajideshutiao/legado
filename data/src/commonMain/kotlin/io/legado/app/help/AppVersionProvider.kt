package io.legado.app.help

import kotlin.concurrent.Volatile

/**
 * 当前运行版本号抽象 (commonMain)。
 *
 * # 存在原因
 *
 * 启动期默认数据补齐 ([StartupMaintenanceShared.initDefaultData]) 需要"当前版本号"作为
 * 版本推进基准, 但各端取法不同且都依赖平台 API:
 * - Android: `AppConst.appInfo.versionCode` (PackageManager.longVersionCode)
 * - 桌面: `DesktopAppInfo.versionCode` (MANIFEST Implementation-Version 解析)
 * - iOS: `NSBundle.CFBundleShortVersionString` 经 versionNameToVersionCode 解析
 * - 鸿蒙: ArkTS 注入的版本名经同一解析
 *
 * 四者都进不了 commonMain, 故按既有 provider 注入模式抽象一层。
 *
 * # 取不到值时返回 0
 *
 * 版本名尚未注入的时机 (鸿蒙 ArkTS 注入与首帧组合存在竞态) 返回 0。调用方据此把
 * "当前版本 0" 视为"版本未推进", 不做任何补齐也不记录, 下次启动再判 —— 与 iOS/鸿蒙
 * 既有 `getAppVersionName()?.let { upVersion(...) }` 的"取不到就下次再判"语义一致。
 *
 * 模式参考 [DefaultDataResourceProvider] / [io.legado.app.help.config.PreferenceProvider]。
 */
interface AppVersionProvider {

    /**
     * 当前运行版本号。
     *
     * 必须每次调用都重新取值 (鸿蒙版本名可能在首次调用后才注入), 实现不要缓存 null 结果。
     */
    val versionCode: Long
}

/**
 * [AppVersionProvider] 容器 (provider 注入模式)。
 *
 * 宿主启动早期注册一次: Android 在 `App.onCreate` 同步段, 桌面在
 * `DesktopCore.registerEarlyProviders`, iOS/鸿蒙在各自入口组合出启动维护协程之前。
 *
 * 未注册时 [get] 抛 [IllegalStateException] 而不是返回 0: 返回 0 会让默认数据补齐永久静默
 * 空转 (版本门恒"未推进"), 属难定位的静默降级。
 */
object AppVersionProviders {

    @Volatile
    private var impl: AppVersionProvider? = null

    /** 宿主启动早期注册一次 (任何启动期维护协程之前)。 */
    fun register(impl: AppVersionProvider) {
        this.impl = impl
    }

    /** 获取已注册实现, 未注册抛出 IllegalStateException。 */
    fun get(): AppVersionProvider =
        impl ?: error("AppVersionProviders not registered; register it in the host entry before startup maintenance")
}
