package io.legado.app.ui.route

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.material.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import io.legado.app.ui.book.read.review.ReviewPostScreen
import io.legado.app.ui.book.read.review.ReviewPostScreenModel
import io.legado.app.ui.book.read.review.ReviewPostUiActions
import io.legado.app.ui.book.read.review.ReviewPostUiEvent
import io.legado.app.ui.compose.component.AppBottomSheetDialog
import io.legado.app.ui.compose.component.AppDialogSizes
import io.legado.app.ui.compose.component.DialogTitleBar
import io.legado.app.ui.compose.component.appDialogSize
import io.legado.app.ui.compose.theme.AppTheme
import io.legado.app.ui.compose.theme.AppTheme.DesignTokens
import legado.shared.generated.resources.Res
import legado.shared.generated.resources.post_review
import legado.shared.generated.resources.reply_review_to
import legado.shared.generated.resources.review_post_hint
import org.jetbrains.compose.resources.stringResource

/**
 * 发表段评/书评弹窗形态 (对照原版 ReviewPostActivity 底部输入面板)。
 *
 * 原版 ReviewPostActivity 是独立 Activity 模拟 BottomSheet 的输入面板 (Manifest
 * `android:theme="@style/AppTheme.BottomSheetInput"`, 注释"段评输入面板(模拟 BottomSheet 的 Activity)"),
 * sheet 贴底 + `translationY = -ime.bottom` 跟随软键盘上浮 (WindowInsetsAnimationCompat 平滑跟
 * 动画), 提交后 setResult 回传 content 由 ReviewListDialog 处理网络提交; KMP 迁移后曾退化为居中
 * 普通对话框, 本弹窗恢复底部输入面板形态, 逻辑全在 shared (桌面端与 app 端共用同一份代码):
 * - [AppBottomSheetDialog] 外壳 (底部贴齐 + 滑入/滑出动画 + scrim, 对照原版 sheet
 *   layout_gravity=bottom), properties 用 [AppDialogSizes.properties]
 * - 面板整体 `imePadding()`: Android 上 sheet 底边顶在软键盘上方 (对照原版
 *   translationY=-ime.bottom 的"整体上移"表现), 键盘收起时回落; desktop/iOS/鸿蒙
 *   ime inset 为 0, 该 padding 为 no-op, 仅保留贴底面板形态
 * - 标题栏 [DialogTitleBar] (标题 post_review, 对照原 ReviewPostRoute)
 * - 正文复用 [ReviewPostScreen] (输入框 + 提交按钮, 已去掉整页标题栏); 输入框进入即聚焦,
 *   对照原版 requestFocus + windowSoftInputMode=stateAlwaysVisible (键盘随弹窗弹出)
 * - 提交: trim 后经 [onPosted] 回传, 由调用方 (ReviewListDialog / ReviewListRoute) 处理网络提交;
 *   随后立即关闭, 对齐 Activity submit = setResult + finish
 * - [replyPreview] 非空时构造 "回复 xxx…" hint (对照 Activity onCreate EXTRA_REPLY_PREVIEW)
 *
 * @param replyPreview 回复预览 (回复评论时为被回复内容, 发书评为 null)
 * @param onPosted 提交回调 (content 已 trim)
 * @param onDismiss 关闭回调 (返回键/点外部/标题栏返回)
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
        // 返回 (标题栏) → 关闭, 无回传 (对照 Activity finish 不 setResult)
        override fun onBack() {
            onDismiss()
        }

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
        properties = AppDialogSizes.properties(),
    ) {
        AppTheme {
            Surface(
                shape = DesignTokens.dialogShape,
                color = AppTheme.colors.background,
                // imePadding: sheet 底边顶在软键盘上方 (对照原版 translationY=-ime.bottom);
                // 无键盘平台 (桌面/iOS/鸿蒙) inset 为 0, 等价贴底面板
                modifier = Modifier
                    .appDialogSize()
                    .imePadding()
                    .padding(16.dp),
            ) {
                Column {
                    DialogTitleBar(
                        title = stringResource(Res.string.post_review),
                        onBack = onDismiss,
                    )
                    ReviewPostScreen(state = state, actions = actions)
                }
            }
        }
    }
}
