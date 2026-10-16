package io.legado.app.ui.root

/**
 * 页面系统栏外观: 页面按自己背后的内容声明状态栏图标明暗 (可选带导航栏底色)。
 *
 * 本结构是**唯一**的页面级系统栏外观通道, 三类页面共用: 阅读页 (对照原版
 * `BaseReadBookActivity.upSystemUiVisibility` 的 setLightStatusBar 分支与
 * `ReadBookActivity.upNavigationBarColor`)、音频播放页与书籍详情页 (两者原版都是
 * `toolBarTheme = Theme.Dark` 的页面, 建页即把状态栏图标设为浅色; 详情页另有按背景图
 * 显隐自行切换的分支)。
 *
 * 底色不在此结构里: 页面区域由内容铺满 (沉浸式), 状态栏底色不在本项职责内。
 */
data class PageSystemBarAppearance(
    /** 状态栏图标明暗: true = 深色图标 (适用于浅色状态栏底色), 对应原版 setLightStatusBar(true) */
    val lightStatusBarIcons: Boolean,
    /**
     * 导航栏底色; null = 按本端既有的导航栏规则 (主题导航栏色, 有壁纸时透明并按贴边像素定图标明暗)
     */
    val navigationBarColor: Int? = null,
)

/**
 * 页面声明的占用者登记 (见 [PageSystemBarAppearance])。
 *
 * 为什么需要它: 页面转场期间新旧两页同时在组合里, 两页对"声明/交还"这对写操作的执行顺序由
 * 组合时机决定, 页面自己判断不出"此刻该不该交还" —— 旧页的交还若晚于新页的声明, 就会把新页
 * 刚声明的外观擦掉 (表现为进页后状态栏图标又变回去)。登记占用者后, 交还只在仍是占用者时生效,
 * 于是**任一顺序**都得到同一终态: 栈顶页的声明生效。
 *
 * 同一窗口只有一份生效的声明, 故这里只记占用者, 声明内容由窗口侧持有 (它也是配置变化后重新
 * 盖回时的取值处)。只在 UI 线程 (组合期) 上访问, 不加锁。
 */
object PageSystemBarAppearances {

    private var owner: Any? = null

    /** 以 [caller] 名义接管占用 (转场期间后继页接管后, 前页的交还自然落空)。 */
    fun claim(caller: Any) {
        owner = caller
    }

    /** [caller] 交还占用; 自己仍持有才作数。 */
    fun release(caller: Any): Boolean {
        if (owner !== caller) return false
        owner = null
        return true
    }
}
