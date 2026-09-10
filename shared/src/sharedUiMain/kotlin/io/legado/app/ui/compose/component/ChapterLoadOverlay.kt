package io.legado.app.ui.compose.component

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.CircularProgressIndicator
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.legado.app.model.chapter.ChapterLoadState
import legado.shared.generated.resources.Res
import legado.shared.generated.resources.loading
import legado.shared.generated.resources.reload
import org.jetbrains.compose.resources.stringResource

/** 覆盖层重试按钮色 (原 漫画/视频 两份实现同值)。 */
private val OverlayActionColor = Color(0xFF165DFF)

/**
 * 章节加载中覆盖层 (漫画 / 音频 用: 转圈 + "加载中"文案)。
 *
 * @param indicator 自定义指示器 (视频侧传缓冲圈, 保持其原有样式)
 */
@Composable
fun ChapterLoadingOverlay(
    modifier: Modifier = Modifier,
    indicator: (@Composable () -> Unit)? = null,
) {
    Box(modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        if (indicator != null) {
            indicator()
            return@Box
        }
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            CircularProgressIndicator(
                color = Color.White,
                strokeWidth = 4.dp,
                modifier = Modifier.size(48.dp),
            )
            Text(
                text = stringResource(Res.string.loading),
                color = Color.White,
                modifier = Modifier.padding(top = 12.dp),
            )
        }
    }
}

/**
 * 章节加载失败覆盖层 (原 漫画 `MangaReaderScreenContent.ErrorOverlay` 与视频
 * `VideoPlayerScreenContent.ErrorOverlay` 两份逐字节相同的实现, 收敛为共享件)。
 */
@Composable
fun ChapterErrorOverlay(
    error: String,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(text = error, color = Color.White, textAlign = TextAlign.Center)
            Text(
                text = stringResource(Res.string.reload),
                color = OverlayActionColor,
                fontSize = 18.sp,
                modifier = Modifier
                    .padding(16.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .clickable { onRetry() }
                    .padding(horizontal = 16.dp, vertical = 8.dp),
            )
        }
    }
}

/**
 * 按 [ChapterLoadState] 渲染覆盖层: 失败优先于加载中 (对齐漫画原有 error 优先口径)。
 *
 * @param loadingIndicator 自定义加载指示器 (视频传缓冲圈)
 */
@Composable
fun ChapterLoadStateOverlay(
    state: ChapterLoadState,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier,
    loadingIndicator: (@Composable () -> Unit)? = null,
) {
    when (state) {
        is ChapterLoadState.Error -> ChapterErrorOverlay(
            error = state.message,
            onRetry = onRetry,
            modifier = modifier,
        )

        ChapterLoadState.Loading -> ChapterLoadingOverlay(
            modifier = modifier,
            indicator = loadingIndicator,
        )

        ChapterLoadState.Idle -> Unit
    }
}
