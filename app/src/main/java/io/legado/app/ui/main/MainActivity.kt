package io.legado.app.ui.main

import android.os.Bundle
import android.text.format.DateUtils
import androidx.activity.addCallback
import androidx.activity.viewModels
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.lifecycleScope
import io.legado.app.BuildConfig
import io.legado.app.R
import io.legado.app.base.BaseComposeActivity
import io.legado.app.constant.AppConst
import io.legado.app.constant.BottomNavTag
import io.legado.app.constant.EventBus
import io.legado.app.constant.PreferKey
import io.legado.app.constant.appInfo
import io.legado.app.help.AppWebDav
import io.legado.app.help.config.AppConfig
import io.legado.app.help.config.LocalConfig
import io.legado.app.help.storage.Backup
import io.legado.app.help.update.AppUpdate
import io.legado.app.service.BaseReadAloudService
import io.legado.app.ui.about.CrashLogsDialog
import io.legado.app.ui.compose.dialogs.alert
import io.legado.app.ui.bookshelf.BookshelfTabController
import io.legado.app.ui.main.bookshelf.BookshelfTab
import io.legado.app.ui.main.explore.ExploreTab
import io.legado.app.ui.main.explore.ExploreTabController
import io.legado.app.ui.main.home.HomeTab
import io.legado.app.ui.main.my.MyTab
import io.legado.app.ui.widget.dialog.TextDialog
import io.legado.app.utils.observeEvent
import io.legado.app.utils.showDialogFragment
import io.legado.app.utils.toastOnUi
import io.legado.app.web.utils.WebAssetSources
import kotlinx.coroutines.Dispatchers.IO
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlin.coroutines.resume

/**
 * 主界面：纯 Compose 壳（附录 H）。四 tab 经 MainScreen 的 Pager 装配，
 * 底栏可配置顺序/显隐、书架双风格、返回键三段语义、reselect 双击均与旧实现等价。
 */
class MainActivity : BaseComposeActivity() {

    val viewModel by viewModels<MainViewModel>()

    private var exitTime: Long = 0
    private var bookshelfReselected: Long = 0
    private var exploreReselected: Long = 0
    private val EXIT_INTERVAL = 2000L

    /** 可见 tab（顺序含配置校验，等价旧 upBottomMenu） */
    var visibleTags by mutableStateOf(computeVisibleTags())
        private set

    /** 书架风格，NOTIFY_MAIN 时刷新（等价旧 getFragmentId 的动态判定） */
    var bookshelfStyle by mutableIntStateOf(AppConfig.bookGroupStyle)
        private set

    /** Pager 当前页（MainScreen 回写），供返回键/重选判定 */
    var currentPage = 0

    /** 首页落点（等价旧 upHomePage），仅初始组合时消费 */
    val initialPage: Int get() = homePageIndex()

    /** 页面跳转指令流：index to smooth */
    val pageSelections = MutableSharedFlow<Pair<Int, Boolean>>(extraBufferCapacity = 4)

    /** tab controller（MainScreen 组合时回传） */
    var bookshelfController: BookshelfTabController? = null
    var exploreController: ExploreTabController? = null

    @Composable
    override fun Content() {
        // 薄壳: 注入 app 端 4 个 Tab Composable + AppConfig 底栏三项配置,
        // shared 版 MainScreen 负责 Pager 装配 + MainBottomBar 渲染
        MainScreen(
            visibleTags = visibleTags,
            initialPage = initialPage,
            pageSelections = pageSelections,
            currentPageSink = { currentPage = it },
            onSelectPage = ::selectPage,
            onReselect = ::onTabReselect,
            homeTab = { HomeTab() },
            // style 切换由 BookshelfTab 内部 key(style) 重建, controller 回传 bookshelfController
            bookshelfTab = { BookshelfTab(style = bookshelfStyle) { bookshelfController = it } },
            exploreTab = { ExploreTab { exploreController = it } },
            myTab = { MyTab() },
            bottomBarIconSize = AppConfig.bottomBarIconSize,
            bottomBarHeight = AppConfig.bottomBarHeight,
            bottomBarLabelMode = AppConfig.bottomBarLabelMode,
        )
    }

    override fun onActivityCreated(savedInstanceState: Bundle?) {
        onBackPressedDispatcher.addCallback(this) {
            val bookshelfPos = visibleTags.indexOf(BottomNavTag.BOOKSHELF)
            if (currentPage != bookshelfPos && bookshelfPos >= 0) {
                selectPage(bookshelfPos, smooth = true)
                return@addCallback
            }
            if (bookshelfController?.back() == true) {
                return@addCallback
            }
            if (System.currentTimeMillis() - exitTime > EXIT_INTERVAL) {
                toastOnUi(R.string.double_click_exit)
                exitTime = System.currentTimeMillis()
            } else {
                if (BaseReadAloudService.pause) {
                    finish()
                } else {
                    moveTaskToBack(true)
                }
            }
        }
    }

    override fun onStart() {
        super.onStart()
        viewModel.isActivityVisible = true
        viewModel.updateUpdateNotification()
    }

    override fun onStop() {
        super.onStop()
        viewModel.isActivityVisible = false
        if (isFinishing) {
            // 退出应用时取消刷新任务, 避免弹出通知
            viewModel.cancelRefreshJobs()
        } else {
            viewModel.updateUpdateNotification()
        }
    }

    override fun onPostCreate(savedInstanceState: Bundle?) {
        super.onPostCreate(savedInstanceState)
        lifecycleScope.launch {
            //版本更新
            upVersion()
            //设置本地密码
            setLocalPassword()
            notifyAppCrash()
            //备份同步
            backupSync()
            //版本更新
            if (AppConfig.autoCheckUpdate) {
                AppUpdate.check(this@MainActivity.lifecycleScope, this@MainActivity, true)
            }
        }
        viewModel.postLoad()
    }

    fun selectPage(index: Int, smooth: Boolean) {
        pageSelections.tryEmit(index to smooth)
    }

    /** 底栏重选当前 tab：300ms 内双击触发（等价旧 onNavigationItemReselected） */
    fun onTabReselect(tag: String) {
        when (tag) {
            BottomNavTag.BOOKSHELF -> {
                if (System.currentTimeMillis() - bookshelfReselected > 300) {
                    bookshelfReselected = System.currentTimeMillis()
                } else {
                    bookshelfController?.gotoTop()
                }
            }

            BottomNavTag.DISCOVERY -> {
                if (System.currentTimeMillis() - exploreReselected > 300) {
                    exploreReselected = System.currentTimeMillis()
                } else {
                    exploreController?.compressExplore()
                }
            }
        }
    }

    /**
     * 版本更新日志
     */
    private suspend fun upVersion() {
        if (LocalConfig.versionCode == AppConst.appInfo.versionCode) return
        LocalConfig.versionCode = AppConst.appInfo.versionCode
        if (!LocalConfig.isFirstOpenApp) return
        // 先读资源( suspend ), 再进 suspendCancellableCoroutine 等待 Dialog 关闭
        val help = String(WebAssetSources.get().read("web/help/md/appHelp.md"))
        suspendCancellableCoroutine<Unit> { block ->
            val dialog = TextDialog(getString(R.string.help), help, TextDialog.Mode.MD)
            dialog.setOnDismissListener {
                block.resume(Unit)
            }
            showDialogFragment(dialog)
        }
    }

    /**
     * 设置本地密码
     */
    private suspend fun setLocalPassword() = suspendCancellableCoroutine sc@{ block ->
        if (LocalConfig.password != null) {
            block.resume(null)
            return@sc
        }
        alert(R.string.set_local_password, R.string.set_local_password_summary) {
            val getText = editTextView(hint = "password")
            onDismiss {
                block.resume(null)
            }
            okButton {
                LocalConfig.password = getText()
            }
            cancelButton {
                LocalConfig.password = ""
            }
        }
    }

    private fun notifyAppCrash() {
        if (!LocalConfig.appCrash || BuildConfig.DEBUG) {
            return
        }
        LocalConfig.appCrash = false
        alert(getString(R.string.draw), "检测到阅读发生了崩溃，是否打开崩溃日志以便报告问题？") {
            yesButton {
                showDialogFragment<CrashLogsDialog>()
            }
            noButton()
        }
    }

    /**
     * 备份同步
     */
    private fun backupSync() {
        if (!AppConfig.autoCheckNewBackup) {
            return
        }
        lifecycleScope.launch {
            val lastBackupFile =
                withContext(IO) { AppWebDav.lastBackUp().getOrNull() } ?: return@launch
            if (lastBackupFile.lastModify - LocalConfig.lastBackup > DateUtils.MINUTE_IN_MILLIS) {
                LocalConfig.lastBackup = lastBackupFile.lastModify
                alert(R.string.restore, R.string.webdav_after_local_restore_confirm) {
                    cancelButton()
                    okButton {
                        viewModel.restoreWebDav(lastBackupFile.displayName)
                    }
                }
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        if (!BuildConfig.DEBUG) {
            Backup.autoBack(this)
        }
    }

    /**
     * 如果重启太快fragment不会重建,这里更新一下书架的排序
     */
    override fun recreate() {
        bookshelfController?.upSort()
        super.recreate()
    }

    override fun observeLiveBus() {
        super.observeLiveBus()

        observeEvent<Boolean>(EventBus.NOTIFY_MAIN) {
            visibleTags = computeVisibleTags()
            bookshelfStyle = AppConfig.bookGroupStyle
            if (it) {
                selectPage(visibleTags.lastIndex, smooth = false)
            }
        }
        observeEvent<String>(PreferKey.threadCount) {
            viewModel.upPool()
        }
    }

    /** 等价旧 upBottomMenu：顺序配置校验（非法回落默认并清 pref）+ showHome/showDiscovery 过滤 */
    private fun computeVisibleTags(): List<String> {
        val defaultTagOrder = listOf(
            BottomNavTag.HOME,
            BottomNavTag.BOOKSHELF,
            BottomNavTag.DISCOVERY,
            BottomNavTag.MY,
        )
        val savedTagOrder = AppConfig.bottomNavItemOrder?.split(",").orEmpty()
        val orderedTags = savedTagOrder
            .takeIf { it.size == 4 && it.toSet() == defaultTagOrder.toSet() }
            ?: defaultTagOrder.also {
                if (AppConfig.bottomNavItemOrder != null) AppConfig.bottomNavItemOrder = null
            }
        val tags = orderedTags.filter { tag ->
            when (tag) {
                BottomNavTag.HOME -> AppConfig.showHome
                BottomNavTag.DISCOVERY -> AppConfig.showDiscovery
                else -> true
            }
        }
        return tags.ifEmpty { listOf(BottomNavTag.BOOKSHELF) }
    }

    /** 等价旧 upHomePage：defaultHomePage 落点，目标 tab 隐藏时回落书架 */
    private fun homePageIndex(): Int {
        val bookshelfPos = visibleTags.indexOf(BottomNavTag.BOOKSHELF)
        val pos = when (AppConfig.defaultHomePage) {
            "home" -> visibleTags.indexOf(BottomNavTag.HOME)
            "bookshelf" -> bookshelfPos
            "explore" -> visibleTags.indexOf(BottomNavTag.DISCOVERY)
            "my" -> visibleTags.indexOf(BottomNavTag.MY)
            else -> bookshelfPos
        }
        return if (pos >= 0) pos else bookshelfPos.coerceAtLeast(0)
    }

}
