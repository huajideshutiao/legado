package io.legado.app.ui.book.read.page.provider

import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import io.legado.app.constant.AppLog
import io.legado.app.data.entities.Book
import io.legado.app.data.entities.BookChapter
import io.legado.app.help.book.BookContent
import io.legado.app.help.book.BookHelp
import io.legado.app.help.book.getBookSource
import io.legado.app.help.config.ReadBookConfig
import io.legado.app.help.coroutine.Coroutine
import io.legado.app.model.ImageProvider
import io.legado.app.model.ReadBook
import io.legado.app.ui.book.read.page.entities.TextChapter
import io.legado.app.ui.book.read.page.entities.TextLine
import io.legado.app.ui.book.read.page.entities.TextPage
import io.legado.app.ui.book.read.page.entities.column.BaseColumn
import io.legado.app.ui.book.read.page.entities.column.ImageColumn
import io.legado.app.ui.book.read.page.entities.column.ReviewColumn
import io.legado.app.ui.book.read.page.entities.column.TextColumn
import io.legado.app.ui.book.read.page.entities.column.refreshLayout
import io.legado.app.ui.book.read.page.render.upTopBottom
import io.legado.app.utils.dpToPx
import io.legado.app.utils.fastSum
import io.legado.app.utils.splitNotBlank
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers.IO
import kotlinx.coroutines.Dispatchers.Main
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.LinkedList

/**
 * 原 TextChapterLayout 内部 `data class Img(src, style, onclick)` 改为引用 commonMain 的 [ImgData]，
 * 供 [TextLayoutEngine] / [ColumnFactory] 接口跨端引用。本 typealias 仅文件内可见，
 * 内部代码沿用 `Img(...)` 构造与 `LinkedList<Img>` 类型声明不变。
 */
private typealias Img = ImgData

class TextChapterLayout(
    scope: CoroutineScope,
    private val textChapter: TextChapter,
    private val textPages: ArrayList<TextPage>,
    private val book: Book,
    private val bookContent: BookContent,
    private val reviewCountDeferred: Deferred<Map<Int, Int>?>? = null,
) : TextLayoutCallback, ColumnFactory {

    // 排版开始时非阻塞 peek 段评数 map：已就绪→用上；未就绪→留 null，等排版结束再决定要不要重排。
    // 暴露 internal 是为了让 ReadBook 在排版完成后判断「段评数是不是后到的」。
    @Volatile
    var reviewCountMap: Map<Int, Int>? = null
        private set

    @Volatile
    private var listener: LayoutProgressListener? = textChapter

    private val paddingLeft = ChapterProvider.paddingLeft
    private val paddingTop = ChapterProvider.paddingTop

    private val titlePaint = ChapterProvider.titlePaint
    private val titlePaintTextHeight = ChapterProvider.titlePaintTextHeight
    private val titlePaintDescent = ChapterProvider.titlePaintDescent

    private val contentPaint = ChapterProvider.contentPaint
    private val contentPaintTextHeight = ChapterProvider.contentPaintTextHeight
    private val contentPaintDescent = ChapterProvider.contentPaintDescent

    // 平台测量收口：Paint 在本次排版生命周期内稳定，测量器一次性建好，热路径按 paint 身份取用，不新分配。
    private val titleMeasurer = AndroidTextMeasurer(titlePaint)
    private val contentMeasurer = AndroidTextMeasurer(contentPaint)

    private val titleTopSpacing = ChapterProvider.titleTopSpacing
    private val titleBottomSpacing = ChapterProvider.titleBottomSpacing
    private val lineSpacingExtra = ChapterProvider.lineSpacingExtra
    private val paragraphSpacing = ChapterProvider.paragraphSpacing

    private val visibleHeight = ChapterProvider.visibleHeight
    private val visibleWidth = ChapterProvider.visibleWidth

    private val viewWidth = ChapterProvider.viewWidth
    private val doublePage = ChapterProvider.doublePage
    private val indentCharWidth = ChapterProvider.indentCharWidth

    private val paragraphIndent = ReadBookConfig.paragraphIndent
    private val titleMode = ReadBookConfig.titleMode
    private val useZhLayout = ReadBookConfig.useZhLayout
    private val isMiddleTitle = ReadBookConfig.isMiddleTitle
    private val textFullJustify = ReadBookConfig.textFullJustify

    /**
     * 排版引擎（纯算术面下沉 shared commonMain）。
     * 持有原 TextChapterLayout 的 engine.durY/absStartX/engine.pendingTextPage/engine.stringBuilder 状态，
     * 8 个排版方法（addCharsToLineMiddle/justify 族/exceed/engine.prepareNextPageIfNeed 等）下沉 engine。
     * 列构造（createColumn）与平台回调（onPageCompleted/ensureActive）通过本类实现
     * ColumnFactory/TextLayoutCallback 接口注入。
     */
    private val engine = TextLayoutEngine(
        visibleWidth = visibleWidth,
        visibleHeight = visibleHeight,
        viewWidth = viewWidth,
        paddingLeft = paddingLeft,
        doublePage = doublePage,
        textFullJustify = textFullJustify,
        columnFactory = this,
        callback = this,
    )

    private val bookChapter inline get() = textChapter.chapter
    private val displayTitle inline get() = textChapter.title
    private val chaptersSize inline get() = textChapter.chaptersSize

    private var floatArray = FloatArray(128)

    // 当前 setTypeText 处理段落号；createColumn 把 reviewChar 转 ReviewColumn 时回填。
    // 0 表示章节级（标题末），>=1 是正文段落号（与 TextLine.paragraphNum 对齐）。
    private var currentParagraphIndex = 0

    private var isCompleted = false
    private val job: Coroutine<*>

    var exception: Throwable? = null

    var channel = Channel<TextPage>(Channel.UNLIMITED)
    private val pageChangeChannel = Channel<Unit>(Channel.CONFLATED)
    private val srcToPagesMap = java.util.concurrent.ConcurrentHashMap<String, MutableSet<Int>>()

    fun notifyPageChanged() {
        pageChangeChannel.trySend(Unit)
    }

    init {
        job = Coroutine.async(
            scope,
            start = CoroutineStart.LAZY,
            executeContext = IO
        ) {
            // 非阻塞 peek：段评数 IO 已完成就当场用上；没完成就先按「没段评」排版，
            // 后续 ReadBook 会在 deferred 完成时触发重排——保证「即开即用」
            reviewCountMap = reviewCountDeferred?.takeIf { it.isCompleted }?.await()
            val parsedLines = ChapterContentParser.parse(bookContent)
            val imageStyle = book.config.imageStyle
            val isSingle = imageStyle.equals(Book.imgStyleSingle, true)
            val allImages = parsedLines.flatMap { it.images }.map { it.src }.distinct()

            if (!isSingle && allImages.isNotEmpty()) {
                val bookSource = book.getBookSource()
                for (src in allImages) {
                    ensureActive()
                    if (!BookHelp.isImageExist(book, src)) {
                        BookHelp.saveImage(bookSource, book, src, bookChapter)
                    }
                }
            }

            if (isSingle && allImages.isNotEmpty()) {
                launch {
                    val bookSource = book.getBookSource()
                    val downloaded =
                        allImages.filter { BookHelp.isImageExist(book, it) }.toMutableSet()

                    val imageToIndexMap = allImages.withIndex().associate { it.value to it.index }

                    for (sig in pageChangeChannel) {
                        delay(300)
                        val isDur = bookChapter.index == ReadBook.durChapterIndex
                        val durPageIdx = if (isDur) ReadBook.durPageIndex else 0

                        val activeImageIndices = mutableSetOf<Int>()
                        for (i in (durPageIdx - 1)..(durPageIdx + 1)) {
                            if (i in 0 until textPages.size) {
                                textPages[i].lines.forEach { line ->
                                    if (line.isImage) {
                                        line.columns.forEach { col ->
                                            if (col is ImageColumn) {
                                                imageToIndexMap[col.src]?.let {
                                                    activeImageIndices.add(it)
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                        }

                        if (activeImageIndices.isEmpty() && durPageIdx == 0) {
                            for (i in 0..2) if (i in allImages.indices) activeImageIndices.add(i)
                        }

                        val downloadWindow = mutableSetOf<String>()
                        activeImageIndices.forEach { centerIdx ->
                            for (offset in -3..3) {
                                val targetIdx = centerIdx + offset
                                if (targetIdx in allImages.indices) {
                                    downloadWindow.add(allImages[targetIdx])
                                }
                            }
                        }

                        val newDownloaded = mutableListOf<String>()
                        for (src in downloadWindow) {
                            ensureActive()
                            if (src !in downloaded) {
                                BookHelp.saveImage(bookSource, book, src, bookChapter)
                                if (BookHelp.isImageExist(book, src)) {
                                    downloaded.add(src)
                                    newDownloaded.add(src)
                                    ImageProvider.bitmapLruCache.remove(
                                        BookHelp.getImage(book, src).absolutePath
                                    )
                                    ImageProvider.getImage(book, src, visibleWidth)
                                }
                            }
                        }

                        if (newDownloaded.isNotEmpty()) {
                            val affectedPages = mutableSetOf<Int>()
                            newDownloaded.forEach { src ->
                                srcToPagesMap[src]?.let { affectedPages.addAll(it) }
                            }

                            affectedPages.forEach { pIdx ->
                                if (pIdx in textPages.indices) {
                                    val page = textPages[pIdx]
                                    var pageUpdated = false
                                    page.lines.forEach { line ->
                                        if (line.isImage) {
                                            line.columns.forEach { col ->
                                                if (col is ImageColumn && col.src in downloaded) {
                                                    if (col.refreshLayout(
                                                            book,
                                                            isSingle
                                                        )
                                                    ) pageUpdated = true
                                                }
                                            }
                                        }
                                    }
                                    if (pageUpdated) page.invalidate()
                                }
                            }
                            if (isDur) withContext(Main) { ReadBook.callBack?.contentLoadFinish() }
                        }
                        if (downloaded.size >= allImages.size) break
                    }
                }
            }

            getTextChapter(book, bookChapter, displayTitle, bookContent, parsedLines)
        }.onError {
            exception = it
            onException(it)
        }.onCancel {
            channel.cancel()
        }.onFinally {
            isCompleted = true
        }
        job.start()
    }

    fun cancel() {
        job.cancel()
        listener = null
    }

    override fun onPageCompleted() {
        val textPage = engine.pendingTextPage
        val pIdx = textPages.size
        textPage.index = pIdx

        // 记录图片到页面的映射
        textPage.lines.forEach { line ->
            if (line.isImage) {
                line.columns.forEach { col ->
                    if (col is ImageColumn) {
                        srcToPagesMap.getOrPut(col.src) { java.util.concurrent.ConcurrentHashMap.newKeySet() }
                            .add(pIdx)
                    }
                }
            }
        }

        textPage.chapterIndex = bookChapter.index
        textPage.chapterSize = chaptersSize
        textPage.title = displayTitle
        textPage.doublePage = doublePage
        textPage.paddingTop = paddingTop
        // 注入 upLinesPosition / isVisible 所需几何参数（数据化前由 ChapterProvider/ReadBookConfig 静态提供）
        textPage.textBottomJustify = ReadBookConfig.textBottomJustify
        textPage.visibleHeight = visibleHeight
        textPage.visibleBottom = ChapterProvider.visibleBottom
        textPage.contentPaintTextHeight = contentPaintTextHeight
        textPage.lineSpacingExtra = lineSpacingExtra
        textPage.isCompleted = true
        textPage.textChapter = textChapter
        textPage.upLinesPosition()
        textPage.upRenderHeight()
        textPages.add(textPage)
        channel.trySend(textPage)
        try {
            listener?.onLayoutPageCompleted(textPages.lastIndex, textPage)
        } catch (e: Exception) {
            e.printStackTrace()
            AppLog.put("调用布局进度监听回调出错\n${e.localizedMessage}", e)
        }
    }

    /**
     * TextLayoutCallback.ensureActive 实现：engine.prepareNextPageIfNeed 中调用，
     * 桥接到 kotlinx.coroutines 的 currentCoroutineContext().ensureActive()。
     */
    override suspend fun ensureActive() {
        currentCoroutineContext().ensureActive()
    }

    private fun onCompleted() {
        channel.close()
        try {
            listener?.onLayoutCompleted()
        } catch (e: Exception) {
            e.printStackTrace()
            AppLog.put("调用布局进度监听回调出错\n${e.localizedMessage}", e)
        } finally {
            listener = null
        }
    }

    private fun onException(e: Throwable) {
        channel.close(e)
        if (e is CancellationException) {
            listener = null
            return
        }
        try {
            listener?.onLayoutException(e)
        } catch (e: Exception) {
            e.printStackTrace()
            AppLog.put("调用布局进度监听回调出错\n${e.localizedMessage}", e)
        } finally {
            listener = null
        }
    }

    private suspend fun getTextChapter(
        book: Book,
        bookChapter: BookChapter,
        displayTitle: String,
        bookContent: BookContent,
        parsedLines: List<ChapterContentParser.ParsedLine>
    ) {
        val contents = bookContent.textList
        val imageStyle = book.config.imageStyle
        val isSingleStyle = imageStyle.equals(Book.imgStyleSingle, true)
        val isTextImageStyle = imageStyle.equals(Book.imgStyleText, true)
        // 当前书源启用段评 + 配了 reviewCountRule 才追加 ▨ 占位符
        val enableReview = ReadBook.bookSource?.let {
            it.enabledReview && !it.reviewRule.reviewCountRule.isNullOrBlank()
        } == true

        if (titleMode != 2 || contents.isEmpty()) {
            displayTitle.splitNotBlank("\n").forEach { text ->
                setTypeText(
                    book,
                    if (enableReview) text + ChapterProvider.reviewChar else text,
                    titlePaint,
                    titlePaintTextHeight,
                    titlePaintDescent,
                    imageStyle,
                    isTitle = true,
                    emptyContent = contents.isEmpty()
                )
                engine.pendingTextPage.lines.last().isParagraphEnd = true
                engine.stringBuilder.append("\n")
            }
            engine.durY += titleBottomSpacing
            if (isSingleStyle && engine.pendingTextPage.lines.isNotEmpty() && contents.isNotEmpty()) {
                engine.prepareNextPageIfNeed()
            }
        }

        var isSetTypedImage = false
        // 逻辑段序号：按源文本 '\n' 切分递增，与书源段评接口约定的 paragraphIndex 对齐
        // ≠ TextLine.paragraphNum（视觉段序号，由 calcTextLinePosition 在排版后基于 isParagraphEnd 递增）
        var paragraphSeq = 0
        parsedLines.forEach { parsedLine ->
            currentCoroutineContext().ensureActive()
            val contentText = parsedLine.text
            val imgList = LinkedList<Img>()
            parsedLine.images.forEach { imgList.add(Img(it.src, it.style ?: "", it.onclick ?: "")) }

            var lineStartIndex = 0
            val contentLength = contentText.length
            while (lineStartIndex < contentLength) {
                var lineEndIndex = contentText.indexOf('\n', lineStartIndex)
                if (lineEndIndex == -1) lineEndIndex = contentLength

                val rawLine = contentText.substring(lineStartIndex, lineEndIndex)
                // 判断是否以图片开头（忽略首部空白）
                val startsWithImage =
                    rawLine.trimStart(' ', '　').startsWith(ChapterProvider.srcReplaceChar)

                val line = if (startsWithImage && !isTextImageStyle) {
                    // 如果开头是块状图片（SINGLE/FULL/DEFAULT），严禁添加段落缩进
                    // 否则缩进空格会占据一行/一页，导致图片前出现莫名其妙的空行/空页
                    rawLine.trimStart(' ', '　')
                } else if (rawLine.startsWith("　　")) {
                    paragraphIndent + rawLine.substring(2)
                } else if (paragraphIndent.isNotEmpty() && rawLine.isNotEmpty()) {
                    paragraphIndent + rawLine.trimStart(' ', '　')
                } else {
                    rawLine
                }

                paragraphSeq++
                val reviewIndex = paragraphSeq
                // 仅给「有计数」的非空段落追加 ▨——计数 map 为空时整章一律不挂气泡，
                // 避免给每段都预留占位破坏排版
                val reviewCountForLine = reviewCountMap?.get(reviewIndex) ?: 0
                val lineWithReview =
                    if (enableReview && line.isNotEmpty() && reviewCountForLine > 0) {
                    line + ChapterProvider.reviewChar
                } else line

                if (isTextImageStyle || imgList.isEmpty()) {
                    setTypeText(
                        book,
                        lineWithReview,
                        contentPaint,
                        contentPaintTextHeight,
                        contentPaintDescent,
                        imageStyle,
                        imgList = imgList,
                        paragraphIndex = reviewIndex
                    )
                } else {
                    if (isSingleStyle && isSetTypedImage) {
                        isSetTypedImage = false
                        engine.prepareNextPageIfNeed()
                    }
                    val embeddedImages = LinkedList<Img>()
                    val hasNonEmbeddedImage = line.contains(ChapterProvider.srcReplaceChar)
                    var isFirstSegment = true
                    val tmp = StringBuilder()
                    lineWithReview.forEach { char ->
                        currentCoroutineContext().ensureActive()
                        if (char == ChapterProvider.srcReplaceChar[0]) {
                            val img = imgList.pollFirst() ?: return@forEach
                            if (img.style.equals("TEXT", true)) {
                                embeddedImages.add(img)
                                tmp.append(char)
                            } else {
                                if (tmp.isNotEmpty()) {
                                    setTypeText(
                                        book,
                                        tmp.toString(),
                                        contentPaint,
                                        contentPaintTextHeight,
                                        contentPaintDescent,
                                        "TEXT",
                                        isFirstLine = isFirstSegment,
                                        imgList = embeddedImages,
                                        paragraphIndex = reviewIndex
                                    )
                                    tmp.clear(); embeddedImages.clear(); isFirstSegment = false
                                }
                                setTypeImage(book, img, contentPaintTextHeight, imageStyle)
                                isSetTypedImage = true
                            }
                        } else tmp.append(char)
                    }
                    if (tmp.isNotEmpty()) {
                        setTypeText(
                            book,
                            tmp.toString(),
                            contentPaint,
                            contentPaintTextHeight,
                            contentPaintDescent,
                            "TEXT",
                            isFirstLine = !hasNonEmbeddedImage && isFirstSegment,
                            imgList = embeddedImages,
                            paragraphIndex = reviewIndex
                        )
                    }
                }
                if (engine.pendingTextPage.lines.isNotEmpty()) engine.pendingTextPage.lines.last().isParagraphEnd =
                    true
                engine.stringBuilder.append("\n")
                lineStartIndex = lineEndIndex + 1
            }
        }

        val textPage = engine.pendingTextPage
        val endPadding = 20.dpToPx()
        if (textPage.height < engine.durY + endPadding) textPage.height =
            engine.durY + endPadding else textPage.height += endPadding
        textPage.text = engine.stringBuilder.toString()
        onPageCompleted()
        onCompleted()
    }

    private suspend fun setTypeImage(book: Book, img: Img, textHeight: Float, imageStyle: String?) {
        val styleUpper = imageStyle?.uppercase()
        val isSingle = styleUpper == Book.imgStyleSingle

        // 始终调用getImageSize触发缓存下载，内部已有isImageExist快速路径
        val rawSize = ImageProvider.getImageSize(book, img.src, ReadBook.bookSource)

        if (rawSize.width <= 0 || rawSize.height <= 0) return

        var (width, height) = getFitSize(
            rawSize.width.toFloat(),
            rawSize.height.toFloat(),
            visibleWidth.toFloat(),
            visibleHeight.toFloat()
        )

        when (styleUpper) {
            Book.imgStyleFull -> {
                width = visibleWidth.toFloat()
                height = rawSize.height.toFloat() * visibleWidth / rawSize.width
                if (height > visibleHeight - engine.durY) {
                    val fit =
                        getFitSize(width, height, visibleWidth.toFloat(), visibleHeight.toFloat())
                    width = fit.first; height = fit.second
                    engine.prepareNextPageIfNeed(engine.durY + height)
                }
            }

            Book.imgStyleSingle -> {
                if (engine.durY > 0f || engine.pendingTextPage.lines.isNotEmpty()) engine.prepareNextPageIfNeed()
                // 占位图也居中显示
                engine.durY = (visibleHeight - height) / 2f
            }

            else -> engine.prepareNextPageIfNeed(engine.durY + height)
        }

        addImageLine(img, width, height)
        if (isSingle) {
            // 单图模式占满本页剩余空间，确保下一内容从新页开始
            engine.durY = visibleHeight.toFloat()
        } else {
            engine.durY += textHeight * paragraphSpacing / 10f
        }
    }

    private fun addImageLine(img: Img, width: Float, height: Float) {
        val textLine = TextLine(isImage = true)
        textLine.text = " "
        textLine.lineTop = engine.durY + paddingTop
        val lineBottom = engine.durY + height + paddingTop
        textLine.lineBottom = lineBottom
        val startX = if (visibleWidth > width) (visibleWidth - width) / 2f else 0f
        textLine.addColumn(
            ImageColumn(
                engine.absStartX + startX,
                engine.absStartX + startX + width,
                img.src,
                img.onclick
            )
        )
        calcTextLinePosition(textPages, textLine, engine.stringBuilder.length)
        engine.stringBuilder.append(" ")
        engine.pendingTextPage.addLine(textLine)
        engine.durY += height
    }

    private suspend fun setTypeText(
        book: Book,
        text: String,
        textPaint: TextPaint,
        textHeight: Float,
        descent: Float,
        imageStyle: String?,
        isTitle: Boolean = false,
        isFirstLine: Boolean = true,
        emptyContent: Boolean = false,
        imgList: LinkedList<Img>? = null,
        paragraphIndex: Int = 0
    ) {
        currentParagraphIndex = paragraphIndex
        val measurer = if (textPaint === titlePaint) titleMeasurer else contentMeasurer
        val widthsArray = allocateFloatArray(text.length)
        measurer.measureGlyphWidths(text, widthsArray)
        val splitResult = measureTextSplit(text, widthsArray)
        val layout = if (useZhLayout) ZhLayout(
            text,
            textPaint,
            visibleWidth,
            splitResult.words,
            splitResult.widths,
            if (isFirstLine) paragraphIndent.length else 0,
            measurer
        )
        else StaticLayout(
            text,
            textPaint,
            visibleWidth,
            Layout.Alignment.ALIGN_NORMAL,
            0f,
            0f,
            true
        )

        engine.durY = calculateInitialYPosition(layout, textHeight, emptyContent, isTitle, imageStyle)
        val shouldCenterTitle =
            isTitle && (isMiddleTitle || emptyContent || imageStyle?.uppercase() == Book.imgStyleSingle)

        for (lineIndex in 0 until layout.lineCount) {
            val textLine = TextLine(isTitle = isTitle)
            engine.prepareNextPageIfNeed(engine.durY + textHeight)
            val lineStart = layout.getLineStart(lineIndex);
            val lineEnd = layout.getLineEnd(lineIndex)
            val lineText = text.substring(lineStart, lineEnd); textLine.text = lineText

            val (lineWords, lineWidths) = if (layout is ZhLayout) {
                val clusterStart = layout.getLineStartCluster(lineIndex);
                val clusterEnd = layout.getLineEndCluster(lineIndex)
                splitResult.words.subList(clusterStart, clusterEnd) to splitResult.widths.subList(
                    clusterStart,
                    clusterEnd
                )
            } else {
                val res = measureTextSplit(lineText, widthsArray, lineStart)
                res.words to res.widths
            }

            val needsIndent = isFirstLine && lineIndex == 0 && !isTitle
            val (adjustedWords, adjustedWidths, startX) = if (needsIndent) {
                val indentLength = paragraphIndent.length.coerceAtMost(lineWords.size)
                val indentX = addIndentChars(engine.absStartX, textLine, indentLength)
                Triple(
                    lineWords.subList(indentLength, lineWords.size),
                    lineWidths.subList(indentLength, lineWidths.size),
                    indentX
                )
            } else Triple(lineWords, lineWidths, 0f)

            val desiredWidth = adjustedWidths.fastSum()
            val isLastLine = lineIndex == layout.lineCount - 1

            when {
                shouldCenterTitle -> engine.addCharsToLineNatural(
                    textLine,
                    adjustedWords,
                    (visibleWidth - desiredWidth) / 2,
                    adjustedWidths,
                    imgList
                )

                isLastLine -> engine.addCharsToLineNatural(
                    textLine,
                    adjustedWords,
                    startX,
                    adjustedWidths,
                    imgList
                )

                else -> engine.addCharsToLineMiddle(
                    textLine,
                    adjustedWords,
                    measurer,
                    desiredWidth,
                    startX,
                    adjustedWidths,
                    imgList
                )
            }
            updateTextLineInfo(textLine, lineText, textHeight, descent)
        }
        engine.durY += textHeight * paragraphSpacing / 10f
    }

    private fun calculateInitialYPosition(
        layout: Layout,
        textHeight: Float,
        emptyContent: Boolean,
        isTitle: Boolean,
        imageStyle: String?
    ): Float {
        if (emptyContent && textPages.isEmpty()) {
            val textPage = engine.pendingTextPage
            if (textPage.lineSize == 0) {
                val ty = (visibleHeight - layout.lineCount * textHeight) / 2
                return if (ty > titleTopSpacing) ty else titleTopSpacing.toFloat()
            } else {
                var textLayoutHeight = layout.lineCount * textHeight
                val firstLine = textPage.getLine(0)
                if (firstLine.lineTop < textLayoutHeight + titleTopSpacing) textLayoutHeight =
                    firstLine.lineTop - titleTopSpacing
                textPage.lines.forEach { it.lineTop -= textLayoutHeight; it.lineBase -= textLayoutHeight; it.lineBottom -= textLayoutHeight }
                return engine.durY - textLayoutHeight
            }
        }
        if (isTitle && textPages.isEmpty() && engine.pendingTextPage.lines.isEmpty()) {
            return when (imageStyle?.uppercase()) {
                Book.imgStyleSingle -> {
                    val ty = (visibleHeight - layout.lineCount * textHeight) / 2
                    if (ty > titleTopSpacing) ty else titleTopSpacing.toFloat()
                }
                else -> engine.durY + titleTopSpacing
            }
        }
        return engine.durY
    }

    private fun updateTextLineInfo(
        textLine: TextLine,
        lineText: String,
        textHeight: Float,
        descent: Float
    ) {
        if (doublePage) textLine.isLeftLine = engine.absStartX < viewWidth / 2
        calcTextLinePosition(textPages, textLine, engine.stringBuilder.length)
        engine.stringBuilder.append(lineText)
        textLine.upTopBottom(engine.durY, textHeight, descent)
        engine.pendingTextPage.addLine(textLine)
        engine.durY += textHeight * lineSpacingExtra
        if (engine.pendingTextPage.height < engine.durY) engine.pendingTextPage.height = engine.durY
    }

    private fun calcTextLinePosition(
        textPages: ArrayList<TextPage>,
        textLine: TextLine,
        sbLength: Int
    ) {
        // 视觉段序号：按排版后的视觉行计算，同一逻辑段内多个 TextLine（折行）共享同号，
        // 遇上一行 isParagraphEnd 才递增。与上层 paragraphSeq（逻辑段号）不同
        val lastLine = engine.pendingTextPage.lines.lastOrNull { it.paragraphNum > 0 }
            ?: textPages.lastOrNull()?.lines?.lastOrNull { it.paragraphNum > 0 }
        textLine.paragraphNum = when {
            lastLine == null -> 1; lastLine.isParagraphEnd -> lastLine.paragraphNum + 1; else -> lastLine.paragraphNum
        }
        textLine.chapterPosition = (textPages.lastOrNull()?.lines?.lastOrNull()
            ?.run { chapterPosition + charSize + if (isParagraphEnd) 1 else 0 } ?: 0) + sbLength
        textLine.pagePosition = sbLength
    }

    private fun addIndentChars(absStartX: Int, textLine: TextLine, indentLength: Int): Float {
        var x = 0f
        repeat(indentLength) {
            val x1 = x + indentCharWidth
            textLine.addColumn(
                TextColumn(
                    absStartX + x,
                    absStartX + x1,
                    ChapterProvider.indentChar
                )
            )
            x = x1; textLine.indentWidth = x
        }
        textLine.indentSize = indentLength; return x
    }

    /**
     * ColumnFactory.createColumn 实现：列构造留 app 端（依赖 ChapterProvider 常量 + reviewCountMap +
     * currentParagraphIndex + imgList 副作用 removeFirst）。原 TextChapterLayout.createColumn 逻辑原样下沉。
     *
     * engine.addCharToLine 通过 columnFactory 接口调用本方法，实现「纯算术面下沉 + 平台构造留 app」。
     */
    override fun createColumn(
        absStartX: Int,
        char: String,
        xStart: Float,
        xEnd: Float,
        imgList: MutableList<ImgData>?
    ): BaseColumn = when {
        char == ChapterProvider.reviewChar -> {
            val cnt = reviewCountMap?.get(currentParagraphIndex) ?: 0
            ReviewColumn(absStartX + xStart, absStartX + xEnd, currentParagraphIndex, cnt)
        }
        imgList != null && char == ChapterProvider.srcReplaceChar -> {
            val img = imgList.removeFirst()
            // 严禁在此处同步下载图片，下载由 init 中的后台协程统一管理
            ImageColumn(absStartX + xStart, absStartX + xEnd, img.src, img.onclick)
        }
        else -> TextColumn(absStartX + xStart, absStartX + xEnd, char)
    }

    private fun allocateFloatArray(size: Int): FloatArray {
        if (size > floatArray.size) floatArray = FloatArray(size); return floatArray
    }

}
