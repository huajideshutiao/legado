package io.legado.app.ui.book.bookmark

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.Surface
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.graphics.rememberGraphicsLayer
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.sp
import kotlin.math.max
import kotlin.math.roundToInt
import io.legado.app.constant.ThreadSafeDateFormat
import io.legado.app.data.entities.Bookmark
import io.legado.app.ui.compose.component.AppDialog
import io.legado.app.ui.compose.component.AppDialogSizes
import io.legado.app.ui.compose.component.AppTextButton
import io.legado.app.ui.compose.component.DialogTitleBar
import io.legado.app.ui.compose.component.appDialogSize
import io.legado.app.ui.compose.theme.AppTheme
import io.legado.app.ui.compose.theme.AppTheme.DesignTokens
import kotlinx.coroutines.launch
import legado.ui.generated.resources.Res
import legado.ui.generated.resources.cancel
import legado.ui.generated.resources.save_image_as
import legado.ui.generated.resources.share
import org.jetbrains.compose.resources.stringResource

/** 导出位图宽度下限: 低于此宽度(如桌面 1x 密度)按倍率放大录制, 预览显示尺寸不变 */
private const val MinExportWidthPx = 1080

/**
 * 原文+笔记合计字数上限, 超限按各自长度比例截断加省略号。
 * 卡片高度与导出位图尺寸随内容量无上界 (OOM/超 GPU 渲染上限), 限字即有界,
 * 预览与导出共用同一份渲染, 截断预览可见。
 */
private const val MaxTextChars = 500

/** 底部水印透明度 (规格: secondaryText 半透明) */
private const val WatermarkAlpha = 0.4f

/** 细分隔线透明度 (对照 ReadStyleScreen.Divider 的 secondaryText.copy(alpha) 惯例) */
private const val DividerAlpha = 0.3f

/**
 * 书签分享卡片 (预览对话框与各端离屏烘焙共用同一份渲染)。
 *
 * 颜色全部跟随当前主题 (AppTheme.colors), 圆角/间距取 [DesignTokens] 档位:
 * 1. 头部: 书名 (中号加粗 primaryText) + 作者 (小字 secondaryText)
 * 2. 原文区: bookText 逐段展示, 左侧 accent 竖装饰线 (无原文不渲染)
 * 3. 笔记区: 细分隔线 + "笔记"小标 + 内容 (content 非空才渲染)
 * 4. 底部: 章节名 · 创建时间 + 右下角"阅读"半透明水印
 */
/** 分享卡片时间格式 (ThreadSafeDateFormat 保证多端线程安全)。 */
private val shareCardDateFormat = ThreadSafeDateFormat("yyyy-MM-dd HH:mm")

@Composable
fun BookmarkShareCard(
    bookmark: Bookmark,
    modifier: Modifier = Modifier,
) {
    val colors = AppTheme.colors
    // 超限按各自长度比例分配配额, 截断段加省略号; 配额 0 时仅保留省略号
    var bodyText = bookmark.bookText
    var noteText = bookmark.content
    val totalChars = bodyText.length + noteText.length
    if (totalChars > MaxTextChars) {
        val bodyQuota = MaxTextChars * bodyText.length / totalChars
        val noteQuota = MaxTextChars - bodyQuota
        if (bodyText.length > bodyQuota) bodyText = bodyText.take(bodyQuota).trimEnd() + "…"
        if (noteText.length > noteQuota) noteText = noteText.take(noteQuota).trimEnd() + "…"
    }
    Surface(
        shape = DesignTokens.shapeLg,
        color = colors.background,
        modifier = modifier,
    ) {
        Column(Modifier.padding(DesignTokens.spacingLg)) {
            // 头部
            Text(
                text = bookmark.bookName,
                color = colors.primaryText,
                fontSize = 18.sp,
                lineHeight = 24.sp,
                fontWeight = FontWeight.Bold,
            )
            if (bookmark.bookAuthor.isNotBlank()) {
                Spacer(Modifier.height(DesignTokens.spacingXs))
                Text(
                    text = bookmark.bookAuthor,
                    color = colors.secondaryText,
                    fontSize = 12.sp,
                    lineHeight = 16.sp,
                )
            }

            // 原文区: 左侧 accent 竖线 + 逐段展示 (行距比正文宽松)
            if (bodyText.isNotBlank()) {
                Spacer(Modifier.height(DesignTokens.spacingLg))
                Row(Modifier.height(IntrinsicSize.Min)) {
                    Box(
                        Modifier
                            .fillMaxHeight()
                            .width(DesignTokens.strokeMedium)
                            .background(colors.accent, DesignTokens.shapeSm),
                    )
                    Spacer(Modifier.width(DesignTokens.spacingMd))
                    Column {
                        bodyText.lines()
                            .filter { it.isNotBlank() }
                            .forEachIndexed { index, line ->
                                if (index > 0) Spacer(Modifier.height(DesignTokens.spacingMd))
                                Text(
                                    text = line,
                                    color = colors.primaryText,
                                    fontSize = 17.sp,
                                    lineHeight = 30.sp,
                                )
                            }
                    }
                }
            }

            // 笔记区
            if (noteText.isNotBlank()) {
                Spacer(Modifier.height(DesignTokens.spacingLg))
                Box(
                    Modifier
                        .fillMaxWidth()
                        .height(DesignTokens.strokeHairline)
                        .background(colors.secondaryText.copy(alpha = DividerAlpha)),
                )
                Spacer(Modifier.height(DesignTokens.spacingMd))
                Text(
                    text = "笔记",
                    color = colors.secondaryText,
                    fontSize = 12.sp,
                    lineHeight = 16.sp,
                )
                Spacer(Modifier.height(DesignTokens.spacingXs))
                Text(
                    text = noteText,
                    color = colors.primaryText,
                    fontSize = 15.sp,
                    lineHeight = 24.sp,
                )
            }

            // 底部: 章节名 · 创建时间 | "阅读"水印
            Spacer(Modifier.height(DesignTokens.spacingLg))
            Row(Modifier.fillMaxWidth()) {
                Text(
                    text = "${bookmark.chapterName} · ${shareCardDateFormat.format(bookmark.time)}",
                    color = colors.secondaryText,
                    fontSize = 12.sp,
                    lineHeight = 16.sp,
                    modifier = Modifier.weight(1f),
                )
                Text(
                    text = "阅读",
                    color = colors.secondaryText.copy(alpha = WatermarkAlpha),
                    fontSize = 12.sp,
                    lineHeight = 16.sp,
                    modifier = Modifier.align(Alignment.Bottom),
                )
            }
        }
    }
}

/**
 * 书签分享卡片预览对话框: 直接渲染 [BookmarkShareCard] (预览即所得), 保存按钮经
 * GraphicsLayer 录制当前卡片 (record + toImageBitmap, 同 SimulationPageDelegateCompose
 * 的截图模式) 交 [BookmarkExporter.exportImage] 编码 PNG + 平台保存。
 *
 * 保存成功后关闭对话框; 失败/取消留对话框由用户重试或退出 (结果提示在 exportImage 内)。
 */
@Composable
fun BookmarkShareCardDialog(
    bookmark: Bookmark,
    onDismiss: () -> Unit,
) {
    val colors = AppTheme.colors
    val titleText = stringResource(Res.string.share)
    val saveText = stringResource(Res.string.save_image_as)
    val cancelText = stringResource(Res.string.cancel)
    // 截图层: 卡片每次重绘都重录, 保存时取最近一次录制 (预览即所得)
    val layer = rememberGraphicsLayer()
    val scope = rememberCoroutineScope()
    var exporting by remember { mutableStateOf(false) }

    AppDialog(onDismissRequest = onDismiss, properties = AppDialogSizes.properties()) {
        Surface(
            shape = DesignTokens.dialogShape,
            color = colors.fillet,
            modifier = Modifier.appDialogSize(),
        ) {
            Column(Modifier.fillMaxWidth()) {
                DialogTitleBar(title = titleText, onBack = onDismiss)

                // 卡片预览: 超出可用高度转内部滚动
                Column(
                    Modifier
                        .fillMaxWidth()
                        .weight(1f, fill = false)
                        .verticalScroll(rememberScrollState())
                        .padding(horizontal = DesignTokens.spacingLg, vertical = DesignTokens.spacingMd),
                ) {
                    BookmarkShareCard(
                        bookmark = bookmark,
                        modifier = Modifier
                            .fillMaxWidth()
                            .drawWithContent {
                            val exportScale = max(1f, MinExportWidthPx / size.width)
                            layer.record(
                                size = IntSize(
                                    (size.width * exportScale).roundToInt(),
                                    (size.height * exportScale).roundToInt(),
                                ),
                            ) {
                                scale(exportScale, Offset.Zero) { this@drawWithContent.drawContent() }
                            }
                            scale(1f / exportScale, Offset.Zero) { drawLayer(layer) }
                        },
                    )
                }

                // 底部按钮栏: 弹性间距 | 取消 | 保存
                Row(
                    Modifier
                        .fillMaxWidth()
                        .padding(horizontal = DesignTokens.spacingDefault)
                        .padding(bottom = DesignTokens.spacingMd),
                ) {
                    Spacer(Modifier.weight(1f))
                    AppTextButton(text = cancelText, color = colors.secondaryText) { onDismiss() }
                    AppTextButton(
                        text = saveText,
                        color = DesignTokens.arcoBlue6,
                        enabled = !exporting,
                    ) {
                        // 卡片尚未完成首帧绘制时 layer 为空, 直接忽略本次点击
                        if (layer.size.width == 0 || layer.size.height == 0) return@AppTextButton
                        exporting = true
                        scope.launch {
                            try {
                                val image = layer.toImageBitmap()
                                if (BookmarkExporter.exportImage(bookmark, image)) {
                                    onDismiss()
                                }
                            } finally {
                                exporting = false
                            }
                        }
                    }
                }
            }
        }
    }
}
