package io.legado.app.ui.config

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import io.legado.app.constant.PreferKey
import io.legado.app.help.config.PreferenceProviders
import io.legado.app.help.file.AppFilesDirs
import io.legado.app.help.file.pickDocumentContent
import io.legado.app.help.file.pickDocuments
import io.legado.app.help.toast.Toasters
import io.legado.app.ui.compose.component.AppTitleBar
import io.legado.app.ui.compose.platform.rememberString
import io.legado.app.ui.dialog.NumberPickerDialog
import io.legado.app.utils.MD5Utils
import io.legado.app.utils.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 鸿蒙端封面设置页入口 (包装 shared/sharedUiMain 的 [CoverConfigScreen])。
 *
 * 实现模式参考 iOS 端 [IosCoverConfigScreen]: 复用 sharedUiMain 跨平台 Composable,
 * 避免复制代码; 顶栏用 sharedUiMain 的 [AppTitleBar] (项目锁 MD2 视觉, 不用 material3 TopAppBar)。
 *
 * @param onBack 返回回调
 */
@Composable
fun OhosCoverConfigScreen(
    onBack: () -> Unit,
) {
    val coverHeightLabel = rememberString("bookshelf_cover_height")
    val selectImageText = rememberString("select_image")
    val saveFailedText = rememberString("save_failed")

    // bookshelfCoverHeight 当前值 + 显隐状态 (mutableIntStateOf 让 summary 重组)
    val prefs = remember { PreferenceProviders.get() }
    var coverHeight by remember {
        mutableIntStateOf(prefs.getInt(PreferKey.bookshelfCoverHeight, 160))
    }
    var showCoverHeightDialog by remember { mutableStateOf(false) }
    val coverHeightSummary = coverHeight.toString()

    // 默认封面路径 (日/夜), 从 prefs 读取初始值, 选择后更新触发 summary 重组
    var dayCoverPath by remember { mutableStateOf(prefs.getString(PreferKey.defaultCover)) }
    var nightCoverPath by remember { mutableStateOf(prefs.getString(PreferKey.defaultCoverDark)) }
    val dayCoverSummary = dayCoverPath.ifEmpty { selectImageText }
    val nightCoverSummary = nightCoverPath.ifEmpty { selectImageText }

    val scope = rememberCoroutineScope()

    Column(Modifier.fillMaxSize()) {
        AppTitleBar(
            title = rememberString("cover_config"),
            onBack = onBack,
        )
        // 鸿蒙端: onDefaultCover 走 OhosFilePicker 选图落盘; onRefreshCover no-op (BookCover 是 Android 专属)
        CoverConfigScreen(
            onDefaultCover = { isNight ->
                // pickDocuments/pickDocumentContent 阻塞调用, 协程中执行
                scope.launch {
                    val urls = pickDocuments(
                        contentTypes = listOf("public.image"),
                        allowsMultiple = false,
                    ) ?: return@launch // 用户取消或桥接未就绪
                    val firstUrl = urls.firstOrNull() ?: return@launch
                    val bytes = withContext(Dispatchers.Default) {
                        pickDocumentContent(firstUrl)
                    } ?: return@launch
                    // 落盘到 {filesDir}/covers/default/{md5}.png
                    val filesDir = AppFilesDirs.get().filesDir
                    val coversDirPath = if (filesDir.endsWith("/")) {
                        "${filesDir}covers/default"
                    } else {
                        "$filesDir/covers/default"
                    }
                    val coverPath = "$coversDirPath/${MD5Utils.md5Encode(firstUrl)}.png"
                    runCatching {
                        val coversDir = File(coversDirPath)
                        if (!coversDir.exists()) coversDir.mkdirs()
                        File(coverPath).writeBytes(bytes)
                    }.onFailure {
                        Toasters.get().toast(saveFailedText)
                        return@launch
                    }
                    val prefKey = if (isNight) PreferKey.defaultCoverDark else PreferKey.defaultCover
                    prefs.putString(prefKey, coverPath)
                    if (isNight) nightCoverPath = coverPath else dayCoverPath = coverPath
                }
            },
            onCoverHeight = { showCoverHeightDialog = true },
            coverHeightSummary = coverHeightSummary,
            dayCoverSummary = dayCoverSummary,
            nightCoverSummary = nightCoverSummary,
            onRefreshCover = {
                // 鸿蒙端无 BookCover 缓存, no-op
            },
        )
    }

    // coverHeight NumberPicker (范围 100..300, 默认 160)
    if (showCoverHeightDialog) {
        NumberPickerDialog(
            title = coverHeightLabel,
            value = coverHeight,
            range = 100..300,
            onConfirm = {
                coverHeight = it
                prefs.putInt(PreferKey.bookshelfCoverHeight, it)
                showCoverHeightDialog = false
            },
            onDismiss = { showCoverHeightDialog = false },
        )
    }
}
