package io.legado.app.constant

object Status {
    const val STOP = 0
    const val PLAY = 1
    // 拉播放链接 + 缓冲窗口, 介于 STOP 与 PLAY 之间 (仅音频用, 朗读侧不产生)
    const val LOADING = 2
    const val PAUSE = 3
}