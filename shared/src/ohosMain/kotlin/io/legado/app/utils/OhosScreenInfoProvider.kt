package io.legado.app.utils

import io.legado.app.napi.OhosNativeBridge

/**
 * [ScreenInfoProvider] 鸿蒙实现: 读 ArkTS 侧注入的显示物理像素尺寸。
 *
 * 数据源: EntryAbility.onWindowStageCreate 经 `legado.registerScreenSize(w, h)` 注入
 * `display.getDefaultDisplaySync()` 的 vp 尺寸 × densityPixels 物理像素
 * (与 iOS [UIScreen.nativeBounds] / Android `displayMetrics` 的物理像素语义一致,
 * 对照 desktop 端 `Toolkit.getDefaultToolkit().screenSize`)。
 *
 * # 降级策略
 * 未注入 (napi 未接入阶段) 时回退 1080x2340, 保证 sharedUiMain AppDialogSizes 不抛
 * IllegalStateException (未注册 ScreenInfoProviders 时 get() 直接 error 导致所有对话框崩溃)。
 */
private object OhosScreenInfoProvider : ScreenInfoProvider {

    /** 未注入时的兜底尺寸 (常见手机物理像素, 仅兼容 napi 未接入阶段)。 */
    private const val FALLBACK_WIDTH_PX = 1080
    private const val FALLBACK_HEIGHT_PX = 2340

    override val screenWidthPx: Int
        get() = OhosNativeBridge.getScreenSizePx().first.takeIf { it > 0 } ?: FALLBACK_WIDTH_PX

    override val screenHeightPx: Int
        get() = OhosNativeBridge.getScreenSizePx().second.takeIf { it > 0 } ?: FALLBACK_HEIGHT_PX
}

/** 宿主启动早期注册一次 (任何对话框尺寸计算之前)。 */
fun registerOhosScreenInfoProvider() {
    ScreenInfoProviders.register(OhosScreenInfoProvider)
}
