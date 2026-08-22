package io.legado.app.ui.route

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.unit.dp
import io.legado.app.ui.book.read.review.ReviewPostScreen
import io.legado.app.ui.book.read.review.ReviewPostScreenModel
import io.legado.app.ui.book.read.review.ReviewPostUiActions
import io.legado.app.ui.book.read.review.ReviewPostUiEvent
import io.legado.app.ui.compose.component.AppBottomSheetDialog
import io.legado.app.ui.compose.component.AppDialogSizes
import io.legado.app.ui.compose.component.LocalSheetDismissRequest
import io.legado.app.ui.compose.platform.bottomSheetBottomInsets
import io.legado.app.ui.compose.platform.rememberImeVisible
import io.legado.app.ui.compose.theme.AppTheme
import legado.shared.generated.resources.Res
import legado.shared.generated.resources.reply_review_to
import legado.shared.generated.resources.review_post_hint
import org.jetbrains.compose.resources.stringResource

/**
 * 发表段评/书评弹窗形态 (对照原版 ReviewPostActivity 底部输入面板)。
 *
 * 原版是独立 Activity 模拟 BottomSheet (Manifest `theme=AppTheme.BottomSheetInput` 透明窗口,
 * `windowSoftInputMode=stateAlwaysVisible|adjustNothing`, `setDecorFitsSystemWindows(false)`),
 * root 铺 scrim + sheet 贴底 `layout_gravity=bottom`, `translationY = -ime.bottom` 跟随软键盘,
 * ime 由可见变不可见时 finish()。本弹窗把该形态搬到四端, 逻辑全在 shared:
 * - [AppBottomSheetDialog] 外壳 (贴底 + 滑入/滑出动画 + 点外部/返回键关闭, 对照原版
 *   sheet 贴底 + root 点击 finish); properties 显式 decorFitsSystemWindows=false
 *   (Android: 窗口 edge-to-edge, ime insets 全量派发, 键盘跟随/收起检测才可靠)
 * - 面板 match_parent 宽 + 顶角 20dp (对照 bg_review_dialog), 底部
 *   `windowInsetsPadding(bottomSheetBottomInsets())` = ime ∪ 导航栏 (iOS 的导航栏
 *   inset 即底部安全区), 面板底边顶在软键盘上方 (对照 translationY=-ime.bottom)
 * - 键盘收起即关闭 (对照 onApplyWindowInsets 的 imeWasVisible 跟踪 + finish)
 * - 正文 [ReviewPostScreen] (输入框 + 发布按钮同行), 进入即聚焦 + 弹键盘
 * - 关闭全部收敛到 [ReviewPostSheetDismiss] (下沉原版 finish(): dismissed 幂等守卫 +
 *   收键盘 + 播退场动画)
 * - 取消 (返回键/点外部) 路径在 onDismissRequest 前置收键盘 + 清焦点: 输入框仍持焦时
 *   CMP Android 销毁弹窗窗口不必然收起 IME, 会残留在下层界面上 (清焦动作由内容侧注册,
 *   见 cancelCleanup 桥的窗口作用域说明)
 * - [replyPreview] 非空时构造 "回复: xxx…" hint (对照 onCreate EXTRA_REPLY_PREVIEW)
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

    // 对照 Activity onCreate hint 构造: replyPreview 非空 → "回复: xxx…", 否则 review_post_hint
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

    // 取消清焦桥: onDismissRequest lambda 定义在 Dialog 外 (主窗口作用域), 拿不到弹窗窗口的
    // 键盘/焦点管理器 (LocalFocusManager/LocalSoftwareKeyboardController 按 Owner 提供, 弹窗
    // 是独立窗口); 真正的收键盘+清焦动作由 Dialog 内容侧注册, 取消路径在这里调用
    val cancelCleanup = remember { mutableStateOf<(() -> Unit)?>(null) }
    AppBottomSheetDialog(
        onDismissRequest = {
            // 取消 (返回键/点外部) 同样收键盘 + 清焦点, 防止 IME 残留在下层界面上
            cancelCleanup.value?.invoke()
            onDismiss()
        },
        // decor=false: Android 窗口 edge-to-edge, ime insets 可靠 (键盘跟随/收起检测的前提)
        properties = AppDialogSizes.properties(decorFitsSystemWindows = false),
    ) {
        AppTheme {
            // 关闭收敛 (对照原版 finish(): dismissed 幂等 + hideSoftInput + 退场动画);
            // 走 LocalSheetDismissRequest 才有退场动画, 直调 onDismiss 会砍掉动画
            val keyboard = LocalSoftwareKeyboardController.current
            val focusManager = LocalFocusManager.current
            val sheetDismiss = LocalSheetDismissRequest.current ?: onDismiss
            DisposableEffect(Unit) {
                cancelCleanup.value = {
                    keyboard?.hide()
                    focusManager.clearFocus()
                }
                onDispose { cancelCleanup.value = null }
            }
            var dismissed by remember { mutableStateOf(false) }
            val finish: () -> Unit = {
                if (!dismissed) {
                    dismissed = true
                    keyboard?.hide()
                    // 清焦点: 输入框仍持焦时仅 hide() 在部分机型上收不起 IME
                    focusManager.clearFocus()
                    sheetDismiss()
                }
            }
            // 键盘由可见变收起 → 关闭面板; dismissed 后跳过, 防 finish 里主动收键盘
            // 再触发一次 (对照原版 imeWasVisible + !dismissed 守卫)
            ReviewPostSheetDismiss(dismissed = dismissed, onImeHidden = finish)

            val actions = object : ReviewPostUiActions {
                override fun onContentChange(content: String) {
                    screenModel.dispatch(ReviewPostUiEvent.ContentChange(content))
                }

                // 对照 submit(): trim 后空白静默返回, 否则回传内容并按 finish 路径关闭
                override fun onSubmit() {
                    val trimmed = state.content.trim()
                    if (trimmed.isBlank()) return
                    onPosted(trimmed)
                    finish()
                }
            }

            Surface(
                // bg_review_dialog: 顶部左右 20dp 圆角, 底部直角 (贴屏底)
                shape = RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp),
                color = AppTheme.colors.background,
                // 原版 sheet 是 match_parent 宽 + 贴底, 不做对话框式收窄/外边距
                modifier = Modifier
                    .fillMaxWidth()
                    .windowInsetsPadding(bottomSheetBottomInsets()),
            ) {
                ReviewPostScreen(state = state, actions = actions)
            }
        }
    }
}

/**
 * 键盘从可见变收起 → 关闭面板 (对照原版 ReviewPostActivity onApplyWindowInsets 的
 * imeWasVisible 跟踪 + finish, 含 dismissed 守卫)。
 *
 * 用事件化的 [rememberImeVisible] 而非逐帧读 `WindowInsets.ime` 数值: 后者在键盘
 * 动画期间每帧变化, 会把本组合拖进逐帧重组; 前者只在可见性翻转时更新一次
 * (语义也与原版 `insets.isVisible(Type.ime())` 一致)。非 Android 平台恒 false, 不触发。
 */
@Composable
private fun ReviewPostSheetDismiss(dismissed: Boolean, onImeHidden: () -> Unit) {
    val imeVisible = rememberImeVisible()
    var imeWasVisible by remember { mutableStateOf(false) }
    LaunchedEffect(imeVisible, dismissed) {
        if (imeVisible) {
            imeWasVisible = true
        } else if (imeWasVisible && !dismissed) {
            onImeHidden()
        }
    }
}
