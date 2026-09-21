package io.legado.app.ui.video

import io.legado.app.constant.AppPattern
import io.legado.app.ui.root.VideoPlayTarget
import io.legado.app.utils.hasPlayableScheme

/**
 * 外部投递的"可直接播放地址" → [VideoPlayTarget.Direct] 的唯一判据 (四端共用)。
 *
 * # 为什么单独一个对象
 *
 * 外部把视频交给我们的入口有好几种形态, 但判定必须完全一致, 否则会出现"文件管理器能播、
 * 分享面板不能播"这种只在某一端通的行为:
 * - Android `ACTION_VIEW` (file/content/http + video\* 或视频扩展名)
 * - Android `ACTION_SEND` (EXTRA_STREAM 文件 URI / EXTRA_TEXT 里的直链)
 * - 桌面 文件关联 argv / `setOpenFileHandler` → [io.legado.app.ui.FileAssociationDispatch]
 * - iOS `CFBundleDocumentTypes` 与鸿蒙 `skills.uris` → [io.legado.app.ui.root.LaunchRequest.ImportFile]
 *
 * # 判据 (两条, 缺一即返回 null 交回调用方继续按书/JSON 处理)
 * 1. 地址带可播 scheme (`http/https/file/content`), 或是一段本地绝对路径;
 * 2. 末段文件名命中 [AppPattern.videoFileRegex], 或调用方明确告知 MIME 是视频 ——
 *    `content://` 的末段常是数字 id 而不是 `x.mp4`, 那种只有 MIME 能认。
 *
 * 需要书源规则解析的网页地址**不在此列**: 那条路必须先拿到 Book,
 * 见 [io.legado.app.ui.association.resolveRoute]。
 */
object VideoDirect {

    /**
     * @param address 外部给的地址: 绝对路径 / file URI / content URI / http 直链
     * @param mimeIsVideo 调用方已从 MIME 判定为视频 (Android intent 的 type、鸿蒙 want 的 type)
     * @param headers 外部随附的请求头 (随载荷透传给播放器)
     * @param title 外部给的显示名 (Android 的 DISPLAY_NAME 等), 空则取地址末段
     * @return 直投播放目标; null = 这不是可直接播放的视频, 由调用方按原有链路继续处理
     */
    fun targetFor(
        address: String,
        mimeIsVideo: Boolean = false,
        headers: Map<String, String> = emptyMap(),
        title: String? = null,
    ): VideoPlayTarget.Direct? {
        if (address.isBlank()) return null
        val url = when {
            address.hasPlayableScheme() -> address
            // 桌面文件关联给的是裸绝对路径, 播放器三端都要带 scheme 的 URI, 这里统一补齐
            address.isLocalPathForm() -> address.toFileUri()
            else -> return null
        }
        val name = url.fileNameOf()
        if (!mimeIsVideo && !name.matches(AppPattern.videoFileRegex)) return null
        return VideoPlayTarget.Direct(
            url = url,
            headers = headers,
            title = title?.takeIf { it.isNotBlank() } ?: name,
        )
    }

    /** 裸本地路径形态: POSIX `/a/b`、Windows 盘符 `C:\a\b` / `C:/a/b`、UNC `\\srv\share`。 */
    private fun String.isLocalPathForm(): Boolean {
        if (contains("://")) return false
        return startsWith("/") || startsWith("\\\\") ||
                (length >= 3 && this[1] == ':' && (this[2] == '/' || this[2] == '\\'))
    }

    /**
     * 裸路径 → `file://` URI。
     *
     * Windows 盘符补成 `file:///D:/...` 三段斜杠形式 (`D:` 不能被当 authority), 并把反斜杠
     * 归一为正斜杠 —— URI 里的 `%5C` 三端解得不一样, 而正斜杠 Windows 文件 API 同样收。
     */
    private fun String.toFileUri(): String {
        val windowsDrive = Regex("^[A-Za-z]:[\\\\/]").containsMatchIn(this)
        val path = if (windowsDrive) replace('\\', '/') else this
        val encoded = path.percentEncodeUnsafe()
        return if (windowsDrive) "file:///$encoded" else "file://$encoded"
    }

    /** 只编码会改变 URI 结构的字符 (空格 / `#` / `?` / `%`); 斜杠与盘符冒号保留。 */
    private fun String.percentEncodeUnsafe(): String = buildString {
        for (c in this@percentEncodeUnsafe) {
            when (c) {
                ' ' -> append("%20")
                '#' -> append("%23")
                '?' -> append("%3F")
                '%' -> append("%25")
                else -> append(c)
            }
        }
    }

    /**
     * 调用方给的 MIME 是否算视频 (Android intent 的 type / 鸿蒙 want 的 type 都走这里)。
     *
     * 判据只留这一份: 两个入口各写一遍的话, 一端补的例外类型另一端不认, 表现成
     * "从系统投播能直接播、从文件管理器分享就按普通文件导入"。
     */
    fun isVideoMime(type: String?): Boolean = type != null &&
        (type.startsWith(VIDEO_MIME_PREFIX, ignoreCase = true) ||
            // 例外: 有投递方给的类型不带 video/ 前缀, 但投来的内容确实是视频
            type.equals(MIME_DMCLIENT_TS, ignoreCase = true))

    /** 视频 MIME 前缀 (调用方判定用)。 */
    const val VIDEO_MIME_PREFIX = "video/"

    private const val MIME_DMCLIENT_TS = "text/vnd.DMClientTS"

    /** 取末段文件名 (先切掉 query/fragment), 用于扩展名判定与标题显示。 */
    private fun String.fileNameOf(): String =
        substringBefore('#').substringBefore('?')
            .substringAfterLast('/').substringAfterLast('\\')
}
