package io.legado.app.ui.debug

import io.legado.app.ui.route.BgTextConfigDialogHost
import io.legado.app.ui.route.MoreConfigDialogHost
import io.legado.app.ui.route.ReadAloudConfigDialogHost
import io.legado.app.ui.route.ReadStyleDialogHost
import io.legado.app.ui.route.ReviewPostDialogHost
import legado.ui.generated.resources.Res
import legado.ui.generated.resources.aloud_config

/**
 * 底部弹层形制预览清单 (上圆角面板, 可拖拽收起)。
 *
 * 这几条都是阅读页/播放页的配置面板, 只依赖配置读写 (不依赖真实阅读会话), 可直接打开。
 * 段评发布面板需要发布上下文, 以占位回复预览打开。
 *
 * 这些面板都没有标题栏 (朗读设置除外), 名字只能写死 (用途说明)。
 */
internal fun buildSheetEntries(): List<DialogPreviewEntry.Inline> = listOf(
    DialogPreviewEntry.Inline(DialogPreviewGroup.SHEET, "sheet_more_config", nameText("阅读界面设置"), "MoreConfigDialogHost") { onDismiss ->
        MoreConfigDialogHost(onDismiss = onDismiss)
    },
    DialogPreviewEntry.Inline(DialogPreviewGroup.SHEET, "sheet_read_style", nameText("阅读样式设置"), "ReadStyleDialogHost") { onDismiss ->
        ReadStyleDialogHost(onDismiss = onDismiss)
    },
    DialogPreviewEntry.Inline(
        DialogPreviewGroup.SHEET,
        "sheet_read_aloud_config",
        titleOf(Res.string.aloud_config),
        "ReadAloudConfigDialogHost",
    ) { onDismiss ->
        ReadAloudConfigDialogHost(onDismiss = onDismiss)
    },
    DialogPreviewEntry.Inline(DialogPreviewGroup.SHEET, "sheet_bg_text_config", nameText("背景与文字"), "BgTextConfigDialogHost") { onDismiss ->
        BgTextConfigDialogHost(onDismiss = onDismiss)
    },
    DialogPreviewEntry.Inline(DialogPreviewGroup.SHEET, "sheet_review_post", nameText("段评发布"), "ReviewPostDialogHost") { onDismiss ->
        ReviewPostDialogHost(
            replyPreview = "预览: 回复某条评论",
            onPosted = { onDismiss() },
            onDismiss = onDismiss,
        )
    },
)
