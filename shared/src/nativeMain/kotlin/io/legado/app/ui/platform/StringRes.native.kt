package io.legado.app.ui.platform

/**
 * [stringRes] 的 iOS/鸿蒙 actual 实现 (nativeMain 中间源集共用)。
 *
 * iOS/鸿蒙两端 actual 实现完全一致 (暂返回空串), 下沉到 nativeMain 共用。
 * - iOS 后续 KP3 接 LocalizedStringKey 时替换
 * - 鸿蒙后续 KP4 接 ohos ResourceTable 时替换
 *
 * 详见 commonMain/ui/platform/StringRes.kt expect 注释。
 * 注: expect 声明位于 commonMain (nativeMain dependsOn commonMain 可见),
 * nativeMain 不依赖 sharedUiMain, 本 actual 不引用 sharedUiMain 任何内容, 可安全下沉。
 */
actual fun stringRes(resId: Int): String = ""
