package io.legado.desktop.js

import com.script.quickjs.QuickJsContext
import com.script.quickjs.QuickJsEngine
import com.script.quickjs.ScriptException
import io.legado.app.exception.NoStackTraceException
import io.legado.app.help.http.OkHttpClientProviders
import io.legado.app.help.http.newCallStrResponse
import io.legado.app.model.script.quickjs.QuickJsSharedJsScopeBase
import io.legado.app.ui.compose.platform.jvmGetString
import kotlinx.coroutines.runBlocking
import java.util.concurrent.ConcurrentHashMap
import kotlin.coroutines.CoroutineContext

/**
 * 桌面端 SharedJsScopeProvider 薄适配。
 *
 * 三层缓存 (bytecodeCache + ThreadLocal LRU + 版本号失效) 与 jsLib 编译/下载/eval
 * 流程下沉在 shared 的 [QuickJsSharedJsScopeBase] (与 app 端
 * io.legado.app.model.script.quickjs.QuickJsSharedJsScopeProvider 共用同一份核心),
 * 本类只提供桌面侧差异:
 * - jsLib URL 下载内容用 in-memory Map 缓存 (桌面无 ACache, 进程退出即丢, 重启重新下载)
 * - 下载走 [OkHttpClientProviders] 注册的单例
 * - eval 报错包装: 把 ScriptException 补上 jsLib 定位信息 (文件:行:列 + 源码行摘录)
 *
 * # 注册时机
 * 由 [registerDesktopJsEngines] 在桌面端 main 入口早期注册, 任何 shared 中
 * `SharedJsScope.getScope(...)` 调用之前 (BaseSource.evalJS / AnalyzeRule 等 JS eval 入口)。
 *
 * # 未注册影响
 * SharedJsScope.provider() 会抛 IllegalStateException "SharedJsScopeProviders 未注册",
 * 被 runCatching 吞掉, 表现为书源 jsLib 规则失效 (source.lib 注入失败, JS 调用 jsLib
 * 函数报 ReferenceError)。
 */
object DesktopQuickJsSharedJsScopeProvider : QuickJsSharedJsScopeBase() {

    /** jsLib URL 下载内容 in-memory 缓存 (替代 app 端 ACache)。 */
    private val jsLibContentCache = ConcurrentHashMap<String, String>()

    // 省略可见性修饰的 override 自动继承基类的 protected 可见性
    override fun cachedJsLibContent(name: String): String? = jsLibContentCache[name]

    override fun storeJsLibContent(name: String, content: String) {
        jsLibContentCache[name] = content
    }

    override fun downloadJsLibContent(url: String): String? = runBlocking {
        OkHttpClientProviders.get().okHttpClient.newCallStrResponse { url(url) }.body
    }

    override fun jsLibDownloadFailedException(url: String): Exception =
        NoStackTraceException(jvmGetString("download_jslib_failed", url))

    /** eval 失败时把 ScriptException 包装成带 jsLib 定位信息的报错 (app 端无此包装)。 */
    override fun evalJsLibBytecode(
        entry: QuickJsSharedJsScopeBase.JsLibBytecode,
        scope: QuickJsContext,
        coroutineContext: CoroutineContext?,
    ) {
        try {
            QuickJsEngine.evalBytecode(entry.bytecode, scope, coroutineContext)
        } catch (e: ScriptException) {
            throw ScriptException(
                buildJsLibErrorMessage(entry.label, entry.source, e),
                e,
                e.fileName ?: entry.label,
                e.lineNumber,
                e.columnNumber,
            )
        }
    }

    private fun buildJsLibErrorMessage(label: String, source: String, e: ScriptException): String {
        val base = e.message?.takeIf { it.isNotBlank() } ?: e.toString()
        val line = e.lineNumber.takeIf { it > 0 } ?: return "$base\njsLib: $label"
        val snippet = source.lines().getOrNull(line - 1)?.trim()?.takeIf { it.isNotEmpty() }
        return buildString {
            append(base)
            append("\njsLib: ").append(label).append(':').append(line)
            if (e.columnNumber > 0) append(':').append(e.columnNumber)
            if (snippet != null) append("\n> ").append(snippet.take(240))
        }
    }
}
