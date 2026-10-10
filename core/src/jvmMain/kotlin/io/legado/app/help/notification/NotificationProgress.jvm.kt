package io.legado.app.help.notification

import java.awt.GraphicsEnvironment
import java.awt.Taskbar
import java.awt.Window
import java.util.concurrent.atomic.AtomicLong

/**
 * [NotificationProgress] 的桌面 JVM actual 实现。
 *
 * 进度只上任务栏进度位 ([DesktopTaskbar])。
 *
 * # 设计要点
 * - 桌面端通知无"持久显示 + 进度条"概念, 进度值走 [DesktopTaskbar] (任务栏/Dock)
 * - 通知标识只用于判定"上屏进度是否属于本次取消的调用方": 取消别的标识不清掉当前那格
 *
 * 模式参考 `registerAndroidMediaNotificationProvider`。
 */
class DesktopNotificationProgress : NotificationProgress {

    override fun showProgress(id: Int, title: String, content: String, progress: Int, max: Int) {
        DesktopTaskbar.show(id, progress, max)
    }

    override fun cancel(id: Int) {
        DesktopTaskbar.clear(id)
    }
}

/**
 * 任务栏进度指示: Windows 任务栏图标进度条 / macOS Dock 徽章 (java.awt.Taskbar 标准 API)。
 * 平台不支持的能力自动跳过, 不做任何私有协议适配。
 *
 * # 标识归属 (进度位只有一个)
 * 平台只留一个进度位 (Windows 绑定窗口, 其余平台全应用一个), 多条业务进度无法并存: 后取用者持有,
 * 释放要标识与代次都相符 (每次取用自增代次) —— 上一代的收尾晚到时, 清不掉新一代刚上屏的进度。
 *
 * # 优先级 (媒体优先)
 * 每条进度都是同一个持有者位上的一次登记, 只是优先级不同: 音频/朗读的媒体进度 (桌面媒体侧直调
 * ITaskbarList3 写同一格) 优先级高于各业务进度 (对照鸿蒙长时任务的"音频优先级最高, 会抢占后台
 * 作业") —— 媒体持有期间业务进度不上屏也不登记, 业务结束时也不清屏; 媒体退场后业务进度靠自己的
 * 下一次进度帧回到屏上。
 *
 * # 谁写那一格
 * 持有者自己写: 业务进度经本对象上屏 ([show]), 媒体进度由媒体侧写 ([showMedia] 只登记归属与
 * 优先级) —— 原生任务栏接口有"暂停"态, 平台标准接口没有。
 *
 * # 取舍
 * 单槽只能显示一条: 同优先级的后写入者顶掉先写入的进度 (被顶掉者下一次进度帧回到屏上), 持有者结束时
 * 清空整格 (另一条业务仍在跑时同样清, 等它下一次进度帧); 非持有者结束不清屏上那条。
 */
object DesktopTaskbar {

    private val taskbar: Taskbar? by lazy {
        runCatching {
            if (GraphicsEnvironment.isHeadless() || !Taskbar.isTaskbarSupported()) null
            else Taskbar.getTaskbar()
        }.getOrNull()
    }

    /** 业务进度 (更新书籍/书源校验/导出) 的优先级。 */
    private const val PRIORITY_BUSINESS = 0

    /** 媒体进度 (音频/朗读) 的优先级高于各业务: 媒体持有期间业务进度让位。 */
    private const val PRIORITY_MEDIA = 1

    /** 取用代次: 每次取用自增 (释放要带回本代下的那次取值)。 */
    private val generationCounter = AtomicLong()

    /** 当前占用进度位的持有者 (标识 + 代次 + 优先级); null = 无持有者。 */
    @Volatile
    private var owner: Owner? = null

    /**
     * 该标识的业务进度取用进度位, 返回本代次 (释放带着它); max <= 0 表示不确定进度, 不上屏但同样取用。
     *
     * 同一业务的每帧进度都是一次取用、都自增代次, 故本代的释放要用本代最后一次取用的取值: 上一代
     * (已取消但收尾晚到的那一代) 用的取值就此对不上, 不清新一代刚上屏的进度。
     */
    fun show(id: Int, progress: Int, max: Int): Long {
        val generation = generationCounter.incrementAndGet()
        // 媒体在跑时让位: 不登记也不上屏 (媒体退场后靠自己的下一帧进度回到屏上)
        if (!claim(id, generation, PRIORITY_BUSINESS, selfDrawn = false)) return generation
        if (max <= 0) return generation
        val percent = (progress.toLong() * 100 / max).toInt().coerceIn(0, 100)
        apply(percent, "$progress/$max")
        return generation
    }

    /**
     * 媒体 (音频/朗读) 进度取用进度位, 返回本代次 (退场带着它释放)。
     *
     * 与业务进度同一登记, 优先级更高 (最高优先级, 不会被让位): 持有期间业务进度一律让位。那一格由
     * 媒体侧自己写 (见类注释), 本函数只登记归属与优先级。
     */
    fun showMedia(id: Int): Long {
        val generation = generationCounter.incrementAndGet()
        claim(id, generation, PRIORITY_MEDIA, selfDrawn = true)
        return generation
    }

    /** 登记本代持有者; 已有更高优先级的持有者时让位 (不登记), 返回 false。 */
    private fun claim(id: Int, generation: Long, priority: Int, selfDrawn: Boolean): Boolean {
        val current = owner
        if (current != null && current.priority > priority) return false
        owner = Owner(id, generation, priority, selfDrawn)
        return true
    }

    /** 释放本代: 只有标识与代次都相符才清空, 别的业务与别的代的进度不被误清。 */
    fun clear(id: Int, generation: Long) = clearIf { it.id == id && it.generation == generation }

    /**
     * 释放该标识当前那一代: 供拿不到代次的释放口用 (进度通知的取消来自共享业务层的收尾回调)。
     *
     * 按标识放行, 不误清别的标识; 对应的业务收尾由共享层按本代判定并保证同代调用 (见
     * `UpdateBookShared` 的任务收尾)。
     */
    fun clear(id: Int) = clearIf { it.id == id }

    /** 清空命中本代的一格 (自绘的持有者那一格由它自己清)。 */
    private fun clearIf(matches: (Owner) -> Boolean) {
        val current = owner ?: return
        if (!matches(current)) return
        owner = null
        // 媒体那一格由媒体侧清 (退场时自己关); 这里只撤登记
        if (current.selfDrawn) return
        apply(-1, null)
    }

    private fun apply(percent: Int, badge: String?) = runCatching {
        val bar = taskbar ?: return@runCatching
        if (bar.isSupported(Taskbar.Feature.PROGRESS_VALUE_WINDOW)) {
            // Windows: 进度绑定到具体窗口
            mainWindow()?.let { bar.setWindowProgressValue(it, percent) }
        }
        if (bar.isSupported(Taskbar.Feature.PROGRESS_VALUE)) {
            bar.setProgressValue(percent)
        }
        if (bar.isSupported(Taskbar.Feature.ICON_BADGE_TEXT)) {
            bar.setIconBadge(badge)
        }
    }

    private fun mainWindow(): Window? =
        Window.getWindows().firstOrNull { it.isDisplayable && it.isVisible }

    /**
     * 进度位持有者: 标识/代次/优先级成对读写, 免得读到两次取用拼起来的组合。
     *
     * [selfDrawn] = 那一格由持有者自己写 (媒体进度走原生接口), 本对象只登记归属与优先级, 释放时也不
     * 代它清屏。
     */
    private class Owner(
        val id: Int,
        val generation: Long,
        val priority: Int,
        val selfDrawn: Boolean,
    )
}

/**
 * 桌面宿主启动早期注册 [NotificationProgress] 的 actual 实现。
 *
 * 调用时机: desktop main(), 在任何 commonMain 代码调用 `NotificationProgresses.get()` 之前。
 *
 * 模式参考 `registerAndroidMediaNotificationProvider`。
 */
fun registerDesktopNotificationProgress() {
    NotificationProgresses.register(DesktopNotificationProgress())
}
