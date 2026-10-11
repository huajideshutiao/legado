package io.legado.app.constant

@Suppress("RegExpRedundantEscape", "unused")
object AppPattern {

    /**
     * 空白字符类的**内部串** (不含方括号): 需要嵌进其他字符类时用它, 例如 `"[$spaceClassBody\\n]"`。
     *
     * 取 **Unicode `White_Space` 属性**全体 (共 25 个码点), 而不是抄某个引擎的 `\s` ——
     * 各端 `\s` 互不相同且都只是它的一部分:
     * - ICU (Android/iOS 系统引擎): `[\t\n\f\r\p{Z}]` —— 缺 U+000B/U+0085 (ICU 用户指南 "\s" 行);
     * - JDK: `\s` 未开 Unicode 语义时为 `[ \t\n\x0B\f\r]` (官方 `Pattern` 类文档原文),
     *   开了才是 `[\p{Z}\t\n\x0B\f\r\x85]` (`CharPredicates.WHITE_SPACE()` 原文);
     * - Kotlin/Native stdlib: `[\t\n\x0B\f\r ]` —— 不含 U+3000 等 `\p{Z}` (`CachedSpace` 原文)。
     *
     * 取并集是唯一能四端同结果的定义; 取任何单端的定义都会把差异从一类空白换个字符重演。
     */
    const val spaceClassBody = "\\t\\n\\x0B\\f\\r\\x85\\p{Z}"

    /**
     * 空白字符类 (含方括号, 不含量词): 单独使用时用它。
     *
     * 各端引擎的 `\s` 定义不同, 规则里一律用本串拼接。
     */
    const val spaceClass = "[$spaceClassBody]"

    val JS_PATTERN: Regex =
        Regex("<js>([\\w\\W]*?)</js>|@js:([\\w\\W]*)", RegexOption.IGNORE_CASE)
    val EXP_PATTERN: Regex = Regex("\\{\\{([\\w\\W]*?)\\}\\}")

    //url 参数选项分隔符（逗号后紧跟 { 视为 UrlOption），AnalyzeUrl.paramPattern 委托此值
    // 空白显式写 [spaceClass]: 各端 `\s` 定义不同
    val urlParamPattern: Regex = Regex("$spaceClass*,$spaceClass*(?=\\{)")

    //dataURL图片类型
    val dataUriRegex = Regex("^data:.*?;base64,(.*)")

    // 作者/书名清洗: 空白显式写 [spaceClass], 非空白取它的补集 ('\S' 同样各端定义不同,
    // 不能直接用)
    private const val nonSpaceClass = "[^$spaceClassBody]"

    val nameRegex = Regex("$spaceClass+作$spaceClass*者.*|$spaceClass+$nonSpaceClass+$spaceClass+著")
    val authorRegex = Regex("^$spaceClass*作$spaceClass*者[:：$spaceClassBody]+|$spaceClass+著")
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
     * 所有标点 (Unicode 标点大类)。
     *
     * `\p{P}` 在各端引擎里都认 (Kotlin/Native stdlib 把单字母属性名补成 `IsP`, 对应
     * `CharCategory` 的标点类并集), 故不展开。
     */
    val bdRegex = Regex("(\\p{P})+")

    /**
     * 换行
     */
    val rnRegex = Regex("[\\r\\n]")

    /**
     * 不发音段落判断 (全空白/控制符/标点/分隔符/符号)。
     *
     * 空白显式写 [spaceClass]: 各端 `\s` 定义不同, native stdlib 的 `\s` 不含 U+3000,
     * 而全角空格段正是朗读时需要跳过的最常见形态。
     */
    val notReadAloudRegex = Regex("^($spaceClass|\\p{C}|\\p{P}|\\p{Z}|\\p{S})+$")

    val xmlContentTypeRegex = "(application|text)/\\w*\\+?xml.*".toRegex()

    val semicolonRegex = ";".toRegex()

    val equalsRegex = "=".toRegex()

    /**
     * 空白 (Unicode 空白语义)。
     *
     * 必须显式写 `\p{Z}`, 理由见 [spaceClass]。
     */
    val spaceRegex = "$spaceClass+".toRegex()

    val regexCharRegex = "[{}()\\[\\].+*?^$\\\\|]".toRegex()

    val LFRegex = "\n".toRegex()
}
