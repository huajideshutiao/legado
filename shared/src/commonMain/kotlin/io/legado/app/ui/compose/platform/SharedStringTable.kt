package io.legado.app.ui.compose.platform

/**
 * 遗留字符串表, 只服务下面 4 个同步非 Composable 调用点 —— 它们所在函数是接口实现或平台回调,
 * 改 suspend 会波及接口签名 (FileThemeConfigProvider) 或身处非协程上下文 (iOS dispatch_async /
 * Native 异常消息构造, 后者 runBlocking 在主线程有死锁风险)。
 *
 * 单一数据源是 composeResources/values/strings.xml: Composable 走 [rememberString],
 * 协程/suspend 上下文走 [findStringResource] + `getString`。**不要新增 key**, 新代码用上面两条路。
 *
 * - `FileThemeConfigProvider.getBuiltinConfigs` (2): default_day_theme / default_night_theme
 * - `Toaster.ios` (1): ok
 * - `NativeFileBookAccessor` (1): native_book_format_not_supported
 * - `NativeQuickJsSharedJsScopeProvider` (1): download_jslib_failed
 */
val sharedStringTable: Map<String, String> = mapOf(
    "ok" to "确定",
    "default_day_theme" to "默认·白天",
    "default_night_theme" to "默认·夜间",
    "native_book_format_not_supported" to
        "iOS/鸿蒙端暂不支持解析 %s 格式本地书 (pdf/cbz 解析器未下沉)",
    "download_jslib_failed" to "下载jsLib-%s失败",
)
