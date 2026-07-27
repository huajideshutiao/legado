package io.legado.app.ui.book.read.config

import android.content.DialogInterface
import android.view.WindowManager
import androidx.compose.runtime.Composable
import io.legado.app.base.BaseComposeDialogFragment
import io.legado.app.help.config.ReadBookConfig
import io.legado.app.ui.book.read.ReadBookEvents

/** 页眉/正文/页脚边距与分隔线配置对话框，即时写 ReadBookConfig 并刷新渲染 */
class PaddingConfigDialog : BaseComposeDialogFragment() {

    override fun onStart() {
        super.onStart()
        // 复刻原实现：不压暗底层，边调边看
        dialog?.window?.let {
            it.clearFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
            val attr = it.attributes
            attr.dimAmount = 0.0f
            it.attributes = attr
        }
    }

    override fun onDismiss(dialog: DialogInterface) {
        super.onDismiss(dialog)
        ReadBookConfig.save()
    }

    @Composable
    override fun Content() {
        PaddingConfigScreen(
            controller = object : PaddingConfigController {
                override var showHeaderLine: Boolean
                    get() = ReadBookConfig.showHeaderLine
                    set(value) { ReadBookConfig.showHeaderLine = value }

                override var showFooterLine: Boolean
                    get() = ReadBookConfig.showFooterLine
                    set(value) { ReadBookConfig.showFooterLine = value }

                override var headerPaddingTop: Int
                    get() = ReadBookConfig.headerPaddingTop
                    set(value) { ReadBookConfig.headerPaddingTop = value }

                override var headerPaddingBottom: Int
                    get() = ReadBookConfig.headerPaddingBottom
                    set(value) { ReadBookConfig.headerPaddingBottom = value }

                override var headerPaddingLeft: Int
                    get() = ReadBookConfig.headerPaddingLeft
                    set(value) { ReadBookConfig.headerPaddingLeft = value }

                override var headerPaddingRight: Int
                    get() = ReadBookConfig.headerPaddingRight
                    set(value) { ReadBookConfig.headerPaddingRight = value }

                override var paddingTop: Int
                    get() = ReadBookConfig.paddingTop
                    set(value) { ReadBookConfig.paddingTop = value }

                override var paddingBottom: Int
                    get() = ReadBookConfig.paddingBottom
                    set(value) { ReadBookConfig.paddingBottom = value }

                override var paddingLeft: Int
                    get() = ReadBookConfig.paddingLeft
                    set(value) { ReadBookConfig.paddingLeft = value }

                override var paddingRight: Int
                    get() = ReadBookConfig.paddingRight
                    set(value) { ReadBookConfig.paddingRight = value }

                override var footerPaddingTop: Int
                    get() = ReadBookConfig.footerPaddingTop
                    set(value) { ReadBookConfig.footerPaddingTop = value }

                override var footerPaddingBottom: Int
                    get() = ReadBookConfig.footerPaddingBottom
                    set(value) { ReadBookConfig.footerPaddingBottom = value }

                override var footerPaddingLeft: Int
                    get() = ReadBookConfig.footerPaddingLeft
                    set(value) { ReadBookConfig.footerPaddingLeft = value }

                override var footerPaddingRight: Int
                    get() = ReadBookConfig.footerPaddingRight
                    set(value) { ReadBookConfig.footerPaddingRight = value }
            },
            onPostConfig = { changes -> ReadBookEvents.postConfig(changes) },
        )
    }
}
