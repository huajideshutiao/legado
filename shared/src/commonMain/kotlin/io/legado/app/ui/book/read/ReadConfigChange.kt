package io.legado.app.ui.book.read

/**
 * 阅读页渲染层刷新语义，取代 EventBus.UP_CONFIG 的魔数数组（注释为旧 int 值）。
 *
 * 从 app 端 `io.legado.app.ui.book.read.ReadBookEvents` 提取到 shared/commonMain，
 * 让 shared/sharedUiMain 中的下沉 Composable (TipConfigScreen / PaddingConfigScreen /
 * EffectiveReplacesScreen 等) 能通过 `onPostConfig: (List<ReadConfigChange>) -> Unit`
 * 回调通知宿主刷新渲染层，而无需直接依赖 app 端 `ReadBookEvents` object。
 *
 * 包名与 app 端原位置 (`io.legado.app.ui.book.read`) 完全一致，app 端 ReadBookEvents.kt
 * 删除 enum 定义后，原 `import io.legado.app.ui.book.read.ReadConfigChange` 不变。
 *
 * 字段顺序与 app 端原 enum 完全一致，ordinal 不变（旧 int 值见行尾注释，仅作历史对照）。
 */
enum class ReadConfigChange {
    SYSTEM_UI,            // 0 状态栏/导航栏可见性
    BG,                   // 1 背景
    STYLE,                // 2 页眉页脚/提示样式
    BG_ALPHA,             // 3 背景透明度
    PAGE_SLOP,            // 4 翻页触发距离
    LOAD_CONTENT,         // 5 重新分页排版
    UP_CONTENT,           // 6 刷新当前视图
    CHAPTER_STYLE,        // 8 字体字号等排版样式
    INVALIDATE_TEXT_PAGE, // 9 失效缓存页
    CHAPTER_LAYOUT,       // 10 绘制尺寸(边距/双页)
    RENDER_TASK,          // 11 重提交离屏渲染
    PAGE_ANIM,            // 12 翻页动画
}
