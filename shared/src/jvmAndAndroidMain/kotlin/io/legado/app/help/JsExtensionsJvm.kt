package io.legado.app.help

/**
 * JVM 半区 (Android/desktop) JS 扩展面。
 *
 * 原实现 (jsoup get/head/post 网络三函数) 已下沉至 [JsExtensionsCommon] (commonMain, 带平台门面),
 * 本接口保留为空壳, 供 BookSourceJsExt / HttpTTSJsExt / DesktopBookSourceJsExt /
 * DesktopAnalyzeRule / DesktopAnalyzeUrl 等继承以组合 [JsEncodeUtilsDefaults] +
 * [JsExtensionsCommon] 两个面; 加密工厂面见 [JsEncodeUtilsDefaults]。
 * native 端无本接口 (AnalyzeRuleCore/AnalyzeUrlCore 由 nativeMain 桥直接走 JsExtensionsCommon)。
 */
@Suppress("unused")
interface JsExtensionsJvm : JsEncodeUtilsDefaults, JsExtensionsCommon
