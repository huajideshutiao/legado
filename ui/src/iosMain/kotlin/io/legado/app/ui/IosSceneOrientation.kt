@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package io.legado.app.ui

import io.legado.app.constant.AppLog
import kotlinx.cinterop.useContents
import platform.Foundation.NSProcessInfo
import platform.UIKit.UIApplication
import platform.UIKit.UIInterfaceOrientationMaskAll
import platform.UIKit.UIInterfaceOrientationMaskAllButUpsideDown
import platform.UIKit.UIInterfaceOrientationMaskLandscape
import platform.UIKit.UISceneActivationStateForegroundActive
import platform.UIKit.UIDevice
import platform.UIKit.UIUserInterfaceIdiomPad
import platform.UIKit.UIWindowScene
import platform.UIKit.UIWindowSceneGeometryPreferencesIOS

// iOS 方向请求共用件: 视频页锁横屏 (IosVideoPlayPlatformProvider.applySystemFullScreen) 与
// 阅读页/漫画页方向锁 (IosWindowController.setOrientation) 共用同一份, 不各写一遍。
//
// iOS 没有"写死方向"的公开通道, 只有这一条官方请求通道:
// `UIWindowScene.requestGeometryUpdateWithPreferences:errorHandler:` 配
// `UIWindowSceneGeometryPreferencesIOS(interfaceOrientations:)`。它是
// `UIDevice.setValue(forKey:"orientation")` 被系统拒后用苹果给出的替代 (iOS 16 起系统日志
// 明确要求改用 requestGeometryUpdate), 故本端不走 UIDevice 那条路。
//
// 边界 (如实保留, 不静默):
// - iOS 16 以下没有受支持的方向 API, 只落一条日志;
// - 请求由系统裁决: 目标方向不在应用/场景允许集合内 (如 iPhone 的倒竖屏, Info.plist 未声明)、
//   或 iPad 处于分屏多任务时会被拒, 拒绝原因由 errorHandler 落日志 (页面观感维持请求前方向)。

/** 进全屏锁横屏: 两个横向方向都允许 (对照 Android SENSOR_LANDSCAPE / 鸿蒙 2)。 */
internal fun landscapeMask(): ULong = UIInterfaceOrientationMaskLandscape

/**
 * 恢复/跟随系统的方向集合 = 本应用 Info.plist 声明的全集 (iPhone: 竖屏 + 两横向 = 26,
 * iPad 另含倒竖屏 = 30), 语义同鸿蒙的 0=UNSPECIFIED。
 *
 * 刻意不照搬 Android 退出视频全屏时锁竖屏 (SCREEN_ORIENTATION_PORTRAIT): iOS 只有一个窗口场景,
 * 锁竖屏会溢出到视频页之外的所有页面, 而本应用其余页面本就允许横屏。
 */
internal fun appSupportedOrientationMask(): ULong =
    if (UIDevice.currentDevice.userInterfaceIdiom == UIUserInterfaceIdiomPad) {
        UIInterfaceOrientationMaskAll
    } else {
        UIInterfaceOrientationMaskAllButUpsideDown
    }

/**
 * 请求前台窗口场景切到 [mask] 允许的方向。
 *
 * @param context 落日志用的场景名 ("视频全屏" / "阅读页方向"), 便于区分调用方
 */
internal fun requestSceneOrientation(mask: ULong, context: String) {
    val osMajorVersion = NSProcessInfo.processInfo.operatingSystemVersion.useContents {
        majorVersion
    }
    if (osMajorVersion < IOS_SCENE_GEOMETRY_MIN_VERSION) {
        AppLog.put("iOS $osMajorVersion 无受支持的方向请求 API, $context 未生效")
        return
    }
    val scene = UIApplication.sharedApplication().connectedScenes
        .filterIsInstance<UIWindowScene>()
        .firstOrNull { it.activationState == UISceneActivationStateForegroundActive }
    if (scene == null) {
        AppLog.put("iOS 找不到前台窗口场景, $context 方向请求未下发")
        return
    }
    scene.requestGeometryUpdateWithPreferences(
        UIWindowSceneGeometryPreferencesIOS(interfaceOrientations = mask),
        errorHandler = { error ->
            AppLog.put("iOS $context 方向请求被拒: ${error?.localizedDescription}")
        },
    )
}

/** `requestGeometryUpdateWithPreferences:errorHandler:` 的最低系统版本 (iOS 16.0)。 */
internal const val IOS_SCENE_GEOMETRY_MIN_VERSION = 16L
