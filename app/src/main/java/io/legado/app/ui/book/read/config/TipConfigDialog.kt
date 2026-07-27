package io.legado.app.ui.book.read.config

import androidx.compose.runtime.Composable
import io.legado.app.base.BaseComposeDialogFragment
import io.legado.app.help.config.ReadBookConfig
import io.legado.app.help.config.ReadTipConfig
import io.legado.app.ui.book.read.ReadBookEvents

/**
 * 页眉页脚/标题信息位 + 提示色 + 字号/间距 配置对话框。
 *
 * 实现已下沉到 shared/sharedUiMain 的 [TipConfigScreen]（app + desktop 共用），
 * 本类作为 thin wrapper 保留 `BaseComposeDialogFragment` 宿主：
 * - 提供 ComposeView / Provider 注入 / 窗口尺寸 / 圆角（由 BaseComposeDialogFragment）
 * - 通过 [TipConfigController] 桥接 app 端 `ReadBookConfig` / `ReadTipConfig`（object 单例）
 * - 通过 `onPostConfig` 回调桥接 app 端 `ReadBookEvents.postConfig`
 * - 通过 `onBack` 回调桥接 `dismissAllowingStateLoss`
 *
 * 调用方契约不变：`TipConfigDialog().show(childFragmentManager, "tipConfigDialog")`。
 */
class TipConfigDialog : BaseComposeDialogFragment() {

    @Composable
    override fun Content() {
        TipConfigScreen(
            controller = object : TipConfigController {
                override var titleMode: Int
                    get() = ReadBookConfig.titleMode
                    set(value) { ReadBookConfig.titleMode = value }

                override var titleSize: Int
                    get() = ReadBookConfig.titleSize
                    set(value) { ReadBookConfig.titleSize = value }

                override var titleTop: Int
                    get() = ReadBookConfig.titleTopSpacing
                    set(value) { ReadBookConfig.titleTopSpacing = value }

                override var titleBottom: Int
                    get() = ReadBookConfig.titleBottomSpacing
                    set(value) { ReadBookConfig.titleBottomSpacing = value }

                override var headerMode: Int
                    get() = ReadTipConfig.headerMode
                    set(value) { ReadTipConfig.headerMode = value }

                override var footerMode: Int
                    get() = ReadTipConfig.footerMode
                    set(value) { ReadTipConfig.footerMode = value }

                override var tipHeaderLeft: Int
                    get() = ReadTipConfig.tipHeaderLeft
                    set(value) { ReadTipConfig.tipHeaderLeft = value }

                override var tipHeaderMiddle: Int
                    get() = ReadTipConfig.tipHeaderMiddle
                    set(value) { ReadTipConfig.tipHeaderMiddle = value }

                override var tipHeaderRight: Int
                    get() = ReadTipConfig.tipHeaderRight
                    set(value) { ReadTipConfig.tipHeaderRight = value }

                override var tipFooterLeft: Int
                    get() = ReadTipConfig.tipFooterLeft
                    set(value) { ReadTipConfig.tipFooterLeft = value }

                override var tipFooterMiddle: Int
                    get() = ReadTipConfig.tipFooterMiddle
                    set(value) { ReadTipConfig.tipFooterMiddle = value }

                override var tipFooterRight: Int
                    get() = ReadTipConfig.tipFooterRight
                    set(value) { ReadTipConfig.tipFooterRight = value }

                override var tipColor: Int
                    get() = ReadTipConfig.tipColor
                    set(value) { ReadTipConfig.tipColor = value }

                override var tipDividerColor: Int
                    get() = ReadTipConfig.tipDividerColor
                    set(value) { ReadTipConfig.tipDividerColor = value }
            },
            onBack = { dismissAllowingStateLoss() },
            onPostConfig = { changes -> ReadBookEvents.postConfig(changes) },
        )
    }
}
