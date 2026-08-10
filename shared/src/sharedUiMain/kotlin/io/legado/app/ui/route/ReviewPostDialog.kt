package io.legado.app.ui.route

import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import io.legado.app.ui.book.read.review.ReviewPostScreen
import io.legado.app.ui.book.read.review.ReviewPostScreenModel
import io.legado.app.ui.book.read.review.ReviewPostUiActions
import io.legado.app.ui.book.read.review.ReviewPostUiEvent
import io.legado.app.ui.compose.component.AppBottomSheetDialog
import io.legado.app.ui.compose.component.AppDialogSizes
import io.legado.app.ui.compose.component.appDialogSize
import io.legado.app.ui.compose.platform.bottomSheetBottomInsets
import io.legado.app.ui.compose.theme.AppTheme
import io.legado.app.ui.compose.theme.AppTheme.DesignTokens
import legado.shared.generated.resources.Res
import legado.shared.generated.resources.reply_review_to
import legado.shared.generated.resources.review_post_hint
import org.jetbrains.compose.resources.stringResource

/**
 * 发表段评/书评弹窗形态 (对照原版 ReviewPostActivity 底部输入面板)。
 *
 * 原版 ReviewPostActivity 是独立 Activity 模拟 BottomSheet 的输入面板 (Manifest
 * `android:theme="@style/AppTheme.BottomSheetInput"`, 注释"段评输入面板(模拟 BottomSheet 的 Activity)"),
 * `WindowCompat.setDecorFitsSystemWindows(window, false)` 开 edge-to-edge, sheet 贴底 +
 * `translationY = -ime.bottom` 跟随软键盘上浮 (WindowInsetsAnimationCompat 平滑跟动画),
 * ime 从可见变不可见时 finish(); KMP 迁移后曾退化为居中普通对话框, 本弹窗恢复底部输入面板形态,
 * 逻辑全在 shared (桌面端与 app 端共用同一份代码):
 * - [AppBottomSheetDialog] 外壳 (底部贴齐 + 滑入/滑出动画 + scrim, 对照原版 sheet
 *   layout_gravity=bottom); properties 走 [AppDialogSizes.properties] 并显式
 *   decorFitsSystemWindows=false (Android: 窗口 edge-to-edge, ime insets 全量派发,
 *   键盘跟随/收起检测才可靠; 窗口主题自带 dim, 补原版 scrim 语义)
 * - 面板底部 `windowInsetsPadding(bottomSheetBottomInsets())`: Android 上
 *   ime ∪ 导航栏 (max 语义), sheet 底边顶在软键盘上方 (对照原版 translationY=-ime.bottom
 *   的"整体上移"表现), 键盘收起时回落; desktop/iOS/鸿蒙 无导航栏语义, 仅 ime,
 *   inset 为 0 时该 padding 为 no-op, 仅保留贴底面板形态
 * - 键盘收起即关闭面板 (对照原版 onApplyWindowInsets 的 imeWasVisible 跟踪 + finish);
 *   主动提交/返回都是直接移除弹窗组合, 组合销毁后本监听随之消失, 无二次触发路径
 * - 无标题栏 (对照原版无 ActionBar), 关闭路径 = 点击外部 + 返回键 (AppBottomSheetDialog
 *   的透明点击层 / BackLayerHandler)
 * - 正文复用 [ReviewPostScreen] (输入框 + 提交按钮同行); 输入框进入即聚焦 + 弹键盘
 *   (对照原版 requestFocus + windowSoftInputMode=stateAlwaysVisible)
 * - 提交: trim 后经 [onPosted] 回传, 由调用方 (ReviewListDialog / ReviewListRoute) 处理网络提交;
 *   随后立即关闭, 对齐 Activity submit = setResult + finish
 * - [replyPreview] 非空时构造 "回复 xxx…" hint (对照 Activity onCreate EXTRA_REPLY_PREVIEW)
 *
 * @param replyPreview 回复预览 (回复评论时为被回复内容, 发书评为 null)
 * @param onPosted 提交回调 (content 已 trim)
 * @param onDismiss 关闭回调 (返回键/点外部/键盘收起)
 */
@Composable
fun ReviewPostDialogHost(
    replyPreview: String?,
    onPosted: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    // 弹窗非路由页: 不经 ScreenModelStore, remember 局部持有即可 (ScreenModel 无平台依赖)
    val screenModel = remember { ReviewPostScreenModel() }
    val state by screenModel.state.collectAsState()

    // 对照 Activity onCreate hint 构造: replyPreview 非空 → "回复 xxx…", 否则 review_post_hint
    val hint = if (!replyPreview.isNullOrBlank()) {
        // take(15) + 省略号, 与 app 端 ReviewPostActivity 一致
        val trimmed = replyPreview.take(15).let {
            if (replyPreview.length > 15) "$it…" else it
        }
        stringResource(Res.string.reply_review_to, trimmed)
    } else {
        stringResource(Res.string.review_post_hint)
    }
    LaunchedEffect(hint) {
        screenModel.dispatch(ReviewPostUiEvent.ShowHint(hint))
    }

    val actions = object : ReviewPostUiActions {
        override fun onContentChange(content: String) {
            screenModel.dispatch(ReviewPostUiEvent.ContentChange(content))
        }

        // 提交: trim 后回传 content 并关闭, 由上层 (ReviewListDialog/ReviewViewModel) 处理网络提交
        // 对照 Activity submit: setResult(RESULT_OK, Intent().putExtra(RESULT_CONTENT, trimmed)) + finish
        override fun onSubmit() {
            val trimmed = state.content.trim()
            if (trimmed.isBlank()) return
            onPosted(trimmed)
            onDismiss()
        }
    }

    AppBottomSheetDialog(
        onDismissRequest = onDismiss,
        // decor=false: Android 窗口 edge-to-edge, ime insets 可靠 (键盘跟随/收起检测的前提);
        // 窗口主题 FloatingDialogWindowTheme 自带 dim (补原版 scrim 暗化)
        properties = AppDialogSizes.properties(decorFitsSystemWindows = false),
    ) {
        AppTheme {
            // 键盘收起即关闭 (对照原版 imeWasVisible 跟踪 + finish 守卫)
            ImeHideWatcher(onDismiss = onDismiss)
            Surface(
                shape = DesignTokens.dialogShape,
                color = AppTheme.colors.background,
                // 底部避让: Android = ime ∪ 导航栏 (键盘顶起 / 键盘收起时落在导航栏上方,
                // 对照原版 translationY=-ime.bottom); 无键盘平台 inset 为 0, 等价贴底面板
                modifier = Modifier
                    .appDialogSize()
                    .windowInsetsPadding(bottomSheetBottomInsets())
                    .padding(16.dp),
            ) {
                ReviewPostScreen(state = state, actions = actions)
            }
        }
    }
}

/**
 * 键盘从可见变收起 → 关闭面板 (对照原版 ReviewPostActivity onApplyWindowInsets 的
 * imeWasVisible 跟踪 + finish)。原版守卫 "dismissed 时跳过" 防的是主动 finish 里
 * hideSoftInput 引起的二次回调; 本弹窗主动关闭 (提交/返回/点外部) 都是移除组合,
 * 本监听随组合销毁, 无二次触发路径, 无需守卫。
 *
 * 必须放在 Dialog 内容内: WindowInsets.ime 读的是对话框窗口的 insets。
 * 非 Android 平台 ime 恒 0, 本监听恒不触发。
 */
@Composable
private fun ImeHideWatcher(onDismiss: () -> Unit) {
    val density = LocalDensity.current
    val imeVisible = WindowInsets.ime.getBottom(density) > 0
    var imeWasVisible by remember { mutableStateOf(false) }
    LaunchedEffect(imeVisible) {
        if (imeVisible) {
            imeWasVisible = true
        } else if (imeWasVisible) {
            onDismiss()
        }
    }
}
