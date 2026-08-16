package io.legado.app.napi

import io.legado.app.web.utils.WebAssetSources
import kotlin.concurrent.Volatile
import kotlinx.coroutines.runBlocking

/**
 * 构建 Markdown 查看器完整 HTML (运行时从 composeResources 直读, 零拷贝零重复)。
 *
 * # 单一数据源
 * web 资源唯一数据源在 `shared/src/commonMain/composeResources/files/web/help/`
 * (Android assets / 桌面 classpath / iOS / 鸿蒙四端同一份, 见 [WebAssetSources])。
 * 鸿蒙端 composeResources 打包进 liblegado_shared.so 内嵌资源, Web 组件无法直接按路径访问,
 * 故由本对象把模板 + js/css 内联拼成完整 HTML (经 [LegadoNativeExports.buildMarkdownViewerHtml]
 * 导出给 ArkTS loadData), **不产生任何平台端资源副本**。
 *
 * # 内联安全
 * - js 内容中的 `</script` 转义为 `<\/script` (JS 字符串等价, 但不会提前闭合 <script> 标签);
 * - css 内容不含 `</style`, 直接内联。
 *
 * # 缓存
 * 首次构建后缓存 (viewer 打开多次不重复读文件/拼接; 约 200KB 内存, 可忽略)。
 *
 * # 线程
 * 内部 runBlocking + 纯内存读取 (不涉及 tsfn), ArkTS 主线程同步调用安全。
 */
object OhosMarkdownViewer {

    /** 构建完成的完整 viewer HTML (进程内缓存)。 */
    @Volatile
    private var cachedHtml: String? = null

    /**
     * 构建 (或取缓存) Markdown 查看器完整 HTML。
     *
     * @return 完整 HTML 字符串 (内含 marked/hljs/github-markdown 亮暗主题); 读取失败返回空串
     */
    fun buildHtml(): String {
        cachedHtml?.let { return it }
        val html = runCatching {
            runBlocking {
                val assets = WebAssetSources.get()
                suspend fun read(path: String): String = assets.read(path).decodeToString()
                val template = read("web/help/markdown_viewer.html")
                template
                    .replace(MARKED_PLACEHOLDER, escapeScript(read("web/help/js/marked.min.js")))
                    .replace(
                        HIGHLIGHT_PLACEHOLDER,
                        escapeScript(read("web/help/js/highlight.min.js"))
                    )
                    .replace(
                        CSS_LIGHT_PLACEHOLDER,
                        read("web/help/css/github-markdown-light.min.css")
                    )
                    .replace(CSS_DARK_PLACEHOLDER, read("web/help/css/github-markdown-dark.css"))
                    .replace(CSS_HLJS_PLACEHOLDER, read("web/help/css/highlight.min.css"))
            }
        }.getOrElse {
            println("[ohos-markdown-viewer] build failed: ${it.message}")
            ""
        }
        cachedHtml = html
        return html
    }

    /** script 内联转义: `</script` (不区分大小写) → `<\/script` (防止提前闭合标签; JS 语义等价)。 */
    private fun escapeScript(js: String): String =
        js.replace(Regex("</script", RegexOption.IGNORE_CASE), "<\\/script")

    private const val MARKED_PLACEHOLDER = "/*__MARKED_JS__*/"
    private const val HIGHLIGHT_PLACEHOLDER = "/*__HIGHLIGHT_JS__*/"
    private const val CSS_LIGHT_PLACEHOLDER = "/*__CSS_LIGHT__*/"
    private const val CSS_DARK_PLACEHOLDER = "/*__CSS_DARK__*/"
    private const val CSS_HLJS_PLACEHOLDER = "/*__HLJS_CSS__*/"
}
