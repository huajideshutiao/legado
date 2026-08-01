package io.legado.app.ui.association

import io.legado.app.ui.association.LegadoDeepLink.parse
import io.legado.app.ui.association.LegadoDeepLinkHandler.consume
import io.legado.app.ui.association.LegadoDeepLinkHandler.handle
import io.legado.app.ui.association.LegadoDeepLinkHandler.pending
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * legado:// deep link 导入类型 (对照 app 端 `FileAssociationFragment.handleOnLineImport`
 * 的 `uri.path` 分支, 一一对应)。
 */
enum class DeepLinkImportType {
    /** /bookSource 与 /rssSource 共用 (ImportBookSourceViewModelShared 兼容 OldRssSource) */
    BOOK_SOURCE,
    /** /replaceRule */
    REPLACE_RULE,
    /** /textTocRule */
    TXT_TOC_RULE,
    /** /httpTTS */
    HTTP_TTS,
    /** /dictRule */
    DICT_RULE,
    /** /theme */
    THEME,
    /** /addToBookshelf (app 端 AddToBookshelfHelper.add) */
    ADD_TO_BOOKSHELF,
    /** /readConfig (app 端 getBytes + importReadConfig) */
    READ_CONFIG,
    /** 未识别 path (app 端 else 分支 determineType: 下载内容嗅探类型) */
    UNKNOWN,
}

/** deep link 解析结果: 导入类型 + `src` 参数 (待导入内容的 URL)。 */
data class DeepLinkImportRequest(
    val type: DeepLinkImportType,
    val src: String,
)

/**
 * legado://`/`yuedu://` deep link 解析器 (KMP commonMain, 各端共享)。
 *
 * # 背景
 *
 * app 端一键导入 (书源/替换规则/主题等) 走 AndroidManifest 的 AssociationActivity
 * intent-filter (scheme legado/yuedu) → `FileAssociationFragment.handleOnLineImport`,
 * 按 `uri.path` 分发到各 ImportXxxDialog。本类把该分发语义原样下沉 commonMain,
 * 各端 (desktop 启动参数 / iOS onOpenURL / 鸿蒙 Want.uri) 只做"拿到 URL → 调 [parse]"。
 *
 * URL 形态 (与 app 端 Android Uri 解析行为一致):
 * - `legado://import/bookSource?src=<URL>` → host=import, path=/bookSource
 * - `legado://booksource/importonline?src=<URL>` → host=booksource, path=/importonline
 *   (老格式, 按 host 分流 booksource/rsssource→书源, replace→替换规则, 其余→UNKNOWN)
 *
 * # 与 app 端语义对照
 *
 * - scheme 精确匹配 "legado"/"yuedu" (FileAssociationFragment:98 的相等判断);
 * - `src` 取查询串第一个匹配值, percent 解码 + '+'→空格 (Android
 *   `Uri.getQueryParameter` 的 UriCodec.decode(convertPlus=true) 行为);
 * - `#` 后内容按 fragment 截掉 (Android Uri 同);
 * - src 缺失/空 → 返回 null (app 端 handleOnLineImport 直接 finishActivity)。
 *
 * Android 端保留原生 intent-filter 路径, 不经过本类。
 */
object LegadoDeepLink {

    /** 是否为 legado 系 deep link (供各端在启动参数/openURL 回调里快速筛选)。 */
    fun isDeepLink(url: String): Boolean {
        val trimmed = url.trim()
        return trimmed.startsWith("legado://") || trimmed.startsWith("yuedu://")
    }

    /**
     * 解析 deep link, 非 legado/yuedu scheme 或缺 `src` 参数返回 null。
     *
     * path→类型映射与 app 端 `handleOnLineImport` 的 when 分支一致
     * (未识别 path 不返回 null 而是 [DeepLinkImportType.UNKNOWN],
     * 对应 app 端 else 分支 determineType 下载嗅探)。
     */
    fun parse(url: String): DeepLinkImportRequest? {
        val trimmed = url.trim()
        val schemeEnd = trimmed.indexOf("://")
        if (schemeEnd <= 0) return null
        val scheme = trimmed.substring(0, schemeEnd)
        if (scheme != "legado" && scheme != "yuedu") return null
        // fragment 截掉 (Android Uri 的 query 不含 fragment)
        val rest = trimmed.substring(schemeEnd + 3).substringBefore('#')
        val queryStart = rest.indexOf('?')
        val hostPath = if (queryStart >= 0) rest.substring(0, queryStart) else rest
        val query = if (queryStart >= 0) rest.substring(queryStart + 1) else return null
        val slash = hostPath.indexOf('/')
        val host = if (slash >= 0) hostPath.substring(0, slash) else hostPath
        val path = if (slash >= 0) hostPath.substring(slash) else ""
        val src = getQueryParameter(query, "src")
        // src 缺失/空: 与 app 端 handleOnLineImport 的 isNullOrEmpty→finishActivity 一致
        if (src.isNullOrEmpty()) return null
        val type = when (path) {
            "/bookSource", "/rssSource" -> DeepLinkImportType.BOOK_SOURCE
            "/replaceRule" -> DeepLinkImportType.REPLACE_RULE
            "/textTocRule" -> DeepLinkImportType.TXT_TOC_RULE
            "/httpTTS" -> DeepLinkImportType.HTTP_TTS
            "/dictRule" -> DeepLinkImportType.DICT_RULE
            "/theme" -> DeepLinkImportType.THEME
            "/addToBookshelf" -> DeepLinkImportType.ADD_TO_BOOKSHELF
            "/readConfig" -> DeepLinkImportType.READ_CONFIG
            "/importonline" -> when (host) {
                "booksource", "rsssource" -> DeepLinkImportType.BOOK_SOURCE
                "replace" -> DeepLinkImportType.REPLACE_RULE
                else -> DeepLinkImportType.UNKNOWN
            }
            else -> DeepLinkImportType.UNKNOWN
        }
        return DeepLinkImportRequest(type, src)
    }

    /** 取查询串第一个匹配 key 的值并解码 (对照 Android Uri.getQueryParameter: 首个匹配 + 解码)。 */
    private fun getQueryParameter(query: String, key: String): String? {
        query.split('&').forEach { pair ->
            val eq = pair.indexOf('=')
            val k = if (eq >= 0) pair.substring(0, eq) else pair
            if (k == key) {
                return if (eq >= 0) decodeQueryValue(pair.substring(eq + 1)) else ""
            }
        }
        return null
    }

    /**
     * percent 解码 + '+'→空格 (对照 Android UriCodec.decode(convertPlus=true) 的 UTF-8 语义)。
     * 非法 %XX 转义原样保留 (宽松处理, 用户手敲 URL 常见)。
     */
    private fun decodeQueryValue(encoded: String): String {
        val sb = StringBuilder(encoded.length)
        val byteBuf = ArrayList<Byte>()
        fun flushBytes() {
            if (byteBuf.isNotEmpty()) {
                sb.append(byteBuf.toByteArray().decodeToString())
                byteBuf.clear()
            }
        }
        var i = 0
        while (i < encoded.length) {
            val c = encoded[i]
            when {
                c == '%' && i + 2 < encoded.length -> {
                    val hi = encoded[i + 1].digitToIntOrNull(16)
                    val lo = encoded[i + 2].digitToIntOrNull(16)
                    if (hi != null && lo != null) {
                        byteBuf.add(((hi shl 4) or lo).toByte())
                        i += 3
                        continue
                    }
                    flushBytes()
                    sb.append(c)
                    i++
                }
                c == '+' -> {
                    flushBytes()
                    sb.append(' ')
                    i++
                }
                else -> {
                    flushBytes()
                    sb.append(c)
                    i++
                }
            }
        }
        flushBytes()
        return sb.toString()
    }
}

/**
 * deep link 待处理请求容器 (各端"投递侧"与"消费侧"的桥)。
 *
 * - 投递侧: desktop `main(args)`/macOS OpenURIHandler、iOS `handleLegadoDeepLink`
 *   (SwiftUI onOpenURL 转发)、鸿蒙 EntryAbility onCreate/onNewWant (napi handleDeepLink)
 *   拿到 URL 后调 [handle];
 * - 消费侧: 各端 UI 顶层 collect [pending], 非 null 时弹对应导入对话框,
 *   完成/取消后调 [consume] 清空 (desktop 见 DesktopDeepLinkImportHost,
 *   iOS/鸿蒙见 sharedUiMain DeepLinkImportHost)。
 *
 * StateFlow 保证"先投递后订阅"(冷启动经启动参数投递, 窗口稍后才组合) 不丢事件。
 * 多次投递取最后一次 (与 app 端 singleTask AssociationActivity 一次处理一条一致)。
 */
object LegadoDeepLinkHandler {

    private val _pending = MutableStateFlow<DeepLinkImportRequest?>(null)

    /** 待处理导入请求 (null=无), 各端 UI 顶层订阅。 */
    val pending: StateFlow<DeepLinkImportRequest?> = _pending.asStateFlow()

    /** 解析并记录 deep link; 返回 false 表示非 legado 系 URL 或缺 src 参数 (未记录)。 */
    fun handle(url: String): Boolean {
        val request = LegadoDeepLink.parse(url) ?: return false
        _pending.value = request
        return true
    }

    /** 消费完成 (导入对话框关闭) 后清空待处理请求。 */
    fun consume() {
        _pending.value = null
    }

    /**
     * 用已解析出具体类型的请求替换当前待处理请求 (对照 app 端 `determineType` 内嗅探出
     * JSON 类型后直接 `showImportDialog(...)`, 不再是"未识别 path"了)。
     *
     * 供 [io.legado.app.ui.association.SchemeImportOps.determineType] 嗅探 UNKNOWN 类型的
     * 下载内容后, 把 `DeepLinkImportType.UNKNOWN` 请求"升级"为具体类型请求 (`src` 直接是
     * 已下载的 JSON 文本, 而非再次触发下载的 URL —— Import*ViewModelShared 的
     * `importSource`/`import` 均接受纯 JSON 文本, 与接受 URL 是同一入口)。
     */
    fun handleResolved(request: DeepLinkImportRequest) {
        _pending.value = request
    }
}
