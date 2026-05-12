package io.legado.app.ui.book.read.page.provider

import android.graphics.Paint
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import io.legado.app.constant.AppLog
import io.legado.app.constant.PageAnim
import io.legado.app.data.entities.Book
import io.legado.app.data.entities.BookChapter
import io.legado.app.help.book.BookContent
import io.legado.app.help.book.BookHelp
import io.legado.app.help.book.getBookSource
import io.legado.app.help.config.AppConfig
import io.legado.app.help.config.ReadBookConfig
import io.legado.app.help.coroutine.Coroutine
import io.legado.app.model.ImageProvider
import io.legado.app.model.ReadBook
import io.legado.app.ui.book.read.page.entities.TextChapter
import io.legado.app.ui.book.read.page.entities.TextLine
import io.legado.app.ui.book.read.page.entities.TextPage
import io.legado.app.ui.book.read.page.entities.column.ImageColumn
import io.legado.app.ui.book.read.page.entities.column.TextColumn
import io.legado.app.utils.dpToPx
import io.legado.app.utils.fastSum
import io.legado.app.utils.getTextWidthsCompat
import io.legado.app.utils.splitNotBlank
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers.IO
import kotlinx.coroutines.Dispatchers.Main
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.LinkedList
import kotlin.math.roundToInt

class TextChapterLayout(
    scope: CoroutineScope,
    private val textChapter: TextChapter,
    private val textPages: ArrayList<TextPage>,
    private val book: Book,
    private val bookContent: BookContent,
) {

    @Volatile
    private var listener: LayoutProgressListener? = textChapter

    private val paddingLeft = ChapterProvider.paddingLeft
    private val paddingTop = ChapterProvider.paddingTop

    private val titlePaint = ChapterProvider.titlePaint
    private val titlePaintTextHeight = ChapterProvider.titlePaintTextHeight
    private val titlePaintFontMetrics = ChapterProvider.titlePaintFontMetrics

    private val contentPaint = ChapterProvider.contentPaint
    private val contentPaintTextHeight = ChapterProvider.contentPaintTextHeight
    private val contentPaintFontMetrics = ChapterProvider.contentPaintFontMetrics

    private val titleTopSpacing = ChapterProvider.titleTopSpacing
    private val titleBottomSpacing = ChapterProvider.titleBottomSpacing
    private val lineSpacingExtra = ChapterProvider.lineSpacingExtra
    private val paragraphSpacing = ChapterProvider.paragraphSpacing

    private val visibleHeight = ChapterProvider.visibleHeight
    private val visibleWidth = ChapterProvider.visibleWidth

    private val viewWidth = ChapterProvider.viewWidth
    private val doublePage = ChapterProvider.doublePage
    private val indentCharWidth = ChapterProvider.indentCharWidth
    private val stringBuilder = StringBuilder()

    private val paragraphIndent = ReadBookConfig.paragraphIndent
    private val titleMode = ReadBookConfig.titleMode
    private val useZhLayout = ReadBookConfig.useZhLayout
    private val isMiddleTitle = ReadBookConfig.isMiddleTitle
    private val textFullJustify = ReadBookConfig.textFullJustify
    private val pageAnim = ReadBookConfig.pageAnim

    private var pendingTextPage = TextPage()

    private val bookChapter inline get() = textChapter.chapter
    private val displayTitle inline get() = textChapter.title
    private val chaptersSize inline get() = textChapter.chaptersSize

    private var durY = 0f
    private var absStartX = paddingLeft
    private var floatArray = FloatArray(128)

    private var isCompleted = false
    private val job: Coroutine<*>

    var exception: Throwable? = null

    var channel = Channel<TextPage>(Channel.UNLIMITED)
    private val pageChangeChannel = Channel<Unit>(Channel.CONFLATED)
    private val srcToPagesMap = java.util.concurrent.ConcurrentHashMap<String, MutableSet<Int>>()

    fun notifyPageChanged() {
        pageChangeChannel.trySend(Unit)
    }

    data class Img(val src: String,val style: String,val onclick: String)

    init {
        job = Coroutine.async(
            scope,
            start = CoroutineStart.LAZY,
            executeContext = IO
        ) {
            val parsedLines = ChapterContentParser.parse(bookContent)
            launch {
                val bookSource = book.getBookSource() ?: return@launch
                val imageStyle = book.getImageStyle()
                val isSingle = imageStyle.equals(Book.imgStyleSingle, true)
                val allImages = parsedLines.flatMap { it.images }.map { it.src }.distinct()
                if (allImages.isEmpty()) return@launch

                val imageToIndexMap = allImages.withIndex().associate { it.value to it.index }
                val downloaded = allImages.filter { BookHelp.isImageExist(book, it) }.toMutableSet()

                for (sig in pageChangeChannel) {
                    delay(300)
                    val isDur = bookChapter.index == ReadBook.durChapterIndex
                    val durPageIdx = if (isDur) ReadBook.durPageIndex else 0

                    // 1. 精确锁定窗口：寻找当前页及附近的图片索引
                    val activeImageIndices = mutableSetOf<Int>()
                    for (i in (durPageIdx - 1)..(durPageIdx + 1)) {
                        if (i in 0 until textPages.size) {
                            textPages[i].lines.forEach { line ->
                                if (line.isImage) {
                                    line.columns.forEach { col ->
                                        if (col is ImageColumn) {
                                            imageToIndexMap[col.src]?.let {
                                                activeImageIndices.add(
                                                    it
                                                )
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }

                    // 如果当前视角附近没图，则下载本章前3张（如果正在读本章开头）
                    if (activeImageIndices.isEmpty() && durPageIdx == 0) {
                        for (i in 0..2) if (i in allImages.indices) activeImageIndices.add(i)
                    }

                    // 2. 构造严格窗口：active 索引及其前后各3张
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
                                    BookHelp.getImage(
                                        book,
                                        src
                                    ).absolutePath
                                )
                                ImageProvider.getImage(book, src, visibleWidth)
                            }
                        }
                    }

                    if (newDownloaded.isNotEmpty()) {
                        // 3. 预布局修正：仅刷新受影响的页面
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
                                                if (col.refreshLayout(book, isSingle)) pageUpdated =
                                                    true
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

    private fun onPageCompleted() {
        val textPage = pendingTextPage
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
        val imageStyle = book.getImageStyle()
        val isSingleStyle = imageStyle.equals(Book.imgStyleSingle, true)
        val isTextImageStyle = imageStyle.equals(Book.imgStyleText, true)

        if (titleMode != 2 || bookChapter.isVolume || contents.isEmpty()) {
            displayTitle.splitNotBlank("\n").forEach { text ->
                setTypeText(
                    book,
                    if (AppConfig.enableReview) text + ChapterProvider.reviewChar else text,
                    titlePaint,
                    titlePaintTextHeight,
                    titlePaintFontMetrics,
                    imageStyle,
                    isTitle = true,
                    emptyContent = contents.isEmpty(),
                    isVolumeTitle = bookChapter.isVolume
                )
                pendingTextPage.lines.last().isParagraphEnd = true
                stringBuilder.append("\n")
            }
            durY += titleBottomSpacing
            if (isSingleStyle && pendingTextPage.lines.isNotEmpty() && contents.isNotEmpty()) {
                prepareNextPageIfNeed()
            }
        }

        var isSetTypedImage = false
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

                if (isTextImageStyle || imgList.isEmpty()) {
                    setTypeText(
                        book,
                        line,
                        contentPaint,
                        contentPaintTextHeight,
                        contentPaintFontMetrics,
                        imageStyle,
                        imgList = imgList
                    )
                } else {
                    if (isSingleStyle && isSetTypedImage) {
                        isSetTypedImage = false
                        prepareNextPageIfNeed()
                    }
                    val embeddedImages = LinkedList<Img>()
                    val hasNonEmbeddedImage = line.contains(ChapterProvider.srcReplaceChar)
                    var isFirstSegment = true
                    val tmp = StringBuilder()
                    line.forEach { char ->
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
                                        contentPaintFontMetrics,
                                        "TEXT",
                                        isFirstLine = isFirstSegment,
                                        imgList = embeddedImages
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
                            contentPaintFontMetrics,
                            "TEXT",
                            isFirstLine = !hasNonEmbeddedImage && isFirstSegment,
                            imgList = embeddedImages
                        )
                    }
                }
                if (pendingTextPage.lines.isNotEmpty()) pendingTextPage.lines.last().isParagraphEnd =
                    true
                stringBuilder.append("\n")
                lineStartIndex = lineEndIndex + 1
            }
        }

        val textPage = pendingTextPage
        val endPadding = 20.dpToPx()
        if (textPage.height < durY + endPadding) textPage.height =
            durY + endPadding else textPage.height += endPadding
        textPage.text = stringBuilder.toString()
        onPageCompleted()
        onCompleted()
    }

    private suspend fun setTypeImage(book: Book, img: Img, textHeight: Float, imageStyle: String?) {
        val styleUpper = imageStyle?.uppercase()
        val isSingle = styleUpper == Book.imgStyleSingle
        val isCached = BookHelp.isImageExist(book, img.src)

        // 核心重构：统一尺寸计算逻辑
        val rawSize = if (isCached) ImageProvider.getImageSize(book, img.src, ReadBook.bookSource)
        else android.util.Size(ImageProvider.errorBitmap.width, ImageProvider.errorBitmap.height)

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
                if (pageAnim != PageAnim.scrollPageAnim && height > visibleHeight - durY) {
                    val fit =
                        getFitSize(width, height, visibleWidth.toFloat(), visibleHeight.toFloat())
                    width = fit.first; height = fit.second
                    prepareNextPageIfNeed(durY + height)
                }
            }

            Book.imgStyleSingle -> {
                if (durY > 0f || pendingTextPage.lines.isNotEmpty()) prepareNextPageIfNeed()
                // 占位图也居中显示
                durY = (visibleHeight - height) / 2f
            }

            else -> prepareNextPageIfNeed(durY + height)
        }

        addImageLine(img, width, height)
        if (isSingle) {
            // 单图模式占满本页剩余空间，确保下一内容从新页开始
            durY = visibleHeight.toFloat()
        } else {
            durY += textHeight * paragraphSpacing / 10f
        }
    }

    private fun getFitSize(rawW: Float, rawH: Float, maxW: Float, maxH: Float): Pair<Float, Float> {
        var w = rawW;
        var h = rawH
        if (w > maxW) {
            h = h * maxW / w; w = maxW
        }
        if (h > maxH) {
            w = w * maxH / h; h = maxH
        }
        return w to h
    }

    private fun addImageLine(img: Img, width: Float, height: Float) {
        val textLine = TextLine(isImage = true)
        textLine.text = " "
        textLine.lineTop = durY + paddingTop
        val lineBottom = durY + height + paddingTop
        textLine.lineBottom = lineBottom
        val startX = if (visibleWidth > width) (visibleWidth - width) / 2f else 0f
        textLine.addColumn(
            ImageColumn(
                absStartX + startX,
                absStartX + startX + width,
                img.src,
                img.onclick
            )
        )
        calcTextLinePosition(textPages, textLine, stringBuilder.length)
        stringBuilder.append(" ")
        pendingTextPage.addLine(textLine)
        durY += height
    }

    private suspend fun setTypeText(
        book: Book,
        text: String,
        textPaint: TextPaint,
        textHeight: Float,
        fontMetrics: Paint.FontMetrics,
        imageStyle: String?,
        isTitle: Boolean = false,
        isFirstLine: Boolean = true,
        emptyContent: Boolean = false,
        isVolumeTitle: Boolean = false,
        imgList: LinkedList<Img>? = null
    ) {
        val widthsArray = allocateFloatArray(text.length)
        textPaint.getTextWidthsCompat(text, widthsArray)
        val splitResult = measureTextSplit(text, widthsArray)
        val layout = if (useZhLayout) ZhLayout(
            text,
            textPaint,
            visibleWidth,
            splitResult.words,
            splitResult.widths,
            if (isFirstLine) paragraphIndent.length else 0
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

        durY = calculateInitialYPosition(layout, textHeight, emptyContent, isTitle, imageStyle)
        val shouldCenterTitle =
            isTitle && (isMiddleTitle || emptyContent || isVolumeTitle || imageStyle?.uppercase() == Book.imgStyleSingle)

        for (lineIndex in 0 until layout.lineCount) {
            val textLine = TextLine(isTitle = isTitle)
            prepareNextPageIfNeed(durY + textHeight)
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
                val indentX = addIndentChars(absStartX, textLine, indentLength)
                Triple(
                    lineWords.subList(indentLength, lineWords.size),
                    lineWidths.subList(indentLength, lineWidths.size),
                    indentX
                )
            } else Triple(lineWords, lineWidths, 0f)

            val desiredWidth = adjustedWidths.fastSum()
            val isLastLine = lineIndex == layout.lineCount - 1

            when {
                shouldCenterTitle -> addCharsToLineNatural(
                    book,
                    absStartX,
                    textLine,
                    adjustedWords,
                    (visibleWidth - desiredWidth) / 2,
                    adjustedWidths,
                    imgList
                )

                isLastLine -> addCharsToLineNatural(
                    book,
                    absStartX,
                    textLine,
                    adjustedWords,
                    startX,
                    adjustedWidths,
                    imgList
                )

                else -> addCharsToLineMiddle(
                    book,
                    absStartX,
                    textLine,
                    adjustedWords,
                    textPaint,
                    desiredWidth,
                    startX,
                    adjustedWidths,
                    imgList
                )
            }
            updateTextLineInfo(textLine, lineText, textHeight, fontMetrics)
        }
        durY += textHeight * paragraphSpacing / 10f
    }

    private fun calculateInitialYPosition(
        layout: Layout,
        textHeight: Float,
        emptyContent: Boolean,
        isTitle: Boolean,
        imageStyle: String?
    ): Float {
        if (emptyContent && textPages.isEmpty()) {
            val textPage = pendingTextPage
            if (textPage.lineSize == 0) {
                val ty = (visibleHeight - layout.lineCount * textHeight) / 2
                return if (ty > titleTopSpacing) ty else titleTopSpacing.toFloat()
            } else {
                var textLayoutHeight = layout.lineCount * textHeight
                val firstLine = textPage.getLine(0)
                if (firstLine.lineTop < textLayoutHeight + titleTopSpacing) textLayoutHeight =
                    firstLine.lineTop - titleTopSpacing
                textPage.lines.forEach { it.lineTop -= textLayoutHeight; it.lineBase -= textLayoutHeight; it.lineBottom -= textLayoutHeight }
                return durY - textLayoutHeight
            }
        }
        if (isTitle && textPages.isEmpty() && pendingTextPage.lines.isEmpty()) {
            return when (imageStyle?.uppercase()) {
                Book.imgStyleSingle -> {
                    val ty = (visibleHeight - layout.lineCount * textHeight) / 2
                    if (ty > titleTopSpacing) ty else titleTopSpacing.toFloat()
                }
                else -> durY + titleTopSpacing
            }
        }
        return durY
    }

    private fun updateTextLineInfo(
        textLine: TextLine,
        lineText: String,
        textHeight: Float,
        fontMetrics: Paint.FontMetrics
    ) {
        if (doublePage) textLine.isLeftLine = absStartX < viewWidth / 2
        calcTextLinePosition(textPages, textLine, stringBuilder.length)
        stringBuilder.append(lineText)
        textLine.upTopBottom(durY, textHeight, fontMetrics)
        pendingTextPage.addLine(textLine)
        durY += textHeight * lineSpacingExtra
        if (pendingTextPage.height < durY) pendingTextPage.height = durY
    }

    private fun calcTextLinePosition(
        textPages: ArrayList<TextPage>,
        textLine: TextLine,
        sbLength: Int
    ) {
        val lastLine = pendingTextPage.lines.lastOrNull { it.paragraphNum > 0 }
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

    private suspend fun addCharsToLineMiddle(
        book: Book,
        absStartX: Int,
        textLine: TextLine,
        words: List<String>,
        textPaint: TextPaint,
        desiredWidth: Float,
        startX: Float,
        textWidths: List<Float>,
        imgList: LinkedList<Img>?
    ) {
        if (!textFullJustify) {
            addCharsToLineNatural(
                book,
                absStartX,
                textLine,
                words,
                startX,
                textWidths,
                imgList
            ); return
        }
        textLine.startX = absStartX + startX
        val residualWidth = visibleWidth - startX - desiredWidth
        val spaceSize = words.count { it == " " }
        if (spaceSize > 0) justifyBySpaces(
            book,
            absStartX,
            textLine,
            words,
            startX,
            textWidths,
            residualWidth,
            spaceSize,
            imgList
        )
        else justifyByLetterSpacing(
            book,
            absStartX,
            textLine,
            words,
            startX,
            textWidths,
            residualWidth,
            textPaint,
            imgList
        )
        exceed(absStartX, textLine, words)
    }

    private suspend fun justifyBySpaces(
        book: Book,
        absStartX: Int,
        textLine: TextLine,
        words: List<String>,
        startX: Float,
        textWidths: List<Float>,
        residualWidth: Float,
        spaceSize: Int,
        imgList: LinkedList<Img>?
    ) {
        val d = residualWidth / spaceSize; textLine.wordSpacing = d;
        var x = startX
        for (index in words.indices) {
            val char = words[index];
            val cw = textWidths[index]
            val x1 = if (char == " " && index != words.lastIndex) x + cw + d else x + cw
            addCharToLine(book, absStartX, textLine, char, x, x1, index + 1 == words.size, imgList)
            x = x1
        }
    }

    private suspend fun justifyByLetterSpacing(
        book: Book,
        absStartX: Int,
        textLine: TextLine,
        words: List<String>,
        startX: Float,
        textWidths: List<Float>,
        residualWidth: Float,
        textPaint: TextPaint,
        imgList: LinkedList<Img>?
    ) {
        val gapCount = words.lastIndex;
        val d = if (gapCount > 0) residualWidth / gapCount else 0f
        textLine.extraLetterSpacingOffsetX = -d / 2; textLine.extraLetterSpacing =
            d / textPaint.textSize
        var x = startX
        for (index in words.indices) {
            val char = words[index];
            val cw = textWidths[index]
            val x1 = if (index != words.lastIndex) x + cw + d else x + cw
            addCharToLine(book, absStartX, textLine, char, x, x1, index + 1 == words.size, imgList)
            x = x1
        }
    }

    private suspend fun addCharsToLineNatural(
        book: Book,
        absStartX: Int,
        textLine: TextLine,
        words: List<String>,
        startX: Float,
        textWidths: List<Float>,
        imgList: LinkedList<Img>?
    ) {
        textLine.startX = absStartX + startX;
        var x = startX
        for (index in words.indices) {
            val char = words[index];
            val cw = textWidths[index];
            val x1 = x + cw
            addCharToLine(book, absStartX, textLine, char, x, x1, index + 1 == words.size, imgList)
            x = x1
        }
        exceed(absStartX, textLine, words)
    }

    private suspend fun addCharToLine(
        book: Book,
        absStartX: Int,
        textLine: TextLine,
        char: String,
        xStart: Float,
        xEnd: Float,
        isLineEnd: Boolean,
        imgList: LinkedList<Img>?
    ) {
        val column = createColumn(book, absStartX, char, xStart, xEnd, imgList)
        textLine.addColumn(column)
    }

    private suspend fun createColumn(
        book: Book,
        absStartX: Int,
        char: String,
        xStart: Float,
        xEnd: Float,
        imgList: LinkedList<Img>?
    ) = when {
        imgList != null && char == ChapterProvider.srcReplaceChar -> {
            val img = imgList.removeFirst()
            // 严禁在此处同步下载图片，下载由 init 中的后台协程统一管理
            ImageColumn(absStartX + xStart, absStartX + xEnd, img.src, img.onclick)
        }
        else -> TextColumn(absStartX + xStart, absStartX + xEnd, char)
    }

    private fun exceed(absStartX: Int, textLine: TextLine, words: List<String>) {
        var size = words.size; if (size < 2) return
        val visibleEnd = absStartX + visibleWidth;
        val columns = textLine.columns;
        var offset = 0
        val endColumn = if (words.last() == " ") {
            size--; offset++; columns[columns.lastIndex - 1]
        } else columns.last()
        val endX = endColumn.end.roundToInt()
        if (endX > visibleEnd) {
            textLine.exceed = true;
            val cc = (endX - visibleEnd) / size
            for (i in 0..<size) {
                textLine.getColumnReverseAt(i, offset)
                    .let { val py = cc * (size - i); it.start -= py; it.end -= py }
            }
        }
    }

    private suspend fun prepareNextPageIfNeed(requestHeight: Float = -1f) {
        if (requestHeight > visibleHeight || requestHeight == -1f) {
            if (pendingTextPage.height < durY) pendingTextPage.height = durY
            if (doublePage && absStartX < viewWidth / 2) {
                pendingTextPage.leftLineSize = pendingTextPage.lineSize
                absStartX = viewWidth / 2 + paddingLeft
            } else {
                if (pendingTextPage.leftLineSize == 0) pendingTextPage.leftLineSize =
                    pendingTextPage.lineSize
                pendingTextPage.text = stringBuilder.toString()
                currentCoroutineContext().ensureActive(); onPageCompleted()
                pendingTextPage = TextPage(); stringBuilder.clear(); absStartX = paddingLeft
            }
            durY = 0f
        }
    }

    private fun allocateFloatArray(size: Int): FloatArray {
        if (size > floatArray.size) floatArray = FloatArray(size); return floatArray
    }

    private class TextSplit(val words: ArrayList<String>, val widths: ArrayList<Float>)

    private fun measureTextSplit(text: String, widthsArray: FloatArray, start: Int = 0): TextSplit {
        val length = text.length;
        var clusterCount = 0
        for (i in start..<start + length) if (widthsArray[i] > 0) clusterCount++
        val widths = ArrayList<Float>(clusterCount);
        val stringList = ArrayList<String>(clusterCount)
        var i = 0
        while (i < length) {
            val clusterBaseIndex = i++; widths.add(widthsArray[start + clusterBaseIndex])
            while (i < length && widthsArray[start + i] == 0f && !isZeroWidthChar(text[i])) i++
            stringList.add(text.substring(clusterBaseIndex, i))
        }
        return TextSplit(stringList, widths)
    }

    private fun isZeroWidthChar(char: Char): Boolean {
        val code = char.code; return code == 8203 || code == 8204 || code == 8205 || code == 8288
    }

}
