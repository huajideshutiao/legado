package io.legado.app.ui.book.read.page.entities

/**
 * 翻页方向枚举（KMP 共用版）。
 *
 * 与 app 端 `io.legado.app.ui.book.read.page.entities.PageDirection` 对应，
 * 命名加 `Shared` 后缀避免与 android 端类名冲突（android 端 PageDirection 仍在原位，
 * 由 app 模块使用；shared 模块及桌面端使用本枚举）。
 *
 * - [NONE]: 未确定方向（手势刚按下，尚未判定 PREV / NEXT）
 * - [PREV]: 向后翻（上一页 / 上一章）
 * - [NEXT]: 向前翻（下一页 / 下一章）
 */
enum class PageDirectionShared {
    NONE, PREV, NEXT
}
