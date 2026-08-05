package io.legado.app.ui.book.read

import kotlin.concurrent.Volatile

/**
 * 当前阅读屏 [ReaderScreenModel] 全局注册 (sharedUiMain)。
 *
 * 供非 Compose 宿主 (如鸿蒙 napi 文本菜单回调 onTextAction) 投递对话框事件
 * ([ReaderScreenModel.postDialogEvent]) 与触发菜单动作 ([ReaderScreenModel.menuState]);
 * 对照 [io.legado.app.model.ActiveReadBookRegistry] 的 ReadBook/ViewModel 注册 ——
 * 后者在 commonMain, 不能引用 sharedUiMain 的 [ReaderScreenModel] 类型, 故单独注册。
 *
 * [ReaderRoute] 在 DisposableEffect 中 attach/detach, 生命周期与阅读屏一致。
 */
object ReaderScreenModelRegistry {
    @Volatile
    private var current: ReaderScreenModel? = null

    /** 当前活动阅读屏的 ScreenModel; 未进入阅读页时为 null。 */
    val currentScreenModel: ReaderScreenModel? get() = current

    fun attach(screenModel: ReaderScreenModel) {
        current = screenModel
    }

    fun detach(screenModel: ReaderScreenModel) {
        if (current === screenModel) current = null
    }
}
