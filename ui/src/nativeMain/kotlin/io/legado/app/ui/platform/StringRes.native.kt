package io.legado.app.ui.platform

/**
 * [stringRes] 的 iOS/鸿蒙 actual 实现 (nativeMain 中间源集共用)。
 *
 * iOS/鸿蒙两端均无 Int resId 调用方 (唯一调用点 EditEntity secondary constructor
 * 在两端都走 String hint primary 重载; commonMain/sharedUiMain 无 EditEntity 调用),
 * 真实字符串走 syncGetString + rememberString (key-based, 与 jvm/iOS 一致),
 * 故保留空串 stub, 无需接入 LocalizedStringKey / ohos ResourceTable。
 *
 * 详见 commonMain/ui/platform/StringRes.kt expect 注释。
 * 注: expect 声明位于 commonMain (nativeMain dependsOn commonMain 可见),
 * nativeMain 不依赖 sharedUiMain, 本 actual 不引用 sharedUiMain 任何内容, 可安全下沉。
 */
actual fun stringRes(resId: Int): String = ""
