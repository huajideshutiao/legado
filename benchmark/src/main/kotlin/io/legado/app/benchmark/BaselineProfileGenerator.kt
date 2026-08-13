package io.legado.app.benchmark

import androidx.benchmark.macro.MacrobenchmarkScope
import androidx.benchmark.macro.junit4.BaselineProfileRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.uiautomator.By
import androidx.test.uiautomator.Until
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * 三核心界面 baseline profile 采集: 小说阅读 / 书架 / 书源编辑。
 *
 * 采集目标: :app 的 nonMinifiedRelease 变体 (扩展自 release, 复制其 applicationIdSuffix,
 * 故包名为 `shutiao.reader` + `.release`; 官方插件 initWith(release) 语义)。
 * 设备要求: Android 13 (API 33)+ 或已 root 的 Android 9 (API 28)+ (BaselineProfileRule 官方限制)。
 * 数据预置: 建议采集前在设备上手动完成一次首启初始化, 并在书架导入至少一本书,
 *   阅读页轨迹才能被采集 (书架为空时自动跳过阅读页部分, 不报错)。
 *
 * UI 定位依据 (zh 语言):
 * - 底部导航 4 tab 图标 contentDescription 恒为中文标签 (「主页/书架/发现/我的」);
 * - 首启拦截链: 隐私协议「同意」→ 帮助文档「确认」→ 「设置本地密码」对话框「取消」;
 * - 阅读页正文为 Canvas 绘制无文字, 翻页走默认点击动作 (右 1/3 下一页 / 中心菜单 / 左 1/3 上一页)。
 */
private const val PACKAGE_NAME = "shutiao.reader.release"

// 底部导航 tab 图标 contentDescription
private const val TAB_BOOKSHELF = "书架"
private const val TAB_MY = "我的"

// 首启拦截链 (均只出现一次, 已初始化后不再出现)
private const val PRIVACY_AGREE = "同意"
private const val HELP_OK = "确认"
private const val SET_PASSWORD_TITLE = "设置本地密码"
private const val CANCEL = "取消"

// 书架空态提示 (bookshelf_empty)
private const val EMPTY_BOOKSHELF_HINT = "书架还空着，先去搜索书籍或从发现里添加吧！"

// 书源列表/编辑页
private const val MORE_MENU = "更多菜单" // 书源列表页顶部溢出菜单 desc (more_menu)
private const val EDIT = "编辑"         // 书源行内编辑按钮 desc (edit)
private const val NEW_SOURCE = "新建书源" // 溢出菜单项 (add_book_source)
private const val SAVE = "保存"         // 编辑页保存按钮 desc (action_save)

@RunWith(AndroidJUnit4::class)
class BaselineProfileGenerator {

    @get:Rule
    val baselineProfileRule = BaselineProfileRule()

    /**
     * 启动 + 书架 + (书架有书时) 阅读页。
     * 书架是冷启动落地页, 启动流 profile 顺带参与 Dex Layout 优化。
     */
    @Test
    fun startupBookshelfReader() =
        baselineProfileRule.collect(PACKAGE_NAME, includeInStartupProfile = true) {
            pressHome()
            startActivityAndWait()
            settleFirstRun()
            // 冷启动落点即书架 tab
            device.wait(Until.hasObject(By.desc(TAB_BOOKSHELF)), 15_000)
            device.findObject(By.desc(TAB_BOOKSHELF)).click()
            device.waitForIdle()
            // 书架滚动 (列表/网格样式均渲染; 空书架滑动无害)
            repeat(2) {
                swipeScrollableUp()
                device.waitForIdle()
            }
            if (openFirstBook()) {
                readPageJourney()
            }
        }

    /**
     * 书源管理 → 书源编辑页。
     * 列表无书源数据时经「新建书源」进入编辑页 (顺带为后续采集造数据);
     * 有书源时点行内「编辑」进入, 两个入口交替覆盖。
     */
    @Test
    fun bookSourceEdit() =
        baselineProfileRule.collect(PACKAGE_NAME) {
            pressHome()
            startActivityAndWait()
            settleFirstRun()
            device.wait(Until.hasObject(By.desc(TAB_MY)), 15_000)
            device.findObject(By.desc(TAB_MY)).click()
            // 「书源」分类在 MyConfigScreen 靠后, 滚动查找
            scrollUntilFound("书源管理", maxSwipes = 6)
            device.findObject(By.text("书源管理")).click()
            // 书源列表页: 顶部溢出菜单 desc 恒有
            device.wait(Until.hasObject(By.desc(MORE_MENU)), 8_000)
            val editButton = device.findObject(By.desc(EDIT))
            if (editButton != null) {
                editButton.click()
            } else {
                device.findObject(By.desc(MORE_MENU)).click()
                device.wait(Until.hasObject(By.text(NEW_SOURCE)), 5_000)
                device.findObject(By.text(NEW_SOURCE)).click()
            }
            // 编辑页 (新建/编辑同页): 保存按钮 desc 恒有
            device.wait(Until.hasObject(By.desc(SAVE)), 8_000)
            // 6 个规则 Tab 逐个切换预热 (窄屏不可见的 tab 自动跳过)
            listOf("搜索", "发现", "详情", "目录", "正文", "基本").forEach { tab ->
                device.findObject(By.text(tab))?.click()
                device.waitForIdle()
            }
            device.findObject(By.desc(SAVE))?.click()
            device.waitForIdle()
        }

    // ==================== helpers ====================

    /**
     * 首启拦截链 (幂等, 非首启全部跳过):
     * 隐私协议「同意」→ 帮助文档「确认」→ 「设置本地密码」对话框「取消」。
     */
    private fun MacrobenchmarkScope.settleFirstRun() {
        if (device.wait(Until.hasObject(By.text(PRIVACY_AGREE)), 10_000)) {
            device.findObject(By.text(PRIVACY_AGREE)).click()
            device.waitForIdle()
        }
        if (device.wait(Until.hasObject(By.text(HELP_OK)), 8_000)) {
            device.findObject(By.text(HELP_OK)).click()
            device.waitForIdle()
        }
        if (device.wait(Until.hasObject(By.text(SET_PASSWORD_TITLE)), 8_000)) {
            device.findObject(By.text(CANCEL)).click()
            device.waitForIdle()
        }
        // 自动检查更新 (GitHub 可达时) 可能弹「发现新版本」: 顶部栏「下载」特征 + 返回关闭
        if (device.wait(Until.hasObject(By.text("下载")), 3_000)) {
            device.pressBack()
            device.waitForIdle()
        }
    }

    /** 滚动当前页面主 scrollable 容器 (找不到容器时退化为屏幕级滑动)。 */
    private fun MacrobenchmarkScope.swipeScrollableUp() {
        val scrollable = device.findObject(By.scrollable(true))
        val bounds = scrollable?.visibleBounds
        if (bounds != null) {
            val fromY = bounds.bottom - bounds.height() / 4
            val toY = bounds.top + bounds.height() / 4
            device.swipe(bounds.centerX(), fromY, bounds.centerX(), toY, 40)
        } else {
            device.swipe(
                device.displayWidth / 2,
                device.displayHeight * 3 / 4,
                device.displayWidth / 2,
                device.displayHeight / 4,
                40
            )
        }
    }

    /** 滚动直到目标文字可见 (用于「我的」页定位书源分类)。 */
    private fun MacrobenchmarkScope.scrollUntilFound(text: String, maxSwipes: Int) {
        var swipes = 0
        while (device.findObject(By.text(text)) == null && swipes < maxSwipes) {
            swipeScrollableUp()
            device.waitForIdle()
            swipes++
        }
    }

    /**
     * 书架第一本书可点则点击进入阅读页; 空书架返回 false。
     * 点击位置取 scrollable 容器顶部下方约 7% 高度, 避开列表内嵌的分组栏 header。
     */
    private fun MacrobenchmarkScope.openFirstBook(): Boolean {
        if (device.wait(Until.hasObject(By.text(EMPTY_BOOKSHELF_HINT)), 2_000)) return false
        val scrollable = device.findObject(By.scrollable(true)) ?: return false
        val bounds = scrollable.visibleBounds
        val y = bounds.top + (bounds.height() * 0.07).toInt()
        device.click(bounds.centerX(), y)
        // 阅读页全屏覆盖后底部导航「书架」desc 消失
        return device.wait(Until.gone(By.desc(TAB_BOOKSHELF)), 10_000)
    }

    /** 阅读页: 右 1/3 下一页 ×3 → 中心菜单展开/收起 → 左 1/3 上一页 ×2 → 返回书架。 */
    private fun MacrobenchmarkScope.readPageJourney() {
        device.waitForIdle()
        repeat(3) {
            device.click(device.displayWidth * 5 / 6, device.displayHeight / 2)
            device.waitForIdle()
        }
        // 中心点击 = 菜单 (正文 Canvas 绘制无文字, 菜单展开后才有「目录」等文字)
        device.click(device.displayWidth / 2, device.displayHeight / 2)
        if (device.wait(Until.hasObject(By.text("目录")), 5_000)) {
            device.pressBack()
            device.waitForIdle()
        }
        repeat(2) {
            device.click(device.displayWidth / 6, device.displayHeight / 2)
            device.waitForIdle()
        }
        device.pressBack()
        device.wait(Until.hasObject(By.desc(TAB_BOOKSHELF)), 5_000)
    }
}
