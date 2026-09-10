package io.legado.desktop.help.tts

import io.legado.app.help.tts.ReadAloudHostShared
import io.legado.app.model.ActiveReadBookRegistry

/**
 * 桌面端朗读宿主: 实现全在 [ReadAloudHostShared], 这里只补托盘 tooltip 要的取值。
 */
object DesktopReadAloudHost : ReadAloudHostShared() {

    /** 书名 / 章节名 (托盘 tooltip 用)。 */
    val bookName: String? get() = ActiveReadBookRegistry.current?.bookValue?.name

    val chapterTitle: String?
        get() = ActiveReadBookRegistry.current?.let { readBook ->
            readBook.chapterListValue?.getOrNull(readBook.durChapterIndexValue)?.title
        }
}
