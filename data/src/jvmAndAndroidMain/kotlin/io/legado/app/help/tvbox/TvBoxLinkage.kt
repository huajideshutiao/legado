package io.legado.app.help.tvbox

/**
 * jar 侧类链接失败的统一收敛。
 *
 * [LinkageError] 属 Error 族: `catch (ClassNotFoundException)` 与 `catch (Exception)` 都接不住,
 * 裸穿到界面只剩 `NoClassDefFoundError: com/tencent/smtt/sdk/WebViewClient` 这种没有上下文的栈 ——
 * 分不清"jar 依赖的类本平台没有"(桌面无 Android WebView/X5 内核)、"jar 字节码坏"与"站点自身坏"。
 * 收敛后带上缺失类名, 让三类原因可区分。
 */
internal fun jarLinkageFailure(what: String, e: LinkageError): Nothing {
    // 缺失类名在 NoClassDefFoundError/ExceptionInInitializerError 的 cause 链末端 (take 防 cause 成环)
    val root = generateSequence<Throwable>(e) { it.cause }.take(16).last()
    val missing = root.message ?: e.message ?: "(无消息)"
    error("$what 失败: ${e::class.simpleName}($missing)")
}
