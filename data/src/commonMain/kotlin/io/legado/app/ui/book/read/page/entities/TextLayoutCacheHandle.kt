package io.legado.app.ui.book.read.page.entities

/**
 * 正文逐列 TextLayoutResult 缓存句柄接口。
 *
 * 数据层 TextPage 通过此接口持有 render 侧正文布局缓存，
 * 具体实现（TextLayoutCache）由 render 侧 lazy 注入（见 PageContentCanvas 取或建挂载）。
 *
 * 设计目的：让 TextPage 数据类不直接 import compose TextMeasurer/TextLayoutResult，
 * 为 KMP 分层（commonMain 不依赖 sharedUiMain）扫清障碍。
 */
interface TextLayoutCacheHandle {

    /**
     * 标记缓存失效（清空并允许按需重建）。
     */
    fun invalidate()

    /**
     * 释放底层资源（TextLayoutResult 等）。
     */
    fun recycle()
}
