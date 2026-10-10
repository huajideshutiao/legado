package io.legado.app.ui

import io.legado.app.constant.AppLog
import io.legado.app.constant.DirUsage
import io.legado.app.constant.PreferKey
import io.legado.app.data.AppDbProviders
import io.legado.app.data.entities.Book
import io.legado.app.data.entities.BookSourcePart
import io.legado.app.data.entities.HttpTTS
import io.legado.app.exception.InvalidBooksDirException
import io.legado.app.help.AppWebDavShared
import io.legado.app.help.DirectLinkUploadRule
import io.legado.app.help.config.LocalConfigKeys
import io.legado.app.help.config.PreferenceProviders
import io.legado.app.help.coroutine.IoDispatcher
import io.legado.app.help.coroutine.mainDispatcher
import io.legado.app.help.toast.Toasters
import io.legado.app.help.upLoadToDirectLink
import io.legado.app.model.fileBook.FileBook
import io.legado.app.ui.book.source.BookSourceSort
import io.legado.app.ui.config.MODE_EDIT_CONFIG
import io.legado.app.ui.config.MODE_EDIT_PREFS
import io.legado.app.ui.config.MODE_NEW_CONFIG
import io.legado.app.ui.root.AppNavigatorProviders
import io.legado.app.ui.root.AppOverlay
import io.legado.app.ui.root.AppRoute
import io.legado.app.ui.root.PlatformCapabilityProviders
import io.legado.app.ui.root.PlatformServiceProviders
import io.legado.app.ui.root.SharedPlatformCapabilities
import io.legado.app.utils.GSON
import io.legado.app.utils.toJson
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okio.FileSystem
import okio.Path.Companion.toPath

/**
 * iOS 与鸿蒙实现逐字相同、但与桌面不同的那部分 [SharedPlatformCapabilities]。
 *
 * 桌面这几项各有自己的形态 (系统浏览器 / 文件选择器 / 无内嵌 WebView 路由), 故不放
 * [SharedPlatformCapabilities]; 两端一行委托到叶子源集函数的 (openURL / 剪贴板 /
 * NativeImportBook) 留在各端 —— 那些是平台边界, 且 nativeMain 与本源集互不可见。
 */
interface NativePlatformCapabilities : SharedPlatformCapabilities {

    /** 移动端保留内嵌 WebViewRoute 路由语义 (对话框内嵌)。 */
    override fun openWebView(url: String, sourceKey: String, sourceName: String) {
        AppNavigatorProviders.get().push(AppRoute.WebView(url, sourceKey, sourceName))
    }

    override fun enableCustomExport(): Boolean =
        PreferenceProviders.get().getBoolean(PreferKey.enableCustomExport, false)

    // 对照 ReadAloudConfigDialog 的"+"按钮/行编辑: showDialogFragment(HttpTtsEditDialog)
    // 迁 Compose Overlay: shared OverlayContentHost 的 "httpTtsEdit" key 接管
    // (payload = 引擎 id, null 表新增)
    override fun showHttpTtsEditDialog(engine: HttpTTS?) {
        AppNavigatorProviders.get().showOverlay(
            AppOverlay.Dialog(
                "httpTtsEdit",
                payload = engine?.id?.toString(),
                // 表单登录 → push WebView 路由盖住本窗口时保留 Overlay, 由内容自管挂起/恢复
                // (对照原版 DialogFragment 被新 Activity 盖住仍存活, 返回后表单原样)
                keepOnPush = true,
            )
        )
    }

    // 直链上传规则页的"测试"按钮: 用表单里尚未保存的规则跑一次真实上传
    override fun testDirectLinkUpload(
        rule: DirectLinkUploadRule,
        onSuccess: (String) -> Unit,
        onError: (String) -> Unit,
    ) {
        capabilityScope.launch {
            runCatching { upLoadToDirectLink("test.json", "{}", "application/json", rule) }
                .onSuccess { result -> withContext(mainDispatcher) { onSuccess(result) } }
                .onFailure { error ->
                    withContext(mainDispatcher) {
                        onError(error.message ?: error.toString())
                    }
                }
        }
    }

    // 对照 BookInfoViewModel.uploadBook: 上传本地书籍文件到 WebDav books/ 子目录
    override fun uploadBook(book: Book, success: (() -> Unit)?) {
        capabilityScope.launch {
            runCatching {
                // 与 AppWebDavShared.exportWebDav 同款惰性认证: native 端无 AppWebDav init 块
                if (AppWebDavShared.authorization == null) AppWebDavShared.upConfig()
                withContext(IoDispatcher) { AppWebDavShared.uploadBook(book) }
            }.onSuccess {
                withContext(mainDispatcher) {
                    Toasters.get().toast("上传成功")
                    success?.invoke()
                }
            }.onFailure { error ->
                withContext(mainDispatcher) { Toasters.get().toast(error.message ?: error.toString()) }
            }
        }
    }

    // 对照 BookInfoViewModel.downloadToLocal
    override fun downloadBookToLocal(book: Book, success: (() -> Unit)?) {
        capabilityScope.launch {
            runCatching { withContext(IoDispatcher) { FileBook.downloadRemoteBook(book) } }
                .onSuccess {
                    withContext(mainDispatcher) {
                        Toasters.get().toast("下载成功")
                        success?.invoke()
                    }
                }
                .onFailure { error ->
                    // 保存位置未设置时走既有的"去设置目录"出口 (与书籍详情的 web 文件导入同一
                    // 入口与偏好键); 用户取消 (uri == null) 时不动既有配置
                    if (error is InvalidBooksDirException) {
                        withContext(mainDispatcher) {
                            PlatformCapabilityProviders.get().pickBookTreeUri { uri ->
                                if (uri != null) {
                                    PreferenceProviders.get().putString(
                                        PreferKey.defaultBookTreeUri, uri
                                    )
                                }
                            }
                        }
                    }
                    AppLog.put("下载远程书籍<${book.name}>失败", error, true)
                }
        }
    }

    override fun getDeleteBookOriginal(): Boolean =
        PreferenceProviders.get().getBoolean(LocalConfigKeys.deleteBookOriginal, false)

    override fun showThemeCustomizeDialog(configIndex: Int?, isNight: Boolean) {
        val mode = if (configIndex == null) MODE_NEW_CONFIG else MODE_EDIT_CONFIG
        AppNavigatorProviders.get().showOverlay(
            AppOverlay.Dialog("theme_customize", payload = "$mode,${configIndex ?: -1},$isNight")
        )
    }

    override fun showCustomizeDayThemeDialog() {
        AppNavigatorProviders.get().showOverlay(
            AppOverlay.Dialog("theme_customize", payload = "$MODE_EDIT_PREFS,-1,false")
        )
    }

    override fun showCustomizeNightThemeDialog() {
        AppNavigatorProviders.get().showOverlay(
            AppOverlay.Dialog("theme_customize", payload = "$MODE_EDIT_PREFS,-1,true")
        )
    }

    /** 分享选中书源 (导出前强制关闭危险 API 开关, 见 [selectedSourcesJson])。 */
    override fun shareBookSourceSelection(
        selection: List<BookSourcePart>,
        allCount: Int,
        sortAscending: Boolean,
        sort: BookSourceSort
    ) {
        capabilityScope.launch {
            val json = selectedSourcesJson(selection) ?: return@launch
            // 分享面板是界面动作, 回主线程; 能力作用域是 IO。
            withContext(mainDispatcher) { shareText(json) }
        }
    }

    override fun pickBookTreeUri(onSelected: (String?) -> Unit) {
        capabilityScope.launch {
            onSelected(PlatformServiceProviders.get().files.pickDirectory(DirUsage.BookTree))
        }
    }

    // iOS/鸿蒙的选目录返回沙箱路径 (普通路径, 非 Android 的 SAF 树 URI)。
    // native 端的 io.legado.app.utils.File 无文件操作能力, 目录列举走 okio FileSystem
    // (与 foundation 的 native File actual 同一实现)。
    // 返回格式必须与 importBook 写入的 bookUrl 一致 ("file://" + 绝对路径), 否则
    // NativeFileBookAccessor.resolveLocalFile 解析不到。
    override fun findBookFileInDir(dirUri: String, fileName: String): String? {
        val fs = FileSystem.SYSTEM
        val dir = dirUri.toPath()
        if (fs.metadataOrNull(dir)?.isDirectory != true) return null
        val direct = dir / fileName
        if (fs.metadataOrNull(direct)?.isRegularFile == true) return "file://${direct}"
        return fs.listOrNull(dir)
            ?.filter { fs.metadataOrNull(it)?.isDirectory == true }
            ?.firstNotNullOfOrNull { sub ->
                (sub / fileName).takeIf { fs.metadataOrNull(it)?.isRegularFile == true }
            }
            ?.let { "file://$it" }
    }
}

/**
 * 选中书源转 JSON: 导出/分享前强制关掉危险 API 开关。
 *
 * `internal` 而非文件私有: iOS/鸿蒙各自的 `exportBookSourceSelection` (落盘方式不同, 未合并)
 * 也要用它。
 */
internal suspend fun selectedSourcesJson(selection: List<BookSourcePart>): String? {
    val urls = selection.map { it.bookSourceUrl }
    val sources = runCatching { AppDbProviders.get().bookSourceDao.getBookSourcesFix(urls) }
        .getOrElse {
            Toasters.get().toast("导出书源失败\n${it.message}")
            return null
        }
    sources.forEach { if (it.enableDangerousApi == true) it.enableDangerousApi = false }
    return GSON.toJson(sources)
}
