package io.legado.desktop.help.tvbox

import io.legado.app.help.tvbox.TvBoxPlatforms
import io.legado.app.help.tvbox.registerComposeTvBoxAssetProvider
import io.legado.app.model.tvbox.TvBoxManager
import io.legado.app.ui.book.tvbox.JvmTvBoxPlatform
import io.legado.app.ui.book.tvbox.TvBoxServiceProviders

/**
 * 桌面端 TVBox 影视源宿主注册 (对照 app 端 App.onCreate 的 AndroidTvBoxHostPlatform /
 * AndroidTvBoxSniffer / TvBoxManager.init 三步):
 *
 * 1. 平台钩子 (jar 类加载/上下文) 注册 + 引导脚本 provider (composeResources 单一数据源)
 *    —— 管理页导入与取数链路的前置;
 * 2. TvBoxService 注册 (jvmAndAndroidMain 实现 Android/桌面共用) —— 「我的」页入口以
 *    [TvBoxServiceProviders.getOrNull] 判空显隐, 注册即入口可见;
 * 3. [TvBoxManager.init] 幂等初始化 —— 重载已持久化配置 + 注册 TVBox 取数委派。
 *
 * 注册时机: DesktopCore.registerSecondaryCoreProviders 内、registerDesktopWebBookProviders
 * 之后。headless 入口共用 DesktopCore,
 * 同样获得 Web 书源取数能力 (无管理页 UI, 入口注册无副作用)。
 */
fun registerDesktopTvBoxProviders() {
    TvBoxPlatforms.register(DesktopTvBoxHostPlatform)
    registerComposeTvBoxAssetProvider()
    TvBoxServiceProviders.register(JvmTvBoxPlatform())
    TvBoxManager.init()
}
