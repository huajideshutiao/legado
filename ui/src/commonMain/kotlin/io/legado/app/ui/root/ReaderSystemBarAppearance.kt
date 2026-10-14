package io.legado.app.ui.root

/**
 * 阅读页系统栏外观 (对照原版 `BaseReadBookActivity.upSystemUiVisibility` 的图标明暗分支
 * 与 `ReadBookActivity.upNavigationBarColor`)。
 *
 * 原版语义:
 * - 菜单布局不可见 (阅读菜单 / 搜索底部菜单 / 底部弹层全部收起): 状态栏图标明暗取当前样式
 *   配置的「深色状态栏图标」; 导航栏底色取阅读背景色。
 * - 菜单布局可见: 状态栏图标明暗按状态栏底色亮度反推 (纯色背景或底部弹层打开时用阅读背景色,
 *   否则用主题状态栏色); 导航栏底色回到主题规则。
 *
 * 底色不在此结构里: 阅读页状态栏区域由页面内容铺满 (沉浸式), 底色不在本项职责内。
 */
data class ReaderSystemBarAppearance(
    /** 状态栏图标明暗: true = 深色图标 (适用于浅色状态栏底色), 对应原版 setLightStatusBar(true) */
    val lightStatusBarIcons: Boolean,
    /**
     * 导航栏底色; null = 按本端非阅读页的导航栏规则
     * (主题导航栏色, 有壁纸时透明并按贴边像素亮度定图标明暗)
     */
    val navigationBarColor: Int?,
)
