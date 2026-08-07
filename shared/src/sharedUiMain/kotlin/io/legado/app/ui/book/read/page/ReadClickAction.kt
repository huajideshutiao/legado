package io.legado.app.ui.book.read.page

import io.legado.app.constant.PreferKey
import io.legado.app.help.config.PreferenceProviders
import io.legado.app.help.toast.Toasters
import io.legado.app.ui.book.read.ReadBookViewModelShared
import io.legado.app.ui.book.read.config.ClickActionConfig
import io.legado.app.ui.book.read.page.entities.PageDirectionShared

/**
 * 小说阅读页点击区域 / 翻页动作, 对照 app 端 `ReadView.onSingleTapUp` + `ClickArea`。
 */

/** 点击落点 → 动作值, 对照 app 端 [io.legado.app.ui.book.read.config.ClickArea] 的 3x3 分区。 */
internal fun ClickActionConfig.actionAt(x: Float, y: Float, width: Int, height: Int): Int {
    if (width <= 0 || height <= 0) return -1
    val col = when {
        x < width * 0.33f -> 0
        x < width * 0.66f -> 1
        else -> 2
    }
    val row = when {
        y < height * 0.33f -> 0
        y < height * 0.66f -> 1
        else -> 2
    }
    return when (row * 3 + col) {
        0 -> tl
        1 -> tc
        2 -> tr
        3 -> ml
        4 -> mc
        5 -> mr
        6 -> bl
        7 -> bc
        else -> br
    }
}

/**
 * 读回 9 个区域动作配置, 与 MoreConfigDialog 的 ClickActionDialog 写同一批 PreferKey。
 * 原版 AppConfig.clickActionXX 是 cachedIntPref, 每次点击现取, 故这里也不做组合期缓存。
 */
internal fun readClickActionConfig(): ClickActionConfig {
    val prefs = runCatching { PreferenceProviders.get() }.getOrNull() ?: return ClickActionConfig()
    val d = ClickActionConfig()
    return ClickActionConfig(
        tl = prefs.getInt(PreferKey.clickActionTL, d.tl),
        tc = prefs.getInt(PreferKey.clickActionTC, d.tc),
        tr = prefs.getInt(PreferKey.clickActionTR, d.tr),
        ml = prefs.getInt(PreferKey.clickActionML, d.ml),
        mc = prefs.getInt(PreferKey.clickActionMC, d.mc),
        mr = prefs.getInt(PreferKey.clickActionMR, d.mr),
        bl = prefs.getInt(PreferKey.clickActionBL, d.bl),
        bc = prefs.getInt(PreferKey.clickActionBC, d.bc),
        br = prefs.getInt(PreferKey.clickActionBR, d.br),
    )
}

/**
 * 九宫格点击区域校验 (对照原版 AppConfig.detectClickArea):
 * 9 格全部非 0 (无菜单格) 时, 强制恢复中间格 (MC) 为菜单动作并 toast。
 * 调用时机与原版一致: 阅读页初始化 (原 ReadBookViewModel.init) + 点击区域配置变更后 (原配置页 onDestroy)。
 */
internal fun detectClickArea() {
    val prefs = PreferenceProviders.get()
    val d = ClickActionConfig()
    val product = prefs.getInt(PreferKey.clickActionTL, d.tl) *
        prefs.getInt(PreferKey.clickActionTC, d.tc) *
        prefs.getInt(PreferKey.clickActionTR, d.tr) *
        prefs.getInt(PreferKey.clickActionML, d.ml) *
        prefs.getInt(PreferKey.clickActionMC, d.mc) *
        prefs.getInt(PreferKey.clickActionMR, d.mr) *
        prefs.getInt(PreferKey.clickActionBL, d.bl) *
        prefs.getInt(PreferKey.clickActionBC, d.bc) *
        prefs.getInt(PreferKey.clickActionBR, d.br)
    if (product != 0) {
        prefs.putInt(PreferKey.clickActionMC, 0)
        Toasters.get().toast("当前没有配置菜单区域,自动恢复中间区域为菜单.")
    }
}

/**
 * 统一翻页入口 (点击区域 / 快捷键共用)。
 *
 * 注入了 [io.legado.app.ui.book.read.page.delegate.PageDelegateCompose] 时走动画委托;
 * 未注入时直接位移页码, 章节边界再切章 (对照 app 端 ReadView.click 的 1/2 分支 + fillPage)。
 */
internal fun ReadBookViewModelShared.turnPage(direction: PageDirectionShared) {
    val delegate = pageDelegate
    if (delegate != null) {
        delegate.keyTurnPage(direction)
        return
    }
    when (direction) {
        PageDirectionShared.NEXT -> if (!nextPage()) moveToNextChapter()
        PageDirectionShared.PREV -> if (!prevPage()) moveToPrevChapter()
        else -> Unit
    }
}
