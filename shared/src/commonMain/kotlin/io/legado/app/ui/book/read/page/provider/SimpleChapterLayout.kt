package io.legado.app.ui.book.read.page.provider

import io.legado.app.data.entities.Book
import io.legado.app.ui.book.read.page.entities.TextChapterRef
import io.legado.app.ui.book.read.page.entities.TextLine
import io.legado.app.ui.book.read.page.entities.TextPage
import io.legado.app.ui.book.read.page.entities.column.BaseColumn
import io.legado.app.ui.book.read.page.entities.column.ImageColumn
import io.legado.app.ui.book.read.page.entities.column.ReviewColumn
import io.legado.app.ui.book.read.page.entities.column.TextColumn
import io.legado.app.utils.fastSum

/**
 * 解析后的段落（文本 + 内嵌图片），供 [SimpleChapterLayout.layout] 图片排版路径使用。
 *
 * 对应 app 端 `ChapterContentParser.ParsedLine`，下沉 commonMain 让跨端排版器直接消费。
 * 调用方负责把原始正文（含 `<img>` 标签）解析为 [ParsedParagraph] 列表：
 * - 文本中的 `<img>` 标签替换为图片占位符（[SimpleChapterLayout.srcReplaceChar]），
 *   并按顺序把 src/style/onclick 收集到 [images]
 * - `<br>` / `\n` 保留在 [text] 中，排版器按 `\n` 切行
 *
 * 不直接依赖 app 端 `ChapterContentParser`（其依赖 `EscapeUtils` / `BookContent`），
 * 由调用方按需构造，保持 commonMain 纯净。
 */
data class ParsedParagraph(
    val text: String,
    val images: List<ImgData> = emptyList(),
)

/** 图片原始尺寸（px）。 */
data class ImageSize(val width: Int, val height: Int)

/**
 * 图片尺寸解析接口（commonMain，供平台注入）。
 *
 * app 端 `ImageProvider.getImageSize` 重度依赖 android Bitmap / 网络下载，无法下沉。
 * 本接口抽象「取图片原始尺寸」+「图片是否已缓存」两个原语，由各平台 actual 实现：
 * - Android: 委托 `ImageProvider.getImageSize` / `BookHelp.isImageExist`
 * - Desktop / iOS / 鸿蒙: 走平台图片缓存 / 网络下载
 *
 * 未注入（null）时 [SimpleChapterLayout.setTypeImage] 直接跳过图片排版（退化为准文本），
 * 与原简化版行为一致，保证无图片能力平台仍可跑通文字排版链路。
 */
interface ImageResolver {
    /** 图片是否已缓存到本地（对应 app 端 `BookHelp.isImageExist`）。 */
    fun isImageExist(src: String): Boolean

    /**
     * 取图片原始尺寸，无效图返回 [ImageSize]`(0, 0)`（对应 app 端 `ImageProvider.getImageSize`）。
     * suspend 因 Android 实现内部可能触发缓存下载；commonMain 调用方应在协程内调用。
     */
    suspend fun getImageSize(src: String): ImageSize
}

/**
 * 章节排版器（commonMain 纯 Kotlin，无 android 依赖）。
 *
 * # 设计目的
 *
 * app 端 [io.legado.app.ui.book.read.page.provider.TextChapterLayout] 重度依赖
 * `android.text.StaticLayout` / `TextPaint` / `BookHelp` / `ImageProvider` /
 * `ChapterProvider` 单例等 Android 专属 API，无法整体下沉到 commonMain。
 *
 * 本类把「读章节文本 → 排版成 [TextPage] 列表」的核心纯 Kotlin 编排流程抽取出来，
 * 让桌面 / iOS / 鸿蒙端在没有 Android 排版栈的情况下也能跑通「读章节 → 排版 → 显示」
 * 链路。复用已下沉的 [TextLayoutEngine] / [ZhLineBreaker] / [TextMeasurer] /
 * [ColumnFactory] / [TextLayoutCallback] 接口，补全编排入口与平台无关的
 * `setTypeText` / 断行调度 / 收尾分页等逻辑。
 *
 * # 功能对齐 app 端 [TextChapterLayout]（KP2-D P2 起）
 *
 * - **图片排版**：[setTypeImage] + [ImageColumn] 构造，依赖注入的 [ImageResolver]
 *   提供「取尺寸 / 判缓存」原语；[layout] 传 [ParsedParagraph] 列表时走图片排版路径，
 *   按 [srcReplaceChar] 分割文本段与图片段，支持 SINGLE / FULL / TEXT / DEFAULT 四种风格。
 * - **段评**：[SimpleColumnFactory] 识别 [reviewChar] 占位符构造 [ReviewColumn]，
 *   段评计数从 [layout] 的 `reviewCountMap` 参数查询（按逻辑段号 [currentParagraphIndex]）。
 * - **双页**：[doublePage]=true 时 [TextLayoutEngine] 自动左右分栏（左栏排满切右栏，
 *   右栏排满换页），[TextLine.isLeftLine] 由 [engine.absStartX] 与 [viewWidth] 比较。
 * - **底部对齐**：[textBottomJustify]=true 时 [TextPage.upLinesPosition] 调整 surplus
 *   填满页面剩余空间；[onPageCompleted] 注入 `lineSpacingExtra` 让 `upLinesPosition`
 *   的 pageHeight 阈值判断与 app 端口径一致。
 * - **段落缩进**：[indentCharWidth] > 0 时走 [addIndentChars] 几何计算（用 [indentChar]
 *   构造等宽 [TextColumn] 替代字符串拼接的前 [paragraphIndent.length] 个字）；
 *   [indentCharWidth] == 0 时退化为字符串拼接（与原简化版一致，向后兼容）。
 * - **断行**：复用已下沉的 [ZhLineBreaker]（中文避头尾），输入 `words/widths/indentSize/
 *   width/cnCharWidth/letterSpacingPx`，输出 `lineStartCluster/lineCount/lineWidth`，
 *   按 cluster 区间切片后调 [TextLayoutEngine.addCharsToLineMiddle] / [addCharsToLineNatural]。
 * - **行几何**：`lineTop = paddingTop + durY` / `lineBottom = lineTop + textHeight` /
 *   `lineBase = lineBottom - descent`，与 app 端 `TextLineRender.upTopBottom` 口径一致。
 * - **预取上下章**：[layout] 的 `prefetchCallback` 参数在排版当前章开始时回调
 *   （`direction = -1` 预取上一章，`direction = +1` 预取下一章），由调用方异步 launch。
 * - **替换规则**：[layout] 的 `contentProcessor` 参数对每段正文应用替换规则（对应
 *   app 端 `ContentProcessor.getContent` 的替换净化链），返回处理后文本再排版。
 * - **朗读高亮**：[calcChapterPosition] 按「上一页末行 chapterPosition + charSize +
 *   段末换行」累计算章节内偏移，写入 [TextLine.chapterPosition]，供 TTS
 *   `TextPage.upPageAloudSpan` / `containPos` 高亮定位（替代原 stringBuilder.length 近似）。
 *
 * # 不做的事
 *
 * - 不做章节正文获取（由 [io.legado.app.ui.book.read.ReadBookViewModelShared]
 *   通过 `BookStorageProviders` 取本地缓存或 `BookContent.analyzeContent` 联网拉取）
 * - 不做图片下载 / 缓存（由 [ImageResolver] 实现侧管理，排版层只查询尺寸）
 * - 不做替换规则的持久化 / DAO 查询（由调用方通过 `ContentProcessorAccessor` 取规则后
 *   包装成 `contentProcessor` lambda 注入）
 *
 * @param measurer 文字宽度测量器（建议传入 [SimpleTextMeasurer] 等宽近似实现，
 *   也可由 actual 平台注入更精确的实现）
 * @param visibleWidth 可视区宽度（px，已扣除左右边距；双页模式为单栏宽）
 * @param visibleHeight 可视区高度（px，已扣除上下边距）
 * @param paddingLeft 左边距（px，用于 `absStartX` 初始化）
 * @param paddingTop 上边距（px，用于 `lineTop` 折算）
 * @param textHeight 行高（px，对应 app 端 `contentPaintTextHeight`）
 * @param descent 文字下行度量（px，对应 app 端 `contentPaintDescent`）
 * @param lineSpacingExtra 行间距乘数（如 1.2，对应 app 端
 *   `ChapterProvider.lineSpacingExtra`，注意 app 端该字段已经是乘数）
 * @param paragraphSpacing 段间距（与 app 端 `ChapterProvider.paragraphSpacing` 一致，
 *   按 `textHeight * paragraphSpacing / 10` 累积到 durY）
 * @param titleTopSpacing 标题顶部留白（px）
 * @param titleBottomSpacing 标题底部留白（px）
 * @param paragraphIndent 段落缩进字符串（默认全角空格 `　　`）
 * @param textFullJustify 是否两端对齐（true 时调 `addCharsToLineMiddle`，false 走
 *   `addCharsToLineNatural`；与 app 端 `ReadBookConfig.textFullJustify` 一致）
 * @param useZhLayout 是否启用 [ZhLineBreaker] 中文避头尾断行；false 时退化为按
 *   `visibleWidth` 累积宽度的最简断行（任务允许的简化路径，桌面端默认 true）
 * @param viewWidth 视图总宽（含左右边距 + 双页中缝，px）；单页默认 `visibleWidth + paddingLeft*2`，
 *   双页模式调用方应显式传完整视图宽（`visibleWidth*2 + paddingLeft*3` 之类）
 * @param doublePage 是否双页排版（false=单页，true=左右分栏；默认 false 与 app 端手机端一致）
 * @param textBottomJustify 是否底部对齐（true 时 [TextPage.upLinesPosition] 调整 surplus；
 *   默认 false，与原简化版一致）
 * @param indentCharWidth 缩进单字宽（px，>0 启用 [addIndentChars] 几何缩进；
 *   0 走字符串拼接，默认 0 向后兼容；app 端 `ChapterProvider.indentCharWidth`）
 * @param indentChar 缩进占位字符（默认全角空格 `　`，app 端 `ChapterProvider.indentChar`）
 * @param reviewChar 段评占位符（空串=不启用段评；app 端 `ChapterProvider.reviewChar` = `▨`）
 * @param srcReplaceChar 图片占位符（空串=不启用图片占位识别；app 端
 *   `ChapterProvider.srcReplaceChar` = `▩`）
 */
@Suppress("unused", "MemberVisibilityCanBePrivate")
class SimpleChapterLayout(
    private val measurer: TextMeasurer,
    private val visibleWidth: Int,
    private val visibleHeight: Int,
    private val paddingLeft: Int,
    private val paddingTop: Int,
    private val textHeight: Float,
    private val descent: Float,
    private val lineSpacingExtra: Float,
    private val paragraphSpacing: Int,
    private val titleTopSpacing: Int,
    private val titleBottomSpacing: Int,
    private val paragraphIndent: String,
    private val textFullJustify: Boolean,
    private val useZhLayout: Boolean = true,
    // 以下为 P2 新增排版参数（带默认值，保持原简化版调用方向后兼容）
    private val viewWidth: Int = visibleWidth + paddingLeft * 2,
    private val doublePage: Boolean = false,
    private val textBottomJustify: Boolean = false,
    private val indentCharWidth: Float = 0f,
    private val indentChar: String = "　",
    private val reviewChar: String = "",
    private val srcReplaceChar: String = "",
) {

    /**
     * 当前 setTypeText 处理段落号；[SimpleColumnFactory.createColumn] 把 [reviewChar]
     * 转 [ReviewColumn] 时回填 [ReviewColumn.paragraphIndex]。0 表示章节级（标题末），
     * >=1 是正文逻辑段号（与书源段评接口约定的 paragraphIndex 对齐，≠ 视觉段号
     * [TextLine.paragraphNum]）。
     */
    private var currentParagraphIndex: Int = 0

    /**
     * 段评计数 map（layout 开始时由调用方注入，key=逻辑段号，value=段评数）。
     * null 表示本章无段评数据，[SimpleColumnFactory.createColumn] 一律构造 count=0 的 ReviewColumn。
     * 对应 app 端 `TextChapterLayout.reviewCountMap`。
     */
    @Volatile
    private var reviewCountMap: Map<Int, Int>? = null

    /**
     * 排版章节正文，产出 [TextPage] 列表。
     *
     * 流程对照 app 端 [TextChapterLayout.getTextChapter]：
     * 1. （可选）触发 [prefetchCallback] 异步预取上一章 / 下一章
     * 2. 标题：[displayTitle] 按行（`\n` 切分）调 [setTypeText] 排版（`isTitle=true`），
     *    标题后追加 [titleBottomSpacing] 留白
     * 3. 正文：
     *    - 若 [parsedParagraphs] 非空，走图片排版路径（按 [srcReplaceChar] 分割文本段与
     *      图片段，图片段调 [setTypeImage]，文本段调 [setTypeText]）
     *    - 否则用 [contents] 纯文本路径（每段前置 [paragraphIndent]，调 [setTypeText]）
     *    - （可选）[contentProcessor] 对每段正文应用替换规则
     *    - 段末 `isParagraphEnd=true`，段间 [paragraphSpacing] 留白
     * 4. 收尾：若 `engine.pendingTextPage` 还有行，手动触发 [TextLayoutCallback.onPageCompleted]
     *    把最后一页入列
     *
     * 调用方需在协程里调用（[setTypeText] 内 `engine.addCharsToLineMiddle` 是 suspend，
     * [setTypeImage] 内 [ImageResolver.getImageSize] 也是 suspend）。
     *
     * @param displayTitle 章节标题（可含 `\n` 多行）
     * @param contents 段落列表（每项为一段正文，已去除 `\n`）；[parsedParagraphs] 为空时使用
     * @param chapterIndex 章节序号（写入 [TextPage.chapterIndex]）
     * @param chapterSize 章节总数（写入 [TextPage.chapterSize]）
     * @param reviewCountMap 段评计数 map（key=逻辑段号 1..N，value=段评数）；null=不挂段评气泡
     * @param parsedParagraphs 解析后的段落（含图片占位符 [srcReplaceChar] + 图片列表）；
     *   非空时替代 [contents] 走图片排版路径
     * @param imageResolver 图片尺寸解析器；null 时跳过 [setTypeImage]（退化为准文本）
     * @param imageStyle 图片风格（`SINGLE` / `FULL` / `TEXT` / `DEFAULT`，对应
     *   [Book.imgStyleSingle] / [Book.imgStyleFull] / [Book.imgStyleText] / [Book.imgStyleDefault]）
     * @param contentProcessor 替换规则处理函数（对每段正文应用，返回处理后文本）；null=不处理
     * @param prefetchCallback 预取回调（`direction=-1` 预取上一章，`+1` 预取下一章）；
     *   调用方应在回调内 launch 协程异步预取，避免阻塞当前排版
     * @return 排版后的 [TextPage] 列表（至少 1 页，空内容时返回占位页）
     */
    suspend fun layout(
        displayTitle: String,
        contents: List<String>,
        chapterIndex: Int,
        chapterSize: Int,
        reviewCountMap: Map<Int, Int>? = null,
        parsedParagraphs: List<ParsedParagraph>? = null,
        imageResolver: ImageResolver? = null,
        imageStyle: String? = null,
        contentProcessor: ((String) -> String)? = null,
        prefetchCallback: ((Int) -> Unit)? = null,
    ): ArrayList<TextPage> {
        val pages = arrayListOf<TextPage>()
        // 注入段评计数 map 供 SimpleColumnFactory 查询
        this.reviewCountMap = reviewCountMap
        this.currentParagraphIndex = 0

        // 1. 异步预取上下章（调用方在回调内 launch 协程，不阻塞当前排版）
        // 对应 app 端 ReadBook 在排版当前章时 prevChapter/nextChapter 排版编排
        prefetchCallback?.let { cb ->
            cb(-1)
            cb(1)
        }

        // 局部回调：通过 lateinit 引用 engine，让 onPageCompleted 能取到 engine.pendingTextPage
        lateinit var engineRef: TextLayoutEngine
        val callback = object : TextLayoutCallback {
            override suspend fun ensureActive() {
                // 简化：无协程取消检查（commonMain 调用方应在协程内调用）
            }

            override fun onPageCompleted() {
                val page = engineRef.pendingTextPage
                val pIdx = pages.size
                page.index = pIdx
                page.chapterIndex = chapterIndex
                page.chapterSize = chapterSize
                page.title = displayTitle
                page.doublePage = doublePage
                page.paddingTop = this@SimpleChapterLayout.paddingTop
                // 注入 upLinesPosition 所需几何参数：
                // - textBottomJustify: true 时 upLinesPosition 调整 surplus 填满页面
                // - lineSpacingExtra: 真实乘数（非 0），让 upLinesPosition 的 pageHeight
                //   阈值判断（lastLine.lineBottom + contentPaintTextHeight * lineSpacingExtra）
                //   与 app 端口径一致
                page.textBottomJustify = textBottomJustify
                page.visibleHeight = this@SimpleChapterLayout.visibleHeight
                page.visibleBottom = this@SimpleChapterLayout.paddingTop + this@SimpleChapterLayout.visibleHeight
                page.contentPaintTextHeight = textHeight
                page.lineSpacingExtra = lineSpacingExtra
                page.isCompleted = true
                // 单页模式 leftLineSize 由 engine.prepareNextPageIfNeed 设置为 lineSize；
                // 双页模式 leftLineSize = 左栏行数。收尾的最后一页若未触发 prepareNextPageIfNeed，
                // 此处兜底设为 lineSize（单页）保证 upLinesPosition 不越界
                if (page.leftLineSize == 0) page.leftLineSize = page.lineSize
                page.upLinesPosition()
                if (page.lineSize > 0) {
                    page.upRenderHeight()
                }
                pages.add(page)
            }
        }
        val engine = TextLayoutEngine(
            visibleWidth = visibleWidth,
            visibleHeight = visibleHeight,
            viewWidth = viewWidth,
            paddingLeft = paddingLeft,
            doublePage = doublePage,
            textFullJustify = textFullJustify,
            columnFactory = SimpleColumnFactory(),
            callback = callback,
        ).also { engineRef = it }
        engine.absStartX = paddingLeft

        // 2. 排版标题
        if (displayTitle.isNotEmpty()) {
            engine.durY += titleTopSpacing
            displayTitle.split("\n").forEach { titleLine ->
                if (titleLine.isBlank()) return@forEach
                setTypeText(engine, pages, titleLine, isTitle = true, paragraphNum = 0)
                // 标题行视为段落末尾
                engine.pendingTextPage.lines.lastOrNull()?.isParagraphEnd = true
                engine.stringBuilder.append("\n")
            }
            engine.durY += titleBottomSpacing
        }

        // 3. 排版正文段落
        var paragraphSeq = 0
        if (parsedParagraphs != null) {
            // 图片排版路径：按 srcReplaceChar 分割文本段与图片段
            paragraphSeq = layoutParsedParagraphs(
                engine, pages, parsedParagraphs, paragraphSeq,
                imageResolver, imageStyle,
            )
        } else {
            // 纯文本路径（向后兼容原简化版）
            for (paragraph in contents) {
                if (paragraph.isBlank()) continue
                paragraphSeq++
                // 替换规则钩子：对每段正文应用 ContentProcessor 替换净化
                val processed = contentProcessor?.invoke(paragraph.trim()) ?: paragraph.trim()
                val line = if (paragraphIndent.isNotEmpty()) paragraphIndent + processed else processed
                currentParagraphIndex = paragraphSeq
                setTypeText(engine, pages, line, isTitle = false, paragraphNum = paragraphSeq, isFirstLine = true)
                engine.pendingTextPage.lines.lastOrNull()?.isParagraphEnd = true
                engine.stringBuilder.append("\n")
                engine.durY += textHeight * paragraphSpacing / 10f
            }
        }

        // 4. 收尾：把 pendingTextPage 作为最后一页入列（如果有内容）
        if (engine.pendingTextPage.lineSize > 0) {
            if (engine.pendingTextPage.height < engine.durY) {
                engine.pendingTextPage.height = engine.durY
            }
            engine.pendingTextPage.text = engine.stringBuilder.toString()
            callback.onPageCompleted()
        }

        // 5. 统一回填 textChapter 引用（pageSize 需要总页数，最后一页排出后才知道）
        if (pages.isNotEmpty()) {
            val ref = SimpleTextChapterRef(pages.size)
            pages.forEach { it.textChapter = ref }
        } else {
            // 兜底：无内容时返回占位空页，让 UI 不至于拿到空列表
            val placeholder = TextPage(
                text = "",
                title = displayTitle,
                chapterIndex = chapterIndex,
                chapterSize = chapterSize,
            ).apply {
                textChapter = SimpleTextChapterRef(1)
                isCompleted = true
            }
            pages.add(placeholder)
        }

        return pages
    }

    /**
     * 图片排版路径：遍历 [parsedParagraphs]，按 `\n` 切行，按 [srcReplaceChar] 分割文本段与图片段。
     *
     * 流程对照 app 端 [TextChapterLayout.getTextChapter] 的 `parsedLines.forEach` 块：
     * 1. 每段 [ParsedParagraph.text] 按 `\n` 切行
     * 2. 行首是 [srcReplaceChar] 且非 TEXT 风格时不加缩进（避免缩进空格占满一行/页）
     * 3. 段评占位符 [reviewChar] 追加到有计数（reviewCountMap[seq] > 0）的非空行末
     * 4. TEXT 风格或本段无图：整行调 [setTypeText]（imgList 传给 ColumnFactory 内联图片）
     * 5. SINGLE/FULL/DEFAULT 风格且有图：按 [srcReplaceChar] 分割，文本段调 [setTypeText]，
     *    图片段调 [setTypeImage]
     *
     * @return 排版后的 paragraphSeq（逻辑段号累计值，供调用方继续递增）
     */
    private suspend fun layoutParsedParagraphs(
        engine: TextLayoutEngine,
        pages: ArrayList<TextPage>,
        parsedParagraphs: List<ParsedParagraph>,
        paragraphSeqStart: Int,
        imageResolver: ImageResolver?,
        imageStyle: String?,
    ): Int {
        var paragraphSeq = paragraphSeqStart
        val styleUpper = imageStyle?.uppercase()
        val isTextImageStyle = styleUpper == Book.imgStyleText
        var isSetTypedImage = false

        for (parsedLine in parsedParagraphs) {
            val contentText = parsedLine.text
            // 本段图片队列（ArrayDeque 替代 app 端 LinkedList，commonMain 通用）
            val imgList = ArrayDeque<ImgData>(parsedLine.images.size)
            parsedLine.images.forEach { imgList.add(it) }

            var lineStartIndex = 0
            val contentLength = contentText.length
            while (lineStartIndex < contentLength) {
                var lineEndIndex = contentText.indexOf('\n', lineStartIndex)
                if (lineEndIndex == -1) lineEndIndex = contentLength

                val rawLine = contentText.substring(lineStartIndex, lineEndIndex)
                // 判断是否以图片占位符开头（忽略首部空白）
                val startsWithImage =
                    srcReplaceChar.isNotEmpty() &&
                        rawLine.trimStart(' ', '　').startsWith(srcReplaceChar)

                // 缩进处理：图片开头的块状行不缩进；否则按 paragraphIndent 拼接
                // （几何缩进 indentCharWidth>0 时在 setTypeText 内 addIndentChars 处理，
                //   此处仍拼接 paragraphIndent 让 words[0..indentLength) 是缩进字符供替代）
                val line = when {
                    startsWithImage && !isTextImageStyle -> rawLine.trimStart(' ', '　')
                    rawLine.startsWith("　　") -> paragraphIndent + rawLine.substring(2)
                    paragraphIndent.isNotEmpty() && rawLine.isNotEmpty() ->
                        paragraphIndent + rawLine.trimStart(' ', '　')
                    else -> rawLine
                }

                paragraphSeq++
                currentParagraphIndex = paragraphSeq
                // 仅给「有计数」的非空段落追加段评占位符——计数 map 为空或计数为 0 时整段不挂气泡，
                // 避免给每段都预留占位破坏排版
                val reviewCountForLine = reviewCountMap?.get(paragraphSeq) ?: 0
                val lineWithReview =
                    if (reviewChar.isNotEmpty() && line.isNotEmpty() && reviewCountForLine > 0) {
                        line + reviewChar
                    } else line

                if (isTextImageStyle || imgList.isEmpty()) {
                    // TEXT 风格或本段无图：整行排版（imgList 传给 ColumnFactory 内联 TEXT 图片）
                    setTypeText(
                        engine, pages, lineWithReview,
                        isTitle = false, paragraphNum = paragraphSeq, isFirstLine = true,
                        imgList = if (isTextImageStyle) imgList else null,
                    )
                } else {
                    // SINGLE/FULL/DEFAULT 风格：按 srcReplaceChar 分割文本段与图片段
                    if (styleUpper == Book.imgStyleSingle && isSetTypedImage) {
                        // SINGLE 模式：上一张图占满本页后，下一内容从新页开始
                        isSetTypedImage = false
                        engine.prepareNextPageIfNeed()
                    }
                    val embeddedImages = ArrayDeque<ImgData>()
                    val hasNonEmbeddedImage = srcReplaceChar.isNotEmpty() &&
                        line.contains(srcReplaceChar)
                    var isFirstSegment = true
                    val tmp = StringBuilder()
                    lineWithReview.forEach { char ->
                        if (srcReplaceChar.isNotEmpty() && char == srcReplaceChar[0]) {
                            val img = imgList.removeFirstOrNull() ?: return@forEach
                            if (img.style.equals("TEXT", true)) {
                                // TEXT 风格图片内联到文本流（imgList 传给 setTypeText）
                                embeddedImages.add(img)
                                tmp.append(char)
                            } else {
                                // 块状图片：先把累积文本排版，再调 setTypeImage
                                if (tmp.isNotEmpty()) {
                                    setTypeText(
                                        engine, pages, tmp.toString(),
                                        isTitle = false, paragraphNum = paragraphSeq,
                                        isFirstLine = isFirstSegment,
                                        imgList = embeddedImages,
                                    )
                                    tmp.clear()
                                    embeddedImages.clear()
                                    isFirstSegment = false
                                }
                                if (setTypeImage(engine, pages, img, imageStyle, imageResolver)) {
                                    isSetTypedImage = true
                                }
                            }
                        } else {
                            tmp.append(char)
                        }
                    }
                    if (tmp.isNotEmpty()) {
                        setTypeText(
                            engine, pages, tmp.toString(),
                            isTitle = false, paragraphNum = paragraphSeq,
                            isFirstLine = !hasNonEmbeddedImage && isFirstSegment,
                            imgList = embeddedImages,
                        )
                    }
                }

                if (engine.pendingTextPage.lines.isNotEmpty()) {
                    engine.pendingTextPage.lines.last().isParagraphEnd = true
                }
                engine.stringBuilder.append("\n")
                lineStartIndex = lineEndIndex + 1
            }
        }
        return paragraphSeq
    }

    /**
     * 排版一段文本（标题或正文段）。
     *
     * 流程对照 app 端 [TextChapterLayout.setTypeText]：
     * 1. 测量字素簇宽度（[TextMeasurer.measureGlyphWidths] + [measureTextSplit] 聚簇）
     * 2. [ZhLineBreaker] 断行（按 [useZhLayout] 决定是否启用；否则按累积宽度退化为最简断行）
     * 3. 对每行调 [TextLayoutEngine.prepareNextPageIfNeed] 检查分页
     * 4. 段落首行几何缩进（[indentCharWidth] > 0 且 [isFirstLine] 且非标题）：[addIndentChars]
     *    替代前 [paragraphIndent.length] 个字，剩余 words/widths 从 indentLength 开始
     * 5. 末行 / 标题行调 [TextLayoutEngine.addCharsToLineNatural]（自然行），
     *    中间行调 [TextLayoutEngine.addCharsToLineMiddle]（两端对齐，受 [textFullJustify] 控制）
     * 6. 更新行几何（`lineTop` / `lineBottom` / `lineBase`）+ `engine.pendingTextPage.addLine`
     *    + `engine.durY += textHeight * lineSpacingExtra`
     * 7. [calcChapterPosition] 写入 [TextLine.chapterPosition]（朗读高亮定位用）
     *
     * @param isFirstLine 是否段落首行（影响几何缩进；标题传 true 但 isTitle=true 时不缩进）
     * @param imgList 内联图片队列（TEXT 风格图片占位符由 ColumnFactory 从此队列取）；
     *   null=纯文本行
     */
    private suspend fun setTypeText(
        engine: TextLayoutEngine,
        pages: ArrayList<TextPage>,
        text: String,
        isTitle: Boolean,
        paragraphNum: Int,
        isFirstLine: Boolean = true,
        imgList: ArrayDeque<ImgData>? = null,
    ) {
        if (text.isEmpty()) return
        val widthsArray = FloatArray(text.length)
        measurer.measureGlyphWidths(text, widthsArray)
        val split = measureTextSplit(text, widthsArray)
        val words = split.words
        val widths = split.widths
        if (words.isEmpty()) return

        // 断行：useZhLayout=true 用 ZhLineBreaker（避头尾），否则退化为按累积宽度的最简断行
        val lineRanges: List<IntRange> = if (useZhLayout) {
            breakByZh(words, widths)
        } else {
            breakByAccumulateWidth(words, widths)
        }

        val useGeomIndent = indentCharWidth > 0f
        for ((lineIdx, range) in lineRanges.withIndex()) {
            val lineWords = words.subList(range.first, range.last + 1)
            val lineWidths = widths.subList(range.first, range.last + 1)
            if (lineWords.isEmpty()) continue

            val textLine = TextLine(isTitle = isTitle)
            textLine.paragraphNum = paragraphNum
            // 分页判定：当前行加进去超过 visibleHeight 时换页
            engine.prepareNextPageIfNeed(engine.durY + textHeight)

            // 段落首行几何缩进：用 addIndentChars 等宽 TextColumn 替代字符串拼接的缩进字符
            // （indentCharWidth>0 启用；与 app 端 addIndentChars 几何计算对齐）
            val needsIndent = useGeomIndent && isFirstLine && lineIdx == 0 && !isTitle
            val (adjustedWords, adjustedWidths, indentStartX) = if (needsIndent) {
                val indentLength = paragraphIndent.length.coerceAtMost(lineWords.size)
                val indentX = addIndentChars(engine.absStartX, textLine, indentLength)
                Triple(
                    lineWords.subList(indentLength, lineWords.size),
                    lineWidths.subList(indentLength, lineWidths.size),
                    indentX,
                )
            } else {
                Triple(lineWords, lineWidths, 0f)
            }

            val desiredWidth = adjustedWidths.fastSum()
            val isLastLine = lineIdx == lineRanges.lastIndex
            // 标题居中（与 app 端 isMiddleTitle 行为对齐，简化版标题一律居中）
            val startX = if (isTitle) (visibleWidth - desiredWidth) / 2 else indentStartX

            if (isLastLine || isTitle || !textFullJustify) {
                // 自然行（标题/末行/未开启两端对齐）
                engine.addCharsToLineNatural(textLine, adjustedWords, startX, adjustedWidths, imgList)
            } else {
                // 两端对齐
                engine.addCharsToLineMiddle(
                    textLine, adjustedWords, measurer, desiredWidth, startX, adjustedWidths, imgList,
                )
            }

            // 双页模式标记左右栏（对应 app 端 updateTextLineInfo 的 isLeftLine 设置）
            if (doublePage) textLine.isLeftLine = engine.absStartX < viewWidth / 2

            // 更新行几何 + 加入 pendingTextPage（与 app 端 updateTextLineInfo 对齐）
            val lineText = lineWords.joinToString("")
            textLine.text = lineText
            textLine.lineTop = paddingTop + engine.durY
            textLine.lineBottom = textLine.lineTop + textHeight
            textLine.lineBase = textLine.lineBottom - descent
            // 朗读高亮：写入章节内字符偏移（替代原 stringBuilder.length 近似）
            textLine.chapterPosition = calcChapterPosition(pages, engine.stringBuilder.length)
            textLine.pagePosition = engine.stringBuilder.length
            engine.stringBuilder.append(lineText)
            engine.pendingTextPage.addLine(textLine)
            engine.durY += textHeight * lineSpacingExtra
            if (engine.pendingTextPage.height < engine.durY) {
                engine.pendingTextPage.height = engine.durY
            }
        }
    }

    /**
     * 排版一张块状图片（SINGLE/FULL/DEFAULT 风格）。
     *
     * 流程对照 app 端 [TextChapterLayout.setTypeImage]：
     * 1. [imageResolver] 为 null 或图片未缓存 → 跳过（返回 false，退化为准文本）
     * 2. 取原始尺寸，[getFitSize] 等比缩放进可视区
     * 3. 按风格调整：
     *    - FULL：宽满可视区，高超则换页并重新 fit
     *    - SINGLE：换新页，垂直居中
     *    - DEFAULT：高度超当前页剩余空间则换页
     * 4. [addImageLine] 构造 [ImageColumn] 入列
     * 5. SINGLE 模式占满本页剩余空间（durY=visibleHeight），其他模式按段间距累积
     *
     * @return true=图片已排版入列；false=跳过（无 resolver / 未缓存 / 无效尺寸）
     */
    private suspend fun setTypeImage(
        engine: TextLayoutEngine,
        pages: ArrayList<TextPage>,
        img: ImgData,
        imageStyle: String?,
        imageResolver: ImageResolver?,
    ): Boolean {
        if (imageResolver == null) return false
        if (!imageResolver.isImageExist(img.src)) return false

        val rawSize = imageResolver.getImageSize(img.src)
        if (rawSize.width <= 0 || rawSize.height <= 0) return false

        var (width, height) = getFitSize(
            rawSize.width.toFloat(),
            rawSize.height.toFloat(),
            visibleWidth.toFloat(),
            visibleHeight.toFloat(),
        )

        val styleUpper = imageStyle?.uppercase()
        when (styleUpper) {
            Book.imgStyleFull -> {
                width = visibleWidth.toFloat()
                height = rawSize.height.toFloat() * visibleWidth / rawSize.width
                if (height > visibleHeight - engine.durY) {
                    val fit = getFitSize(width, height, visibleWidth.toFloat(), visibleHeight.toFloat())
                    width = fit.first
                    height = fit.second
                    engine.prepareNextPageIfNeed(engine.durY + height)
                }
            }
            Book.imgStyleSingle -> {
                if (engine.durY > 0f || engine.pendingTextPage.lines.isNotEmpty()) {
                    engine.prepareNextPageIfNeed()
                }
                // 单图模式占位图垂直居中
                engine.durY = (visibleHeight - height) / 2f
            }
            else -> engine.prepareNextPageIfNeed(engine.durY + height)
        }

        addImageLine(engine, pages, img, width, height)
        if (styleUpper == Book.imgStyleSingle) {
            // 单图模式占满本页剩余空间，确保下一内容从新页开始
            engine.durY = visibleHeight.toFloat()
        } else {
            engine.durY += textHeight * paragraphSpacing / 10f
        }
        return true
    }

    /**
     * 构造图片行并入列。对照 app 端 [TextChapterLayout.addImageLine]。
     *
     * - [TextLine.isImage] = true，text 占位为空格（与 app 端一致，供 charSize 计算用）
     * - [ImageColumn] 水平居中（`startX = (visibleWidth - width) / 2`）
     * - [TextLine.chapterPosition] / [pagePosition] 走 [calcChapterPosition]（朗读高亮对齐）
     */
    private fun addImageLine(
        engine: TextLayoutEngine,
        pages: ArrayList<TextPage>,
        img: ImgData,
        width: Float,
        height: Float,
    ) {
        val textLine = TextLine(isImage = true)
        textLine.text = " "
        textLine.lineTop = engine.durY + paddingTop
        textLine.lineBottom = engine.durY + height + paddingTop
        val startX = if (visibleWidth > width) (visibleWidth - width) / 2f else 0f
        textLine.addColumn(
            ImageColumn(
                engine.absStartX + startX,
                engine.absStartX + startX + width,
                img.src,
                img.onclick,
            ),
        )
        if (doublePage) textLine.isLeftLine = engine.absStartX < viewWidth / 2
        textLine.chapterPosition = calcChapterPosition(pages, engine.stringBuilder.length)
        textLine.pagePosition = engine.stringBuilder.length
        engine.stringBuilder.append(" ")
        engine.pendingTextPage.addLine(textLine)
        engine.durY += height
    }

    /**
     * 计算行的章节内字符偏移（朗读高亮定位用）。
     *
     * 对照 app 端 [TextChapterLayout.calcTextLinePosition] 的 chapterPosition 部分：
     * `上一页末行 chapterPosition + charSize + 段末换行(1) + 当前 stringBuilder.length`。
     *
     * 朗读时 [TextPage.containPos] 用 `lines.first().chapterPosition` 判断章节字符位置
     * 是否在本页，[TextPage.upPageAloudSpan] 按行 `text.length + 段末换行` 累加定位
     * 朗读起点——依赖 [TextLine.chapterPosition] 是真实章节偏移，而非 stringBuilder.length 近似。
     */
    private fun calcChapterPosition(
        pages: ArrayList<TextPage>,
        sbLength: Int,
    ): Int {
        val lastPageLastLine = pages.lastOrNull()?.lines?.lastOrNull()
        val base = lastPageLastLine?.run {
            chapterPosition + charSize + if (isParagraphEnd) 1 else 0
        } ?: 0
        return base + sbLength
    }

    /**
     * 段落首行几何缩进：用 [indentChar] 构造 [indentLength] 个等宽 [TextColumn] 加入 [textLine]，
     * 返回缩进总宽度（px）。对照 app 端 [TextChapterLayout.addIndentChars]。
     *
     * 与字符串拼接的差异：字符串拼接把 `paragraphIndent`（如 `　　`）作为普通字符测量宽度
     * （可能因测量器精度偏差），几何缩进则用固定 [indentCharWidth] 保证缩进宽度精确可控。
     *
     * @param absStartX 列绝对起始 X（含 paddingLeft）
     * @param textLine 当前行（缩进列加入此行）
     * @param indentLength 缩进字符数（= [paragraphIndent.length]，截断到行字数）
     * @return 缩进总宽度（px），作为后续正文的 startX
     */
    private fun addIndentChars(absStartX: Int, textLine: TextLine, indentLength: Int): Float {
        var x = 0f
        repeat(indentLength) {
            val x1 = x + indentCharWidth
            textLine.addColumn(TextColumn(absStartX + x, absStartX + x1, indentChar))
            x = x1
            textLine.indentWidth = x
        }
        textLine.indentSize = indentLength
        return x
    }

    /**
     * 用 [ZhLineBreaker] 断行：返回每行在 words 列表中的 cluster index 区间（闭区间）。
     *
     * ZhLineBreaker 输出：
     * - [ZhLineBreaker.lineStartCluster][i] = 第 i 行起点 cluster index
     * - [ZhLineBreaker.lineStartCluster][i + 1] = 第 i 行终点 cluster index（exclusive）
     * - [ZhLineBreaker.lineCount] = 总行数
     *
     * 注意 lineStartCluster 数组容量至少 lineCount + 1（init 中 lineStart[line+1] = ...
     * 会把「下一行起点」写入；最后一行也写一次）。
     */
    private fun breakByZh(
        words: List<String>,
        widths: List<Float>,
    ): List<IntRange> {
        val breaker = ZhLineBreaker(
            words = words,
            widths = widths,
            indentSize = 0, // 缩进已在调用前拼接到 text 前部（几何缩进则在 setTypeText 内 addIndentChars 处理）
            width = visibleWidth,
            cnCharWidth = measurer.textSizePx,
            letterSpacingPx = measurer.letterSpacingPx,
        )
        val result = arrayListOf<IntRange>()
        for (i in 0 until breaker.lineCount) {
            val start = breaker.lineStartCluster[i]
            val end = breaker.lineStartCluster[i + 1] - 1
            if (end >= start) result.add(start..end)
        }
        return result
    }

    /**
     * 退化断行：按 [visibleWidth] 累积字宽，超过即断。
     *
     * 用于 [useZhLayout]=false 场景，简化逻辑不处理避头尾。
     * 返回每行在 words 列表中的 cluster index 区间（闭区间）。
     */
    private fun breakByAccumulateWidth(
        words: List<String>,
        widths: List<Float>,
    ): List<IntRange> {
        val result = arrayListOf<IntRange>()
        var lineStart = 0
        var lineWidth = 0f
        for (i in words.indices) {
            val cw = widths[i]
            if (lineWidth + cw > visibleWidth && i > lineStart) {
                result.add(lineStart..(i - 1))
                lineStart = i
                lineWidth = 0f
            }
            lineWidth += cw
        }
        if (lineStart < words.size) {
            result.add(lineStart..(words.size - 1))
        }
        return result
    }

    /**
     * 列工厂：按 [reviewChar] / [srcReplaceChar] / 普通字符分别构造 [ReviewColumn] /
     * [ImageColumn] / [TextColumn]。对照 app 端 [TextChapterLayout.createColumn]。
     *
     * - [reviewChar]（段评占位符）→ [ReviewColumn]（带 [currentParagraphIndex] 和段评计数）
     * - [srcReplaceChar]（图片占位符）且 imgList 非空 → [ImageColumn]（从 imgList 取出 src/onclick）
     * - 其他 → [TextColumn]
     *
     * 段评计数从 [reviewCountMap] 按 [currentParagraphIndex] 查询（null 或 0 时构造 count=0）。
     * 图片占位符从 [imgList] removeFirst 取出（副作用，与 app 端 `imgList.removeFirst()` 一致）。
     */
    private inner class SimpleColumnFactory : ColumnFactory {
        override fun createColumn(
            absStartX: Int,
            char: String,
            xStart: Float,
            xEnd: Float,
            imgList: MutableList<ImgData>?,
        ): BaseColumn = when {
            reviewChar.isNotEmpty() && char == reviewChar -> {
                val cnt = reviewCountMap?.get(currentParagraphIndex) ?: 0
                ReviewColumn(absStartX + xStart, absStartX + xEnd, currentParagraphIndex, cnt)
            }
            srcReplaceChar.isNotEmpty() && imgList != null && char == srcReplaceChar -> {
                val img = imgList.removeFirstOrNull()
                if (img != null) {
                    // 严禁在此处同步下载图片，下载由 ImageResolver 实现侧统一管理
                    ImageColumn(absStartX + xStart, absStartX + xEnd, img.src, img.onclick)
                } else {
                    TextColumn(absStartX + xStart, absStartX + xEnd, char)
                }
            }
            else -> TextColumn(absStartX + xStart, absStartX + xEnd, char)
        }
    }

    /**
     * 简化版 [TextChapterRef]：仅承载 [pageSize] 一个字段。
     *
     * app 端 [io.legado.app.ui.book.read.page.entities.TextChapter] 重度依赖
     * Book/BookChapter/ReplaceRule 等 entity，无法下沉 commonMain；
     * commonMain 的 [TextPage] 通过 [TextChapterRef] 接口解耦，本类作为最简实现注入。
     */
    private class SimpleTextChapterRef(override val pageSize: Int) : TextChapterRef
}
