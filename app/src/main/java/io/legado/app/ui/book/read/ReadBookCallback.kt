package io.legado.app.ui.book.read

import io.legado.app.ui.book.read.config.AutoReadDialog
import io.legado.app.ui.book.read.config.ReadAloudDialog

/**
 * 统一的阅读界面回调接口，合并了 ReadMenu、SearchMenu、AutoReadDialog、ReadAloudDialog 的 CallBack。
 * 菜单和对话框可使用 activity as ReadBookCallback 代替各自的 CallBack 类型进行转换。
 *
 * 未纳入的接口：
 * - MangaMenu.CallBack：由 ReadMangaActivity 实现，upSystemUiVisibility(Boolean) 签名与无参版本冲突
 * - SpeakEngineDialog.CallBack：通过 parentFragment as? CallBack 使用，属于 Fragment 级别转换
 */
interface ReadBookCallback : ReadMenu.CallBack, SearchMenu.CallBack,
    AutoReadDialog.CallBack, ReadAloudDialog.CallBack
