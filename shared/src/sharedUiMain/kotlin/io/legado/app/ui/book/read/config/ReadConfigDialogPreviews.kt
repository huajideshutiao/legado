package io.legado.app.ui.book.read.config

import androidx.compose.runtime.Composable
import androidx.compose.ui.tooling.preview.Preview
import io.legado.app.data.entities.HttpTTS
import io.legado.app.ui.preview.LegadoThemePreview

/**
 * [SpeakEngineDialog.kt] / [ReadAloudDialog.kt] / [PageKeyDialog.kt] / [ClickActionDialog.kt] 的 @Preview。
 *
 * 假数据: [HttpTTS] 列表 / [ClickActionConfig] 用纯内存对象构造, 不依赖 DB。
 */

// ===== SpeakEngineDialog =====

private val previewEngines = listOf(
    HttpTTS(
        id = 1L,
        name = "朗读引擎 A",
        url = "https://tts.example.com/a",
    ),
    HttpTTS(
        id = 2L,
        name = "朗读引擎 B",
        url = "https://tts.example.com/b",
    ),
    HttpTTS(
        id = 3L,
        name = "朗读引擎 C",
        url = "https://tts.example.com/c",
    ),
)

@Preview
@Composable
fun SpeakEngineDialogPreview() = LegadoThemePreview {
    SpeakEngineDialog(
        engines = previewEngines,
        selectedEngineUrl = "1",
        onSelectEngine = {},
        onEditEngines = {},
        onDeleteEngine = {},
        onDismiss = {},
    )
}

@Preview
@Composable
fun SpeakEngineDialogSysDefaultPreview() = LegadoThemePreview {
    // 选中"系统默认"
    SpeakEngineDialog(
        engines = previewEngines,
        selectedEngineUrl = null,
        onSelectEngine = {},
        onEditEngines = {},
        onDeleteEngine = {},
        onDismiss = {},
    )
}

@Preview
@Composable
fun SpeakEngineDialogEmptyPreview() = LegadoThemePreview {
    SpeakEngineDialog(
        engines = emptyList(),
        selectedEngineUrl = null,
        onSelectEngine = {},
        onEditEngines = {},
        onDeleteEngine = {},
        onDismiss = {},
    )
}

@Preview
@Composable
fun SpeakEngineDialogDarkPreview() = LegadoThemePreview(dark = true) {
    SpeakEngineDialog(
        engines = previewEngines,
        selectedEngineUrl = "2",
        onSelectEngine = {},
        onEditEngines = {},
        onDeleteEngine = {},
        onDismiss = {},
    )
}

// ===== ReadAloudDialog =====

@Preview
@Composable
fun ReadAloudDialogPlayingPreview() = LegadoThemePreview {
    ReadAloudDialog(
        isPlaying = true,
        initialTimer = 0,
        initialSpeechRate = 5,
        initialFollowSys = false,
        onPlayPause = {},
        onStop = {},
        onPrev = {},
        onNext = {},
        onPrevParagraph = {},
        onNextParagraph = {},
        onSetTimer = {},
        onAdjustSpeed = {},
        onFollowSysChange = {},
        onOpenChapterList = {},
        onShowMenuBar = {},
        onBackstage = {},
        onOpenSettings = {},
        onDismiss = {},
    )
}

@Preview
@Composable
fun ReadAloudDialogPausedPreview() = LegadoThemePreview {
    ReadAloudDialog(
        isPlaying = false,
        initialTimer = 30,
        initialSpeechRate = 15,
        initialFollowSys = false,
        onPlayPause = {},
        onStop = {},
        onPrev = {},
        onNext = {},
        onPrevParagraph = {},
        onNextParagraph = {},
        onSetTimer = {},
        onAdjustSpeed = {},
        onFollowSysChange = {},
        onOpenChapterList = {},
        onShowMenuBar = {},
        onBackstage = {},
        onOpenSettings = {},
        onDismiss = {},
    )
}

@Preview
@Composable
fun ReadAloudDialogFollowSysPreview() = LegadoThemePreview {
    ReadAloudDialog(
        isPlaying = true,
        initialTimer = 0,
        initialSpeechRate = 5,
        initialFollowSys = true,
        onPlayPause = {},
        onStop = {},
        onPrev = {},
        onNext = {},
        onPrevParagraph = {},
        onNextParagraph = {},
        onSetTimer = {},
        onAdjustSpeed = {},
        onFollowSysChange = {},
        onOpenChapterList = {},
        onShowMenuBar = {},
        onBackstage = {},
        onOpenSettings = {},
        onDismiss = {},
    )
}

@Preview
@Composable
fun ReadAloudDialogDarkPreview() = LegadoThemePreview(dark = true) {
    ReadAloudDialog(
        isPlaying = true,
        initialTimer = 60,
        initialSpeechRate = 10,
        initialFollowSys = false,
        onPlayPause = {},
        onStop = {},
        onPrev = {},
        onNext = {},
        onPrevParagraph = {},
        onNextParagraph = {},
        onSetTimer = {},
        onAdjustSpeed = {},
        onFollowSysChange = {},
        onOpenChapterList = {},
        onShowMenuBar = {},
        onBackstage = {},
        onOpenSettings = {},
        onDismiss = {},
    )
}

// ===== PageKeyDialog =====

private val previewKeyMappings = mapOf(
    21 to "prev_page",  // KEYCODE_DPAD_LEFT
    22 to "next_page",  // KEYCODE_DPAD_RIGHT
    19 to "prev_page",  // KEYCODE_DPAD_UP
    20 to "next_page",  // KEYCODE_DPAD_DOWN
)

@Preview
@Composable
fun PageKeyDialogPreview() = LegadoThemePreview {
    PageKeyDialog(
        keyMappings = previewKeyMappings,
        onConfirm = {},
        onDismiss = {},
    )
}

@Preview
@Composable
fun PageKeyDialogEmptyPreview() = LegadoThemePreview {
    PageKeyDialog(
        keyMappings = emptyMap(),
        onConfirm = {},
        onDismiss = {},
    )
}

@Preview
@Composable
fun PageKeyDialogDarkPreview() = LegadoThemePreview(dark = true) {
    PageKeyDialog(
        keyMappings = previewKeyMappings,
        onConfirm = {},
        onDismiss = {},
    )
}

// ===== ClickActionDialog =====

@Preview
@Composable
fun ClickActionDialogPreview() = LegadoThemePreview {
    // 用 ClickActionConfig 默认值 (TL=2/TR=1/MC=0 等)
    ClickActionDialog(
        clickActionConfig = ClickActionConfig(),
        onConfirm = {},
        onDismiss = {},
    )
}

@Preview
@Composable
fun ClickActionDialogAllMenuPreview() = LegadoThemePreview {
    // 所有区域都设为"菜单"(0)
    ClickActionDialog(
        clickActionConfig = ClickActionConfig(
            tl = 0, tc = 0, tr = 0,
            ml = 0, mc = 0, mr = 0,
            bl = 0, bc = 0, br = 0,
        ),
        onConfirm = {},
        onDismiss = {},
    )
}

@Preview
@Composable
fun ClickActionDialogMixedPreview() = LegadoThemePreview {
    // 混合配置: 上区域翻页, 中区域菜单, 下区域朗读
    ClickActionDialog(
        clickActionConfig = ClickActionConfig(
            tl = 2, tc = 2, tr = 1,
            ml = 0, mc = 0, mr = 0,
            bl = 5, bc = 6, br = 5,
        ),
        onConfirm = {},
        onDismiss = {},
    )
}

@Preview
@Composable
fun ClickActionDialogDarkPreview() = LegadoThemePreview(dark = true) {
    ClickActionDialog(
        clickActionConfig = ClickActionConfig(),
        onConfirm = {},
        onDismiss = {},
    )
}
