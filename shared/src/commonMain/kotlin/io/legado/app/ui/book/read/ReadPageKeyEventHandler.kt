package io.legado.app.ui.book.read

/**
 * 阅读页按键处理器（KMP 共用算法核心）。
 *
 * 对照 app 端 `ReadBookActivity.ReadBookKeyHandler`：下沉翻页按键判定与派发逻辑到
 * shared/commonMain，平台 actual 把平台 KeyEvent 的 keyCode 透传到 [onKeyDown]。
 *
 * - 不依赖 `android.view.KeyEvent`：keyCode 数值与 Android 一致（见 [ReadKeyCodes]），
 *   桌面/iOS/鸿蒙端按平台 KeyEvent 映射后透传即可。
 * - 菜单可见时不拦截翻页键（return false），由宿主处理菜单显隐（对照 app 端
 *   `if (menuLayoutIsVisible || event.repeatCount > 0) return false`）。
 * - 按项目规则：音量键始终用于翻页（移除 `volumeKeyPageOnPlay` 守卫）。
 *
 * @param menuLayoutIsVisible 菜单层（顶/底栏、底部弹窗）是否可见
 * @param isPrevKey 用户自定义"上一页"keyCode 判定（对照 app 端 `BaseReadBookActivity.isPrevKey`）
 * @param isNextKey 用户自定义"下一页"keyCode 判定（对照 app 端 `BaseReadBookActivity.isNextKey`）
 * @param onPrevPage 上一页回调（返回是否成功翻页，未到章首时 true，到章首时由调用方触发切章）
 * @param onNextPage 下一页回调（返回是否成功翻页）
 * @param onPrevChapter 切到上一章占位参数（shared 端不直接调用，平台 actual 在
 *   [onPrevPage] 返回 false 时自行触发切章，预留此参数供未来扩展）
 * @param onNextChapter 切到下一章占位参数（语义同 [onPrevChapter]）
 * @param keyPageDebouncer 可选的翻页去抖器（对照 app 端 `throttle` + `nextPageDebounce`），
 *   null 时直接同步调 [onPrevPage]/[onNextPage]
 */
class ReadPageKeyEventHandler(
    private val menuLayoutIsVisible: () -> Boolean,
    private val isPrevKey: (Int) -> Boolean,
    private val isNextKey: (Int) -> Boolean,
    private val onPrevPage: () -> Unit,
    private val onNextPage: () -> Unit,
    private val onPrevChapter: () -> Unit = {},
    private val onNextChapter: () -> Unit = {},
    private val keyPageDebouncer: ReadKeyPageDebouncer? = null,
) {

    /**
     * KeyDown 处理：返回 true 表示已拦截。
     *
     * 与原 app 端语义一致：菜单可见 / 长按重复事件 / 非翻页键 → return false 让事件继续传播。
     */
    fun onKeyDown(keyCode: Int, repeatCount: Int = 0): Boolean {
        if (menuLayoutIsVisible() || repeatCount > 0) return false
        // 用户自定义 prev/next 键优先（对照 app 端 isPrevKey/isNextKey 分支）
        when {
            isPrevKey(keyCode) -> {
                dispatchPage(prev = true)
                return true
            }

            isNextKey(keyCode) -> {
                dispatchPage(prev = false)
                return true
            }
        }
        // 物理翻页键：音量键始终翻页（按 project_memory 规则移除 volumeKeyPageOnPlay 守卫）
        when (keyCode) {
            ReadKeyCodes.VOLUME_UP -> {
                dispatchPage(prev = true)
                return true
            }

            ReadKeyCodes.VOLUME_DOWN -> {
                dispatchPage(prev = false)
                return true
            }

            ReadKeyCodes.PAGE_UP -> {
                dispatchPage(prev = true)
                return true
            }

            ReadKeyCodes.PAGE_DOWN -> {
                dispatchPage(prev = false)
                return true
            }

            ReadKeyCodes.SPACE -> {
                dispatchPage(prev = false)
                return true
            }
        }
        return false
    }

    /**
     * KeyUp 处理：对照 app 端原版 `onKeyUp`，音量键抬起无需特殊处理（返回 false 让事件继续）。
     *
     * 原 app 端此处用于音量键抬起时调 `volumeKeyPage(NONE)` 触发去抖尾调；
     * shared 版统一由 [keyPageDebouncer] 内部处理 trailing 调用，无需在此分发。
     */
    fun onKeyUp(keyCode: Int): Boolean {
        when (keyCode) {
            ReadKeyCodes.VOLUME_UP, ReadKeyCodes.VOLUME_DOWN -> {
                keyPageDebouncer?.flush()
                return false
            }
        }
        return false
    }

    /** 鼠标滚轮翻页（对照 app 端 `mouseWheelPage`，去抖 trailing 模式） */
    fun onMouseWheel(down: Boolean, mouseWheelPageEnabled: Boolean) {
        if (menuLayoutIsVisible() || !mouseWheelPageEnabled) return
        keyPageDebouncer?.let { debouncer ->
            // 滚轮去抖：trailing 模式（与原 app 端 mouseWheel=true 一致）
            debouncer.dispatch(prev = !down, leading = false, trailing = true)
            return
        }
        dispatchPage(prev = !down)
    }

    private fun dispatchPage(prev: Boolean) {
        val debouncer = keyPageDebouncer
        if (debouncer != null) {
            // 物理键去抖：leading 模式（与原 app 端 mouseWheel=false 一致）
            debouncer.dispatch(prev = prev, leading = true, trailing = false)
            return
        }
        if (prev) onPrevPage() else onNextPage()
    }
}

/**
 * 翻页去抖协议（对照 app 端 `throttle` + `nextPageDebounce.apply { wait=200; maxWait=200; leading=true; trailing=false }`）。
 *
 * 平台 actual 实现此接口以接入各自 throttle 框架；shared 端不提供默认实现。
 */
interface ReadKeyPageDebouncer {
    /** 派发一次翻页动作；leading=true 立即执行首帧，trailing=true 在静默期补一次尾调 */
    fun dispatch(prev: Boolean, leading: Boolean, trailing: Boolean)

    /** 强制刷新去抖尾调（音量键抬起时使用，对照原 app 端 `volumeKeyPage(NONE)`） */
    fun flush()
}

/**
 * 跨平台翻页 keyCode 常量（与 Android `android.view.KeyEvent` 数值一致）。
 *
 * 平台 actual 把平台 KeyEvent 映射到这些数值后透传给 [ReadPageKeyEventHandler.onKeyDown]。
 * 桌面端 AWT KeyEvent 需做映射（如 `VK_PAGE_UP` → [PAGE_UP]）。
 */
object ReadKeyCodes {
    const val VOLUME_UP = 24
    const val VOLUME_DOWN = 25
    const val PAGE_UP = 92
    const val PAGE_DOWN = 93
    const val SPACE = 62
    const val MENU = 82
}
