package io.legado.app.constant

@Suppress("RegExpRedundantEscape", "unused")
object AppPattern {
    val JS_PATTERN: Regex =
        Regex("<js>([\\w\\W]*?)</js>|@js:([\\w\\W]*)", RegexOption.IGNORE_CASE)
    val EXP_PATTERN: Regex = Regex("\\{\\{([\\w\\W]*?)\\}\\}")

    //url 参数选项分隔符（逗号后紧跟 { 视为 UrlOption），AnalyzeUrl.paramPattern 委托此值
    val urlParamPattern: Regex = Regex("\\s*,\\s*(?=\\{)")

    //dataURL图片类型
    val dataUriRegex = Regex("^data:.*?;base64,(.*)")

    val nameRegex = Regex("\\s+作\\s*者.*|\\s+\\S+\\s+著")
    val authorRegex = Regex("^\\s*作\\s*者[:：\\s]+|\\s+著")
    val fileNameRegex = Regex("[\\\\/:*?\"<>|.]")
    val fileNameRegex2 = Regex("[\\\\/:*?\"<>|]")
    val splitGroupRegex = Regex("[,;，；]")
    val titleNumPattern: Regex = Regex("(第)(.+?)(章)")

    //书源调试信息中的各种符号
    val debugMessageSymbolRegex = Regex("[⇒◇┌└≡]")

    //本地书籍支持类型
    val bookFileRegex = Regex(".*\\.(txt|epub|pdf|cbz)", RegexOption.IGNORE_CASE)

    /**
     * 可直接交给播放器播放的视频文件后缀 (外部“打开方式 / 分享 / 文件关联”投递)。
     *
     * 与 Android manifest 的视频 `pathPattern` 声明、桌面 `fileAssociation` 列表逐一对应，
     * 三处不同步就会出现“系统把文件交给我们但我们自己不认”。
     *
     * 不含 .mpd (DASH): 对外申报 = 四端都得能播, 而
     * - iOS: AVPlayer 只支持 HLS/渐进式, 无 DASH (见 `IosVideoPlayPlatformProvider`
     *   的 `iosPlayRejectReason` 注释: “iOS 只支持 http / https / m3u8 直链与本地视频文件”);
     * - 鸿蒙: `@ohos.multimedia.media` 的 `createMediaSourceWithUrl` 文档写了支持 DASH;
     * - Android: 已引 media3-exoplayer-dash, 书源给的 .mpd 直链内部能播。
     * 取交集 → 仍不申报 mpd; 单端能播不等于可对外声明。
     */
    /**
     * 可对外投递的视频类型: 扩展名 → MIME。唯一清单, 下面两样都由它派生/对齐:
     * [videoFileRegex] (我们自己认不认) 与桌面运行期向系统的申报 (DesktopUrlProtocol)。
     */
    val videoFileTypes: List<Pair<String, String>> = listOf(
        "mp4" to "video/mp4",
        "mkv" to "video/x-matroska",
        "mov" to "video/quicktime",
        "webm" to "video/webm",
        "flv" to "video/x-flv",
        "avi" to "video/x-msvideo",
        "ts" to "video/mp2t",
        "m3u8" to "application/vnd.apple.mpegurl",
    )

    val videoFileRegex: Regex =
        Regex(
            ".*\\.(${videoFileTypes.joinToString("|") { it.first }})$",
            RegexOption.IGNORE_CASE,
        )
    //压缩文件支持类型
    val archiveFileRegex = Regex(".*\\.(zip|rar|7z|tar|gz|bz2|xz|lzma)$", RegexOption.IGNORE_CASE)

    val imgFileRegex = Regex(".*\\.(jpg|jpeg|png|gif|bmp|webp|svg|apng|avif|heic|heif)$", RegexOption.IGNORE_CASE)

    /**
     * 所有标点
     */
    val bdRegex = Regex("(\\p{P})+")

    /**
     * 换行
     */
    val rnRegex = Regex("[\\r\\n]")

    /**
     * 不发音段落判断
     */
    val notReadAloudRegex = Regex("^(\\s|\\p{C}|\\p{P}|\\p{Z}|\\p{S})+$")

    val xmlContentTypeRegex = "(application|text)/\\w*\\+?xml.*".toRegex()

    val semicolonRegex = ";".toRegex()

    val equalsRegex = "=".toRegex()

    val spaceRegex = "\\s+".toRegex()

    val regexCharRegex = "[{}()\\[\\].+*?^$\\\\|]".toRegex()

    val LFRegex = "\n".toRegex()
}
