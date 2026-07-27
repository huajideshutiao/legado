package io.legado.desktop.ui.book.audio

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.Slider
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.legado.app.ui.compose.component.AlertButton
import io.legado.app.ui.compose.component.AppAlertDialog
import io.legado.app.ui.compose.theme.AppTheme
import io.legado.app.constant.AppLog
import io.legado.app.constant.BookType
import io.legado.app.constant.EventBus
import io.legado.app.constant.Status
import io.legado.app.data.entities.Book
import io.legado.app.help.http.OkHttpClientProviders
import io.legado.app.model.AudioPlayShared
import io.legado.app.ui.book.audio.AudioPlayScreenContent
import io.legado.app.ui.compose.platform.jvmGetString
import io.legado.app.ui.compose.platform.rememberString
import io.legado.app.utils.FlowBus
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Request
import java.io.ByteArrayInputStream
import java.io.File
import javax.imageio.ImageIO

/**
 * 桌面端音频播放 Screen (薄壳): 主体 UI 调用 shared [AudioPlayScreenContent], 平台特殊部分以 slot 注入。
 *
 * # 职责
 *
 * 包装 shared/commonMain 的 [AudioPlayShared] 跨组件状态, 通过 [FlowBus] 订阅
 * [EventBus].AUDIO_* 事件刷新 UI, 命令面经 [AudioPlayShared] 派发到
 * [io.legado.desktop.audio.DesktopAudioPlayProvider] (已注册为 AudioPlayCommander)。
 *
 * # 平台 slot
 *
 * - [AudioPlayScreenContent.coverSlot]: 圆形封面 (produceState + OkHttp + ImageIO, 失败兜底 "?")
 * - [AudioPlayScreenContent.blurBgSlot]: 模糊背景 (加载策略同 coverSlot, 失败兜底深色底)
 * - [AudioPlayScreenContent.lrcSlot]: LazyColumn 简化文本列表 + 当前行高亮
 * - [AudioPlayScreenContent.timerDialogSlot]/[speedDialogSlot]: AlertDialog + Slider
 *   (桌面端常见交互, 与 app 端 Popup 形态不同)
 * - [AudioPlayScreenContent.onStop]: 桌面端独有停止钮 (app 端传 null 不显示)
 *
 * # 生命周期
 *
 * - 进入: [LaunchedEffect](book.bookUrl) 调 [AudioPlayShared.resetData] 加载书 + 章节列表,
 *   status==STOP 时调 [AudioPlayShared.loadOrUpPlayUrl] 触发播放
 * - 退出: [DisposableEffect].onDispose 在非 PLAY 状态调 [AudioPlayShared.stop] 释放播放器
 *   (对照 app 端 AudioPlayActivity.onDestroy)
 *
 * @param book 待播放的音频书
 * @param onBack 返回回调 (切回调用方路由)
 * @param onOpenToc 打开目录回调 (切到 TOC 路由, 携带 Book)
 * @param onOpenChangeSource 打开换源回调 (切到 CHANGE_SOURCE 路由, 携带 Book)
 */
@Composable
fun AudioPlayScreen(
    book: Book,
    onBack: () -> Unit,
    onOpenToc: (Book) -> Unit = {},
    onOpenChangeSource: (Book) -> Unit = {},
) {
    // ---- 初始化 AudioPlayShared (对照 app 端 AudioPlayViewModel.initData) ----
    LaunchedEffect(book.bookUrl) {
        AudioPlayShared.inBookshelf = book.type and BookType.notShelf == 0
        AudioPlayShared.resetData(book)
        if (AudioPlayShared.status == Status.STOP) {
            AudioPlayShared.loadOrUpPlayUrl()
        }
    }
    // 退出时若已停/已暂停才停止播放, 释放 jlayer 播放器资源; LOADING(拉链接+缓冲中) 算"在播", 留给后台继续
    DisposableEffect(book.bookUrl) {
        onDispose {
            val cur = AudioPlayShared.status
            if (cur == Status.STOP || cur == Status.PAUSE) {
                AudioPlayShared.stop()
            }
        }
    }

    // ---- 订阅 EventBus sticky 事件为 Compose State (对照 app 端 observeLiveBus) ----
    val status by stickyEventState(EventBus.AUDIO_STATE, Status.STOP)
    val subTitle by stickyEventState(EventBus.AUDIO_SUB_TITLE, "")
    val durationMs by stickyEventState(EventBus.AUDIO_SIZE, 0)
    val progressMs by stickyEventState(EventBus.AUDIO_PROGRESS, 0)
    val bufferMs by stickyEventState(EventBus.AUDIO_BUFFER_PROGRESS, 0)
    val speed by stickyEventState(EventBus.AUDIO_SPEED, 1f)
    val timerMinute by stickyEventState(EventBus.AUDIO_DS, 0)
    val loading by stickyEventState(EventBus.AUDIO_LOADING, false)
    val playMode by stickyEventState(EventBus.PLAY_MODE_CHANGED, AudioPlayShared.PlayMode.LIST_END_STOP)
    val lrcData by stickyEventState(EventBus.AUDIO_LRC, emptyList<Pair<Int, String>>())
    val lrcProgress by stickyEventState(EventBus.AUDIO_LRCPROGRESS, -1)

    val isPlaying = status == Status.PLAY
    val coverUrl by stickyEventState(EventBus.AUDIO_COVER, "")
    val resolvedCoverUrl = coverUrl.ifBlank { book.getDisplayCover() }
    var coverVisible by remember { mutableStateOf(true) }

    // ---- 调用 shared AudioPlayScreenContent, 注入桌面端平台 slot ----
    AudioPlayScreenContent(
        title = book.name,
        subTitle = subTitle,
        coverUrl = resolvedCoverUrl,
        coverVisible = coverVisible && !resolvedCoverUrl.isNullOrEmpty(),
        timerMinute = timerMinute,
        speed = speed,
        progressMs = progressMs,
        durationMs = durationMs,
        bufferMs = bufferMs,
        isPlaying = isPlaying,
        loading = loading,
        playMode = playMode,
        prevEnabled = AudioPlayShared.durChapterIndex > 0,
        nextEnabled = AudioPlayShared.durChapterIndex < AudioPlayShared.simulatedChapterSize - 1,
        accentColor = AppTheme.colors.accent,
        onBack = onBack,
        onOpenChangeSource = { onOpenChangeSource(book) },
        onCoverClick = { coverVisible = false },
        onTogglePlay = {
            when (AudioPlayShared.status) {
                Status.PLAY -> AudioPlayShared.pause()
                Status.PAUSE -> AudioPlayShared.resume()
                else -> AudioPlayShared.loadOrUpPlayUrl()
            }
        },
        onPrev = { AudioPlayShared.prev() },
        onNext = { AudioPlayShared.next() },
        onChangePlayMode = { AudioPlayShared.changePlayMode() },
        onOpenToc = { onOpenToc(book) },
        onSeek = { AudioPlayShared.adjustProgress(it) },
        onSetTimer = { AudioPlayShared.setTimer(it) },
        onSetSpeed = { AudioPlayShared.adjustSpeed(it) },
        onStop = { AudioPlayShared.stop() },
        coverSlot = { url, modifier -> CoverSlot(url, modifier) },
        // blurBgSlot: 模糊背景独立兜底 (深色底, 与圆形封面 "?" 占位区分)
        blurBgSlot = { url, modifier -> BlurBgSlot(url, modifier) },
        lrcSlot = { modifier -> LrcSlot(lrcData, lrcProgress, modifier) },
        timerDialogSlot = { initial, onProgressChanged, onDismiss ->
            TimerDialog(initial, onProgressChanged, onDismiss)
        },
        speedDialogSlot = { initial, onProgressChanged, onDismiss ->
            SpeedDialog(initial, onProgressChanged, onDismiss)
        },
        // 桌面端默认参数: timerIconKey="ic_time_add_24dp", speedIconKey="ic_speed",
        // chapterListIconKey="ic_toc", filletLabelColor=0x66000000, playMenuButtonPressedBgEnabled=false,
        // playMenuAlpha=1f, titleBarHorizontalPadding=8dp, playModeIconPadding=4dp
    )
}

// ---- 平台 coverSlot: 圆形封面 (produceState + OkHttp + ImageIO, 失败兜底 "?") ----

/**
 * 圆形封面加载 Composable (modifier 由 shared 注入: 200dp + clip + border + clickable)。
 *
 * 加载成功: [Image] 铺满 modifier (contentScale=Crop 由 clip(CircleShape) 裁切为圆形);
 * 加载中/失败: 居中显示 "?" 占位 (对照原 DesktopBookCover.InfoCover 占位风格)。
 */
@Composable
private fun CoverSlot(coverUrl: String?, modifier: Modifier) {
    val bitmap by produceState<ImageBitmap?>(null, coverUrl) {
        if (coverUrl.isNullOrEmpty()) return@produceState
        value = loadAudioCover(coverUrl)
    }
    val bmp = bitmap
    Box(modifier, contentAlignment = Alignment.Center) {
        if (bmp != null) {
            Image(
                bitmap = bmp,
                contentDescription = rememberString("cover"),
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop,
            )
        } else {
            // 兜底: 书名首字 (对照 DesktopBookCover.InfoCover 占位风格)
            Text(
                text = "?",
                color = Color.White,
                fontSize = 36.sp,
                fontWeight = FontWeight.Bold,
            )
        }
    }
}

// ---- 平台 blurBgSlot: 模糊背景 (produceState + OkHttp + ImageIO, 失败兜底深色底) ----

/**
 * 模糊封面背景加载 Composable (modifier=matchParentSize)。
 *
 * 加载成功: [Image] 铺满 + Crop; 加载中/失败: 深色底兜底 (避免白底刺眼)。
 */
@Composable
private fun BlurBgSlot(coverUrl: String?, modifier: Modifier) {
    val bitmap by produceState<ImageBitmap?>(null, coverUrl) {
        if (coverUrl.isNullOrEmpty()) return@produceState
        value = loadAudioCover(coverUrl)
    }
    val bmp = bitmap
    if (bmp != null) {
        Image(
            bitmap = bmp,
            contentDescription = null,
            modifier = modifier,
            contentScale = ContentScale.Crop,
        )
    } else {
        // 加载中/失败: 深色底兜底 (避免白底刺眼)
        Box(modifier.fillMaxSize().background(Color(0xFF1A1A1A)))
    }
}

// ---- 平台 lrcSlot: LazyColumn 简化文本列表 + 当前行高亮 ----

/**
 * 歌词显示 (桌面端简化实现)。
 *
 * app 端用自绘 LrcView (滚动 + 渐变动画), 桌面端用 LazyColumn 文本列表 + 当前行高亮。
 * 歌词数据由 [io.legado.desktop.audio.DesktopAudioPlayProvider.loadLrcData] 经
 * EventBus.AUDIO_LRC 推送; AUDIO_LRCPROGRESS 推进当前行高亮 (每秒随播放位置刷新)。
 * 无歌词 (subContent 规则空 / 加载失败) 时显示占位文本。
 */
@Composable
private fun LrcSlot(
    lrcData: List<Pair<Int, String>>,
    lrcProgress: Int,
    modifier: Modifier = Modifier,
) {
    if (lrcData.isEmpty()) {
        // 无歌词: 居中占位提示
        Box(modifier, contentAlignment = Alignment.Center) {
            Text(
                text = rememberString("no_lyrics"),
                color = Color.White.copy(alpha = 0.5f),
                fontSize = 14.sp,
            )
        }
        return
    }
    // 计算当前高亮行 (时间戳 <= lrcProgress 的最后一行)
    val currentLine = remember(lrcData, lrcProgress) {
        lrcData.indexOfLast { it.first <= lrcProgress }.coerceAtLeast(0)
    }
    LazyColumn(
        modifier = modifier,
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        items(lrcData) { line ->
            val isActive = lrcData.indexOf(line) == currentLine
            Text(
                text = line.second,
                color = if (isActive) Color.White else Color.White.copy(alpha = 0.5f),
                fontSize = if (isActive) 16.sp else 14.sp,
                textAlign = TextAlign.Center,
            )
        }
    }
}

// ---- 定时/倍速弹窗 (AlertDialog + Slider, 桌面端常见交互) ----

@Composable
private fun TimerDialog(
    initial: Int,
    onProgressChanged: (Int) -> Unit,
    onDismiss: () -> Unit,
) {
    var value by remember { mutableIntStateOf(initial) }
    AppAlertDialog(
        onDismissRequest = onDismiss,
        title = rememberString("set_timer"),
        okButton = AlertButton(rememberString("ok")),
    ) {
        Column(Modifier.padding(horizontal = 24.dp)) {
            Text(rememberString("timer_m", value))
            Slider(
                value = value.toFloat(),
                onValueChange = {
                    value = it.toInt()
                    onProgressChanged(value)
                },
                valueRange = 0f..180f,
            )
        }
    }
}

@Composable
private fun SpeedDialog(
    initial: Float,
    onProgressChanged: (Float) -> Unit,
    onDismiss: () -> Unit,
) {
    var value by remember { mutableStateOf(initial) }
    AppAlertDialog(
        onDismissRequest = onDismiss,
        title = rememberString("speed"),
        okButton = AlertButton(rememberString("ok")),
    ) {
        Column(Modifier.padding(horizontal = 24.dp)) {
            Text("%.1fX".format(value))
            // 0.5..3.0 step 0.1 → 26 个值, steps = 26 - 2 = 24
            Slider(
                value = value,
                onValueChange = {
                    value = it
                    onProgressChanged(it)
                },
                valueRange = 0.5f..3.0f,
                steps = 24,
            )
        }
    }
}

// ---- 辅助: sticky 事件订阅为 Compose State (对照 app 端 observeEventSticky) ----

/**
 * 订阅 [FlowBus.withSticky] 事件为 Compose [State], 初始值为 [initial]。
 *
 * 桌面端等价 app 端 `observeEventSticky<EventBus.XXX> { ... }`:
 * FlowBus.withSticky 的 replay=1, 首次 collect 立即收到最近一次 post 的值;
 * 未 post 过时保持 [initial] 直到首次 post。
 */
@Composable
private fun <T> stickyEventState(key: String, initial: T): State<T> {
    return produceState(initial, key) {
        FlowBus.withSticky(key).collect {
            @Suppress("UNCHECKED_CAST")
            value = it as T
        }
    }
}

// ---- 辅助: 封面加载 (参照 DesktopBookCover.loadCoverBitmap, 不引入 Glide) ----

/**
 * 加载封面为 [ImageBitmap] (本地路径或网络 URL)。
 *
 * 参照 [io.legado.desktop.ui.component.DesktopBookCover] 的加载策略:
 * - `file://` / 绝对路径 `/...`: [ImageIO.read] 读文件
 * - `http://` / `https://`: [OkHttpClientProviders] 下载字节流后 [ImageIO.read] 解码
 * - 任意步骤异常: 返回 null (调用方走占位)
 */
private suspend fun loadAudioCover(src: String): ImageBitmap? = withContext(Dispatchers.IO) {
    runCatching {
        val image = when {
            src.startsWith("file://") -> ImageIO.read(File(src.removePrefix("file://")))
            src.startsWith("/") -> ImageIO.read(File(src))
            src.startsWith("http://") || src.startsWith("https://") -> {
                val client = OkHttpClientProviders.get().okHttpClient
                val req = Request.Builder().url(src).build()
                client.newCall(req).execute().use { resp ->
                    if (!resp.isSuccessful) null
                    else resp.body.bytes().let { ImageIO.read(ByteArrayInputStream(it)) }
                }
            }
            else -> null
        }
        image?.toComposeImageBitmap()
    }.onFailure { AppLog.put(jvmGetString("audio_cover_load_failed_log", src, it.message), it) }.getOrNull()
}
