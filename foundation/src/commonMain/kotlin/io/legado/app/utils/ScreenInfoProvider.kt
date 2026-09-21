package io.legado.app.utils

import kotlin.concurrent.Volatile

/**
 * 跨平台屏幕尺寸 Provider 接口。
 *
 * app 端 SystemUtils.screenWidthPx/screenHeightPx 原直接读
 * `android.content.res.Resources.displayMetrics` (依赖 Android Context), 不能下沉
 * shared commonMain。桌面端 (Compose Desktop) 需要屏幕尺寸时通过本接口注入平台实现解耦。
 * 安卓端实现委托 `appCtx.resources.displayMetrics.widthPixels/heightPixels`,
 * 桌面端实现委托 `java.awt.Toolkit.getDefaultToolkit().screenSize`。
 *
 * 模式参考 PreferenceProvider / PasswordProvider。
 */
interface ScreenInfoProvider {

    /** 屏幕像素宽度 */
    val screenWidthPx: Int

    /** 屏幕像素高度 */
    val screenHeightPx: Int
}

/**
 * ScreenInfoProvider 容器。宿主启动早期注册一次。
 *
 * shared/commonMain 内访问点用 `ScreenInfoProviders.get().screenWidthPx` 等替代
 * 平台 Resources / Toolkit 调用, 行为完全一致, 仅多一层 provider 间接。
 *
 * 各端注册: app 端 `registerAndroidScreenInfoProvider` / 桌面端 `registerDesktopScreenInfoProvider` /
 * iOS 端 `registerIosScreenInfoProvider` / 鸿蒙端 `registerOhosScreenInfoProvider`
 * (鸿蒙尺寸由 EntryAbility 经 legado.registerScreenSize 注入显示物理像素)。
 */
object ScreenInfoProviders {
    @Volatile
    private var impl: ScreenInfoProvider? = null

    /** 宿主启动早期注册一次（任何 shared 调用之前）。 */
    fun register(impl: ScreenInfoProvider) {
        this.impl = impl
    }

    /** 取已注册实现；未注册抛出 IllegalStateException 帮助早期发现初始化遗漏。 */
    fun get(): ScreenInfoProvider =
        impl
            ?: error("ScreenInfoProviders.impl not registered; call registerAndroidScreenInfoProvider() / registerDesktopScreenInfoProvider() / registerIosScreenInfoProvider() / registerOhosScreenInfoProvider() first")
}
