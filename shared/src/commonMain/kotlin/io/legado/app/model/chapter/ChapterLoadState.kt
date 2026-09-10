package io.legado.app.model.chapter

/**
 * 章节内容加载状态 (视频与漫画 ViewModel 状态驱动)。
 *
 * 当前覆盖范围为视频 ([io.legado.app.ui.book.video.VideoPlayViewModelShared]) 与
 * 漫画 ([io.legado.app.ui.book.manga.MangaReaderViewModelShared]) 的章节装载流程，
 * 渲染层由 [io.legado.app.ui.compose.component.ChapterLoadStateOverlay] 消费整页覆盖层。
 * 文字阅读通过占位页排版流处理，音频播放由专用事件通道驱动。
 */
sealed interface ChapterLoadState {

    /** 空闲 (已有内容或尚未开始)。 */
    data object Idle : ChapterLoadState

    /** 加载中。 */
    data object Loading : ChapterLoadState

    /**
     * 加载失败。
     *
     * 原版漫画的 `loadFailLiveData: Pair<String, Boolean>` 第二元恒为 true (可重试),
     * 故不复刻该维度: 失败一律给重试入口。
     *
     * @param message 展示给用户的原因
     */
    data class Error(val message: String) : ChapterLoadState

    val isLoading: Boolean get() = this is Loading

    val errorMessage: String? get() = (this as? Error)?.message
}
