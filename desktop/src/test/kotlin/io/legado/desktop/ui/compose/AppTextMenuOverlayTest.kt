package io.legado.desktop.ui.compose

import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.LocalTextToolbar
import androidx.compose.ui.platform.TextToolbar
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.DesktopComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.click
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performMouseInput
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipe
import androidx.compose.ui.test.v2.runDesktopComposeUiTest
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.PopupProperties
import androidx.compose.ui.window.PopupPositionProvider
import io.legado.app.help.config.AppConfigProviders
import io.legado.app.help.config.PreferenceProviders
import io.legado.app.help.file.AppFilesDir
import io.legado.app.help.file.AppFilesDirs
import io.legado.app.ui.book.read.page.SelectionGeometry
import io.legado.app.ui.compose.component.AppDialog
import io.legado.app.ui.compose.component.AppPopup
import io.legado.app.ui.compose.component.LocalDialogAnchorSize
import io.legado.app.ui.compose.platform.LocalOverlayTopInset
import io.legado.app.ui.compose.theme.AppColors
import io.legado.app.ui.compose.theme.LocalAppColors
import io.legado.app.ui.compose.theme.ProvideAppTextToolbar
import io.legado.app.ui.reader.ReaderImageActionMenu
import io.legado.app.ui.reader.ReaderImageActions
import io.legado.app.ui.reader.ReaderTextActionMenu
import io.legado.app.ui.reader.ReaderTextActions
import io.legado.app.ui.reader.ReaderTextSelectionRequest
import io.legado.app.ui.reader.readerMenuAnchor
import io.legado.app.ui.root.PlatformCapabilityProviders
import io.legado.desktop.config.DesktopAppConfigAccessor
import io.legado.desktop.config.DesktopPreferenceProvider
import io.legado.desktop.ui.DesktopPlatformCapabilities
import legado.ui.generated.resources.Res
import legado.ui.generated.resources.back
import legado.ui.generated.resources.more
import java.io.File
import java.nio.file.Files
import org.jetbrains.compose.resources.stringResource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 文本菜单覆盖层的真实 Compose 测试: 生产 `ReaderTextActionMenu` / `ReaderImageActionMenu` /
 * `ProvideAppTextToolbar` / `AppPopup` / `AppDialog` 直接驱动, 不加测试开关、不复刻几何。
 *
 * 下层正文用与生产一致的 sibling 结构 (先声明、菜单宿主后声明): 命中计数只挂在正文
 * sibling 上, 祖先收到未消费事件不计入。所有比较都用 [SemanticsNode.boundsInWindow]
 * (窗口坐标, 已裁切), 与生产菜单的窗口坐标同一空间。
 */
@OptIn(ExperimentalTestApi::class)
class AppTextMenuOverlayTest {

    private val colors = AppColors(
        accent = Color(0xFF165DFF),
        background = Color.White,
        bottomBackground = Color(0xFFF2F2F2),
        primaryText = Color(0xFF212121),
        secondaryText = Color(0xFF595959),
        menuText = Color(0xFF212121),
        summaryText = Color(0xFF909090),
        controlNormal = Color(0x8A000000),
        textDisabled = Color(0x61000000),
        statusBar = Color.White,
        navigationBar = Color.White,
        fillet = Color(0xFFF2F2F2),
        isDark = false,
    )

    private val selection = ReaderTextSelectionRequest(
        text = "选中文字",
        geometry = SelectionGeometry(
            textArea = Rect(160f, 320f, 320f, 344f),
            startHandle = Rect(160f, 344f, 184f, 368f),
            endHandle = Rect(296f, 344f, 320f, 368f),
        ),
    )

    // ======================= 场景 =======================

    /** 阅读页文本菜单场景: 正文 sibling 在下, 菜单宿主在上 (与生产根 Box 同构)。 */
    @Composable
    private fun ReaderMenuScene(
        request: ReaderTextSelectionRequest?,
        onPageDown: () -> Unit = {},
        onMenuAction: () -> Unit = {},
        layoutDirection: LayoutDirection = LayoutDirection.Ltr,
        fontScale: Float = 1f,
        overlayTopInset: Dp = 0.dp,
        content: @Composable () -> Unit = {},
    ) {
        CompositionLocalProvider(
            LocalAppColors provides colors,
            LocalLayoutDirection provides layoutDirection,
            LocalDensity provides Density(1f, fontScale),
            LocalOverlayTopInset provides overlayTopInset,
        ) {
            Box(Modifier.fillMaxSize()) {
                PageSibling(onPageDown)
                content()
                ReaderTextActionMenu(
                    request = request,
                    actions = ReaderTextActions(
                        onReplace = { onMenuAction() },
                        onBookmark = { onMenuAction() },
                        onUnderline = { _, _ -> onMenuAction() },
                        onReadAloud = { onMenuAction() },
                        onSearchContent = { onMenuAction() },
                    ),
                    onFinally = {},
                )
            }
        }
    }

    /**
     * 通道场景: 主窗口装一套文本菜单通道, [content] 里放真正可选的文本 (框架选区菜单走这条路)。
     */
    @Composable
    private fun ChannelScene(
        onPageDown: () -> Unit = {},
        layoutDirection: LayoutDirection = LayoutDirection.Ltr,
        overlayTopInset: Dp = 0.dp,
        content: @Composable () -> Unit,
    ) {
        CompositionLocalProvider(
            LocalAppColors provides colors,
            LocalLayoutDirection provides layoutDirection,
            LocalOverlayTopInset provides overlayTopInset,
        ) {
            Box(Modifier.fillMaxSize()) {
                PageSibling(onPageDown)
                ProvideAppTextToolbar {
                    Box(Modifier.fillMaxSize()) { content() }
                }
            }
        }
    }

    /** 下层正文/选区手柄的替身: 只记录真实命中。 */
    @Composable
    private fun PageSibling(onPageDown: () -> Unit) {
        RecordMenuDescriptions(menuStrings)
        Box(
            Modifier
                .fillMaxSize()
                .background(Color(0xFFEEEEEE))
                .testTag("page")
                .pointerInput(Unit) {
                    awaitEachGesture {
                        awaitFirstDown(requireUnconsumed = false)
                        onPageDown()
                    }
                },
        )
    }

    /**
     * 菜单按钮的本地化无障碍描述 (生产 `Res.string.more` / `Res.string.back`)。
     * 语义定位用真实名称, 不用宽度/固定像素猜哪个是返回按钮。
     */
    private class MenuStrings {
        var more: String = ""
        var back: String = ""
    }

    private val menuStrings = MenuStrings()

    @Composable
    private fun RecordMenuDescriptions(target: MenuStrings) {
        target.more = stringResource(Res.string.more)
        target.back = stringResource(Res.string.back)
    }

    // ======================= 查询工具 =======================

    /** 所有可点节点 (合并树) 的窗口坐标: 菜单项/⋯/返回行。 */
    private fun DesktopComposeUiTest.clickablesWindow(): List<Rect> =
        onAllNodes(hasClickAction()).fetchSemanticsNodes()
            .map { it.boundsInWindow }
            // 未放置节点 (bounds 为空) 不参与几何比较
            .filter { it.width > 0f && it.height > 0f }

    /**
     * 真正可见的文字行 (未合并树, 窗口坐标, 裁切后高度 > 0); 用于退场前后按文字内容对齐
     * 同一行, 不用被裁空节点的最小 top 冒充位置。
     */
    private fun DesktopComposeUiTest.visibleTextRows(): List<Pair<String, Rect>> =
        onAllNodes(hasText("", substring = true), useUnmergedTree = true)
            .fetchSemanticsNodes()
            .mapNotNull { node ->
                val label = node.config.getOrNull(SemanticsProperties.Text)
                    ?.joinToString("") { it.text }
                    ?.takeIf { it.isNotBlank() }
                    ?: return@mapNotNull null
                val bounds = node.boundsInWindow
                if (bounds.width <= 0f || bounds.height <= 0f) return@mapNotNull null
                label to bounds
            }

    /**
     * 面板返回按钮 (按真实无障碍描述定位, 不看宽度)。
     * 描述未记录时直接失败: 空描述查不到节点不等于"面板未展开"。
     */
    private fun DesktopComposeUiTest.backButtons(): List<SemanticsNode> {
        assertTrue("菜单返回按钮描述尚未从资源记录", menuStrings.back.isNotEmpty())
        return onAllNodes(hasContentDescription(menuStrings.back)).fetchSemanticsNodes()
    }

    /**
     * 卡片锚定边 (窗口坐标): 面板打开时返回行通栏, 其右缘就是卡片右缘 (返回按钮与面板同宽);
     * 只有横排时 ⋮ 右缘补容器内边距 (8dp = 本组锚定用例固定的 density 1 场景像素值)。
     */
    private fun DesktopComposeUiTest.cardRightEdge(): Float {
        val back = backButtons()
        if (back.isNotEmpty()) return back.maxOf { it.boundsInWindow.right }
        return clickablesWindow().maxOf { it.right } + 8f
    }

    private fun DesktopComposeUiTest.hasBackButton(): Boolean = backButtons().isNotEmpty()

    /**
     * 是否有菜单标签被单行省略: 对真实 TextLayoutResult 逐行检查 `isLineEllipsized`
     * (并同时看 hasVisualOverflow), 不复刻字体或按字符数推算。
     */
    private fun DesktopComposeUiTest.menuLabelEllipsized(): Boolean {
        val nodes = onAllNodes(hasText("", substring = true), useUnmergedTree = true)
            .fetchSemanticsNodes()
        for (node in nodes) {
            val action = node.config.getOrNull(SemanticsActions.GetTextLayoutResult) ?: continue
            val results = mutableListOf<TextLayoutResult>()
            if (action.action?.invoke(results) != true) continue
            if (results.any { result ->
                    result.hasVisualOverflow ||
                        (0 until result.lineCount).any { result.isLineEllipsized(it) }
                }
            ) {
                return true
            }
        }
        return false
    }

    /** 每帧校验 (density 1 场景): 锚定边与展开侧边缘恒定, 且卡片不出窗口。 */
    private fun DesktopComposeUiTest.assertAnchoredWhileStepping(
        frames: Int = 26,
        anchoredRight: Float,
        anchoredBottomFixed: Boolean,
        anchoredBottom: Float,
        windowWidth: Float,
    ) {
        repeat(frames) {
            mainClock.advanceTimeByFrame()
            val bounds = clickablesWindow()
            if (bounds.isEmpty()) return@repeat
            val right = cardRightEdge()
            val bottom = bounds.maxOf { it.bottom }
            val left = bounds.minOf { it.left }
            assertEquals("窗口右缘漂移", anchoredRight, right, 0.5f)
            if (anchoredBottomFixed) {
                assertEquals("展开侧下缘漂移", anchoredBottom, bottom, 0.5f)
            }
            assertTrue("卡片越出窗口左界", left >= 0f)
            assertTrue("卡片越出窗口右界", right <= windowWidth)
        }
    }

    /** 滚动条是否在列表右侧画了东西 (排除背景色): 桌面条宽 8dp, 取距卡片右缘 4px 一列采样。 */
    private fun DesktopComposeUiTest.hasScrollbarPixels(
        cardRight: Float,
        listTop: Float,
        listBottom: Float,
    ): Boolean {
        val pixels = captureToImage().toPixelMap()
        val x = (cardRight - 4f).toInt()
        if (x < 0 || x >= pixels.width) return false
        var y = (listTop + 6f).toInt()
        val end = (listBottom - 6f).toInt()
        while (y < end) {
            val c = pixels[x, y]
            if (c.alpha > 0.2f && !isPanelBackground(c)) return true
            y += 3
        }
        return false
    }

    private fun isPanelBackground(c: Color): Boolean =
        kotlin.math.abs(c.red - colors.bottomBackground.red) < 0.02f &&
            kotlin.math.abs(c.green - colors.bottomBackground.green) < 0.02f &&
            kotlin.math.abs(c.blue - colors.bottomBackground.blue) < 0.02f

    /**
     * 点「更多」展开溢出面板。描述来自资源 (场景里的 [RecordMenuDescriptions]),
     * 未就绪时直接失败, 不拿空描述查一个查不到当结果。
     */
    private fun DesktopComposeUiTest.openOverflow() {
        assertTrue("菜单「更多」按钮描述尚未从资源记录", menuStrings.more.isNotEmpty())
        onNodeWithContentDescription(menuStrings.more).performMouseInput { click() }
    }

    /** 点返回按钮 (真实 Back 描述) 收回横排, 与 [openOverflow] 对称。 */
    private fun DesktopComposeUiTest.closeOverflow() {
        assertTrue("菜单返回按钮描述尚未从资源记录", menuStrings.back.isNotEmpty())
        onNodeWithContentDescription(menuStrings.back).performMouseInput { click() }
    }

    // ======================= R1/R5: 逐帧锚定边 =======================

    @Test
    fun `向上展开每一帧右缘与底缘不动且收回到横排`() = runDesktopComposeUiTest(640, 720) {
        var request by mutableStateOf<ReaderTextSelectionRequest?>(null)
        setContent { ReaderMenuScene(request) }
        runOnUiThread { request = selection }
        waitForIdle()

        val rowRight = cardRightEdge()
        val rowBottom = clickablesWindow().maxOf { it.bottom }

        mainClock.autoAdvance = false
        openOverflow()
        assertAnchoredWhileStepping(anchoredRight = rowRight, anchoredBottomFixed = true, anchoredBottom = rowBottom, windowWidth = 640f)

        // 收回: 点返回按钮从面板回到横排, 同一锚定边
        closeOverflow()
        assertAnchoredWhileStepping(anchoredRight = rowRight, anchoredBottomFixed = true, anchoredBottom = rowBottom, windowWidth = 640f)

        mainClock.autoAdvance = true
        waitForIdle()
        val settled = clickablesWindow()
        assertEquals("收回后应是横排", 5, settled.size)
        assertFalse("收回后仍显示返回按钮", hasBackButton())
    }

    @Test
    fun `向下展开每一帧上缘不动`() = runDesktopComposeUiTest(640, 720) {
        var request by mutableStateOf<ReaderTextSelectionRequest?>(null)
        // 选区贴近窗口顶: 菜单向下展开
        val topSelection = ReaderTextSelectionRequest(
            text = "顶",
            geometry = SelectionGeometry(
                textArea = Rect(160f, 40f, 320f, 64f),
                startHandle = Rect(160f, 64f, 184f, 88f),
                endHandle = Rect(296f, 64f, 320f, 88f),
            ),
        )
        setContent { ReaderMenuScene(request) }
        runOnUiThread { request = topSelection }
        waitForIdle()

        val rowRight = cardRightEdge()
        val rowTop = clickablesWindow().minOf { it.top }

        mainClock.autoAdvance = false
        openOverflow()
        repeat(26) {
            mainClock.advanceTimeByFrame()
            val bounds = clickablesWindow()
            if (bounds.isEmpty()) return@repeat
            assertEquals("窗口右缘漂移", rowRight, cardRightEdge(), 0.5f)
            assertEquals("展开侧上缘漂移", rowTop, bounds.minOf { it.top }, 0.5f)
            assertTrue(bounds.maxOf { it.bottom } <= 720f)
        }
        mainClock.autoAdvance = true
        waitForIdle()
    }

    @Test
    fun `途中反向与快速重复仍锁定锚定边`() = runDesktopComposeUiTest(640, 720) {
        var request by mutableStateOf<ReaderTextSelectionRequest?>(null)
        setContent { ReaderMenuScene(request) }
        runOnUiThread { request = selection }
        waitForIdle()

        val rowRight = cardRightEdge()

        mainClock.autoAdvance = false
        // 途中反向: 开 3 帧后点返回按钮收回 (真实反向, 不是再次展开)
        openOverflow()
        repeat(3) { mainClock.advanceTimeByFrame() }
        closeOverflow()
        assertAnchoredWhileStepping(frames = 8, anchoredRight = rowRight, anchoredBottomFixed = false, anchoredBottom = 0f, windowWidth = 640f)
        // 快速重复 4 次, 明确交替: 开 → 收 → 开 → 收
        repeat(4) { index ->
            if (index % 2 == 0) openOverflow() else closeOverflow()
            repeat(2) { mainClock.advanceTimeByFrame() }
        }
        assertAnchoredWhileStepping(frames = 20, anchoredRight = rowRight, anchoredBottomFixed = false, anchoredBottom = 0f, windowWidth = 640f)
        mainClock.autoAdvance = true
        waitForIdle()
        // 最后一次是收回: 过渡收敛后必须真的是横排 (5 个可点节点, 无返回按钮)
        assertEquals("快速重复后应收敛到横排", 5, clickablesWindow().size)
        assertFalse("快速重复后仍显示返回按钮", hasBackButton())
    }

    @Test
    fun `RTL 每一帧锁定物理左边`() = runDesktopComposeUiTest(640, 720) {
        var request by mutableStateOf<ReaderTextSelectionRequest?>(null)
        setContent { ReaderMenuScene(request, layoutDirection = LayoutDirection.Rtl) }
        runOnUiThread { request = selection }
        waitForIdle()

        val row = clickablesWindow()
        val rowLeft = row.minOf { it.left } - 8f
        assertTrue(rowLeft >= 12f)

        mainClock.autoAdvance = false
        openOverflow()
        repeat(26) {
            mainClock.advanceTimeByFrame()
            val bounds = clickablesWindow()
            if (bounds.isEmpty()) return@repeat
            assertEquals("RTL 锚定左边漂移", rowLeft, bounds.minOf { it.left }, 0.5f)
            assertTrue("RTL 卡片越出右界", bounds.maxOf { it.right } <= 640f)
        }
        mainClock.autoAdvance = true
        waitForIdle()
    }

    // ======================= A1: 尺寸矩阵 =======================

    /**
     * 通用矩阵: 多 density (1/1.25/2.625/2.75) × 字体缩放 (1/1.5) × 多可用宽度。
     * 断言实际卡片在可用区边距内、宽窗短标签的 TextLayoutResult 未被省略。
     * 菜单由真实旧通道 `showMenu` 驱动 (框架同一入口)。
     */
    @Test
    fun `多密度与宽度矩阵下卡片守边距且宽窗短标签不被省略`() = runDesktopComposeUiTest(2400, 900) {
        var toolbar: TextToolbar? = null
        var density by mutableFloatStateOf(1f)
        var fontScale by mutableFloatStateOf(1f)
        var areaDp by mutableFloatStateOf(600f)
        setContent {
            CompositionLocalProvider(
                LocalAppColors provides colors,
                LocalDensity provides Density(density, fontScale),
            ) {
                Box(Modifier.fillMaxSize()) {
                    PageSibling {}
                    ProvideAppTextToolbar {
                        Box(
                            Modifier
                                .width(areaDp.dp)
                                .fillMaxHeight()
                                .testTag("area"),
                        ) {
                            toolbar = LocalTextToolbar.current
                        }
                    }
                }
            }
        }

        val anchor = Rect(20f, 30f, 140f, 60f)
        fun show() = runOnUiThread {
            toolbar!!.showMenu(
                rect = anchor,
                onCopyRequested = {},
                onPasteRequested = {},
                onCutRequested = {},
                onSelectAllRequested = {},
            )
        }

        for (d in floatArrayOf(1f, 1.25f, 2.625f, 2.75f)) {
            for (fs in floatArrayOf(1f, 1.5f)) {
                val marginPx = with(Density(d, fs)) { 12.dp.toPx() }
                // 宽窗: 完整显示, 不省略
                runOnUiThread {
                    density = d
                    fontScale = fs
                    areaDp = 600f
                }
                waitForIdle()
                show()
                waitForIdle()
                val wideArea = onNodeWithTag("area").fetchSemanticsNode().boundsInWindow
                val wideItems = clickablesWindow()
                assertTrue("宽窗未显示菜单 d=$d fs=$fs", wideItems.isNotEmpty())
                assertTrue(
                    "宽窗卡片越过可用区右缘 d=$d fs=$fs",
                    wideItems.maxOf { it.right } + marginPx <= wideArea.right + 0.5f,
                )
                assertTrue(
                    "宽窗卡片越过可用区左缘 d=$d fs=$fs",
                    wideItems.minOf { it.left } >= wideArea.left,
                )
                assertFalse("宽窗短标签被省略 d=$d fs=$fs", menuLabelEllipsized())

                // 多档窄宽 (折叠临界): 卡片仍在可用区内
                for (widthDp in intArrayOf(280, 220, 180, 150)) {
                    runOnUiThread { areaDp = widthDp.toFloat() }
                    waitForIdle()
                    val area = onNodeWithTag("area").fetchSemanticsNode().boundsInWindow
                    val items = clickablesWindow()
                    assertTrue("窄窗未显示菜单 d=$d fs=$fs w=$widthDp", items.isNotEmpty())
                    assertTrue(
                        "窄窗卡片越过可用区右缘 d=$d fs=$fs w=$widthDp: ${items.maxOf { it.right }} > ${area.right - marginPx}",
                        items.maxOf { it.right } + marginPx <= area.right + 0.5f,
                    )
                    assertTrue(
                        "窄窗卡片越过可用区左缘 d=$d fs=$fs w=$widthDp",
                        items.minOf { it.left } >= area.left,
                    )
                }
                runOnUiThread { toolbar!!.hide() }
                waitForIdle()
            }
        }
    }

    // ======================= A2: 会话归属 =======================

    /**
     * 淡出 (140ms) 未结束时的多个时点重开: 新请求必须从收起态开始, 旧动作不执行。
     * 请求实例即会话身份 (与标签无关: 新请求文案相同)。
     */
    @Test
    fun `淡出中多个时点重开新请求默认收起`() = runDesktopComposeUiTest(640, 720) {
        var request by mutableStateOf<ReaderTextSelectionRequest?>(null)
        var menuClicks by mutableIntStateOf(0)
        setContent { ReaderMenuScene(request, onPageDown = {}, onMenuAction = { menuClicks++ }) }

        val reopened = ReaderTextSelectionRequest(
            text = "选中文字",
            geometry = SelectionGeometry(
                textArea = Rect(220f, 320f, 380f, 344f),
                startHandle = Rect(220f, 344f, 244f, 368f),
                endHandle = Rect(356f, 344f, 380f, 368f),
            ),
        )

        for (frame in intArrayOf(1, 3, 5, 7)) {
            runOnUiThread { request = selection }
            waitForIdle()
            openOverflow()
            waitForIdle()
            assertTrue("第 $frame 帧场景: 面板未展开", hasBackButton())

            mainClock.autoAdvance = false
            runOnUiThread { request = null }
            mainClock.advanceTimeByFrame()
            repeat(frame) { mainClock.advanceTimeByFrame() }
            runOnUiThread { request = reopened }
            mainClock.advanceTimeByFrame()

            assertFalse("淡出第 $frame 帧后的新请求继承了旧展开态", hasBackButton())
            assertEquals("新请求不应执行旧动作", 0, menuClicks)
            assertTrue("新请求未显示", clickablesWindow().isNotEmpty())

            mainClock.autoAdvance = true
            runOnUiThread { request = null }
            waitForIdle()
            mainClock.advanceTimeBy(300)
            waitForIdle()
        }
    }

    /**
     * 同一会话内只更新几何 (重复 showMenu, params 非空) 不重置用户展开态;
     * hide 之后的新会话从收起态开始。
     */
    @Test
    fun `同一会话几何更新保留展开态而新会话默认收起`() = runDesktopComposeUiTest(900, 700) {
        var toolbar: TextToolbar? = null
        setContent {
            CompositionLocalProvider(LocalAppColors provides colors) {
                Box(Modifier.fillMaxSize()) {
                    PageSibling {}
                    ProvideAppTextToolbar {
                        Box(
                            Modifier
                                .width(240.dp)
                                .fillMaxHeight()
                                .testTag("area"),
                        ) {
                            toolbar = LocalTextToolbar.current
                        }
                    }
                }
            }
        }

        fun showMenu(rect: Rect) = runOnUiThread {
            toolbar!!.showMenu(
                rect = rect,
                onCopyRequested = {},
                onPasteRequested = {},
                onCutRequested = {},
                onSelectAllRequested = {},
            )
        }

        showMenu(Rect(20f, 40f, 120f, 60f))
        waitForIdle()
        openOverflow()
        waitForIdle()
        assertTrue("旧通道面板未展开", hasBackButton())

        // 同会话: 仅几何变化 → 展开态保留
        showMenu(Rect(20f, 140f, 120f, 160f))
        waitForIdle()
        assertTrue("同会话几何更新重置了展开态", hasBackButton())

        // hide → 淡出中重开 = 新会话 → 收起
        mainClock.autoAdvance = false
        runOnUiThread { toolbar!!.hide() }
        mainClock.advanceTimeByFrame()
        mainClock.advanceTimeBy(32)
        showMenu(Rect(20f, 240f, 120f, 260f))
        mainClock.advanceTimeByFrame()
        assertFalse("新会话继承了旧展开态", hasBackButton())
        mainClock.autoAdvance = true
        waitForIdle()
    }

    // ======================= R3: 窄窗只留更多 =======================

    @Test
    fun `窄到104dp时只显示更多且动作全在面板里`() = runDesktopComposeUiTest(104, 640) {
        var request by mutableStateOf<ReaderTextSelectionRequest?>(null)
        var menuClicks by mutableIntStateOf(0)
        setContent {
            ReaderMenuScene(
                request = request,
                onMenuAction = { menuClicks++ },
                // 锚居中, 窗口只有 104 宽: 一个一级项都放不下
            )
        }
        runOnUiThread { request = selection }
        waitForIdle()

        val row = clickablesWindow()
        assertEquals("窄窗横排应只剩 ⋮ 一个可点节点", 1, row.size)
        // 该节点按语义确认就是"更多"按钮 (不是靠宽度/位置猜)
        assertEquals(
            "横排唯一节点不是更多按钮",
            row.single(),
            onNodeWithContentDescription(menuStrings.more).fetchSemanticsNode().boundsInWindow,
        )
        assertTrue("仅 ⋮ 也不得出屏", row.maxOf { it.right } <= 104f - 12f)
        assertTrue("仅 ⋮ 也不得越过左边界", row.minOf { it.left } >= 0f)

        openOverflow()
        waitForIdle()
        val panel = clickablesWindow()
        assertTrue("面板未显示", panel.isNotEmpty())
        // 面板列表可滚动: 未合并文字节点覆盖全部 9 个动作 (不靠可见面积计数)
        val allLabels = onAllNodes(hasText("", substring = true), useUnmergedTree = true)
            .fetchSemanticsNodes()
            .count { it.config.getOrNull(SemanticsProperties.Text)?.isNotEmpty() == true }
        assertTrue("一级项为 0 时全部动作应在面板里, 实际文字行=$allLabels", allLabels >= 9)
        // 面板里第一项可点: 动作真实可用
        onAllNodes(hasClickAction()).onFirst().performMouseInput { click() }
        waitForIdle()
        assertEquals("面板里的动作未执行", 1, menuClicks)
    }

    // ======================= R4: 控制条禁区 =======================

    @Test
    fun `桌面控制条禁区内的菜单真实显示在禁区之下`() = runDesktopComposeUiTest(640, 720) {
        var request by mutableStateOf<ReaderTextSelectionRequest?>(null)
        val nearTop = ReaderTextSelectionRequest(
            text = "顶",
            geometry = SelectionGeometry(
                textArea = Rect(160f, 56f, 320f, 80f),
                startHandle = Rect(160f, 80f, 184f, 104f),
                endHandle = Rect(296f, 80f, 320f, 104f),
            ),
        )
        setContent { ReaderMenuScene(request, overlayTopInset = 40.dp) }
        runOnUiThread { request = nearTop }
        waitForIdle()

        val bounds = clickablesWindow()
        assertTrue("菜单未显示", bounds.isNotEmpty())
        // 禁区上界 = 40dp 控制条 + 8dp 间距; 卡片再让 12dp 屏边距
        assertTrue("菜单画进了控制条禁区: ${bounds.minOf { it.top }}", bounds.minOf { it.top } >= 40f + 8f)
    }

    // ======================= R4: 偏移 Popup 内的文字选择菜单 =======================

    @Test
    fun `偏移 Popup 内的选中文字菜单出现在本窗口选区旁`() = runDesktopComposeUiTest(640, 720) {
        var showPopup by mutableStateOf(false)
        var pageDowns by mutableIntStateOf(0)
        var popupToolbar: TextToolbar? = null
        var mainToolbar: TextToolbar? = null
        val fixedProvider = object : PopupPositionProvider {
            override fun calculatePosition(
                anchorBounds: IntRect,
                windowSize: IntSize,
                layoutDirection: LayoutDirection,
                popupContentSize: IntSize,
            ) = IntOffset(220, 300)
        }
        setContent {
            ChannelScene(onPageDown = { pageDowns++ }) {
                mainToolbar = LocalTextToolbar.current
                if (showPopup) {
                    AppPopup(
                        popupPositionProvider = fixedProvider,
                        properties = PopupProperties(focusable = true),
                    ) {
                        popupToolbar = LocalTextToolbar.current
                        Box(Modifier.size(320.dp, 180.dp).testTag("popup-card")) {
                            SelectionContainer {
                                Text(
                                    text = "弹出层里可选中文字",
                                    modifier = Modifier.testTag("popup-text"),
                                )
                            }
                        }
                    }
                }
            }
        }
        runOnUiThread { showPopup = true }
        waitForIdle()
        assertNotSame("弹出层仍在用主窗口通道", mainToolbar, popupToolbar)

        onNodeWithTag("popup-text").performTouchInput { longClick(center) }
        waitForIdle()

        val text = onNodeWithTag("popup-text").fetchSemanticsNode()
        val items = clickablesWindow()
        assertTrue("Popup 内长按没有出现菜单", items.isNotEmpty())
        assertTrue("菜单与选区不在同一窗口坐标空间",
            kotlin.math.abs(items.minOf { it.top } - text.boundsInWindow.top) < 120f)
        // 菜单在本窗口范围内 (弹出层卡片的窗口坐标范围)
        val card = onNodeWithTag("popup-card").fetchSemanticsNode().boundsInWindow
        assertTrue("菜单画到弹出层窗口之外", items.all { it.right <= card.right + 24f && it.left >= card.left - 24f })
        assertEquals("菜单项点击不应落到正文", 0, pageDowns)
    }

    // ======================= R4: AppDialog 内的文字选择菜单 =======================

    @Test
    fun `AppDialog 内的选中文字菜单属于对话框窗口`() = runDesktopComposeUiTest(640, 720) {
        ensureDesktopConfig()
        var showDialog by mutableStateOf(false)
        var dialogToolbar: TextToolbar? = null
        var mainToolbar: TextToolbar? = null
        setContent {
            ChannelScene {
                mainToolbar = LocalTextToolbar.current
                if (showDialog) {
                    CompositionLocalProvider(LocalDialogAnchorSize provides IntSize(640, 720)) {
                        AppDialog(onDismissRequest = {}, dim = false) {
                            dialogToolbar = LocalTextToolbar.current
                            Box(Modifier.size(300.dp, 160.dp).testTag("dialog-card")) {
                                SelectionContainer {
                                    Text(
                                        text = "对话框里可选中文字",
                                        modifier = Modifier.testTag("dialog-text"),
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
        runOnUiThread { showDialog = true }
        waitForIdle()
        assertNotSame("对话框仍在用主窗口通道", mainToolbar, dialogToolbar)

        onNodeWithTag("dialog-text").performTouchInput { longClick(center) }
        waitForIdle()

        val text = onNodeWithTag("dialog-text").fetchSemanticsNode()
        val items = onAllNodes(hasClickAction()).fetchSemanticsNodes()
        assertTrue("对话框内长按没有出现菜单", items.isNotEmpty())

        // 菜单必须紧邻选区 (同一坐标空间里的锚定关系):
        // CMP 1.11.1 的对话框 scene layer 不把 layer 偏移计入 positionInWindow,
        // 无法用"根偏移"数值区分层归属; 层归属由上面的通道实例断言 + 真实显示覆盖。
        assertTrue(
            "菜单与选区不在同一位置空间",
            kotlin.math.abs(items.minOf { it.boundsInWindow.top } - text.boundsInWindow.top) < 120f,
        )
    }

    // ======================= F3/R1: 命中与退场 =======================

    @Test
    fun `透明外框穿透_卡片空白不落正文_菜单项可点`() = runDesktopComposeUiTest(640, 720) {
        var request by mutableStateOf<ReaderTextSelectionRequest?>(null)
        var pageDowns by mutableIntStateOf(0)
        var menuClicks by mutableIntStateOf(0)
        setContent {
            ReaderMenuScene(
                request = request,
                onPageDown = { pageDowns++ },
                onMenuAction = { menuClicks++ },
            )
        }
        runOnUiThread { request = selection }
        waitForIdle()

        val row = clickablesWindow()
        val cardLeft = row.minOf { it.left } - 8f
        val cardRight = row.maxOf { it.right } + 8f
        val cardTop = row.minOf { it.top }
        val cardBottom = row.maxOf { it.bottom }

        onRoot().performTouchInput { click(Offset((cardLeft + cardRight) / 2f, cardTop - 20f)) }
        waitForIdle()
        assertEquals("透明外框吞掉了点击", 1, pageDowns)

        onRoot().performTouchInput { click(Offset(cardRight - 4f, (cardTop + cardBottom) / 2f)) }
        waitForIdle()
        assertEquals("活动卡片空白穿透到正文", 1, pageDowns)
        assertEquals("活动卡片空白触发动作", 0, menuClicks)

        onAllNodes(hasClickAction()).onFirst().performMouseInput { click() }
        waitForIdle()
        assertEquals("菜单项点击未生效", 1, menuClicks)
        assertEquals("菜单项点击漏到正文", 1, pageDowns)
    }

    @Test
    fun `退场期间同一可见行位置不变且输入落到下层`() = runDesktopComposeUiTest(640, 720) {
        var request by mutableStateOf<ReaderTextSelectionRequest?>(null)
        var pageDowns by mutableIntStateOf(0)
        var menuClicks by mutableIntStateOf(0)
        setContent {
            ReaderMenuScene(
                request = request,
                onPageDown = { pageDowns++ },
                onMenuAction = { menuClicks++ },
            )
        }
        runOnUiThread { request = selection }
        waitForIdle()
        openOverflow()
        waitForIdle()

        // 真实滚动面板 (返回行固定): 从最上面的面板行往上拖
        val panelRows = clickablesWindow()
        val rowTop = panelRows.minOf { it.top }
        val swipeStart = panelRows.first { it.top == rowTop }.center
        onRoot().performTouchInput {
            swipe(swipeStart, Offset(swipeStart.x, swipeStart.y - 60f), durationMillis = 200)
        }
        waitForIdle()

        // 取一行"真正可见"的文字 (裁切后仍有高度), 记录内容与位置
        val before = visibleTextRows()
        assertTrue("面板没有文字行", before.isNotEmpty())
        val (label, rowBefore) = before.minBy { it.second.top }
        val scrolledTop = before.minOf { it.second.top }

        mainClock.autoAdvance = false
        runOnUiThread { request = null }
        mainClock.advanceTimeByFrame()
        mainClock.advanceTimeBy(32)

        // 同一行 (按文字内容识别) 在退场淡出中的窗口位置不变 → 滚动位置未丢
        val during = visibleTextRows()
        val sameLine = during.firstOrNull { it.first == label }
        assertNotNull("退场中找不到同一文字行", sameLine)
        assertEquals("退场期间列表位置跳动", rowBefore.top, sameLine!!.second.top, 0.5f)
        assertTrue("退场期间列表整体上跳", during.minOf { it.second.top } <= scrolledTop + 0.5f)
        assertEquals("退场期间仍可点菜单项", 0, clickablesWindow().size)

        onRoot().performTouchInput { click(swipeStart) }
        waitForIdle()
        assertEquals("退场期间点击未落到下层正文", 1, pageDowns)
        assertEquals("退场期间执行了旧动作", 0, menuClicks)

        onRoot().performTouchInput {
            swipe(swipeStart, Offset(swipeStart.x, swipeStart.y - 60f), durationMillis = 120)
        }
        waitForIdle()
        assertEquals("退场期间拖动未落到下层正文", 2, pageDowns)

        mainClock.autoAdvance = true
        mainClock.advanceTimeBy(200)
        waitForIdle()
        assertTrue("退场结束菜单未消失", visibleTextRows().isEmpty())
    }

    // ======================= R2: 短面板 / 可滚面板 =======================

    @Test
    fun `短溢出面板不被撑高且没有滚动条`() = runDesktopComposeUiTest(200, 640) {
        // 图片菜单 4 项 + 窄窗: 一级项放不下 → 全部进面板, 面板行数少 → 无需滚动
        val request = buildImageRequest()
        ReaderImageActionMenu.show(request.anchor, request.actions)
        try {
            setContent { ImageMenuScene() }
            waitForIdle()

            openOverflow()
            waitForIdle()

            val items = clickablesWindow()
            assertTrue("图片菜单应有更多与返回行", items.size >= 2)
            val cardRight = items.maxOf { it.right }
            val cardBottom = items.maxOf { it.bottom }
            val cardTop = items.minOf { it.top }
            // 返回行 = 按无障碍描述定位的返回按钮 (与面板同宽, 不能按宽度区分)
            val collapseRow = backButtons().single().boundsInWindow
            assertEquals("返回行被挤掉", 50f, collapseRow.height, 1f)
            assertEquals("返回行不在卡片底部", cardBottom, collapseRow.bottom, 0.5f)
            assertEquals("返回行未通栏 (不是卡片右缘)", cardRight, collapseRow.right, 0.5f)
            assertFalse(
                "无需滚动的面板出现了滚动条",
                hasScrollbarPixels(cardRight, cardTop, collapseRow.top),
            )
        } finally {
            ReaderImageActionMenu.dismiss()
        }
    }

    @Test
    fun `可滚面板返回行固定并出现滚动条`() = runDesktopComposeUiTest(640, 720) {
        var request by mutableStateOf<ReaderTextSelectionRequest?>(null)
        setContent { ReaderMenuScene(request) }
        runOnUiThread { request = selection }
        waitForIdle()
        openOverflow()
        waitForIdle()

        val before = clickablesWindow()
        val collapseBefore = backButtons().single().boundsInWindow
        val cardRight = before.maxOf { it.right }
        val listTop = before.minOf { it.top }

        // 可滚面板应有滚动条像素
        assertTrue(
            "可滚面板没有滚动条",
            hasScrollbarPixels(cardRight, listTop, collapseBefore.top),
        )

        val rowTop = before.minOf { it.top }
        val swipeStart = before.first { it.top == rowTop }.center
        onRoot().performTouchInput {
            swipe(swipeStart, Offset(swipeStart.x, swipeStart.y - 60f), durationMillis = 200)
        }
        waitForIdle()

        val after = clickablesWindow()
        val collapseAfter = backButtons().single().boundsInWindow
        assertEquals("返回行随滚动移动", collapseBefore, collapseAfter)
        assertTrue("面板没有真的滚动", after.minOf { it.top } <= rowTop)
        assertTrue(
            "滚动后滚动条消失",
            hasScrollbarPixels(cardRight, listTop, collapseAfter.top),
        )
    }

    // ======================= F1/R5: 字体缩放 / 生命周期 =======================

    @Test
    fun `字体缩放后重算且仍在可用区内`() = runDesktopComposeUiTest(640, 720) {
        var request by mutableStateOf<ReaderTextSelectionRequest?>(null)
        var fontScale by mutableFloatStateOf(1f)
        setContent { ReaderMenuScene(request, fontScale = fontScale) }
        runOnUiThread { request = selection }
        waitForIdle()
        val before = clickablesWindow()
        val beforeWidth = before.maxOf { it.right } - before.minOf { it.left }

        runOnUiThread { fontScale = 2f }
        waitForIdle()

        val after = clickablesWindow()
        assertTrue("字体放大后菜单越出屏边距", after.maxOf { it.right } + 8f <= 640f - 12f)
        assertTrue(
            "字体放大后尺寸未重算",
            after.maxOf { it.right } - after.minOf { it.left } > beforeWidth,
        )
    }

    @Test
    fun `关闭后新请求用新锚且旧动作不串用`() = runDesktopComposeUiTest(640, 720) {
        var request by mutableStateOf<ReaderTextSelectionRequest?>(null)
        var clicksA by mutableIntStateOf(0)
        var clicksB by mutableIntStateOf(0)
        var current by mutableStateOf<ReaderTextActions?>(null)
        setContent {
            CompositionLocalProvider(LocalAppColors provides colors) {
                Box(Modifier.fillMaxSize()) {
                    ReaderTextActionMenu(
                        request = request,
                        actions = current ?: ReaderTextActions(
                            onReplace = { clicksA++ },
                            onBookmark = { clicksA++ },
                            onUnderline = { _, _ -> clicksA++ },
                            onReadAloud = { clicksA++ },
                            onSearchContent = { clicksA++ },
                        ),
                        onFinally = {},
                    )
                }
            }
        }
        runOnUiThread { request = selection }
        waitForIdle()
        val first = clickablesWindow()
        val firstRight = first.maxOf { it.right }
        onAllNodes(hasClickAction()).onFirst().performMouseInput { click() }
        waitForIdle()
        assertEquals(1, clicksA)

        // 关闭 → 等到完全移除 → 新请求 (锚右移 120)
        mainClock.autoAdvance = false
        runOnUiThread { request = null }
        repeat(40) { mainClock.advanceTimeByFrame() }
        assertTrue("关闭后菜单未消失", clickablesWindow().isEmpty())
        mainClock.autoAdvance = true

        val moved = ReaderTextSelectionRequest(
            text = "另一段",
            geometry = SelectionGeometry(
                textArea = Rect(280f, 320f, 440f, 344f),
                startHandle = Rect(280f, 344f, 304f, 368f),
                endHandle = Rect(416f, 344f, 440f, 368f),
            ),
        )
        runOnUiThread {
            current = ReaderTextActions(
                onReplace = { clicksB++ },
                onBookmark = { clicksB++ },
                onUnderline = { _, _ -> clicksB++ },
                onReadAloud = { clicksB++ },
                onSearchContent = { clicksB++ },
            )
            request = moved
        }
        waitForIdle()
        val second = clickablesWindow()
        assertTrue("新请求未显示", second.isNotEmpty())
        assertTrue("新请求没有按新锚定位", second.maxOf { it.right } > firstRight + 60f)
        onAllNodes(hasClickAction()).onFirst().performMouseInput { click() }
        waitForIdle()
        assertEquals("旧动作被新请求复用", 1, clicksA)
        assertEquals("新请求的动作未执行", 1, clicksB)
    }

    @Test
    fun `菜单控件不抢当前焦点`() = runDesktopComposeUiTest(640, 720) {
        var request by mutableStateOf<ReaderTextSelectionRequest?>(null)
        val focusRequester = FocusRequester()
        setContent {
            ReaderMenuScene(request) {
                Box(
                    Modifier
                        .size(24.dp)
                        .testTag("probe")
                        .focusRequester(focusRequester)
                        .focusable(),
                )
            }
        }
        runOnUiThread { focusRequester.requestFocus() }
        waitForIdle()
        onNodeWithTag("probe").assertIsFocused()

        runOnUiThread { request = selection }
        waitForIdle()
        openOverflow()
        waitForIdle()
        onNodeWithTag("probe").assertIsFocused()
        assertFalse("展开面板后菜单应仍可见", clickablesWindow().isEmpty())
    }

    // ======================= 辅助 =======================

    /** 图片菜单场景: 正文 sibling + 主窗通道 + 图片菜单宿主 (与阅读页同一挂载方式)。 */
    @Composable
    private fun ImageMenuScene() {
        CompositionLocalProvider(LocalAppColors provides colors) {
            Box(Modifier.fillMaxSize()) {
                PageSibling {}
                ProvideAppTextToolbar {
                    Box(Modifier.fillMaxSize())
                }
                ReaderImageActionMenu.Host()
            }
        }
    }

    private class ImageMenuFixture(
        val anchor: Rect,
        val actions: ReaderImageActions,
    )

    private fun buildImageRequest(): ImageMenuFixture = ImageMenuFixture(
        anchor = readerMenuAnchor(120f, 300f),
        actions = ReaderImageActions(
            view = {},
            refresh = {},
            save = {},
            selectDirectory = {},
        ),
    )

    private companion object {
        private var configReady = false

        /**
         * 真实桌面配置链: 隔离临时目录 + 生产 `DesktopPreferenceProvider` /
         * `DesktopAppConfigAccessor` / `DesktopPlatformCapabilities`。只注册配置与能力,
         * 不启动应用、不起网络、不开窗口。
         */
        @Synchronized
        fun ensureDesktopConfig() {
            if (configReady) return
            val root = Files.createTempDirectory("legado-text-menu-test").toFile()
            val files = File(root, "files").apply { mkdirs() }
            val cache = File(root, "cache").apply { mkdirs() }
            AppFilesDirs.register(object : AppFilesDir {
                override val filesDir: String = files.absolutePath
                override val cacheDir: String = cache.absolutePath
                override val externalFilesDir: String? = null
                override val externalCacheDir: String? = null
            })
            PreferenceProviders.register(DesktopPreferenceProvider())
            AppConfigProviders.register(DesktopAppConfigAccessor())
            PlatformCapabilityProviders.register(DesktopPlatformCapabilities)
            configReady = true
        }
    }
}
