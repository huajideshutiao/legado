package io.legado.app.ui.book.read.config

import android.content.DialogInterface
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.painter.BitmapPainter
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.colorResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.graphics.drawable.toBitmap
import io.legado.app.utils.TransType
import io.legado.app.R
import io.legado.app.help.config.AppConfig
import io.legado.app.help.config.ReadBookConfig
import io.legado.app.model.ReadBook
import io.legado.app.ui.book.read.ReadBookActivity
import io.legado.app.ui.book.read.ReadBookEvents
import io.legado.app.ui.book.read.ReadConfigChange.BG
import io.legado.app.ui.book.read.ReadConfigChange.CHAPTER_STYLE
import io.legado.app.ui.book.read.ReadConfigChange.INVALIDATE_TEXT_PAGE
import io.legado.app.ui.book.read.ReadConfigChange.LOAD_CONTENT
import io.legado.app.ui.book.read.ReadConfigChange.STYLE
import io.legado.app.ui.book.read.ReadConfigChange.UP_CONTENT
import io.legado.app.ui.compose.component.AppCheckbox
import io.legado.app.ui.compose.component.AppDetailSeekBar
import io.legado.app.ui.compose.component.RadioChip
import io.legado.app.ui.compose.component.StrokeTextChip
import io.legado.app.ui.compose.dialogs.alert
import io.legado.app.ui.compose.dialogs.selector
import io.legado.app.ui.compose.platform.rememberPainter
import io.legado.app.ui.compose.theme.AppTheme
import io.legado.app.ui.font.FontSelectDialog
import io.legado.app.utils.ChineseUtils
import io.legado.app.utils.showConverterSelector
import io.legado.app.utils.showDialogFragment

/** 阅读样式设置：字重/字体/缩进/简繁/边距/信息 + 排版滑条 + 翻页动画 + 背景样式列表 */
class ReadStyleDialog : BaseReadBottomComposeDialog(), FontSelectDialog.CallBack {

    private val callBack get() = activity as? ReadBookActivity

    override fun onDismiss(dialog: DialogInterface) {
        super.onDismiss(dialog)
        ReadBookConfig.save()
    }

    @Composable
    override fun Content() {
        val context = LocalContext.current
        val colors = rememberReadMenuColors()
        // 样式切换/共用布局切换后需整体重读配置（对齐原 upView）
        var refresh by remember { mutableIntStateOf(0) }
        var textBold by remember(refresh) { mutableIntStateOf(ReadBookConfig.textBold) }
        var chineseType by remember { mutableIntStateOf(AppConfig.chineseConverterType) }
        var pageAnim by remember(refresh) { mutableIntStateOf(ReadBook.pageAnim()) }
        var shareLayout by remember { mutableStateOf(ReadBookConfig.shareLayout) }
        var textSize by remember(refresh) { mutableIntStateOf(ReadBookConfig.textSize - 5) }
        var letterSpacing by remember(refresh) {
            mutableIntStateOf((ReadBookConfig.letterSpacing * 100).toInt() + 50)
        }
        var lineSize by remember(refresh) { mutableIntStateOf(ReadBookConfig.lineSpacingExtra) }
        var paragraphSpacing by remember(refresh) {
            mutableIntStateOf(ReadBookConfig.paragraphSpacing)
        }
        var styleSelect by remember { mutableIntStateOf(ReadBookConfig.styleSelect) }

        fun changeBgTextConfig(index: Int) {
            if (index != ReadBookConfig.styleSelect) {
                ReadBookConfig.styleSelect = index
                styleSelect = index
                refresh++
                ReadBookEvents.postConfig(BG, STYLE, LOAD_CONTENT)
                ReadBookEvents.postActionBarChange()
            }
        }

        fun showBgTextConfig(index: Int) {
            dismissAllowingStateLoss()
            changeBgTextConfig(index)
            callBack?.showBgTextConfig()
        }

        Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
            // 顶部功能小按钮行
            Row(
                Modifier.fillMaxWidth().padding(top = 16.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                SegmentChip(
                    segments = stringResource(R.string.font_weight_text),
                    activeRange = mapOf(0 to (0 to 1), 1 to (2 to 3), 2 to (4 to 5))[textBold],
                    textColor = colors.secondaryText,
                ) {
                    context.alert(titleResource = R.string.text_font_weight_converter) {
                        items(
                            context.resources.getStringArray(R.array.text_font_weight).toList()
                        ) { _, i ->
                            ReadBookConfig.textBold = i
                            textBold = i
                            ReadBookEvents.postConfig(CHAPTER_STYLE, INVALIDATE_TEXT_PAGE, UP_CONTENT)
                        }
                    }
                }
                Spacer(Modifier.weight(1f))
                StrokeTextChip(stringResource(R.string.text_font), textColor = colors.secondaryText) {
                    showDialogFragment<FontSelectDialog>()
                }
                Spacer(Modifier.weight(1f))
                StrokeTextChip(stringResource(R.string.text_indent), textColor = colors.secondaryText) {
                    context.selector(
                        title = getString(R.string.text_indent),
                        items = resources.getStringArray(R.array.indent).toList()
                    ) { _, index ->
                        ReadBookConfig.paragraphIndent = "　".repeat(index)
                        ReadBookEvents.postConfig(CHAPTER_STYLE, LOAD_CONTENT)
                    }
                }
                Spacer(Modifier.weight(1f))
                SegmentChip(
                    segments = "简/繁",
                    activeRange = mapOf(1 to (0 to 1), 2 to (2 to 3))[chineseType],
                    textColor = colors.secondaryText,
                ) {
                    ChineseUtils.showConverterSelector(context) {
                        chineseType = it
                        ChineseUtils.unLoad(*TransType.entries.toTypedArray())
                        ReadBookEvents.postConfig(LOAD_CONTENT)
                    }
                }
                Spacer(Modifier.weight(1f))
                StrokeTextChip(stringResource(R.string.padding), textColor = colors.secondaryText) {
                    dismissAllowingStateLoss()
                    callBack?.showPaddingConfig()
                }
                Spacer(Modifier.weight(1f))
                StrokeTextChip(stringResource(R.string.information), textColor = colors.secondaryText) {
                    TipConfigDialog().show(childFragmentManager, "tipConfigDialog")
                }
            }
            AppDetailSeekBar(
                title = stringResource(R.string.text_size),
                value = textSize, max = 45, textColor = colors.text,
                valueFormat = { (it + 5).toString() },
                onChanged = {
                    textSize = it
                    ReadBookConfig.textSize = it + 5
                    ReadBookEvents.postConfig(CHAPTER_STYLE, LOAD_CONTENT)
                },
                modifier = Modifier.padding(top = 4.dp),
            )
            AppDetailSeekBar(
                title = stringResource(R.string.text_letter_spacing),
                value = letterSpacing, max = 100, textColor = colors.text,
                valueFormat = { ((it - 50) / 100f).toString() },
                onChanged = {
                    letterSpacing = it
                    ReadBookConfig.letterSpacing = (it - 50) / 100f
                    ReadBookEvents.postConfig(CHAPTER_STYLE, LOAD_CONTENT)
                },
            )
            AppDetailSeekBar(
                title = stringResource(R.string.line_size),
                value = lineSize, max = 20, textColor = colors.text,
                valueFormat = { ((it - 10) / 10f).toString() },
                onChanged = {
                    lineSize = it
                    ReadBookConfig.lineSpacingExtra = it
                    ReadBookEvents.postConfig(CHAPTER_STYLE, LOAD_CONTENT)
                },
            )
            AppDetailSeekBar(
                title = stringResource(R.string.paragraph_size),
                value = paragraphSpacing, max = 20, textColor = colors.text,
                valueFormat = { (it / 10f).toString() },
                onChanged = {
                    paragraphSpacing = it
                    ReadBookConfig.paragraphSpacing = it
                    ReadBookEvents.postConfig(CHAPTER_STYLE, LOAD_CONTENT)
                },
            )
            Divider()
            Text(
                stringResource(R.string.page_anim),
                color = colors.text.copy(alpha = 0.75f), fontSize = 12.sp,
                modifier = Modifier.padding(bottom = 4.dp),
            )
            Row(Modifier.fillMaxWidth()) {
                listOf(
                    R.string.page_anim_cover,
                    R.string.page_anim_slide,
                    R.string.page_anim_simulation,
                    R.string.page_anim_scroll,
                    R.string.page_anim_none,
                ).forEachIndexed { index, res ->
                    RadioChip(
                        text = stringResource(res),
                        checked = pageAnim == index,
                        textColor = colors.text,
                        modifier = Modifier.weight(1f).padding(4.dp),
                    ) {
                        ReadBookConfig.pageAnim = index
                        pageAnim = index
                        callBack?.upPageAnim()
                        ReadBook.loadContent(false)
                    }
                }
            }
            Divider()
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    stringResource(R.string.text_bg_style),
                    color = colors.text.copy(alpha = 0.75f), fontSize = 12.sp,
                    modifier = Modifier.weight(1f),
                )
                Text(stringResource(R.string.share_layout), color = colors.text)
                AppCheckbox(
                    checked = shareLayout,
                    onCheckedChange = {
                        ReadBookConfig.shareLayout = it
                        shareLayout = it
                        refresh++
                        ReadBookEvents.postConfig(BG, STYLE, LOAD_CONTENT)
                    },
                    modifier = Modifier.padding(start = 8.dp, end = 16.dp),
                )
            }
            LazyRow(
                Modifier.fillMaxWidth().padding(top = 8.dp, bottom = 16.dp),
                contentPadding = PaddingValues(horizontal = 8.dp),
            ) {
                items(ReadBookConfig.configList.size) { index ->
                    val item = ReadBookConfig.configList[index]
                    StyleCircleItem(
                        name = item.name.ifBlank { "文字" },
                        textColor = Color(item.curTextColor()),
                        bgKey = refresh,
                        bg = item,
                        selected = styleSelect == index,
                        onClick = { changeBgTextConfig(index) },
                        onLongClick = { showBgTextConfig(index) },
                    )
                }
                item {
                    AddStyleItem(colors.text) {
                        ReadBookConfig.configList.add(ReadBookConfig.Config())
                        showBgTextConfig(ReadBookConfig.configList.lastIndex)
                    }
                }
            }
        }
    }

    @Composable
    private fun Divider() {
        Box(
            Modifier
                .fillMaxWidth()
                .padding(vertical = 8.dp)
                .height(0.8.dp)
                .background(colorResource(R.color.btn_bg)),
        )
    }

    /** 分段选择小按钮（复刻 SegmentSelectTextView）：当前段染 accent 色 */
    @Composable
    private fun SegmentChip(
        segments: String,
        activeRange: Pair<Int, Int>?,
        textColor: Color,
        onClick: () -> Unit,
    ) {
        val accent = AppTheme.colors.accent
        val text = buildAnnotatedString {
            append(segments)
            activeRange?.let { (start, end) ->
                addStyle(SpanStyle(color = accent), start, end)
            }
        }
        val shape = RoundedCornerShape(4.dp)
        Text(
            text = text,
            color = textColor,
            fontSize = 14.sp,
            maxLines = 1,
            modifier = Modifier
                .clip(shape)
                .border(1.dp, textColor, shape)
                .combinedClickable(onClick = onClick)
                .padding(horizontal = 8.dp, vertical = 4.dp),
        )
    }

    /** 样式圆形预览项：背景圆 + 名称，选中 accent 描边加粗（复刻 StyleAdapter 项） */
    @Composable
    private fun StyleCircleItem(
        name: String,
        textColor: Color,
        bgKey: Int,
        bg: ReadBookConfig.Config,
        selected: Boolean,
        onClick: () -> Unit,
        onLongClick: () -> Unit,
    ) {
        val accent = AppTheme.colors.accent
        val painter = remember(bg, bgKey) {
            BitmapPainter(bg.curBgDrawable(100, 150).toBitmap(100, 150).asImageBitmap())
        }
        Box(
            Modifier
                .padding(horizontal = 8.dp)
                .size(48.dp)
                .clip(CircleShape)
                .combinedClickable(onClick = onClick, onLongClick = onLongClick),
            contentAlignment = Alignment.Center,
        ) {
            Image(
                painter = painter,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .matchParentSize()
                    .border(1.dp, if (selected) accent else textColor, CircleShape),
            )
            Text(
                text = name,
                color = textColor,
                fontSize = 14.sp,
                maxLines = 1,
                fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
            )
        }
    }

    /** 新增样式项：加号圆（复刻 addFooterView），点击新建配置并进入编辑 */
    @Composable
    private fun AddStyleItem(textColor: Color, onClick: () -> Unit) {
        Box(
            Modifier
                .padding(horizontal = 8.dp)
                .size(48.dp)
                .padding(6.dp)
                .clip(CircleShape)
                .border(1.dp, textColor, CircleShape)
                .combinedClickable(onClick = onClick),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                painter = rememberPainter("ic_add"),
                contentDescription = stringResource(R.string.add),
                tint = textColor,
            )
        }
    }

    override val curFontPath: String
        get() = ReadBookConfig.textFont

    override fun selectFont(path: String) {
        if (path != ReadBookConfig.textFont || path.isEmpty()) {
            ReadBookConfig.textFont = path
            ReadBookEvents.postConfig(STYLE, LOAD_CONTENT)
        }
    }
}
