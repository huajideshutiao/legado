package io.legado.app.constant

@Suppress("RegExpRedundantEscape", "unused")
object AppPattern {
    val JS_PATTERN: Regex =
        Regex("<js>([\\w\\W]*?)</js>|@js:([\\w\\W]*)", RegexOption.IGNORE_CASE)
    val EXP_PATTERN: Regex = Regex("\\{\\{([\\w\\W]*?)\\}\\}")

    //url 参数选项分隔符（逗号后紧跟 { 视为 UrlOption），AnalyzeUrl.paramPattern 委托此值
    val urlParamPattern: Regex = Regex("\\s*,\\s*(?=\\{)")

    //匹配格式化后的图片格式
    val imgPattern: Regex = Regex("<img src=\"([^\"]*(?:\"[^>]+\\})?)[^>]+>")

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
