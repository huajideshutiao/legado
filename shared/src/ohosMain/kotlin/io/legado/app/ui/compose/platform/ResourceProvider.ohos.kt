package io.legado.app.ui.compose.platform

/**
 * ohos 平台 ResourceProvider 占位 (TODO: ohos actual 待真实实现)。
 *
 * ## 重要说明: ohosMain 不继承 sharedUiMain
 *
 * 依据 `shared/build.gradle:207-210` 配置:
 * ```
 * ohosMain {
 *     dependsOn(commonMain)
 *     // KP5: ohosMain 不继承 sharedUiMain (鸿蒙 UI 用 ArkTS, 不参与 Compose 编译)
 *     // 鸿蒙 target 仅编译 commonMain + ohosMain 业务逻辑, Compose UI 代码在 sharedUiMain 中隔离
 * }
 * ```
 *
 * 因此 ohosMain **看不到** sharedUiMain 中 expect 声明的
 * `rememberPainter` / `rememberString` / `rememberColor` / `rememberStringArray`
 * (expect 声明路径: `shared/src/sharedUiMain/kotlin/io/legado/app/ui/compose/platform/ResourceProvider.kt`),
 * 无法提供 actual 实现 (Kotlin expect/actual 要求 actual 必须有对应 expect, 否则编译错误)。
 *
 * 鸿蒙端 UI 由 ArkTS 实现, 不参与 Compose 编译, 不需要 ResourceProvider。
 * 此文件仅为后续可能启用 ohos Compose target 时的占位, 当前不参与编译产物。
 *
 * ## 后续计划
 *
 * 若未来 ohosMain 启用 sharedUiMain 继承 (ohos Compose target 启用),
 * 可参照 iosMain stub 风格提供 actual 实现:
 * - Painter: 返回 Icons.Filled.Help 占位 (material-icons-extended 已在 sharedUiMain 声明)
 * - String: 返回 key 本身 (调试期可接受)
 * - Color: 返回 Color.Unspecified
 * - StringArray: 返回 emptyList()
 *
 * 或用 ohos 平台 API (ohos.global.resource.ResourceManager) 实现真实资源访问:
 * - `ohos.global.resource.ResourceManager.getStringByNameSync(key)` 取字符串
 * - `ohos.global.resource.ResourceManager.getDrawableByName(key)` 取 Drawable
 * - `ohos.global.resource.ResourceManager.getColorByName(key)` 取颜色
 * - `ohos.global.resource.ResourceManager.getStringArrayByNameSync(key)` 取字符串数组
 *
 * 注意: ohos API 需要 ohos SDK 依赖, 当前 shared/build.gradle 未声明 ohos SDK,
 * 启用 ohos Compose target 时需同步补充依赖。
 *
 * 此 object 仅为 KDoc 载体, 无实际逻辑, 避免与 sharedUiMain 的 expect 函数签名冲突。
 */
internal object OhosResourceProviderPlaceholder
