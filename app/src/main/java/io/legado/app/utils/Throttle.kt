package io.legado.app.utils

// 未下沉至 shared/jvmAndAndroidMain 原因: 详见同包 Debounce.kt 顶部注释.
// Throttle 继承 Debounce, 共用 buildMainHandler() 主线程 Handler, 下沉会引发同样的
// trailing 回调主线程依赖问题 (case 4: keyPageDebounce 鼠标滚轮场景).
class Throttle<T>(
    wait: Long = 0L,
    leading: Boolean = true,
    trailing: Boolean = true,
    func: () -> T
) : Debounce<T>(wait, wait, leading, trailing, func)

fun <T> throttle(
    wait: Long = 0L,
    leading: Boolean = true,
    trailing: Boolean = true,
    func: () -> T
) = Throttle(wait, leading, trailing, func)
