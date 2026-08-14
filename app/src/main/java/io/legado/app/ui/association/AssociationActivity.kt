package io.legado.app.ui.association

import android.content.Intent
import android.os.Bundle
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.net.toUri
import io.legado.app.base.BaseComposeActivity
import io.legado.app.constant.BookType
import io.legado.app.constant.Theme
import io.legado.app.data.AppDbProviders
import io.legado.app.data.entities.Book
import io.legado.app.help.IntentData
import io.legado.app.help.book.addType
import io.legado.app.help.toast.Toasters
import io.legado.app.model.webBook.WebBook.getBookInfoByUrlAwait
import io.legado.app.ui.main.MainActivity
import io.legado.app.ui.root.PlatformCapabilities
import io.legado.app.ui.root.PlatformCapabilityProviders
import io.legado.app.ui.widget.dialog.WaitDialog
import io.legado.app.utils.startActivity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * legado:// / yuedu:// deep link 透明壳 Activity (对照 master AssociationActivity):
 *
 * - **透明浮层**: AppTheme.Transparent + excludeFromRecents + singleTask, 点击深链时本壳
 *   浮在其他应用之上, 只显示导入对话框/转圈, 主界面页面 (书架/发现等) 零渲染。
 * - **勾选六型 / READ_CONFIG / UNKNOWN**: onActivityCreated/onNewIntent →
 *   [LegadoDeepLinkHandler.handle] → [DeepLinkImportHost] 弹导入对话框, 完成/取消
 *   (pending 置空) 后 finish 回到原应用。
 * - **ADD_TO_BOOKSHELF / READ_BOOK** (需要导航): 壳内显示共享 [WaitDialog] 抓书
 *   (书架直读先查书架, 无则抓详情; 添加书架直接抓详情 + addType(notShelf)),
 *   **不落库**, 经 [IntentData.book] 直传内存书, 转发 MainActivity
 *   (route=book_info/read_book extra → LegadoApp NavigateTo 分支读 IntentData.book
 *   推详情页/阅读页, 对照 master BookInfoActivity/ReadBookActivity 读 IntentData.book),
 *   壳 finish。
 *
 * 容器照常加载 (App 进程 + App.onCreate 的 provider), 但主界面页面不组合;
 * 冷启动 (App 未运行) 时壳是首个 Activity, 需自注册最小 [PlatformCapabilities]
 * (AppDialog 依赖 dialogTransitionSpec, get() 未注册直接 error); 热启动 (MainActivity
 * 已注册 AndroidPlatformCapabilities) 则保留其实现, 不覆盖。
 */
class AssociationActivity : BaseComposeActivity(theme = Theme.Transparent, imageBg = false) {

    /** 待处理的"需要导航"请求 (addToBookshelf / read): 壳内转圈抓书后转发主界面。 */
    private var pendingBookNav by mutableStateOf<Pair<DeepLinkImportType, String>?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        // 冷启动: 壳是首个 Activity, PlatformCapabilities 未注册 (AppDialog.get() 会 error);
        // 热启动 (MainActivity 已注册) 保留其实现, 避免覆盖系统动画缩放等差异
        if (PlatformCapabilityProviders.getOrNull() == null) {
            PlatformCapabilityProviders.register(object : PlatformCapabilities {
                override fun exitApplication() = finish()

                override fun openExternalUrl(url: String) {
                    runCatching {
                        startActivity(
                            Intent(Intent.ACTION_VIEW, url.toUri()).apply {
                                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                            }
                        )
                    }
                }

                override fun shareText(text: String) {
                    runCatching {
                        val intent = Intent(Intent.ACTION_SEND).apply {
                            type = "text/plain"
                            putExtra(Intent.EXTRA_TEXT, text)
                        }
                        startActivity(Intent.createChooser(intent, null))
                    }
                }
            })
        }
        super.onCreate(savedInstanceState)
    }

    @Composable
    override fun Content() {
        val nav = pendingBookNav
        if (nav == null) {
            // 纯导入 (勾选六型/readConfig/unknown): 只挂共享导入宿主, 不渲染主界面页面
            DeepLinkImportHost()
            // 导入完成/对话框关闭 → pending 置空 → 结束透明壳回到原应用
            LaunchedEffect(Unit) {
                LegadoDeepLinkHandler.pending.collect { if (it == null) finish() }
            }
            return
        }
        // addToBookshelf / read: 壳内转圈抓书 (透明浮层), IntentData 直传 Book 后转发主界面
        WaitDialog(visible = true, onDismissRequest = {})
        LaunchedEffect(nav) {
            runCatching {
                withContext(Dispatchers.IO) { resolveBookForNav(nav.first, nav.second) }
            }.onSuccess { book ->
                // 直传内存书 (对照 master AddToBookshelfHelper: IntentData.book = book → 目标页读内存)
                IntentData.book = book
                startActivity<MainActivity> {
                    putExtra("route", bookRouteName(nav.first))
                    putExtra("bookUrl", book.bookUrl)
                }
                finish()
            }.onFailure { e ->
                Toasters.get().toast(e.message ?: "打开失败")
                finish()
            }
        }
    }

    override fun onActivityCreated(savedInstanceState: Bundle?) {
        super.onActivityCreated(savedInstanceState)
        handleIntent(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIntent(intent)
    }

    private fun handleIntent(intent: Intent?) {
        val uri = intent?.data ?: run {
            finish()
            return
        }
        val request = LegadoDeepLink.parse(uri.toString())
        if (request == null) {
            // 缺 src / 非 legado 系: 静默结束 (对照 app 端 handleOnLineImport 缺 src finish)
            finish()
            return
        }
        if (request.type == DeepLinkImportType.ADD_TO_BOOKSHELF ||
            request.type == DeepLinkImportType.READ_BOOK
        ) {
            // 需要导航的两型: 壳内处理 (Content 里转圈 → 抓书 → 转发主界面), 不进 shared pending
            pendingBookNav = request.type to request.src
            return
        }
        LegadoDeepLinkHandler.handle(uri.toString())
    }

    /** 抓书 (addToBookshelf: 抓详情+未上架标记; read: 书架书直接读, 未收录等同添加书架)。 */
    private suspend fun resolveBookForNav(
        type: DeepLinkImportType,
        bookUrl: String,
    ): Book {
        if (type == DeepLinkImportType.READ_BOOK) {
            // 书架直读: 已在书架 → 直接读; 不在书架 → 等同添加书架 (抓详情进详情页)
            AppDbProviders.get().bookDao.getBook(bookUrl)?.let { return it }
        }
        // 添加书架 / 书架直读未收录: 抓详情 + 标记未上架 (对照 master AddToBookshelfHelper,
        // 不落库, 直接经 IntentData 传内存书)
        return getBookInfoByUrlAwait(bookUrl).apply { addType(BookType.notShelf) }
    }

    /** 目标路由名 (LegadoApp NavigateTo 分支消费 IntentData.book 后推对应路由)。 */
    private fun bookRouteName(type: DeepLinkImportType): String =
        if (type == DeepLinkImportType.READ_BOOK) "read_book" else "book_info"
}
