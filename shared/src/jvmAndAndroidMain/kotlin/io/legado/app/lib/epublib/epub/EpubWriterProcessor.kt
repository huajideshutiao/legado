package io.legado.app.lib.epublib.epub

import io.legado.app.lib.epublib.domain.EpubBook
import kotlin.math.min

/**
 * epub导出进度管理器
 * 
 * @author Discut
 */
class EpubWriterProcessor {
    var totalProgress: Int = 0
    var currentProgress: Int = 0
        private set
    private var callback: Callback? = null

    fun setCallback(callback: Callback) {
        this.callback = callback
    }

    fun updateCurrentProgress(current: Int) {
        this.currentProgress = min(current, totalProgress)
        callback?.onProgressing(totalProgress, this.currentProgress)
    }

    fun getCallback(): Callback? {
        return callback
    }

    @Suppress("unused")
    interface Callback {
        fun onStart(epubBook: EpubBook?) {
        }

        fun onProgressing(total: Int, progress: Int) {
        }

        fun onEnd(epubBook: EpubBook?) {
        }
    }
}
