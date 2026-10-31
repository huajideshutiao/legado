package io.legado.app.ui

import io.legado.app.constant.AppLog
import platform.UIKit.UIDevice
import platform.UIKit.UIInterfaceOrientationMaskAll
import platform.UIKit.UIInterfaceOrientationMaskAllButUpsideDown
import platform.UIKit.UIInterfaceOrientationMaskLandscape
import platform.UIKit.UIUserInterfaceIdiomPad

/**
 * Windows klib 校验专用 stub (源根 src/iosWindowsCheckMain, 仅非 mac 主机的
 * iosArm64Main/iosSimulatorArm64Main 编译挂载): 真实实现在 iosMain 的 IosSceneOrientation.kt,
 * 其请求通道依赖 sceneorientation cinterop 的 lg_request_scene_orientation (需本机 Apple SDK),
 * Windows 上由本 stub 顶替, 使方向锁的消费方通过 klib 语法/签名校验。
 *
 * 方向集合常量与真实实现同源 (UIKit 平台库在非 mac 主机同样可用); 请求通道不会在真实设备
 * 执行 (mac 上编译真实实现, 本文件不挂载), 故按"系统无该 API"落日志。
 */
internal fun landscapeMask(): ULong = UIInterfaceOrientationMaskLandscape

internal fun appSupportedOrientationMask(): ULong =
    if (UIDevice.currentDevice.userInterfaceIdiom == UIUserInterfaceIdiomPad) {
        UIInterfaceOrientationMaskAll
    } else {
        UIInterfaceOrientationMaskAllButUpsideDown
    }

internal fun requestSceneOrientation(mask: ULong, context: String) {
    @Suppress("UNUSED_EXPRESSION")
    mask
    AppLog.put("iOS 方向请求未下发 (非 mac 主机校验构建): $context")
}
