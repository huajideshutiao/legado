package io.legado.app.ui.book.read.page.entities

/**
 * Canvas 录制缓存句柄接口。
 *
 * 数据层 TextLine/TextPage 通过此接口持有 render 缓存，
 * 具体实现（CanvasRecorder 包装）由 render 侧注入。
 *
 * 设计目的：让 TextLine/TextPage 数据类不直接 import android CanvasRecorder，
 * 为后续 KMP 下沉 shared commonMain 扫清障碍。
 */
interface CanvasRecorderHandle {

    /**
     * 标记缓存失效，下次绘制时重新录制。
     */
    fun invalidate()

    /**
     * 释放底层资源（Picture/RenderNode 等）。
     */
    fun recycle()
}
