package io.legado.app.ui.book.read.page.entities

/**
 * TextPage 对 TextChapter 的最小依赖面（commonMain 跨端共用）。
 *
 * TextChapter 本体重度依赖 android（Book/BookChapter/BookContent/LayoutProgressListener/
 * TextChapterLayout 等），不下沉 shared commonMain。
 * TextPage 仅消费 `pageSize` 一个属性（readProgress 计算），通过本接口解耦，
 * 让 TextPage 可下沉 commonMain 而 TextChapter 留 app 端。
 *
 * app 端 TextChapter 实现本接口，TextPage.textChapter 字段类型为 `TextChapterRef?`。
 */
interface TextChapterRef {
    val pageSize: Int
}
