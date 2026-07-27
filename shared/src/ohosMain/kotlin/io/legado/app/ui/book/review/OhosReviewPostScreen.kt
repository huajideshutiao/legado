package io.legado.app.ui.book.review

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.Button
import androidx.compose.material.OutlinedTextField
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import io.legado.app.data.entities.Book
import io.legado.app.help.toast.Toasters
import io.legado.app.ui.compose.component.AppTitleBar
import io.legado.app.ui.compose.platform.rememberString
import io.legado.app.ui.compose.theme.AppTheme

/**
 * 鸿蒙端书评/段评发布 Screen 入口 (对照 app 端 [io.legado.app.ui.book.read.ReviewPostActivity])。
 *
 * # 背景
 *
 * app 端 [ReviewPostActivity] 是一个 BottomSheet 风格的段评输入面板, 用 BasicTextField + 圆角气泡
 * decorationBox 实现, 用于在阅读页 / 段评列表内发布段评或回复段评。
 * 段评内容仅为文本 (无评分星级), 通过 Intent result 返回给调用方 ([io.legado.app.ui.book.read.ReviewListDialog].submitPost)。
 *
 * 鸿蒙端为对齐"发布书评"路由化需求 (REVIEW_POST 路由), 提供 [OhosReviewPostScreen] 作为独立路由入口,
 * 内部仅做 stub 让路由可用, 后续接入真实段评提交流程 (reviewRule.replyRule)。
 *
 * # Stub 内容
 *
 * - 顶部 TitleBar 显示 "发布书评"
 * - 中间 OutlinedTextField 多行编辑区 (占位文案 "想说点什么？")
 * - 底部 Button 发布按钮 (启用条件: 文本非空)
 *
 * # 与 app 端差异
 *
 * - **UI 形态**: app 端是 BottomSheet 风格输入面板 (BasicTextField + 圆角气泡 decorationBox);
 *   鸿蒙端是独立全屏路由 (OutlinedTextField 多行编辑区)
 * - **提交链路**: app 端通过 Intent result 返回文本给 ReviewListDialog; 鸿蒙端无 ReviewListDialog
 *   包装, onPosted 仅切回原路由, 真实提交 (reviewRule.replyRule JS) 待后续接入
 * - **回复场景**: app 端支持 replyPreview 占位文案 (回复某条段评); 鸿蒙端暂未接入
 * - **评分星级**: app 端原 ReviewPostActivity 无评分星级 (段评纯文本), 鸿蒙端亦不接入
 *
 * @param book 待发布书评的书籍 (由 OhosNavHost 注入; 预留给后续 reviewRule 提交取 book.origin)
 * @param onBack 返回回调 (切回原路由, 由 OhosNavHost 注入)
 * @param onPosted 发布完成回调 (由 OhosNavHost 注入切回原路由; stub 阶段不调用, 真实提交成功后调用)
 */
@Composable
fun OhosReviewPostScreen(
    book: Book,
    onBack: () -> Unit,
    onPosted: () -> Unit,
) {
    var reviewText by remember { mutableStateOf("") }
    val publishTitle = rememberString("publish_review")
    val postButtonText = rememberString("post_review")
    val hintText = rememberString("review_post_hint")
    val notImplementedText = "鸿蒙端书评发布待接入"

    // book 切换时清空输入 (路由复用同一 Screen, 切换不同 book 时重置)
    LaunchedEffect(book) {
        reviewText = ""
    }

    Column(Modifier.fillMaxSize()) {
        AppTitleBar(
            title = publishTitle,
            onBack = onBack,
        )
        // 内容编辑区 (多行)
        OutlinedTextField(
            value = reviewText,
            onValueChange = { reviewText = it },
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .padding(16.dp),
            placeholder = {
                Text(
                    text = hintText,
                    color = AppTheme.colors.secondaryText,
                )
            },
        )
        // 底部发布按钮
        Button(
            onClick = {
                // TODO: 接入书源 reviewRule.replyRule 提交段评
                //   1. 用 book.origin 查 BookSource
                //   2. 取 source.reviewRule?.replyRule (JS)
                //   3. 执行 JS (变量: paragraphIndex / content / reviewId)
                //   4. 成功后调 onPosted() 切回原路由
                Toasters.get().toast(notImplementedText)
            },
            enabled = reviewText.isNotBlank(),
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
        ) {
            Text(postButtonText)
        }
    }
}
