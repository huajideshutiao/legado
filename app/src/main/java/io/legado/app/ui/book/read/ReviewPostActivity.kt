package io.legado.app.ui.book.read

import android.content.Intent
import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Easing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.imeAnimationTarget
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.colorResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.view.WindowCompat
import io.legado.app.R
import io.legado.app.ui.compose.platform.AndroidAppConfigProvider
import io.legado.app.ui.compose.platform.AndroidEventBusProvider
import io.legado.app.ui.compose.platform.AndroidPreferenceStoreProvider
import io.legado.app.ui.compose.platform.AndroidThemeStoreProvider
import io.legado.app.ui.compose.platform.LocalAppConfigProvider
import io.legado.app.ui.compose.platform.LocalEventBusProvider
import io.legado.app.ui.compose.platform.LocalPreferenceStoreProvider
import io.legado.app.ui.compose.platform.LocalThemeStoreProvider
import io.legado.app.ui.compose.theme.AppTheme
import io.legado.app.utils.hideSoftInput
import kotlinx.coroutines.launch

/** 段评输入面板。独立 Activity 模拟 BottomSheet,避免叠在 ReviewListDialog 上时
 *  双层 BottomSheetDialogFragment 在 IME inset/动画时序上互相干扰。
 *
 *  sheet 的 translationY = exitY + imeY:imeY = -ime.bottom 让 sheet 跟随 IME 顶,
 *  入场即天然 slide-in;exitY 由退场动画驱动滑到屏外(dismissed 后冻结 imeY)。
 */
class ReviewPostActivity : AppCompatActivity() {

    // Compose state：finish() 置位触发退场动画，动画毕才真正 finish
    private var dismissed by mutableStateOf(false)

    override fun onCreate(savedInstanceState: Bundle?) {
        WindowCompat.setDecorFitsSystemWindows(window, false)
        super.onCreate(savedInstanceState)
        @Suppress("DEPRECATION")
        overridePendingTransition(0, 0)

        val replyPreview = intent.getStringExtra(EXTRA_REPLY_PREVIEW)
        val hint = if (!replyPreview.isNullOrBlank()) {
            val trimmed = replyPreview.take(15).let {
                if (replyPreview.length > 15) "$it…" else it
            }
            getString(R.string.reply_review_to, trimmed)
        } else {
            getString(R.string.review_post_hint)
        }
        setContent {
            // 注入 Android actual Provider，供 commonMain AppTheme 通过 LocalXxx 取依赖
            val themeStoreProvider = remember { AndroidThemeStoreProvider() }
            val appConfigProvider = remember { AndroidAppConfigProvider() }
            val eventBusProvider = remember { AndroidEventBusProvider() }
            val preferenceStoreProvider = remember { AndroidPreferenceStoreProvider() }
            CompositionLocalProvider(
                LocalThemeStoreProvider provides themeStoreProvider,
                LocalAppConfigProvider provides appConfigProvider,
                LocalEventBusProvider provides eventBusProvider,
                LocalPreferenceStoreProvider provides preferenceStoreProvider,
            ) {
                AppTheme {
                    ReviewPostScreen(hint)
                }
            }
        }
    }

    private fun submit(text: String) {
        val trimmed = text.trim()
        if (trimmed.isBlank()) return
        setResult(RESULT_OK, Intent().putExtra(RESULT_CONTENT, trimmed))
        finish()
    }

    /** 所有关闭路径都收敛到这里走退场动画。 */
    override fun finish() {
        if (dismissed) return
        dismissed = true
        currentFocus?.hideSoftInput()
    }

    private fun finishAfterExit() {
        super.finish()
        @Suppress("DEPRECATION")
        overridePendingTransition(0, 0)
    }

    @OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
    @Composable
    private fun ReviewPostScreen(hint: String) {
        val density = LocalDensity.current
        var text by remember { mutableStateOf("") }
        val focusRequester = remember { FocusRequester() }
        val rootAlpha = remember { Animatable(0f) }
        val scrimAlpha = remember { Animatable(1f) }
        val exitY = remember { Animatable(0f) }
        var sheetHeight by remember { mutableIntStateOf(0) }

        // IME 跟随；dismissed 后冻结，由 exitY 单独驱动 sheet 到屏外
        val imeBottom = WindowInsets.ime.getBottom(density)
        var frozenImeY by remember { mutableFloatStateOf(0f) }
        SideEffect {
            if (!dismissed) frozenImeY = -imeBottom.toFloat()
        }
        val imeY = if (dismissed) frozenImeY else -imeBottom.toFloat()

        // 返回键路径:系统先收起 IME 再触发 finish。用动画目标值判定,
        // 与原 onApplyWindowInsets 一样在收起动画起始帧就响应
        val imeVisibleTarget = WindowInsets.imeAnimationTarget.getBottom(density) > 0
        var imeWasVisible by remember { mutableStateOf(false) }
        LaunchedEffect(imeVisibleTarget) {
            if (imeVisibleTarget) {
                imeWasVisible = true
            } else if (imeWasVisible && !dismissed) {
                finish()
            }
        }

        LaunchedEffect(Unit) {
            focusRequester.requestFocus()
            rootAlpha.animateTo(1f, tween(ANIM_DURATION))
        }
        // 退场：scrim 淡出 + sheet 滑出(补偿冻结 imeY 让终值 = sheet 高度即完全离屏)
        LaunchedEffect(dismissed) {
            if (!dismissed) return@LaunchedEffect
            launch { scrimAlpha.animateTo(0f, tween(ANIM_DURATION)) }
            exitY.animateTo(
                targetValue = sheetHeight.toFloat() - frozenImeY,
                animationSpec = tween(ANIM_DURATION, easing = AccelerateEasing),
            )
            finishAfterExit()
        }

        Box(
            Modifier
                .fillMaxSize()
                .graphicsLayer { alpha = rootAlpha.value }
        ) {
            Box(
                Modifier
                    .fillMaxSize()
                    .graphicsLayer { alpha = scrimAlpha.value }
                    .background(Color(0x80000000))
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                    ) { finish() }
            )
            Column(
                Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .onSizeChanged { sheetHeight = it.height }
                    .graphicsLayer { translationY = exitY.value + imeY }
                    .clip(RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp))
                    .background(colorResource(R.color.background))
                    // 消费点击，防止穿透到 scrim 关闭
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                    ) {}
                    .padding(horizontal = 16.dp)
                    .padding(top = 8.dp)
            ) {
                Row(
                    Modifier
                        .fillMaxWidth()
                        .padding(bottom = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    // 保留 BasicTextField: 自定义 decorationBox 实现 18dp 圆角气泡式聊天输入, AppTextField outlined 边框不匹配
                    BasicTextField(
                        value = text,
                        onValueChange = { text = it },
                        modifier = Modifier
                            .weight(1f)
                            .focusRequester(focusRequester),
                        textStyle = TextStyle(
                            color = colorResource(R.color.primaryText),
                            fontSize = 13.sp,
                        ),
                        cursorBrush = SolidColor(colorResource(R.color.accent)),
                        maxLines = 6,
                        keyboardOptions = KeyboardOptions(
                            capitalization = KeyboardCapitalization.Sentences,
                            imeAction = ImeAction.Send,
                        ),
                        keyboardActions = KeyboardActions(onSend = { submit(text) }),
                        decorationBox = { innerTextField ->
                            Box(
                                Modifier
                                    .clip(RoundedCornerShape(18.dp))
                                    .background(colorResource(R.color.background_card))
                                    .heightIn(min = 40.dp)
                                    .padding(horizontal = 16.dp, vertical = 12.dp),
                                contentAlignment = Alignment.CenterStart,
                            ) {
                                if (text.isEmpty()) {
                                    Text(
                                        text = hint,
                                        color = colorResource(R.color.secondaryText),
                                        fontSize = 13.sp,
                                    )
                                }
                                innerTextField()
                            }
                        },
                    )
                    Text(
                        text = stringResource(R.string.post_review),
                        color = colorResource(R.color.secondaryText),
                        fontSize = 14.sp,
                        modifier = Modifier
                            .padding(start = 8.dp)
                            .clickable(enabled = text.isNotBlank()) { submit(text) }
                            .padding(horizontal = 12.dp, vertical = 8.dp),
                    )
                }
            }
        }
    }

    companion object {
        const val EXTRA_REPLY_PREVIEW = "replyPreview"
        const val RESULT_CONTENT = "content"
        private const val ANIM_DURATION = 200

        // 对齐原 AccelerateInterpolator(factor=1)
        private val AccelerateEasing = Easing { it * it }
    }
}
