package io.legado.app.ui.about

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import io.legado.app.data.entities.Book
import io.legado.app.data.entities.ReadRecordShow
import io.legado.app.ui.compose.theme.AppTheme.DesignTokens
import io.legado.app.ui.preview.LegadoThemePreview
import io.legado.app.ui.preview.previewBookFresh
import io.legado.app.ui.preview.previewBookSample

/**
 * [ReadRecordScreen] 的 @Preview。
 *
 * heatmapSlot 用灰色占位条 (真实热力图组件在 app 端), coverSlot 用书名前两字占位。
 */

private val previewRecords = listOf(
    ReadRecordShow(bookName = "三体", readTime = 5 * 3600 + 1800, lastRead = 1_700_000_000, day = 20260726),
    ReadRecordShow(bookName = "活着", readTime = 45 * 60, lastRead = 1_699_900_000, day = 20260726),
    ReadRecordShow(bookName = "白夜行", readTime = 12 * 3600, lastRead = 1_699_800_000, day = 20260725),
)

private val previewBookMap = mapOf(
    "三体" to previewBookSample,
    "活着" to previewBookFresh,
)

private val previewHeatmapSlot: @Composable (Modifier) -> Unit = { modifier ->
    Box(
        modifier
            .fillMaxWidth()
            .height(96.dp)
            .background(Color(0x22165DFF), DesignTokens.shapeDefault),
        contentAlignment = Alignment.Center,
    ) {
        Text("热力图占位", color = Color(0xFF888888))
    }
}

private val previewRecordCoverSlot: @Composable (ReadRecordShow, Book?, Modifier) -> Unit = { record, _, modifier ->
    Box(
        modifier
            .aspectRatio(0.75f)
            .background(Color(0xFF888888), DesignTokens.shapeSm),
        contentAlignment = Alignment.Center,
    ) {
        Text(record.bookName.take(2), color = Color.White)
    }
}

private val noOpRecordActions = object : ReadRecordUiActions {
    override fun onBack() {}
    override fun onSearchChange(text: String) {}
    override fun onSearch(text: String) {}
    override fun onSearchFocusChanged(focused: Boolean) {}
    override fun onSortSelect(mode: Int) {}
    override fun onToggleEnableRecord() {}
    override fun onClearAll() {}
    override fun onStepMonth(delta: Int) {}
    override fun openBook(item: ReadRecordShow) {}
    override fun sureDelAlert(item: ReadRecordShow) {}
    override fun clearSearchFocus() {}
}

private fun previewRecordState(
    items: List<ReadRecordShow> = previewRecords,
    itemsPerDayMode: Boolean = true,
) = ReadRecordUiState(
    sortMode = 2,
    heatmapYear = 2026,
    heatmapMonth = 7,
    todayYear = 2026,
    todayMonth = 7,
    items = items,
    bookMap = previewBookMap,
    todayTimeByBook = mapOf("三体" to 1800L, "活着" to 600L),
    totalTimeByBook = mapOf("三体" to (5 * 3600L + 1800), "活着" to (45 * 60L), "白夜行" to (12 * 3600L)),
    itemsPerDayMode = itemsPerDayMode,
    heatmapData = mapOf(20260724 to 3600L, 20260725 to 7200L, 20260726 to 1800L),
    summaryToday = 2400L,
    summaryWeek = 6 * 3600L,
    summaryMonth = 28 * 3600L,
    summaryAll = 320 * 3600L,
    summaryBookCount = 42,
    summaryAvgRead = 5400L,
)

@Preview
@Composable
fun ReadRecordScreenPreview() = LegadoThemePreview {
    ReadRecordScreen(
        state = previewRecordState(),
        actions = noOpRecordActions,
        heatmapSlot = previewHeatmapSlot,
        coverSlot = previewRecordCoverSlot,
    )
}

@Preview
@Composable
fun ReadRecordScreenEmptyPreview() = LegadoThemePreview {
    ReadRecordScreen(
        state = previewRecordState(items = emptyList()),
        actions = noOpRecordActions,
        heatmapSlot = previewHeatmapSlot,
        coverSlot = previewRecordCoverSlot,
    )
}

@Preview
@Composable
fun ReadRecordScreenTotalModePreview() = LegadoThemePreview {
    ReadRecordScreen(
        state = previewRecordState(itemsPerDayMode = false),
        actions = noOpRecordActions,
        heatmapSlot = previewHeatmapSlot,
        coverSlot = previewRecordCoverSlot,
    )
}

@Preview
@Composable
fun ReadRecordScreenDarkPreview() = LegadoThemePreview(dark = true) {
    ReadRecordScreen(
        state = previewRecordState(),
        actions = noOpRecordActions,
        heatmapSlot = previewHeatmapSlot,
        coverSlot = previewRecordCoverSlot,
    )
}
