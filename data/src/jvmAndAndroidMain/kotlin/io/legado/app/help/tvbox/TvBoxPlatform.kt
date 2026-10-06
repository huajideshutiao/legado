package io.legado.app.help.tvbox

import android.content.Context
import java.io.File
import kotlin.concurrent.Volatile

/**
 * TVBox 宿主平台钩子 (jvmAndAndroidMain 共用契约, 平台差异经它注入):
 *
 * - [appContext]: jar 契约里的 android.content.Context (Android=应用上下文, 桌面=jvmMain
 *   stub 实例), 供 Init.set 与 jar 内 Init.context() / Spider.init(context, ext) 使用;
 * - [newJarClassLoader]: spider jar 类加载器 (Android=DexClassLoader, 桌面=URLClassLoader)。
 *
 * 引导脚本读取不在此钩子内: 已统一走 composeResources 单一数据源, 见 [TvBoxHostAssetProviders]。
 *
 * Android 端由 app 模块 AndroidTvBoxHostPlatform 注册 (App.onCreate), 桌面端由
 * desktop-core DesktopTvBoxHostPlatform 注册 (DesktopCore.registerDesktopTvBoxProviders)。
 */
interface TvBoxPlatform {

    /** jar 契约上下文 (Init.set / spider.init 的入参; jar 内经 Init.context() 回取)。 */
    val appContext: Context

    /** 为已就位的 jar 文件新建类加载器 (父加载器与 odex 目录由平台自决)。 */
    fun newJarClassLoader(jarFile: File): ClassLoader
}

/** [TvBoxPlatform] 容器 (provider 注入模式, 同 TvBoxServiceProviders)。 */
object TvBoxPlatforms {

    @Volatile
    private var impl: TvBoxPlatform? = null

    /** 宿主启动早期注册一次 (任何 TvBoxManager/装载器使用之前)。 */
    fun register(impl: TvBoxPlatform) {
        this.impl = impl
    }

    /** 获取已注册实现, 未注册抛出 IllegalStateException。 */
    fun get(): TvBoxPlatform =
        impl ?: error("TvBoxPlatforms not registered; 当前平台未接入 TVBox 宿主钩子")

    /** 仅测试场景: 清空注册 (生产代码勿调用)。 */
    fun reset() {
        impl = null
    }
}
