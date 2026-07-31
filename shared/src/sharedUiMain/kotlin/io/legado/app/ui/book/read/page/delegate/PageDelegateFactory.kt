package io.legado.app.ui.book.read.page.delegate

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import io.legado.app.constant.PageAnim
import io.legado.app.help.config.LocalReadConfigProviders
import io.legado.app.ui.book.read.ReadBookEvents
import io.legado.app.ui.book.read.ReadBookViewModelShared
import io.legado.app.ui.book.read.ReadConfigChange
import kotlinx.coroutines.CoroutineScope

/**
 * 按 `ReadBookConfig.pageAnim` 选取翻页动画委托，对照 app 端 `ReadView.upPageAnim()`。
 *
 * 委托实例挂在 composition 上：翻页动画配置变更（[ReadConfigChange.PAGE_ANIM]）时重建，
 * 离开阅读页时 `onDestroy()` 释放动画协程。同时写回 [ReadBookViewModelShared.pageDelegate]，
 * 供快捷键翻页（[io.legado.app.ui.book.read.page.turnPage]）等非 Compose 入口复用。
 */
@Composable
fun rememberPageDelegate(viewModel: ReadBookViewModelShared): PageDelegateCompose {
    val readBookConfig = LocalReadConfigProviders.current.readBookConfig
    val scope = rememberCoroutineScope()

    var pageAnim by remember(readBookConfig) { mutableIntStateOf(readBookConfig.pageAnim) }
    LaunchedEffect(readBookConfig) {
        ReadBookEvents.configChange.collect { changes ->
            if (ReadConfigChange.PAGE_ANIM in changes) pageAnim = readBookConfig.pageAnim
        }
    }

    val delegate = remember(viewModel, scope, pageAnim) {
        createPageDelegate(pageAnim, viewModel, scope)
    }
    DisposableEffect(viewModel, delegate) {
        viewModel.pageDelegate = delegate
        onDispose {
            delegate.onDestroy()
            if (viewModel.pageDelegate === delegate) viewModel.pageDelegate = null
        }
    }
    return delegate
}

/** 翻页动画值 → 委托实例，分支与 app 端 `ReadView.upPageAnim()` 的 when 一一对应。 */
internal fun createPageDelegate(
    @PageAnim.Anim pageAnim: Int,
    viewModel: ReadBookViewModelShared,
    scope: CoroutineScope,
): PageDelegateCompose = when (pageAnim) {
    PageAnim.coverPageAnim -> CoverPageDelegateCompose(viewModel, scope)
    PageAnim.slidePageAnim -> SlidePageDelegateCompose(viewModel, scope)
    PageAnim.simulationPageAnim -> SimulationPageDelegateCompose(viewModel, scope)
    PageAnim.scrollPageAnim -> ScrollPageDelegateCompose(viewModel, scope)
    else -> NoAnimPageDelegateCompose(viewModel, scope)
}
